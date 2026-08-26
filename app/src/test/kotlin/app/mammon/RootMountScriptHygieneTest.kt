package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
