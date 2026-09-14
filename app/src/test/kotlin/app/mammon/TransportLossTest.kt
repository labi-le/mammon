package app.mammon

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.TimeoutException
import org.dcache.nfs.status.BadSessionException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TransportLoss.isLoss] decides whether recovery may rebuild the connection, and
 * [TransportLoss.mayResend] whether the operation may go out a second time. Confusing a
 * timeout for a lost connection re-sends a request that may still be in flight, so the
 * timeout wrapper is pinned here alongside the losses.
 */
class TransportLossTest {

    @Test fun `an EOF from a dead socket is connection loss`() {
        assertTrue(TransportLoss.isLoss(EOFException()))
    }

    @Test fun `loss is recognised through the cause chain, not only at the top`() {
        assertTrue(TransportLoss.isLoss(IOException("nfs4 call failed", EOFException())))
    }

    /** The reply may still arrive, so this must never license a re-send. */
    @Test fun `a call that timed out is not connection loss`() {
        assertFalse(TransportLoss.isLoss(IOException("nfs4 call timed out", TimeoutException())))
    }

    @Test fun `a reset connection is loss`() {
        assertTrue(TransportLoss.isLoss(SocketException("Connection reset")))
    }

    /** A transport shut down rather than reset raises neither an EOF nor a SocketException. */
    @Test fun `a channel closed under the call is loss`() {
        assertTrue(TransportLoss.isLoss(ClosedChannelException()))
    }

    /**
     * A status is the server answering, so it outranks a transport failure nested under
     * it: classifying it as loss would rebuild a healthy connection and route an answer —
     * here a session the caller must re-establish — into the recovery path.
     */
    @Test fun `a server status is not loss, at the top or wrapped`() {
        val answered = BadSessionException("bad session", EOFException())
        assertFalse(TransportLoss.isLoss(answered))
        assertFalse(TransportLoss.isLoss(IOException("nfs4 call failed", answered)))
    }

    @Test fun `an unrelated failure is not loss`() {
        assertFalse(TransportLoss.isLoss(IllegalStateException("session already closed")))
    }

    @Test fun `only an idempotent operation may be sent again`() {
        assertFalse(TransportLoss.mayResend(nonIdempotent = true))
        assertTrue(TransportLoss.mayResend(nonIdempotent = false))
    }
}
