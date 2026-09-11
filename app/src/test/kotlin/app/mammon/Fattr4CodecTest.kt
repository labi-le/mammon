package app.mammon

import org.dcache.nfs.v4.xdr.attrlist4
import org.dcache.nfs.v4.xdr.bitmap4
import org.dcache.nfs.v4.xdr.fattr4
import org.dcache.nfs.v4.xdr.nfs4_prot
import org.dcache.nfs.v4.xdr.nfs_ftype4
import org.dcache.oncrpc4j.xdr.Xdr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the fattr4 decode: an attribute list is a bare concatenation in ascending
 * bitmap order, so both the ordering and the refusal to guess past an attribute the
 * request never asked for are load-bearing. Fixtures are encoded with the library's
 * own XDR writer so they cannot drift from what a server really sends.
 */
class Fattr4CodecTest {

    private fun fattr(vararg bits: Int, values: Xdr.() -> Unit): fattr4 {
        val xdr = Xdr(1024)
        xdr.beginEncoding()
        xdr.values()
        xdr.endEncoding()
        return fattr4().apply {
            attrmask = bitmap4.of(*bits)
            attr_vals = attrlist4(xdr.bytes)
        }
    }

    @Test fun `directory decodes type size and modification time`() {
        val attrs = Fattr4Codec.decode(
            fattr(nfs4_prot.FATTR4_TYPE, nfs4_prot.FATTR4_SIZE, nfs4_prot.FATTR4_TIME_MODIFY) {
                xdrEncodeInt(nfs_ftype4.NF4DIR)
                xdrEncodeLong(4096L)
                xdrEncodeLong(1_700_000_000L)
                xdrEncodeInt(123_456_789)
            },
        )!!
        assertTrue(attrs.isDirectory)
        assertFalse(attrs.isRegular)
        assertEquals(4096L, attrs.size)
        assertEquals(1_700_000_000_123L, attrs.mtimeMillis)
    }

    @Test fun `regular file is listable and not a directory`() {
        val attrs = Fattr4Codec.decode(
            fattr(nfs4_prot.FATTR4_TYPE, nfs4_prot.FATTR4_SIZE, nfs4_prot.FATTR4_TIME_MODIFY) {
                xdrEncodeInt(nfs_ftype4.NF4REG)
                xdrEncodeLong(2_015_390L)
                xdrEncodeLong(1L)
                xdrEncodeInt(0)
            },
        )!!
        assertFalse(attrs.isDirectory)
        assertTrue(attrs.isRegular)
        assertTrue(attrs.isListable)
        assertEquals(2_015_390L, attrs.size)
        assertEquals(NodeAttrs(false, 2_015_390L, 1000L), attrs.toNode())
    }

    @Test fun `symlink decodes but is not listable`() {
        val attrs = Fattr4Codec.decode(
            fattr(nfs4_prot.FATTR4_TYPE, nfs4_prot.FATTR4_SIZE, nfs4_prot.FATTR4_TIME_MODIFY) {
                xdrEncodeInt(nfs_ftype4.NF4LNK)
                xdrEncodeLong(7L)
                xdrEncodeLong(0L)
                xdrEncodeInt(0)
            },
        )!!
        assertFalse(attrs.isListable)
    }

    @Test fun `a fattr4 without the type attribute is undecodable`() {
        assertNull(
            Fattr4Codec.decode(
                fattr(nfs4_prot.FATTR4_SIZE, nfs4_prot.FATTR4_TIME_MODIFY) {
                    xdrEncodeLong(1L)
                    xdrEncodeLong(0L)
                    xdrEncodeInt(0)
                },
            ),
        )
    }

    @Test fun `an attribute the request never asked for is not guessed past`() {
        assertNull(
            Fattr4Codec.decode(
                fattr(nfs4_prot.FATTR4_TYPE, nfs4_prot.FATTR4_MODE, nfs4_prot.FATTR4_TIME_MODIFY) {
                    xdrEncodeInt(nfs_ftype4.NF4REG)
                    xdrEncodeInt(0x1ED)
                    xdrEncodeLong(0L)
                    xdrEncodeInt(0)
                },
            ),
        )
    }

    @Test fun `withheld attributes decode to nothing`() {
        assertNull(Fattr4Codec.decode(null))
    }
}
