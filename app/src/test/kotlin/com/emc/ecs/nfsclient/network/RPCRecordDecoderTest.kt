package com.emc.ecs.nfsclient.network

import app.mammon.NFS_READ_CHUNK
import java.nio.ByteBuffer
import org.jboss.netty.buffer.ChannelBuffers
import org.jboss.netty.handler.codec.embedder.CodecEmbedderException
import org.jboss.netty.handler.codec.embedder.DecoderEmbedder
import org.jboss.netty.handler.codec.frame.TooLongFrameException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val LAST_FRAGMENT = 0x80000000.toInt()

/**
 * What a full-size READ reply costs beyond its payload: a 24-byte RPC accepted-reply header, 104
 * bytes of NFSv3 READ3resok (status, post_op_attr with its 84-byte fattr3, count, eof and the
 * data length) and a 4-byte record mark per fragment, so 4 KiB covers it for any fragmentation a
 * server honouring rtmax can produce.
 */
private const val REPLY_OVERHEAD = 4 * 1024

/**
 * Pins the vendored [RPCRecordDecoder] against a record whose fragments arrive over more than one
 * socket read. Upstream consumes a non-last fragment and returns null with the reader index
 * advanced; netty's `FrameDecoder.updateCumulation` then drops the consumed prefix, so the last
 * fragment's rewind by the accumulated record length lands below zero and throws. Feeding a whole
 * record in one chunk never reaches that, which is why source review called the class correct.
 */
class RPCRecordDecoderTest {

    private fun recordOf(vararg fragments: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(fragments.sumOf { fragment -> fragment.size + 4 })
        fragments.forEachIndexed { index, fragment ->
            val size = if (index == fragments.lastIndex) LAST_FRAGMENT or fragment.size else fragment.size
            buffer.putInt(size)
            buffer.put(fragment)
        }
        return buffer.array()
    }

    private fun DecoderEmbedder<ByteArray>.feed(bytes: ByteArray, chunkBounds: List<Int>): List<ByteArray> {
        (listOf(0) + chunkBounds + listOf(bytes.size)).zipWithNext { from, to ->
            offer(ChannelBuffers.wrappedBuffer(bytes, from, to - from))
        }
        finish()
        return generateSequence { poll() }.toList()
    }

    private fun feed(bytes: ByteArray, chunkBounds: List<Int>): List<ByteArray> =
        DecoderEmbedder<ByteArray>(RPCRecordDecoder()).feed(bytes, chunkBounds)

    /**
     * Fragment boundaries fall at 68, 120 and 156. The chunks stop at 30, 90 and 156: inside
     * fragment one, inside fragment two, then the rest of fragment two with the whole of fragment
     * three, and the last fragment alone. Against upstream `decode` the fourth offer throws
     * `IndexOutOfBoundsException` from `AbstractChannelBuffer.readerIndex`, which `DecoderEmbedder`
     * wraps in `CodecEmbedderException`.
     */
    @Test fun `a record split across chunks that straddle fragment boundaries decodes`() {
        val xid = 0x1A2B3C4D
        val first = ByteBuffer.allocate(64).putInt(xid).put(ByteArray(60) { 0xA1.toByte() }).array()
        val second = ByteArray(48) { 0xB2.toByte() }
        val third = ByteArray(32) { 0xC3.toByte() }
        val fourth = ByteArray(16) { 0xD4.toByte() }
        val record = recordOf(first, second, third, fourth)

        val decoded = feed(record, listOf(30, 90, 156))

        assertEquals(1, decoded.size)
        assertArrayEquals(record, decoded[0])

        val reassembled = RecordMarkingUtil.removeRecordMarking(decoded[0])
        assertArrayEquals(first + second + third + fourth, reassembled.buffer.copyOf(reassembled.offset))
        assertEquals(xid, reassembled.xid)
    }

    /** One byte at a time also proves a four-byte record mark survives being split. */
    @Test fun `a record offered one byte at a time decodes`() {
        val first = ByteBuffer.allocate(12).putInt(0x55667788).put(ByteArray(8) { 0xE5.toByte() }).array()
        val second = ByteArray(8) { 0xF6.toByte() }
        val record = recordOf(first, second)

        val decoded = feed(record, (1 until record.size).toList())

        assertEquals(1, decoded.size)
        assertArrayEquals(record, decoded[0])
    }

    /** The decoder must stop at the first last-fragment mark and start the next record empty. */
    @Test fun `consecutive records in one chunk decode separately`() {
        val firstRecord = recordOf(ByteArray(8) { 0x01 }, ByteArray(8) { 0x02 })
        val secondRecord = recordOf(ByteArray(12) { 0x03 })

        val decoded = feed(firstRecord + secondRecord, listOf(10, 24))

        assertEquals(2, decoded.size)
        assertArrayEquals(firstRecord, decoded[0])
        assertArrayEquals(secondRecord, decoded[1])
    }

