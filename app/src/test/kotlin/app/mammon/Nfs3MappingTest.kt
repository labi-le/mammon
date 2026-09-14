package app.mammon

import com.emc.ecs.nfsclient.nfs.NfsStatus
import java.io.FileNotFoundException
import org.dcache.nfs.nfsstat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The NFSv3 status table, judged where a user meets it: the exception a picker renders and
 * the errno a FUSE writer gets back. Naked mutation calls skip the library's own status
 * check, so [failureFor] is the whole of this backend's error vocabulary.
 *
 * Statuses come from the two client libraries' own constants rather than from literals: a
 * number retyped here could agree with the implementation and still disagree with the wire.
 */
class Nfs3MappingTest {

    /** The v3 status, the v4.1 status for the same condition, and what a user must see. */
    private class Row(
        val v3: Int,
        val v4: Int,
        val failure: Class<out NfsFailure>,
        val errno: SafErrno,
    )

    private val shared = listOf(
        Row(
            NfsStatus.NFS3ERR_PERM.value,
            nfsstat.NFSERR_PERM,
            NfsFailure.PermissionDenied::class.java,
            SafErrno.ACCESS,
        ),
        Row(
            NfsStatus.NFS3ERR_ACCES.value,
            nfsstat.NFSERR_ACCESS,
            NfsFailure.PermissionDenied::class.java,
            SafErrno.ACCESS,
        ),
        Row(
            NfsStatus.NFS3ERR_ROFS.value,
            nfsstat.NFSERR_ROFS,
            NfsFailure.PermissionDenied::class.java,
            SafErrno.ACCESS,
        ),
        Row(
            NfsStatus.NFS3ERR_NOENT.value,
            nfsstat.NFSERR_NOENT,
            NfsFailure.NotFound::class.java,
            SafErrno.NOENT,
        ),
        Row(
            NfsStatus.NFS3ERR_NOTDIR.value,
            nfsstat.NFSERR_NOTDIR,
            NfsFailure.NotFound::class.java,
            SafErrno.NOENT,
        ),
        Row(
            NfsStatus.NFS3ERR_EXIST.value,
            nfsstat.NFSERR_EXIST,
            NfsFailure.AlreadyExists::class.java,
            SafErrno.EXISTS,
        ),
        Row(
            NfsStatus.NFS3ERR_NOTEMPTY.value,
            nfsstat.NFSERR_NOTEMPTY,
            NfsFailure.DirectoryNotEmpty::class.java,
            SafErrno.NOTEMPTY,
        ),
        Row(
            NfsStatus.NFS3ERR_NOSPC.value,
            nfsstat.NFSERR_NOSPC,
            NfsFailure.OutOfSpace::class.java,
            SafErrno.NOSPC,
        ),
        Row(
            NfsStatus.NFS3ERR_DQUOT.value,
            nfsstat.NFSERR_DQUOT,
            NfsFailure.OutOfSpace::class.java,
            SafErrno.NOSPC,
        ),
        Row(
            NfsStatus.NFS3ERR_NOTSUPP.value,
            nfsstat.NFSERR_NOTSUPP,
            NfsFailure.Unsupported::class.java,
            SafErrno.NOSYS,
        ),
    )

    /**
     * A full disk that arrives as EIO is retried forever by a copier, and a squashed
     * identity that arrives as ENOENT reads as "the file vanished" instead of "you may not".
     */
    @Test fun `each mapped status reaches the writer as its own errno`() {
        for (row in shared) {
            val mapped = failureFor(row.v3, "create /export/a")
            assertEquals("status ${row.v3}", row.failure, mapped.javaClass)
            assertEquals("status ${row.v3}", row.errno, mapped.safErrno())
        }
    }

    /**
     * If this fails a user can tell v3 from v4.1 by the error one share gives them, which is
     * what the shared table exists to prevent — and it now fails for an edit to either
     * backend's arms, because both mappers are called for real.
     */
    @Test fun `both backends answer the same condition with the same failure`() {
        for (row in shared) {
            assertEquals(
                "nfs3 ${row.v3} vs nfs4 ${row.v4}",
                failureFor(row.v3, "mkdir /export/d").javaClass,
                failureForV4(row.v4, "mkdir /export/d").javaClass,
            )
        }
    }

    /**
     * A procedure the server refuses is not a broken export: on the generic arm the errno
     * would be EIO, which a writer treats as a fault to retry, and the picker drops an
     * UnsupportedOperationException without showing the user anything at all.
     */
    @Test fun `a refused optional procedure reads as unavailable, not as a fault`() {
        val mapped = failureFor(NfsStatus.NFS3ERR_NOTSUPP.value, "rmdir /export/d")
        assertEquals(NfsFailure.Unsupported::class.java, mapped.javaClass)
        assertEquals(SafErrno.NOSYS, mapped.safErrno())
        assertTrue(mapped.asSafException("m") is FileNotFoundException)
    }

    /**
     * NFS3ERR_JUKEBOX is the server asking for the call to be made again, so reporting it
     * as [NfsFailure.Server] would tell a caller the operation demonstrably did not happen
     * — which for a migrated or HSM-backed file is both wrong and permanent-sounding.
     */
    @Test fun `a server asking us to come back later is transient`() {
        assertEquals(
            NfsFailure.Timeout::class.java,
            failureFor(NfsStatus.NFS3ERR_JUKEBOX.value, "read /export/a").javaClass,
        )
    }

    /**
     * The status name and the object are a user's only clue on an unusual server; a bare
     * "I/O error" is unactionable and indistinguishable between two different faults.
     */
    @Test fun `an unmapped status names itself and what it refused`() {
        assertEquals(
            "NFS3ERR_IO: create /export/a",
            failureFor(NfsStatus.NFS3ERR_IO.value, "create /export/a").message,
        )
        assertEquals(
            "NFS3ERR_STALE: getattr /export/a",
            failureFor(NfsStatus.NFS3ERR_STALE.value, "getattr /export/a").message,
        )
        val mapped = failureFor(NfsStatus.NFS3ERR_SERVERFAULT.value, "write /export/a")
        assertEquals(NfsFailure.Server::class.java, mapped.javaClass)
        assertEquals(SafErrno.IO, mapped.safErrno())
    }

    /** A status no RFC defines still has its number, and that is what a capture will show. */
    @Test fun `a status the specification does not define keeps its number`() {
        val mapped = failureFor(12345, "create /export/a")
        assertEquals(NfsFailure.Server::class.java, mapped.javaClass)
        assertEquals("nfs3 status 12345: create /export/a", mapped.message)
    }

    /** A refusal naming no object leaves a user copying a tree unable to tell which file. */
    @Test fun `the refused object rides along in the message`() {
        assertTrue(
            failureFor(NfsStatus.NFS3ERR_ACCES.value, "create /export/a/b").message!!
                .contains("/export/a/b"),
        )
        assertTrue(
            failureFor(NfsStatus.NFS3ERR_NOTEMPTY.value, "rmdir /export/d").message!!
                .contains("/export/d"),
        )
    }
}
