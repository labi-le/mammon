package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mount diagnosis must name the cause the user can act on. Each branch is driven from
 * a synthetic (exit code, stderr, /proc/filesystems, FUSE outcome) tuple, so no root, no
 * /dev/fuse and no kernel are needed to pin the ordering down.
 *
 * The ordering is the substance: root missing outranks everything because every rung
 * below it was never really attempted, and the kernel's own fs-type list outranks the
 * script's guess because the script only sees a failed command.
 */
class RootMountDiagnosisTest {

    private val withFuse = "nodev\tsysfs\n\text4\nnodev\tfuse\nnodev\tnfs\nnodev\tnfs4\n"

    private val withoutFuse = "nodev\tsysfs\n\text4\nnodev\tnfs\n"

    /** Nothing registered: the state the b/c split exists for. */
    private val bareProc = "nodev\tsysfs\n\text4\n"
    private fun classify(
        attempts: List<RootMount.Attempt>,
        filesystems: String,
        fuse: RootMount.FuseOutcome?,
        moduleDirs: String = "",
    ) = RootMount.classifyMountFailure(attempts, filesystems, moduleDirs, fuse)

    @Test fun `su that cannot be started is reported as no root, never as a kernel claim`() {
        val attempts = listOf(
            RootMount.Attempt(-1, "Cannot run program \"su\": error=2, No such file or directory"),
        )

        assertEquals(RootMount.MountDiagnosis.NO_ROOT, classify(attempts, withFuse, null))
        // The kernel here HAS fuse; an su that never started must not be blamed on it.
        assertEquals(RootMount.MountDiagnosis.NO_ROOT, classify(attempts, withoutFuse, null))
    }

    @Test fun `an su that never answered is no root, not a kernel verdict`() {
        val attempts = listOf(RootMount.Attempt(124, "su timed out"))

        assertEquals(RootMount.MountDiagnosis.NO_ROOT, classify(attempts, withFuse, null))
        assertEquals(RootMount.MountDiagnosis.NO_ROOT, classify(attempts, withoutFuse, null))
    }

    @Test fun `no root outranks every FUSE outcome`() {
        val attempts = listOf(
            RootMount.Attempt(32, "mount: no such device"),
            RootMount.Attempt(-1, "Cannot run program \"su\""),
        )

        for (outcome in RootMount.FuseOutcome.entries) {
            assertEquals(
                outcome.name,
                RootMount.MountDiagnosis.NO_ROOT,
                classify(attempts, withFuse, outcome),
            )
        }
    }

    /** 127 is what a shell returns for command-not-found, so a rooted device missing
     *  a helper inside the script must not be told it has no root. */
    @Test fun `exit 127 from the mount script is not a no-root verdict`() {
        val attempts = listOf(
            RootMount.Attempt(127, "sh: nsenter: not found"),
            RootMount.Attempt(127, "sh: nsenter: not found"),
        )

        assertEquals(RootMount.MountDiagnosis.GENERIC, classify(attempts, withFuse, null))
    }

    @Test fun `a FUSE rung that found no device is a kernel that cannot give us FUSE`() {
        val attempts = listOf(RootMount.Attempt(1, "mount: /dev/fuse: No such file or directory"))

        assertEquals(
            RootMount.MountDiagnosis.KERNEL_LACKS_FUSE,
            classify(attempts, withFuse, RootMount.FuseOutcome.NO_DEVICE),
        )
    }

    @Test fun `a kernel without a fuse line outranks whatever the FUSE script concluded`() {
        val attempts = listOf(RootMount.Attempt(1, "mount: unknown filesystem type 'fuse'"))

        for (outcome in RootMount.FuseOutcome.entries) {
            assertEquals(
                outcome.name,
                RootMount.MountDiagnosis.KERNEL_LACKS_FUSE,
                classify(attempts, withoutFuse, outcome),
            )
        }
    }

