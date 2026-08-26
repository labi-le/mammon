package app.mammon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * fslib.sh's boot automount opens /dev/fuse and hands the descriptor to mount(2),
 * exactly like RootMount.kt's fuseMountScript. Magisk's /system/bin/sh is mksh, which
 * sets close-on-exec on fds >= 3 opened by `exec` redirection — so the mount child must
 * inherit the fd from a GROUP redirection, not from `exec 3<>`. These pins run the real
 * fslib.sh through its host dry-run overrides (MAMMON_PROC_MOUNTS / MAMMON_FUSE_DEVICE)
 * under mksh: the first asserts the stubbed mount still sees fd 3, the second asserts
 * an Android storage mountpoint is refused before the mount is ever attempted.
 */
class FslibAutomountFuseFdTest {

    @Test fun `automount keeps fd 3 open across exec under mksh`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)
        val fslib = fslib()
        val (log, record) = runAutomount(fslib, mksh!!, "/mnt/nas")
        assertTrue(
            "automount mount stub saw fd 3 closed (mksh cloexec on exec-redirection fds >= 3):\n" +
                "record:\n$record\nlog:\n$log",
            record.contains("FD_OK"),
        )
    }

    @Test fun `automount refuses Android storage mountpoints before mounting`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)
        val fslib = fslib()
        val (log, record) = runAutomount(fslib, mksh!!, "/storage/emulated/0/nfs")
        assertTrue(
            "automount must SKIP the storage mountpoint before any mount call:\n$log",
            log.contains("SKIPPED: mountpoint '/storage/emulated/0/nfs' is Android's own storage"),
        )
        assertFalse("mount must never run for a refused mountpoint:\n$record", record.contains("MOUNT "))
    }

    /** Builds the harness once; the automount path needs the real magisk-module/fslib.sh. */
    private fun fslib(): File {
        val f = File(System.getProperty("mammon.fslib") ?: "../magisk-module/fslib.sh")
        assertTrue("fslib.sh not found at $f", f.isFile)
        return f
    }

    /** Runs mammon_automount_main under mksh with the given mountpoint saved, returning
     *  the load.log text and the mount stub's record. */
    private fun runAutomount(fslib: File, mksh: String, mountpoint: String): Pair<String, String> {
        val dir = File.createTempFile("mammon-fslib-", "").apply { delete(); mkdirs(); deleteOnExit() }
        File(dir, "automount").writeText("1")
        File(dir, "mammon.xml").writeText(
            // Android's SharedPreferences writes one entry per line, indented; the
            // harness must mirror that or mammon_pref_value's line-anchored sed misses.
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" +
                "<map>\n" +
                "    <string name=\"host\">192.0.2.1</string>\n" +
                "    <string name=\"export\">/export</string>\n" +
                "    <int name=\"port\" value=\"2049\" />\n" +
                "    <string name=\"mountpoint\">$mountpoint</string>\n" +
                "</map>\n",
        )
        File(dir, "mounts").writeText("") // nothing mounted at $mountpoint
        val log = File(dir, "load.log").apply { writeText("") }
        val record = File(dir, "record").apply { writeText("") }

        val path = File(dir, "path").apply { mkdirs() }
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
        // Everything else the automount path calls resolves to a silent success, and the
        // reachability/readiness probes are stubbed so the run reaches the mount at once.
        for (name in listOf("app_process", "modprobe", "pm", "nc", "grep", "tail", "wc", "sleep", "umount", "mkdir")) {
            val body = if (name == "pm") "echo package:/data/app/base.apk\n" else "exit 0\n"
            File(path, name).apply { writeText("#!/bin/sh\n$body"); setExecutable(true) }
        }

        val builder = ProcessBuilder(mksh, "-c", ". '${fslib.path}' && mammon_automount_main '${log.path}'")
        builder.environment()["PATH"] = path.path + ":" + (System.getenv("PATH").orEmpty())
        builder.environment()["MODDIR"] = dir.path
        builder.environment()["MAMMON_PREFS_FILE"] = File(dir, "mammon.xml").path
        builder.environment()["MAMMON_PROC_MOUNTS"] = File(dir, "mounts").path
        builder.environment()["MAMMON_FUSE_DEVICE"] = "/dev/fuse"
        builder.environment()["MAMMON_WAIT_TRIES"] = "1"
        builder.environment()["MAMMON_WAIT_INTERVAL"] = "0"
        val proc = builder.start()
        proc.inputStream.bufferedReader().use { it.readText() }
        proc.errorStream.bufferedReader().use { it.readText() }
        proc.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return log.readText() to record.readText()
    }

    private fun findOnPath(name: String): String? =
        (System.getenv("PATH").orEmpty().split(':').firstOrNull { File(it, name).canExecute() })
            ?.let { File(it, name).absolutePath }

    private companion object {
        const val SHELL_TIMEOUT_SECONDS = 60L
    }
}
