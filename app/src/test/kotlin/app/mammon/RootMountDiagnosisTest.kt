package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun classify(
        attempts: List<RootMount.Attempt>,
        filesystems: String,
        fuse: RootMount.FuseOutcome?,
    ) = RootMount.classifyMountFailure(attempts, filesystems, fuse)

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

    @Test fun `an unreadable proc filesystems does not become a fuse-capable claim`() {
        val attempts = listOf(RootMount.Attempt(1, "mount.nfs: Connection timed out"))

        assertEquals(
            RootMount.MountDiagnosis.KERNEL_LACKS_FUSE,
            classify(attempts, "", RootMount.FuseOutcome.FAILED),
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
}