    /** Was pinned as KERNEL_LACKS_FUSE before the fs list was captured in su context;
     *  field evidence showed kernels WITH fuse reporting 'lacks' off a denied read,
     *  so blank text is now no evidence at all. */
    @Test fun `an unreadable proc filesystems degrades to unknown, never unsupported`() {
        val attempts = listOf(RootMount.Attempt(1, "mount.nfs: Connection timed out"))

        assertEquals(
            RootMount.MountDiagnosis.GENERIC,
            classify(attempts, "", RootMount.FuseOutcome.FAILED),
        )
        assertEquals(
            RootMount.MountDiagnosis.GENERIC,
            classify(attempts, "   \n\t \n", RootMount.FuseOutcome.MOUNT_REFUSED),
        )
    }

    @Test fun `a mounted FUSE whose daemon never served blames the daemon, not the kernel`() {
        val attempts = listOf(RootMount.Attempt(1, "mount.nfs: Connection timed out"))

        assertEquals(
            RootMount.MountDiagnosis.FUSE_DAEMON_FAILED,
            classify(attempts, withFuse, RootMount.FuseOutcome.DAEMON_SILENT),
        )
        assertEquals(
            RootMount.MountDiagnosis.FUSE_DAEMON_FAILED,
            classify(attempts, withFuse, RootMount.FuseOutcome.MOUNT_UNRESPONSIVE),
        )
    }

    @Test fun `a refused or otherwise failed FUSE mount on a fuse-capable kernel stays generic`() {
        val attempts = listOf(RootMount.Attempt(1, "mount.nfs: Connection timed out"))

        assertEquals(
            RootMount.MountDiagnosis.GENERIC,
            classify(attempts, withFuse, RootMount.FuseOutcome.MOUNT_REFUSED),
        )
        assertEquals(
            RootMount.MountDiagnosis.GENERIC,
            classify(attempts, withFuse, RootMount.FuseOutcome.FAILED),
        )
    }

    @Test fun `a ladder that never reached the FUSE rung gets no FUSE verdict`() {
        val attempts = listOf(
            RootMount.Attempt(32, "mount: no such device"),
            RootMount.Attempt(32, "mount: no such device"),
        )

        assertEquals(RootMount.MountDiagnosis.GENERIC, classify(attempts, withoutFuse, null))
        assertEquals(RootMount.MountDiagnosis.GENERIC, classify(attempts, withFuse, null))
    }

    @Test fun `a fs type is matched on the whole column, so fuseblk is not fuse`() {
        assertTrue(RootMount.hasFilesystem("nodev\tfuse\n", "fuse"))
        assertFalse(RootMount.hasFilesystem("nodev\tfuseblk\n", "fuse"))
        assertFalse(RootMount.hasFilesystem("nodev\tnfsd\n\tnfs_common\n", "nfs"))
        assertTrue(RootMount.hasFilesystem("nodev\tsysfs\n\text4\n", "ext4"))
        assertFalse(RootMount.hasFilesystem("", "fuse"))
    }

    /** The b/c split: with nothing registered at all, evidence of a module file on
     *  the device must produce MODULE_FILES_PRESENT, and an empty listing must keep
     *  the old verdict — otherwise the new string would fire on truly bare kernels. */
    @Test fun `module files present with nothing registered names the unloadable module`() {
        val attempts = listOf(
            RootMount.Attempt(32, "mount: no such device"),
            RootMount.Attempt(32, "mount: no such device"),
        )

        assertEquals(
            RootMount.MountDiagnosis.MODULE_FILES_PRESENT,
            classify(attempts, bareProc, null, "/vendor/lib/modules/fuse.ko\n"),
        )
        assertEquals(
            RootMount.MountDiagnosis.MODULE_FILES_PRESENT,
            classify(attempts, bareProc, null, "/system/lib/modules/nfs.ko\n"),
        )
    }

    @Test fun `an empty module listing keeps the neither-nor verdict`() {
        val attempts = listOf(
            RootMount.Attempt(32, "mount: no such device"),
            RootMount.Attempt(32, "mount: no such device"),
        )
        assertEquals(RootMount.MountDiagnosis.GENERIC, classify(attempts, withoutFuse, null))
    }

