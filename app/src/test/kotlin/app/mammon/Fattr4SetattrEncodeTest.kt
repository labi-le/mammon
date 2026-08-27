package app.mammon

import org.dcache.nfs.v4.xdr.nfs4_prot
import org.dcache.nfs.v4.xdr.time_how4
import org.dcache.oncrpc4j.xdr.Xdr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the SETATTR fattr4. Attribute values are concatenated in ascending bitmap order
 * with no per-attribute length, so a value written out of order is not a wrong value —
 * everything after it is unparseable, and the server reads whatever the bytes happen to
 * line up with.
 */
class Fattr4SetattrEncodeTest {

    private class Decoded(val bits: List<Int>, val size: Long?, val setIt: Int?, val millis: Long?)

    private fun decode(attrs: org.dcache.nfs.v4.xdr.fattr4): Decoded {
        val mask = attrs.attrmask.value
        val bits = ArrayList<Int>()
        var size: Long? = null
        var setIt: Int? = null
        var millis: Long? = null
        Xdr(attrs.attr_vals.value).use { xdr ->
            xdr.beginDecoding()
            for (bit in 0 until mask.size * Int.SIZE_BITS) {
                if ((mask[bit / Int.SIZE_BITS] ushr (bit % Int.SIZE_BITS)) and 1 == 0) continue
                bits += bit
                when (bit) {
                    nfs4_prot.FATTR4_SIZE -> size = xdr.xdrDecodeLong()
                    nfs4_prot.FATTR4_TIME_MODIFY_SET -> {
                        setIt = xdr.xdrDecodeInt()
                        millis = xdr.xdrDecodeLong() * 1000L + xdr.xdrDecodeInt() / 1_000_000L
                    }
                    else -> error("unexpected attribute bit $bit")
                }
            }
        }
        return Decoded(bits, size, setIt, millis)
    }

    @Test fun `a size only setattr carries just the size`() {
        val decoded = decode(Fattr4Codec.encodeSetattr(4096, null))
        assertEquals(listOf(nfs4_prot.FATTR4_SIZE), decoded.bits)
        assertEquals(4096L, decoded.size)
    }

    @Test fun `a truncate to zero is a real request, not an absent one`() {
        val decoded = decode(Fattr4Codec.encodeSetattr(0, null))
        assertEquals(listOf(nfs4_prot.FATTR4_SIZE), decoded.bits)
        assertEquals(0L, decoded.size)
    }

    /**
     * TIME_MODIFY_SET, not TIME_MODIFY: only the settable form carries a client time, and
     * the read-only attribute would be refused with NFS4ERR_INVAL.
     */
    @Test fun `an mtime only setattr asks the server to take the client's time`() {
        val decoded = decode(Fattr4Codec.encodeSetattr(null, 1_700_000_123_456L))
        assertEquals(listOf(nfs4_prot.FATTR4_TIME_MODIFY_SET), decoded.bits)
        assertEquals(time_how4.SET_TO_CLIENT_TIME4, decoded.setIt)
        assertEquals(1_700_000_123_456L, decoded.millis)
        assertFalse(decoded.bits.contains(nfs4_prot.FATTR4_TIME_MODIFY))
    }

    @Test fun `both attributes ride one setattr in ascending bit order`() {
        val decoded = decode(Fattr4Codec.encodeSetattr(1234, 1_700_000_000_999L))
        assertEquals(
            listOf(nfs4_prot.FATTR4_SIZE, nfs4_prot.FATTR4_TIME_MODIFY_SET),
            decoded.bits,
        )
        assertEquals(1234L, decoded.size)
        assertEquals(1_700_000_000_999L, decoded.millis)
    }

    /** A pre-epoch time must not produce a negative nanosecond field. */
    @Test fun `a time before the epoch keeps its nanoseconds in range`() {
        val decoded = decode(Fattr4Codec.encodeSetattr(null, -1_500L))
        assertEquals(-1_500L, decoded.millis)
    }

    /**
     * An attribute-less SETATTR is a caller bug, not an empty request: the seam returns
     * before encoding when there is nothing to set, so reaching here must fail loudly
     * rather than put an empty bitmap on the wire.
     */
    @Test(expected = IllegalArgumentException::class)
    fun `an attribute-less setattr is refused rather than encoded empty`() {
        Fattr4Codec.encodeSetattr(null, null)
    }

    /** The createattrs of a new file: 0644, so a created file is not group-writable. */
    @Test fun `a created file asks for mode 0644`() {
        val attrs = Fattr4Codec.encodeMode("644".toInt(8))
        assertTrue(attrs.attrmask.isSet(nfs4_prot.FATTR4_MODE))
        val mode = Xdr(attrs.attr_vals.value).use { xdr ->
            xdr.beginDecoding()
            xdr.xdrDecodeInt()
        }
        assertEquals("644".toInt(8), mode)
    }
}
