package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The mount diagnosis must name the cause the user can act on. Each branch is driven
 * from a synthetic (exit code, stderr, /proc/filesystems) triple, so no root, no device
 * and no kernel are needed to pin the ordering down.
 */
class RootMountDiagnosisTest {

    private val withNfs = """
        nodev	sysfs
        	ext4
        nodev	fuse
        nodev	nfs
        nodev	nfs4
    """.trimIndent()

    private val withoutNfs = """
        nodev	sysfs
        	ext4
        nodev	fuse
    """.trimIndent()

    private fun classify(
        attempts: List<RootMount.Attempt>,
        filesystems: String,
    ) = RootMount.classifyMountFailure(attempts, filesystems)

    @Test fun `su that cannot be executed is reported as no root, never as a kernel claim`() {
        val attempts = listOf(
            RootMount.Attempt(127, "Cannot run program \"su\": error=2, No such file or directory"),
        )

        assertEquals(RootMount.MountDiagnosis.NO_ROOT, classify(attempts, withNfs))
        // The kernel here HAS nfs; a missing su must not be blamed on the kernel.
        assertEquals(RootMount.MountDiagnosis.NO_ROOT, classify(attempts, withoutNfs))
    }

    @Test fun `exit 127 alone is enough, and outranks an ENODEV from another version`() {
        val attempts = listOf(
            RootMount.Attempt(1, "mount: no such device"),
            RootMount.Attempt(127, "su: not found"),
        )

        assertEquals(RootMount.MountDiagnosis.NO_ROOT, classify(attempts, withNfs))
    }

    @Test fun `absent nfs and nfs4 fs types are reported as a kernel without NFS`() {
        val attempts = listOf(
            RootMount.Attempt(32, "mount: unknown filesystem type 'nfs'"),
            RootMount.Attempt(32, "mount: unknown filesystem type 'nfs'"),
        )

        assertEquals(RootMount.MountDiagnosis.KERNEL_LACKS_NFS, classify(attempts, withoutNfs))
    }

    @Test fun `nfs4 alone still counts as kernel NFS support`() {
        val onlyV4 = "nodev\tsysfs\nnodev\tnfs4"
        val attempts = listOf(
            RootMount.Attempt(32, "mount: no such device"),
            RootMount.Attempt(32, "mount: no such device"),
        )

        assertEquals(RootMount.MountDiagnosis.VERSION_MODULE_MISSING, classify(attempts, onlyV4))
    }

    @Test fun `a name that merely contains nfs is not the nfs fs type`() {
        val attempts = listOf(RootMount.Attempt(1, "mount: some other failure"))

        assertEquals(
            RootMount.MountDiagnosis.KERNEL_LACKS_NFS,
            classify(attempts, "nodev\tnfsd\n\tnfs_common"),
        )
    }

    @Test fun `ENODEV from every attempted version with the fs type present blames the module`() {
        val attempts = listOf(
            RootMount.Attempt(32, "mount: no such device"),
            RootMount.Attempt(32, "mount: unknown filesystem type 'nfs'"),
        )

        assertEquals(RootMount.MountDiagnosis.VERSION_MODULE_MISSING, classify(attempts, withNfs))
    }

    @Test fun `ENODEV on only one version is not a module verdict`() {
        val attempts = listOf(
            RootMount.Attempt(32, "mount: no such device"),
            RootMount.Attempt(1, "mount.nfs: access denied by server"),
        )

        assertEquals(RootMount.MountDiagnosis.GENERIC, classify(attempts, withNfs))
    }

    @Test fun `any other failure stays generic`() {
        val attempts = listOf(
            RootMount.Attempt(1, "mount.nfs: Connection timed out"),
            RootMount.Attempt(1, "mount.nfs: Connection timed out"),
        )

        assertEquals(RootMount.MountDiagnosis.GENERIC, classify(attempts, withNfs))
    }

    @Test fun `an unreadable proc filesystems does not become a kernel-supports-NFS claim`() {
        val attempts = listOf(RootMount.Attempt(1, "mount.nfs: Connection timed out"))

        assertEquals(RootMount.MountDiagnosis.KERNEL_LACKS_NFS, classify(attempts, ""))
    }
}