    /** The b/c split must not disturb the old verdicts: a FUSE rung that failed with
     *  fuse unregistered and no module file anywhere is still the neither-nor case. */
    @Test fun `failed fuse rung with no module files keeps the kernel verdict`() {
        val attempts = listOf(RootMount.Attempt(1, "mount: unknown filesystem type 'fuse'"))

        assertEquals(
            RootMount.MountDiagnosis.KERNEL_LACKS_FUSE,
            classify(attempts, bareProc, RootMount.FuseOutcome.FAILED),
        )
        assertEquals(
            RootMount.MountDiagnosis.KERNEL_LACKS_FUSE,
            classify(attempts, bareProc, RootMount.FuseOutcome.MOUNT_REFUSED),
        )
    }

    /** A fuse.ko anywhere in the listing must be enough even when the kernel rungs
     *  never got as far as a FUSE attempt. */
    @Test fun `grep finds fuse dot ko among other module files`() {
        val attempts = listOf(
            RootMount.Attempt(32, "mount: no such device"),
            RootMount.Attempt(32, "mount: no such device"),
        )
        val listing = """
            /vendor/lib/modules/exfat.ko
            /vendor/lib/modules/fuse.ko
            /system/lib/modules/xt_qtaguid.ko
        """.trimIndent()

        assertEquals(
            RootMount.MountDiagnosis.MODULE_FILES_PRESENT,
            classify(attempts, bareProc, null, listing),
        )
    }

    /** The three verdicts that infer "nothing registered" from the fs text: each must
     *  refuse to speak when the text is blank, whatever the other inputs say. */
    @Test fun `a blank fs list is no evidence for any kernel claim`() {
        val failedKernel = listOf(RootMount.Attempt(32, "mount: no such device"))

        assertEquals(
            RootMount.MountDiagnosis.GENERIC,
            classify(failedKernel, "", null, "/vendor/lib/modules/fuse.ko\n"),
        )
        assertEquals(
            RootMount.MountDiagnosis.GENERIC,
            classify(emptyList(), "\n", RootMount.FuseOutcome.MOUNT_REFUSED),
        )
    }

    /** Observed outcomes still speak on blank text: the mount landed and the daemon
     *  never served, which claims nothing about the kernel either way. */
    @Test fun `a silent daemon is blamed even when the fs list is blank`() {
        assertEquals(
            RootMount.MountDiagnosis.FUSE_DAEMON_FAILED,
            classify(emptyList(), "", RootMount.FuseOutcome.DAEMON_SILENT),
        )
    }

    /** NO_DEVICE stays unconditional on purpose: it is observed by open("/dev/fuse")
     *  failing, not inferred from any /proc read. */
    @Test fun `a missing fuse device is a kernel claim even with blank text`() {
        assertEquals(
            RootMount.MountDiagnosis.KERNEL_LACKS_FUSE,
            classify(emptyList(), "", RootMount.FuseOutcome.NO_DEVICE),
        )
    }

    /** The mount scripts dump /proc/filesystems in the same root run, after the
     *  marker; the classifier must eat exactly that section. */
    @Test fun `the root fs list is taken from the marked section of su output`() {
        val stdout = """
            /dev/root / ext4 ro 0 0
            __MAMMON_FS_LIST__
            nodev	sysfs
            	ext4
            nodev	fuse
        """.trimIndent()

        assertEquals("nodev\tsysfs\n\text4\nnodev\tfuse", RootMount.fsListFromRoot(stdout))
        // The ROOT-context text decides, here one without fuse registered.
        assertEquals(
            RootMount.MountDiagnosis.KERNEL_LACKS_FUSE,
            classify(emptyList(), RootMount.fsListFromRoot("__MAMMON_FS_LIST__\nnodev\tnfs\n"), RootMount.FuseOutcome.FAILED),
        )
        assertEquals(
            RootMount.MountDiagnosis.GENERIC,
            classify(
                emptyList(),
                RootMount.fsListFromRoot("x /mnt/nas nfs rw 0 0\n__MAMMON_FS_LIST__\nnodev\tnfs4\n"),
                null,
            ),
        )
    }

