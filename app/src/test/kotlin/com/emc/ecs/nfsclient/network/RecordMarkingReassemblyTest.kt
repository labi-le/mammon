package com.emc.ecs.nfsclient.network

import com.emc.ecs.nfsclient.rpc.Xdr
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

private const val LAST_FRAGMENT = 0x80000000.toInt()

/**
 * Pins RFC 1831 record reassembly in the vendored [RecordMarkingUtil]. A reply that fits one
 * fragment always worked; a multi-fragment reply - what a server sends for a large READ, e.g.
 * 300 KB answered as five fragments - came back shifted, because the upstream cursor did not
 * count the four-byte record mark.
 *
 * The test lives in this package because `removeRecordMarking` is package-private upstream and
 * stays that way.
 */
class RecordMarkingReassemblyTest {

    private fun record(vararg fragments: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(fragments.sumOf { fragment -> fragment.size + 4 })
        fragments.forEachIndexed { index, fragment ->
            val size = if (index == fragments.lastIndex) LAST_FRAGMENT or fragment.size else fragment.size
            buffer.putInt(size)
            buffer.put(fragment)
        }
        return buffer.array()
    }

    private fun payloadOf(reassembled: Xdr): ByteArray = reassembled.buffer.copyOf(reassembled.offset)

    /**
     * Each fragment carries its own fill byte, so a cursor landing four bytes early shows up as
     * wrong payload content. The last word of fragment one reads as a valid last-fragment mark
     * of size 4, which keeps that early read inside the buffer instead of overrunning it: the
     * upstream arithmetic then returns 24 bytes ending in fragment two's record mark rather
     * than throwing, and content is what fails.
     */
    @Test fun `a three fragment record reassembles byte exact`() {
        val xid = 0x11223344
        val first = ByteBuffer.allocate(20)
            .putInt(xid)
            .put(ByteArray(12) { 0xA1.toByte() })
            .putInt(LAST_FRAGMENT or 4)
            .array()
        val second = ByteArray(8) { 0xB2.toByte() }
        val third = ByteArray(12) { 0xC3.toByte() }

        val reassembled = RecordMarkingUtil.removeRecordMarking(record(first, second, third))

        assertArrayEquals(first + second + third, payloadOf(reassembled))
        assertEquals(xid, reassembled.xid)
    }

    @Test fun `a single fragment record reassembles byte exact`() {
        val xid = 0x55667788
        val only = ByteBuffer.allocate(12)
            .putInt(xid)
            .put(ByteArray(8) { 0xD4.toByte() })
            .array()

        val reassembled = RecordMarkingUtil.removeRecordMarking(record(only))

        assertArrayEquals(only, payloadOf(reassembled))
        assertEquals(xid, reassembled.xid)
    }

    /**
     * RFC 1831 does not require a fragment to be a multiple of four bytes, and `Xdr.putBytes`
     * ends in `skip`, which rounds the destination offset up to the next four. Reassembly must
     * therefore carry its own output cursor: otherwise a non-last fragment of an unaligned
     * length leaves zero bytes at the seam and the reassembled length over-counts by as many.
     */
    @Test fun `unaligned fragments reassemble with no padding at the seams`() {
        val xid = 0x0A0B0C0D
        val first = ByteBuffer.allocate(6).putInt(xid).put(ByteArray(2) { 0xE1.toByte() }).array()
        val second = ByteArray(5) { 0xE2.toByte() }
        val third = ByteArray(4) { 0xE3.toByte() }

        val reassembled = RecordMarkingUtil.removeRecordMarking(record(first, second, third))

        assertEquals(15, reassembled.offset)
        assertArrayEquals(first + second + third, payloadOf(reassembled))
        assertEquals(xid, reassembled.xid)
    }
}
