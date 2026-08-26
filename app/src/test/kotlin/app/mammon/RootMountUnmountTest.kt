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
}