    @Test fun `su output without the marker yields a blank fs list but intact mounts`() {
        val legacy = "/dev/root / ext4 ro 0 0"

        assertEquals("", RootMount.fsListFromRoot(legacy))
        assertNull(RootMount.mountsFromRoot(null))
        assertEquals(legacy, RootMount.mountsFromRoot(legacy))
        assertEquals(
            "/dev/root / ext4 ro 0 0\n",
            RootMount.mountsFromRoot("$legacy\n__MAMMON_FS_LIST__\nnodev\tfuse\n"),
        )
        // A marker-less capture yields a blank fs list, so no kernel claim is possible.
        assertEquals(
            RootMount.MountDiagnosis.GENERIC,
            classify(
                emptyList(),
                RootMount.fsListFromRoot("/dev/root / ext4 ro 0 0\n"),
                RootMount.FuseOutcome.FAILED,
            ),
        )
    }

    @Test fun `each FUSE script exit code maps to how far the rung got`() {
        assertNull(RootMount.fuseOutcomeFor(0))
        assertEquals(RootMount.FuseOutcome.FAILED, RootMount.fuseOutcomeFor(71))
        assertEquals(RootMount.FuseOutcome.NO_DEVICE, RootMount.fuseOutcomeFor(73))
        assertEquals(RootMount.FuseOutcome.MOUNT_REFUSED, RootMount.fuseOutcomeFor(74))
        assertEquals(RootMount.FuseOutcome.DAEMON_SILENT, RootMount.fuseOutcomeFor(75))
        assertEquals(RootMount.FuseOutcome.MOUNT_UNRESPONSIVE, RootMount.fuseOutcomeFor(76))
        assertEquals(RootMount.FuseOutcome.FAILED, RootMount.fuseOutcomeFor(1))
        assertEquals(RootMount.FuseOutcome.FAILED, RootMount.fuseOutcomeFor(255))
    }

    /** The dump block must be reachable on EVERY classified exit: a failure whose
     *  stdout never carries the marker silently degrades to the unprivileged read
     *  the root capture was introduced to replace. */
    @Test fun `the kernel script dumps mounts and fs list after a failed mount`() {
        val script = RootMount.kernelMountScript("192.0.2.1", "/export", 2049, "/mnt/nas", "4.2")

        assertTrue("marker missing:\n$script", "__MAMMON_FS_LIST__" in script)
        assertTrue("cat /proc/filesystems must follow the marker",
            script.indexOf("__MAMMON_FS_LIST__") < script.indexOf("cat /proc/filesystems"))
        // mount's status is captured before the dumps, which run unconditionally.
        assertFalse(Regex("\\bmount -t nfs[^\n]*&&").containsMatchIn(script))
    }

    @Test fun `every fuse exit that classifies emits the dump first`() {
        val script = RootMount.fuseMountScript(
            "192.0.2.1", "/export", 2049, "/mnt/nas", RootMount.FuseLaunch("/data/app/apk", "/cache/l", AuthIdentity.DEFAULT),
        )

        // The refused exit dumps before exiting; the silent/unresponsive teardowns
        // reuse mammon_dump inside teardown(); the success tail is a bare call whose
        // group redirection closes after it.
        assertTrue(
            "refused exit must dump",
            "|| { mammon_dump; exit 74; }" in script,
        )
        assertTrue(
            "teardown dumps too",
            "umount -l '/mnt/nas' 2>/dev/null; mammon_dump; exit" in script,
        )
        assertTrue(
            "success tail dumps",
            script.trimEnd().endsWith("mammon_dump\n} 3<>/dev/fuse"),
        )
        assertTrue(
            "dump defined before first use",
            script.indexOf("mammon_dump() {") < script.indexOf("exec 3<>/dev/fuse"),
        )
    }

    /** End-to-end over a real shell: a failing kernel rung still prints both dumps,
     *  and the parsers recover mounts text plus fs list from it. */
    @Test fun `a failed kernel rung still yields the root fs list through sh`() {
        val script = RootMount.kernelMountScript("192.0.2.1", "/export", 2049, "/nonexistent-root/mp", "4.2")
        val proc = ProcessBuilder("sh", "-c", script).start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        assertTrue("marker must reach stdout even on failure", "__MAMMON_FS_LIST__" in out)
        assertNotNull(RootMount.mountsFromRoot(out))
        assertEquals(java.io.File("/proc/filesystems").readText().trim(), RootMount.fsListFromRoot(out))
    }
}
