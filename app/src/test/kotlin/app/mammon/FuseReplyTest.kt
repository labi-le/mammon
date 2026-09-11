package app.mammon

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins `fuse_out_header`. The kernel reads the length and the error out of the buffer
 * while the daemon writes only the return value to the device, so a disagreement between
 * the two wedges the connection rather than failing one request. Every field is decoded
 * back out of the array in [Fuse.ORDER] instead of being taken on trust.
 */
class FuseReplyTest {

    private class OutHeader(bytes: ByteArray) {
        private val buf = ByteBuffer.wrap(bytes).order(Fuse.ORDER)
        val length: Int get() = buf.getInt(0)
        val error: Int get() = buf.getInt(4)
        val unique: Long get() = buf.getLong(8)
    }

    @Test fun `a success reply states header plus payload, no error and the request unique`() {
        val into = ByteArray(256)

        val returned = Fuse.reply(into, unique = 9_001L, payload = 104)

        val header = OutHeader(into)
        assertEquals(Fuse.OUT_HEADER_SIZE + 104, returned)
        assertEquals(120, header.length)
        assertEquals(0, header.error)
        assertEquals(9_001L, header.unique)
    }

    @Test fun `the returned length is the length the kernel will read out of the buffer`() {
        val into = ByteArray(4096)

        val payloads = intArrayOf(
            0, 1, 7, Fuse.ATTR_OUT_SIZE, Fuse.PAGE_SIZE - Fuse.OUT_HEADER_SIZE,
        )

        for (payload in payloads) {
            val returned = Fuse.reply(into, unique = 1L, payload = payload)
            assertEquals(returned, OutHeader(into).length)
            assertEquals(Fuse.OUT_HEADER_SIZE + payload, returned)
        }
    }

    @Test fun `a refusal carries the errno negated, not the positive value it was given`() {
        val into = ByteArray(64)

        Fuse.reply(into, unique = 2L, payload = 0, errno = Fuse.EROFS)
        assertEquals(-30, OutHeader(into).error)

        Fuse.reply(into, unique = 2L, payload = 0, errno = Fuse.ENOSYS)
        assertEquals(-38, OutHeader(into).error)

        Fuse.reply(into, unique = 2L, payload = 0, errno = Fuse.EINVAL)
        assertEquals(-22, OutHeader(into).error)
    }

    @Test fun `a refusal reports a header-only length even when a payload size is passed`() {
        val into = ByteArray(4096)

        val returned = Fuse.reply(into, unique = 3L, payload = 2048, errno = Fuse.ENOENT)

        val header = OutHeader(into)
        assertEquals(Fuse.OUT_HEADER_SIZE, returned)
        assertEquals(Fuse.OUT_HEADER_SIZE, header.length)
        assertEquals(-2, header.error)
    }

    @Test fun `unique round-trips a value past Int MAX_VALUE that 32 bits would truncate`() {
        val into = ByteArray(64)
        val unique = Int.MAX_VALUE.toLong() + 1L

        Fuse.reply(into, unique = unique, payload = 0)

        assertEquals(2_147_483_648L, OutHeader(into).unique)

        Fuse.reply(into, unique = 0x0123_4567_89AB_CDEFL, payload = 0)
        assertEquals(0x0123_4567_89AB_CDEFL, OutHeader(into).unique)
    }
}
