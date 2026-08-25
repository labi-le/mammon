package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootMountNamespaceTest {

    /** The backings a mammon mount can have: the two kernel rungs plus the FUSE daemon. */
    private fun mountedAt(content: String, mountpoint: String): MountEntry? =
        MountsParser.parse(content).find {
            it.mountPoint == mountpoint &&
                (it.fsType == "nfs" || it.fsType == "nfs4" || it.fsType == "fuse")
        }

    @Test fun `mountedAt matches only nfs, nfs4 and fuse at exact point`() {
        val proc = """
            /dev/block/by-name/system /system ext4 ro 0 0
            192.0.2.1:/export/data /mnt/nas nfs rw,vers=3,nolock 0 0
            tmpfs /mnt/nas/inner tmpfs rw 0 0
        """.trimIndent()

        assertEquals("nfs", mountedAt(proc, "/mnt/nas")?.fsType)
        assertNull(mountedAt(proc, "/mnt"))
        assertNull(mountedAt(proc, "/mnt/nas/inner"))
        assertNull(mountedAt(proc, "/storage/emulated/0"))

        val v4 = proc.replace(" nfs ", " nfs4 ")
        assertEquals("nfs4", mountedAt(v4, "/mnt/nas")?.fsType)

        // an ext4 bind at the same point means our nfs view is gone
        val ext4 = proc.replace(" nfs ", " ext4 ")
        assertNull(mountedAt(ext4, "/mnt/nas"))
    }

    @Test fun `a fuse backing at the mountpoint counts as mounted`() {
        val proc = """
            /dev/block/by-name/system /system ext4 ro 0 0
            /dev/fuse /mnt/nas fuse ro,nosuid,nodev,user_id=0,group_id=0,allow_other 0 0
        """.trimIndent()

        assertEquals("fuse", mountedAt(proc, "/mnt/nas")?.fsType)
        assertNull(mountedAt(proc, "/mnt"))
        assertNull(mountedAt(proc, "/mnt/nas/inner"))
    }

    /** fuseblk is a block-device FUSE mount nothing here produces, so it must not be
     *  read as a mammon mount. */
    @Test fun `fuseblk at the mountpoint is not a mammon backing`() {
        val proc = "/dev/block/sda1 /mnt/nas fuseblk rw 0 0"

        assertNull(mountedAt(proc, "/mnt/nas"))
    }

    /**
     * The predicate above states the intent; this drives the production classifier with
     * the same text, so narrowing the fs-type set fails here rather than only in the UI.
     */
    @Test fun `mountedState recognises every backing the ladder can leave behind`() {
        val nfs = "192.0.2.1:/export/data /mnt/nas nfs rw,vers=3,nolock 0 0"
        val nfs4 = "192.0.2.1:/export/data /mnt/nas nfs4 rw,vers=4.2 0 0"
        val fuse = "/dev/fuse /mnt/nas fuse ro,nosuid,nodev,user_id=0,group_id=0 0 0"

        assertEquals(RootMount.State.MOUNTED, RootMount.mountedState("/mnt/nas", nfs))
        assertEquals(RootMount.State.MOUNTED, RootMount.mountedState("/mnt/nas", nfs4))
        assertEquals(RootMount.State.MOUNTED, RootMount.mountedState("/mnt/nas", fuse))

        assertEquals(RootMount.State.NOT_MOUNTED, RootMount.mountedState("/mnt", fuse))
        assertEquals(
            RootMount.State.NOT_MOUNTED,
            RootMount.mountedState("/mnt/nas", "/dev/block/sda1 /mnt/nas fuseblk rw 0 0"),
        )
        // No text at all is not evidence of an absent mount: su may simply have failed.
        assertEquals(RootMount.State.UNKNOWN, RootMount.mountedState("/mnt/nas", ""))
    }
}
