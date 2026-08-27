package app.mammon

import org.dcache.nfs.nfsstat
import org.dcache.nfs.v4.xdr.COMPOUND4res
import org.dcache.nfs.v4.xdr.OPEN4res
import org.dcache.nfs.v4.xdr.SETATTR4res
import org.dcache.nfs.v4.xdr.nfs_opnum4
import org.dcache.nfs.v4.xdr.nfs_resop4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the three decisions the v4.1 write path makes without a server: how much payload
 * one operation may carry, when a failed COMPOUND may be re-sent, and how a create reply
 * whose trailing CLOSE was refused is read.
 */
class NfsWriteSeamTest {

    @Test fun `the payload never exceeds what the server granted`() {
        for (granted in listOf(8 * 1024, 64 * 1024, 128 * 1024, 1024 * 1024, 4 * 1024 * 1024)) {
            val chunk = grantedChunk(granted, NFS_WRITE_CHUNK)
            assertTrue("chunk $chunk exceeds grant $granted", chunk <= granted)
            assertTrue("chunk $chunk is not positive", chunk > 0)
        }
    }

    @Test fun `a grant larger than the app ceiling is capped at the ceiling`() {
        assertEquals(NFS_WRITE_CHUNK, grantedChunk(8 * 1024 * 1024, NFS_WRITE_CHUNK))
        assertEquals(NFS_READ_CHUNK, grantedChunk(8 * 1024 * 1024, NFS_READ_CHUNK))
    }

    /** The measured case: the server grants 1 MiB where the app used to keep its own 128 KiB. */
    @Test fun `a one mebibyte grant is honoured rather than discarded`() {
        val chunk = grantedChunk(1024 * 1024, NFS_WRITE_CHUNK)
        assertTrue("granted 1 MiB should beat the old 128 KiB constant", chunk > 128 * 1024)
        assertTrue(chunk <= 1024 * 1024)
    }

    /** A grant smaller than the headers cannot go negative or zero. */
    @Test fun `a grant below the header budget still leaves a usable chunk`() {
        assertEquals(1, grantedChunk(1024, NFS_WRITE_CHUNK))
        assertEquals(1, grantedChunk(0, NFS_WRITE_CHUNK))
    }

    @Test fun `session loss is recoverable for any operation`() {
        for (status in listOf(
            nfsstat.NFSERR_BADSESSION,
            nfsstat.NFSERR_DEADSESSION,
            nfsstat.NFSERR_STALE_CLIENTID,
            nfsstat.NFSERR_SEQ_MISORDERED,
            nfsstat.NFSERR_BADSLOT,
            nfsstat.NFSERR_STALE,
            nfsstat.NFSERR_FHEXPIRED,
        )) {
            assertTrue("$status must be recoverable", recoverable(status, nonIdempotent = false))
            assertTrue("$status proves the operation never ran", recoverable(status, nonIdempotent = true))
        }
    }

    /**
     * NFS4ERR_EXPIRED is returned by OPEN, SETATTR and WRITE themselves, so it cannot
     * prove a create or a REMOVE did not take effect: retrying on it would execute the
     * operation twice.
     */
    @Test fun `an expired lease is not recoverable for an operation that must not repeat`() {
        assertTrue(recoverable(nfsstat.NFSERR_EXPIRED, nonIdempotent = false))
        assertFalse(recoverable(nfsstat.NFSERR_EXPIRED, nonIdempotent = true))
    }

    @Test fun `a plain error is never recoverable`() {
        for (status in listOf(
            nfsstat.NFSERR_ACCESS,
            nfsstat.NFSERR_EXIST,
            nfsstat.NFSERR_NOENT,
            nfsstat.NFSERR_NOTEMPTY,
            nfsstat.NFSERR_NOSPC,
        )) {
            assertFalse("$status must not be retried", recoverable(status, nonIdempotent = false))
            assertFalse("$status must not be retried", recoverable(status, nonIdempotent = true))
        }
    }

    @Test fun `only a server-requested delay retries after waiting`() {
        assertTrue(retryAfterDelay(nfsstat.NFSERR_DELAY))
        assertTrue(retryAfterDelay(nfsstat.NFSERR_GRACE))
        assertFalse(retryAfterDelay(nfsstat.NFSERR_ACCESS))
        assertFalse(retryAfterDelay(nfsstat.NFSERR_BADSESSION))
    }

    private fun reply(status: Int, vararg ops: nfs_resop4): COMPOUND4res =
        COMPOUND4res().apply {
            this.status = status
            resarray = listOf(*ops)
        }

    private fun openResult(status: Int): nfs_resop4 = nfs_resop4().apply {
        resop = nfs_opnum4.OP_OPEN
        opopen = OPEN4res().apply { this.status = status }
    }

    private fun setattrResult(status: Int): nfs_resop4 = nfs_resop4().apply {
        resop = nfs_opnum4.OP_SETATTR
        opsetattr = SETATTR4res().apply { this.status = status }
    }

    /**
     * A COMPOUND stops at its first failing operation, so a reply whose status is the
     * CLOSE's error still carries a successful OPEN before it — and the file exists.
     */
    @Test fun `a create whose trailing close failed still reports the open as done`() {
        val res = reply(nfsstat.NFSERR_BAD_STATEID, openResult(nfsstat.NFS_OK))
        assertTrue(openSucceeded(res))
    }

    @Test fun `a create that failed at the open is not reported as done`() {
        assertFalse(openSucceeded(reply(nfsstat.NFSERR_EXIST, openResult(nfsstat.NFSERR_EXIST))))
    }

    @Test fun `a reply that never reached the open is not reported as done`() {
        assertFalse(openSucceeded(reply(nfsstat.NFSERR_BADSESSION)))
        assertFalse(openSucceeded(reply(nfsstat.NFSERR_ACCESS, setattrResult(nfsstat.NFSERR_ACCESS))))
    }

    @Test fun `an unusable display name never becomes a path component`() {
        assertNull(PathCodec.componentOf("a/b"))
        assertNull(PathCodec.componentOf("../etc"))
        assertNull(PathCodec.componentOf(".."))
        assertNull(PathCodec.componentOf("."))
        assertNull(PathCodec.componentOf(""))
        assertNull(PathCodec.componentOf("evil\u0000.txt"))
        assertNull(PathCodec.componentOf(null))
        assertEquals("report.txt", PathCodec.componentOf("report.txt"))
        assertEquals("a b.txt", PathCodec.componentOf("a b.txt"))
    }

    @Test fun `a child path cannot be steered out of its parent by its name`() {
        assertNull(PathCodec.childPath("/a", "../b"))
        assertNull(PathCodec.childPath("/a", "b/c"))
        assertEquals("/a/b", PathCodec.childPath("/a", "b"))
    }
}
