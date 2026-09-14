package app.mammon

import java.io.EOFException
import java.net.SocketException
import java.nio.channels.ClosedChannelException
import org.dcache.nfs.ChimeraNFSException

/**
 * Tells a connection that is gone apart from a server that answered.
 *
 * The distinction decides whether a rebuilt transport may carry an operation again, so it
 * is a pure function with no Android and no network in it: a timeout says nothing about
 * whether the request reached the server, while a closed socket says only that no answer
 * is coming — before a call goes out nothing was sent, but a loss under one already in
 * flight cannot say whether the server applied it, which is what [mayResend] is for.
 */
internal object TransportLoss {

    /** Guards against a cause chain that loops back on itself. */
    private const val MAX_DEPTH = 16

    /** True when [e] proves the connection is gone rather than the server answering. */
    fun isLoss(e: Throwable): Boolean {
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth < MAX_DEPTH) {
            when (cause) {
                // A status is an answer: the connection carried both the request and the
                // reply, whatever wrapped it afterwards.
                is ChimeraNFSException -> return false
                is EOFException, is ClosedChannelException, is SocketException -> return true
            }
            val next = cause.cause
            cause = if (next === cause) null else next
            depth++
        }
        return false
    }

    /**
     * True when an operation may be sent again on a rebuilt connection.
     *
     * A mid-flight loss cannot say whether the server applied the operation, so only an
     * idempotent one is safe to repeat.
     */
    fun mayResend(nonIdempotent: Boolean): Boolean = !nonIdempotent
}