    /**
     * A record cut off by a closed connection must yield nothing rather than a truncated record,
     * and `cleanup` must release its fragments there and then. Connection gives each channel its
     * own decoder, so reuse is not how the app runs; the reuse here is what an assertion can see,
     * and it is also what keeps the class correct under a pipeline that shares the handler.
     */
    @Test fun `a record truncated by close yields nothing and leaves no state`() {
        val decoder = RPCRecordDecoder()
        val truncated = recordOf(ByteArray(8) { 0x07 }, ByteArray(8) { 0x08 }).copyOf(20)

        assertEquals(emptyList<ByteArray>(), DecoderEmbedder<ByteArray>(decoder).feed(truncated, emptyList()))

        val whole = recordOf(ByteArray(12) { 0x09 })
        val reused = DecoderEmbedder<ByteArray>(decoder)
        reused.offer(ChannelBuffers.wrappedBuffer(whole))
        reused.finish()

        assertArrayEquals(whole, reused.poll())
        assertNull(reused.poll())
    }

    private fun nonLastFragment(payloadSize: Int): ByteArray =
        ByteBuffer.allocate(payloadSize + 4).putInt(payloadSize).array()

    /**
     * A server that never sets the last-fragment bit must be refused once the record passes
     * [RPCRecordDecoder.MAX_RECORD_LENGTH], rather than accumulated until the heap runs out.
     */
    @Test fun `a record past the maximum length is refused rather than accumulated`() {
        val decoder = DecoderEmbedder<ByteArray>(RPCRecordDecoder())
        val payload = 256 * 1024
        val fragment = ChannelBuffers.wrappedBuffer(nonLastFragment(payload))
        val offersToPassTheCap = RPCRecordDecoder.MAX_RECORD_LENGTH / (payload + 4) + 1

        val thrown = assertThrows(CodecEmbedderException::class.java) {
            repeat(offersToPassTheCap) { decoder.offer(fragment.duplicate()) }
        }

        assertTrue(thrown.cause is TooLongFrameException)
        assertNull(decoder.poll())
    }

    /**
     * The cap is tested on the length a mark declares, so a fragment too large for it is refused
     * with none of its payload received — where testing the accumulated length instead returned
     * null and waited for the bytes it means to refuse, spending the memory the cap denies.
     */
    @Test fun `a fragment declaring more than the maximum is refused before its bytes arrive`() {
        val decoder = DecoderEmbedder<ByteArray>(RPCRecordDecoder())
        val markAlone = ByteBuffer.allocate(4).putInt(RPCRecordDecoder.MAX_RECORD_LENGTH).array()

        val thrown = assertThrows(CodecEmbedderException::class.java) {
            decoder.offer(ChannelBuffers.wrappedBuffer(markAlone))
        }

        assertTrue(thrown.cause is TooLongFrameException)
        assertNull(decoder.poll())
    }

    /** The largest reply the app can provoke, a 512 KiB READ over nine fragments, is under it. */
    @Test fun `a 512 KiB class record decodes`() {
        val fragments = Array(9) { index -> ByteArray(if (index == 8) 60 else 65532) { 0x5A } }
        val record = recordOf(*fragments)

        val decoded = feed(record, listOf(record.size / 2))

        assertEquals(1, decoded.size)
        assertArrayEquals(record, decoded[0])
        val reassembled = RecordMarkingUtil.removeRecordMarking(decoded[0])
        assertEquals(fragments.sumOf { fragment -> fragment.size }, reassembled.offset)
    }

    /**
     * The coupling the cap's comment describes, as something that fails: raising the read ceiling
     * past what the decoder accepts is caught here and not by a server's first full-size reply.
     */
    @Test fun `the cap leaves room for a full-size read reply`() {
        assertTrue(
            "MAX_RECORD_LENGTH is ${RPCRecordDecoder.MAX_RECORD_LENGTH} bytes, and a full-size " +
                "READ reply needs $NFS_READ_CHUNK + $REPLY_OVERHEAD of it: either raise " +
                "MAX_RECORD_LENGTH in RPCRecordDecoder.java or lower NFS_READ_CHUNK in " +
                "NfsSession.kt. Shipped as it stands, every full-size READ reply throws " +
                "TooLongFrameException, the channel closes, RpcWrapper re-sends the same xid on " +
                "a fresh connection, it fails identically, and the user sees an unreachable " +
                "error with the cause only in logcat.",
            NFS_READ_CHUNK + REPLY_OVERHEAD <= RPCRecordDecoder.MAX_RECORD_LENGTH,
        )
    }
}
