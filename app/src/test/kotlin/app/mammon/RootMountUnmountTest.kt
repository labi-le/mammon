package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootMountUnmountTest {

    private val unmounted = "/dev/root / ext4 ro 0 0\n"

    private val mounted = """
        /dev/root / ext4 ro 0 0
        192.0.2.1:/export/data /mnt/nas nfs rw,vers=3,nolock 0 0
    """.trimIndent()

    private val replaced = """
        /dev/root / ext4 ro 0 0
        /dev/block/dm-8 /mnt/nas ext4 rw 0 0
    """.trimIndent()

    @Test fun `unmount script always dumps global mounts before returning umount status`() {
        assertEquals(
            """
            umount '/mnt/nas'
            S=${'$'}?
            cat /proc/1/mounts
            exit ${'$'}S
            """.trimIndent(),
            RootMount.unmountScript("/mnt/nas"),
        )
    }


    @Test fun `invalid argument with no remaining mount is treated as not mounted`() {
        val result = RootMount.classifyUnmountResult(
            "/mnt/nas",
            RootMount.SuResult(1, unmounted, "umount: /mnt/nas: Invalid argument"),
        )

        assertTrue(result.ok)
        assertEquals("not mounted", result.message)
        assertEquals(RootMount.State.NOT_MOUNTED, result.stateAfter)
        assertNull(result.fsType)
    }

    @Test fun `invalid argument with the mount still present stays a failure`() {
        val result = RootMount.classifyUnmountResult(
            "/mnt/nas",
            RootMount.SuResult(1, mounted, "umount: /mnt/nas: Invalid argument"),
        )

        assertFalse(result.ok)
        assertEquals("umount failed: umount: /mnt/nas: Invalid argument", result.message)
        assertEquals(RootMount.State.MOUNTED, result.stateAfter)
        assertEquals("nfs", result.fsType)
    }

    @Test fun `a different filesystem still counts as a remaining mountpoint`() {
        val result = RootMount.classifyUnmountResult(
            "/mnt/nas",
            RootMount.SuResult(1, replaced, "umount: /mnt/nas: Invalid argument"),
        )

        assertFalse(result.ok)
        assertEquals("umount failed: umount: /mnt/nas: Invalid argument", result.message)
    }

    @Test fun `busy still reports busy when the mount remains`() {
        val result = RootMount.classifyUnmountResult(
            "/mnt/nas",
            RootMount.SuResult(1, mounted, "umount: /mnt/nas: Device or resource busy"),
        )

        assertFalse(result.ok)
        assertEquals("target is busy — close apps using it first", result.message)
        assertEquals(RootMount.State.MOUNTED, result.stateAfter)
        assertEquals("nfs", result.fsType)
    }

    /**
     * The routing read precedes every unmount, and on a phone without root it fails as
     * SU_TIMED_OUT_CODE after SU_TIMEOUT_SECONDS. A literal-path fallback then spawns a
     * second su that cannot succeed either, so the Unmount button held both buttons
     * disabled for 60 s instead of 30 and still reported a umount error rather than root.
     */
    @Test fun `an unmount that cannot get su returns the root verdict without a second spawn`() {
        val spawned = mutableListOf<String>()

        val result = RootMount.unmount("/storage/emulated/0/nfs") { script ->
            spawned += script
            RootMount.SuResult(124, null, "su timed out")
        }

        assertEquals(listOf("cat /proc/1/mountinfo"), spawned)
        assertFalse(result.ok)
        assertEquals(RootMount.MountDiagnosis.NO_ROOT, result.diagnosis)
        assertFalse("the message must name root, not umount: ${result.message}", result.message.contains("umount"))
    }

    /** Only the TOOL being absent short-circuits. A path refusal is decided before any
     *  su runs, and the mount it names may still be standing from another entrance. */
    @Test fun `a path the router refuses is still unmounted literally`() {
        val spawned = mutableListOf<String>()

        val result = RootMount.unmount("/data/media/0/nfs") { script ->
            spawned += script
            RootMount.SuResult(0, unmounted, "")
        }

        assertEquals(listOf(RootMount.unmountScript("/data/media/0/nfs")), spawned)
        assertTrue(result.ok)
        assertNull("a literal unmount is not routed", result.appVisible)
    }

    /** The two spawns a routed unmount really needs, and the master path in the second:
     *  a slave never propagates to its master, so the view-side umount withdraws nothing. */
    @Test fun `a routed unmount reads the table once and umounts the master path`() {
        val mountinfo = """
            2860 918 0:122 / /mnt/user/0/emulated rw,relatime shared:805 - fuse /dev/fuse rw,user_id=0
            2890 957 0:122 / /storage/emulated rw,relatime master:805 - fuse /dev/fuse rw,user_id=0
        """.trimIndent()
        val spawned = mutableListOf<String>()

        val result = RootMount.unmount("/storage/emulated/0/nfs") { script ->
            spawned += script
            RootMount.SuResult(0, if (spawned.size == 1) mountinfo else unmounted, "")
        }

        assertEquals(
            listOf("cat /proc/1/mountinfo", RootMount.unmountScript("/mnt/user/0/emulated/0/nfs")),
            spawned,
        )
        assertTrue(result.ok)
        assertEquals("unmounted /storage/emulated/0/nfs", result.message)
        assertEquals("/storage/emulated/0/nfs", result.appVisible)
    }
}
