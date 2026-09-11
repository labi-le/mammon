package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class MountsParserTest {

    private val sample = """
        rootfs / rootfs rw 0 0
        /dev/block/by-name/system /system ext4 ro,seclabel,relatime 0 0
        192.0.2.1:/export/data /mnt/nas nfs4 rw,relatime,vers=4.1 0 0
        tmpfs /dev tmpfs rw,seclabel,nosuid 0 0
        /dev/fuse /mnt/runtime/default/emulated fuse rw,nosuid 0 0
        192.0.2.7:/tank /storage/with\040space nfs rw,vers=3,nolock 0 0
    """.trimIndent()

    @Test fun `parses entries`() {
        val mounts = MountsParser.parse(sample)
        assertEquals(6, mounts.size)
        val nfs4 = mounts.first { it.mountPoint == "/mnt/nas" }
        assertEquals("nfs4", nfs4.fsType)
        assertEquals("192.0.2.1:/export/data", nfs4.device)
    }

    @Test fun `skips malformed lines`() {
        val mounts = MountsParser.parse("one two\n\n/dev/x /x ext4 rw 0 0")
        assertEquals(1, mounts.size)
        assertEquals("ext4", mounts[0].fsType)
    }

    @Test fun `octal space unescaped`() {
        val mounts = MountsParser.parse(sample)
        val spaced = mounts.first { it.fsType == "nfs" }
        assertEquals("/storage/with space", spaced.mountPoint)
    }

    @Test fun `mountedAt matches type and point`() {
        val mounts = MountsParser.parse(sample)
        val found = mounts.find {
            it.mountPoint == "/mnt/nas" && (it.fsType == "nfs" || it.fsType == "nfs4")
        }
        assertNotNull(found)
        assertNull(mounts.find { it.mountPoint == "/mnt/other" })
        // a v4 mount is detected but the app's v3 mount flow would still be separate state
        assertNotNull(mounts.find { it.mountPoint == "/storage/with space" && it.fsType == "nfs" })
    }

    @Test fun `empty content yields empty list`() {
        assertEquals(0, MountsParser.parse("").size)
    }

    @Test fun `backslash octal escape decoded`() {
        assertEquals("a b", MountsParser.unescape("a\\040b"))
        assertEquals("tab\there?", MountsParser.unescape("tab\\011here?"))
    }
}
