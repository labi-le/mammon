package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootMountNamespaceTest {

    private fun findNfs(content: String, mountpoint: String): MountEntry? =
        MountsParser.parse(content).find {
            it.mountPoint == mountpoint && (it.fsType == "nfs" || it.fsType == "nfs4")
        }

    @Test fun `mountedAt matches only nfs and nfs4 at exact point`() {
        val proc = """
            /dev/block/by-name/system /system ext4 ro 0 0
            192.0.2.1:/export/data /mnt/nas nfs rw,vers=3,nolock 0 0
            tmpfs /mnt/nas/inner tmpfs rw 0 0
        """.trimIndent()

        assertEquals("nfs", findNfs(proc, "/mnt/nas")?.fsType)
        assertNull(findNfs(proc, "/mnt"))
        assertNull(findNfs(proc, "/mnt/nas/inner"))
        assertNull(findNfs(proc, "/storage/emulated/0"))

        val v4 = proc.replace(" nfs ", " nfs4 ")
        assertEquals("nfs4", findNfs(v4, "/mnt/nas")?.fsType)

        // an ext4 bind at the same point means our nfs view is gone
        val ext4 = proc.replace(" nfs ", " ext4 ")
        assertNull(findNfs(ext4, "/mnt/nas"))
    }
}
