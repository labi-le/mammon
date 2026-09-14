package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [NfsAccess.UNFRAGMENTED_REPLY_MAX] against the fragment threshold it was chosen
 * for. Raising it is the regression: the client library misparses any reply the server
 * splits over several RPC record fragments.
 */
class Nfs3FragmentBoundTest {

    /** Bytes libtirpc and its ntirpc fork put in a fragment before closing it. */
    private val fragmentThreshold = 65532

    /**
     * A READ reply above its payload: RPC reply header, status, post_op_attr, count, eof
     * and data length, plus the XDR padding of an unaligned count.
     */
    private val readReplyOverhead = 24 + 4 + 88 + 4 + 4 + 4 + 3

    @Test fun `the bound is the largest page-aligned read one fragment can carry`() {
        val bound = NfsAccess.UNFRAGMENTED_REPLY_MAX
        assertEquals("a partial page wastes a round trip", 0, bound % Fuse.PAGE_SIZE)
        assertTrue(
            "a $bound byte reply would be fragmented",
            bound + readReplyOverhead <= fragmentThreshold,
        )
        assertTrue(
            "one more page still fits, so the bound gives away round trips",
            bound + Fuse.PAGE_SIZE + readReplyOverhead > fragmentThreshold,
        )
    }
}
