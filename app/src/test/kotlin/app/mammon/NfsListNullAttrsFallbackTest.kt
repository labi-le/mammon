package app.mammon

import com.emc.ecs.nfsclient.nfs.NfsDirectoryPlusEntry
import com.emc.ecs.nfsclient.nfs.NfsType
import com.emc.ecs.nfsclient.rpc.Xdr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the READDIRPLUS null-attributes fallback through [NfsAccess.resolveEntry]:
 * an entry whose attributes the server withheld must survive via one stat, and be
 * dropped when that stat fails. Entries are built through the library's own XDR
 * decoder so the fixture shape cannot drift from what readdirplus really returns.
 */
class NfsListNullAttrsFallbackTest {

    /** Encodes one READDIRPLUS entry: name, attrs-present flag (+fattr3), fh flag. */
    private fun entry(name: String, withAttrs: Boolean, type: NfsType): NfsDirectoryPlusEntry {
        val x = Xdr(1024)
        x.putLong(1)                       // fileId
        x.putString(name)
        x.putLong(2)                       // cookie
        x.putBoolean(withAttrs)
        if (withAttrs) {
            x.putInt(type.value)           // fattr3.type
            repeat(4) { x.putInt(0) }      // mode, nlink, uid, gid
            x.putLong(0)                   // size
            x.putLong(0)                   // used
            x.putInt(0); x.putInt(0)       // rdev specdata
            x.putLong(0)                   // fsid
            x.putLong(0)                   // fileid
            repeat(6) { x.putInt(0) }      // atime/mtime/ctime (seconds + nsec)
        }
        x.putBoolean(false)                // no file handle follows
        x.setOffset(0)
        return NfsDirectoryPlusEntry(x)
    }

    @Test fun `kept child with carried attributes needs no stat`() {
        val e = entry("dir", true, NfsType.NFS_DIR)
        var stats = 0
        val child = NfsAccess.resolveEntry("/", e, { stats++; error("must not stat") })
        assertEquals("/dir", child!!.path)
        assertTrue(child.attributes.isDirectory)
        assertEquals(0, stats)
    }

    @Test fun `null attributes fall back to exactly one stat`() {
        val e = entry("file.txt", false, NfsType.NFS_DIR)
        val statAttrs = entry("probe", true, NfsType.NFS_REG).attributes!!
        var stats = 0
        val child = NfsAccess.resolveEntry("/", e) { path ->
            stats++
            assertEquals("/file.txt", path)
            statAttrs
        }
        assertEquals("/file.txt", child!!.path)
        assertFalse(child.attributes.isDirectory)
        assertEquals(1, stats)
    }

    @Test fun `entry dropped when the fallback stat finds nothing`() {
        assertNull(NfsAccess.resolveEntry("/", entry("gone", false, NfsType.NFS_DIR)) { null })
    }

    @Test fun `dot names dropped before any stat happens`() {
        assertNull(
            NfsAccess.resolveEntry("/", entry("..", false, NfsType.NFS_DIR)) {
                error("must not stat")
            },
        )
    }

    @Test fun `carried attributes do not rescue dot names`() {
        assertNull(
            NfsAccess.resolveEntry("/", entry(".", true, NfsType.NFS_DIR)) {
                error("must not stat")
            },
        )
    }

    @Test fun `non listable stat type is dropped after fallback`() {
        assertNull(
            NfsAccess.resolveEntry("/", entry("fifo", false, NfsType.NFS_DIR)) {
                entry("fifo", true, NfsType.NFS_FIFO).attributes
            },
        )
    }
}
