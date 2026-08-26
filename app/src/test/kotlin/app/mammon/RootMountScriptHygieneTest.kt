package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The mount scripts are Kotlin raw strings, so a `//` comment written inside one ships to
 * the device as a command. v0.6.1 did exactly that and every mount reported
 * `sh: //: can't execute: Is a directory`; no rung sets `-e`, so the script ran on and
 * that noise became the whole verdict through the stderr fallback in `failed()`. These
 * pins put the generated text through a real `sh`, which is the only place the defect
 * was ever visible.
 */
class RootMountScriptHygieneTest {

    @Test fun `no generated script line is a Kotlin comment`() {
        for ((name, script) in generated()) {
            val stray = script.lines().filter { it.trimStart().startsWith("//") }
            assertEquals("$name ships Kotlin comments as commands: $stray", emptyList<String>(), stray)
        }
    }

    @Test fun `no generated script line fails to execute under a real shell`() {
        for ((name, script) in generated()) {
            val err = stderrUnderStubPath(script)
            for (marker in listOf("can't execute", "Is a directory")) {
                assertFalse(
                    "$name stderr carries \"$marker\", which firstLine() would report as the verdict:\n$err",
                    err.lineSequence().any { marker in it },
                )
            }
        }
    }

    @Test fun `both generated scripts pass a shell syntax check`() {
        for ((name, script) in generated()) {
            val file = File(tempDir("syntax"), "script.sh").apply { writeText(script) }
            val proc = ProcessBuilder("/bin/sh", "-n", file.path).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals("$name is not valid shell:\n$out", 0, proc.exitValue())
        }
    }

    /**
     * Android's /system/bin/sh is mksh, and mksh sets close-on-exec on fds >= 3 opened
     * by `exec` redirection — so a `exec 3<>/dev/fuse` form hands the mount child a
     * closed descriptor and mount(2) fails EINVAL. Only the real target shell shows it:
     * dash and bash keep such fds open, which is why the rest of this suite's dash runs
     * cannot model the failure. The stubbed mount records whether /proc/self/fd/3 still
     * resolves after the exec; the fd must survive, or the FUSE launch chain is broken.
     */
    @Test fun `fuseMountScript keeps fd 3 open across exec under mksh`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)

        val record = File(tempDir("record"), "mount_record").apply { writeText("") }
        val script = generated().first { it.first == "fuseMountScript" }.second
        val file = File(tempDir("script"), "script.sh").apply { writeText(script) }

        val path = tempDir("path")
        File(path, "mount").apply {
            writeText(
                """
                #!/bin/sh
                if [ -e /proc/self/fd/3 ]; then echo FD_OK; else echo FD_MISSING; fi >> '${record.path}'
                echo "MOUNT ${'$'}@" >> '${record.path}'
                exit 0
                """.trimIndent(),
            )
            setExecutable(true)
        }
        for (name in STUBBED_COMMANDS) {
            if (name == "mount") continue
            File(path, name).apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
        }

        val builder = ProcessBuilder(mksh, file.path)
        builder.environment()["PATH"] = path.path + ":" + (System.getenv("PATH").orEmpty())
        val proc = builder.start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val recordText = record.readText()
        assertTrue(
            "mount stub saw fd 3 closed (mksh cloexec on exec-redirection fds >= 3):\n" +
                "record:\n$recordText\nstdout:\n$out",
            recordText.contains("FD_OK"),
        )
    }

    /**
     * app_process feeds every leading dash-arg to ART (an unknown one exits before
     * main) and parses `--nice-name` only after the "/" parent dir, before the class —
     * a trailing flag leaks into main()'s argv and breaks the daemon's 4-arg contract.
     * The v0.6.5 daemon died to the leading shape on a real device; this pins the flag
     * between "/" and the class so neither shape can regress.
     */
    @Test fun `fuseMountScript passes nice-name between the class dir and the daemon class`() {
        val launch = generated().first { it.first == "fuseMountScript" }.second
            .lines().first { "app.mammon.FuseDaemonKt" in it }
        val classIdx = launch.indexOf("app.mammon.FuseDaemonKt")
        val slashIdx = launch.indexOf(" / ")
        val niceIdx = launch.indexOf("--nice-name=app.mammon:fuse")
        assertTrue("no daemon class token in launch line: $launch", classIdx >= 0)
        assertTrue("no classpath-dir argument in launch line: $launch", slashIdx >= 0)
        assertTrue("no --nice-name in launch line: $launch", niceIdx >= 0)
        assertTrue(
            "--nice-name must sit between the / argument and app.mammon.FuseDaemonKt (leading flags die in ART, trailing ones leak into main):\n$launch",
            niceIdx > slashIdx && classIdx > niceIdx,
        )
    }

    private fun findOnPath(name: String): String? =
        (System.getenv("PATH").orEmpty().split(':').firstOrNull { File(it, name).canExecute() })
            ?.let { File(it, name).absolutePath }

    private fun generated(): List<Pair<String, String>> {
        val mountpoint = tempDir("mp").path
        val log = File(tempDir("log"), "fuse.log").path
        return listOf(
            "kernelMountScript" to
                RootMount.kernelMountScript("192.0.2.1", "/export", 2049, mountpoint, "4.2"),
            "fuseMountScript" to
                RootMount.fuseMountScript(
                    "192.0.2.1", "/export", 2049, mountpoint,
                    RootMount.FuseLaunch("/data/app/base.apk", log),
                ),
        )
    }

    /** Every external command the scripts call resolves to a silent success, so the run
     *  reaches its own tail instead of the host's real mount, modprobe or app_process.
     *  A stubbed `grep` also satisfies the daemon-readiness wait immediately. */
    private fun stderrUnderStubPath(script: String): String {
        val path = tempDir("path")
        for (name in STUBBED_COMMANDS) {
            File(path, name).apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
        }
        val file = File(tempDir("script"), "script.sh").apply { writeText(script) }
        val builder = ProcessBuilder("/bin/sh", file.path)
        builder.environment()["PATH"] = path.path
        val proc = builder.start()
        val out = CompletableFuture.supplyAsync {
            proc.inputStream.bufferedReader().use { it.readText() }
        }
        val err = CompletableFuture.supplyAsync {
            proc.errorStream.bufferedReader().use { it.readText() }
        }
        proc.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        out.get(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return err.get(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun tempDir(prefix: String): File =
        File.createTempFile("mammon-$prefix", "").let {
            it.delete()
            it.mkdirs()
            it.deleteOnExit()
            it
        }

    private companion object {
        val STUBBED_COMMANDS = listOf(
            "app_process", "cat", "chmod", "grep", "ls", "mkdir", "modprobe",
            "mount", "sleep", "timeout", "umount",
        )
        const val SHELL_TIMEOUT_SECONDS = 60L
    }
}
