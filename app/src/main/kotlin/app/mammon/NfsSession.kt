package app.mammon

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * Bytes per NFS READ the app is willing to ask for, and the pump buffer size the
 * provider matches to it. Only a ceiling: the v4.1 session clamps every payload to what
 * CREATE_SESSION granted, the v3 session to FSINFO rtmax.
 *
 * It also sizes resident memory, so a bump for round-trip reasons is not free:
 * `FuseNfsDaemon.MAX_WRITE` is this constant and its `BUFFER` is that plus 64 KiB, held
 * as a request and a reply buffer per serving thread and per `serve` — 5.6 MiB at this
 * value across the daemon's four workers.
 */
const val NFS_READ_CHUNK = 512 * 1024

/**
 * Bytes per NFS WRITE the app is willing to send. Only a ceiling: the v4.1 session
 * clamps every payload to what CREATE_SESSION granted, the v3 session to FSINFO wtmax.
 */
const val NFS_WRITE_CHUNK = 1024 * 1024

/** The attributes a SAF row needs, in the shape both protocol versions can produce. */
data class NodeAttrs(val isDirectory: Boolean, val size: Long, val lastModifiedMillis: Long)

/** One directory child with its attributes; [path] is export-absolute. */
data class ChildEntry(val path: String, val attributes: NodeAttrs)

/**
 * The failures a frontend must be able to tell apart without reading a message: SAF maps
 * them onto the documented `DocumentsProvider` exceptions, FUSE onto errnos. Sealed so
 * that mapping is exhaustive — a new case cannot be added without every frontend's
 * `when` refusing to compile.
 *
 * Still an [IOException], because capability is server-side and can change between two
 * calls: any caller that only wants "the operation did not happen" keeps working.
 */
sealed class NfsFailure(message: String) : IOException(message) {

    /** The server refused this identity — the everyday case on a root_squash export. */
    class PermissionDenied(what: String) : NfsFailure("permission denied: $what")

    class NotFound(what: String) : NfsFailure("no such file or directory: $what")

    /** What a GUARDED4 create answers, so a create never silently truncates. */
    class AlreadyExists(what: String) : NfsFailure("already exists: $what")

    /** Its own case because a recursive delete treats it as ordinary control flow, and
     *  would abort on the generic error the way it does on EIO. */
    class DirectoryNotEmpty(what: String) : NfsFailure("directory not empty: $what")

    /** Quota counts as full: both mean the write will not fit, and neither is retryable. */
    class OutOfSpace(what: String) : NfsFailure("no space left: $what")

    /**
     * Not available here: either this backend has no implementation, or the server
     * refused the procedure — RFC 1813 §3.3.13 lists NFS3ERR_NOTSUPP for RMDIR, so a
     * real mutation can come back refused. [NfsSession.implementsWrites] rules out the
     * first half up front and nothing about the second, which arrives per call as
     * [PermissionDenied] does.
     */
    class Unsupported(operation: String) : NfsFailure("$operation is not available on this share")

    /**
     * No usable answer came back: the call outran its deadline, or the server answered by
     * asking for it to be made again (NFS3ERR_JUKEBOX). Either way whether it applied is
     * unknown. [Server] is an answer the server gave, so there the operation demonstrably
     * did not happen — which is why this cannot fold into it.
     */
    class Timeout(what: String) : NfsFailure("timed out: $what")

    /**
     * The server could not be reached, or the connection to it could not be remade. The
     * connection is the separator from [Timeout], not the request: there it stayed up and
     * only the answer never came, while here it is gone and this call will not get it
     * back. So whether the server applied it is unknown too — it may have gone before the
     * call was seen, or after it was carried out and the reply died with the socket.
     */
    class Unreachable(what: String) : NfsFailure("unreachable: $what")

    /** Everything else the server said, so the `when` above it can stay exhaustive. */
    class Server(what: String) : NfsFailure(what)
}

/**
 * A newly created file: the open handle and the attributes the same round trip returned.
 * Both exist because every caller needs both — re-fetching the attributes would be a
 * second walk from the export root for something the create already had in hand.
 */
data class CreatedFile(val file: NfsFile, val attributes: NodeAttrs)

/**
 * An opened file and the attributes its open already fetched. Both backends have to read
 * the attributes anyway to reject a directory, so a caller that needs the size — a proxy
 * fd seeding its length, say — gets it without a second round trip.
 */
data class OpenedFile(val file: NfsFile, val attributes: NodeAttrs)

/** Everything one [NfsSession] is opened for: where the export is, and who we claim to be. */
data class NfsTarget(val spec: ExportSpec, val identity: AuthIdentity)

/**
 * One open regular file. FUSE issues reads at arbitrary offsets from arbitrary
 * threads, which a sequential [InputStream] cannot serve, so the offset lives in the
 * call rather than in the handle.
 */
interface NfsFile : Closeable {

    /** Reads up to [len] bytes at [offset] into [dst]; returns the count read, 0 at end of file. */
    fun readAt(offset: Long, dst: ByteArray, off: Int, len: Int): Int

    /**
     * Writes up to [len] bytes from [src] at [offset]; returns the count the server took,
     * which may be short, so callers loop as they would over `write(2)`.
     */
    fun writeAt(offset: Long, src: ByteArray, off: Int, len: Int): Int
}

/**
 * The operations [NfsDocumentsProvider] and the FUSE daemon perform, over one long-lived
 * server session. Every method blocks on network I/O: callers must stay off the main
 * thread.
 *
 * Every member is abstract, mutating ones included: a backend that declares
 * [implementsWrites] cannot then forget one of them and fail at runtime instead.
 */
interface NfsSession : Closeable {

    /**
     * Whether this BACKEND implements the mutating half. It says nothing about whether
     * the export or the claimed identity permits a write: a `ro` export and a squashed
     * uid both answer true here and then fail every call with
     * [NfsFailure.PermissionDenied]. A frontend may use it to avoid advertising a
     * writable surface it can never serve; it may not use it as permission.
     */
    val implementsWrites: Boolean

    /** Export root attributes, null when the export is missing or not a directory. */
    fun probeRoot(): NodeAttrs?

    /** Attributes for one documentId, null when it does not exist. */
    fun stat(docId: String): NodeAttrs?

    /** Children of the directory named by [docId], directories first. */
    fun list(docId: String): List<ChildEntry>

    /**
     * Opens [docId]; throws when it is missing or not a regular file. The handle also
     * serves [NfsFile.writeAt] on a backend whose [implementsWrites] is true.
     */
    fun openFile(docId: String): OpenedFile

    fun streamFor(docId: String): InputStream = NfsFileStream(openFile(docId).file)

    /**
     * Creates [name] under [parentDocId] and returns it open, failing with
     * [NfsFailure.AlreadyExists] rather than truncating an existing file.
     */
    fun createFile(parentDocId: String, name: String): CreatedFile

    /** Returns the new directory's attributes, from the same round trip that made it. */
    fun makeDirectory(parentDocId: String, name: String): NodeAttrs

    /**
     * Removes [name] under [parentDocId], whether a file or an empty directory: NFSv4
     * has one REMOVE and does not discriminate by type. A frontend owing POSIX
     * semantics — `unlink(2)` must fail EISDIR on a directory, `rmdir(2)` ENOTDIR on a
     * file — has to check the type itself; the server will not.
     *
     * [isDirectory] spares a backend that must pick a procedure the lookup its caller
     * already paid for. Null means "find out". A wrong hint is not free: it costs the
     * refused round trip before that lookup, and on a server that honours REMOVE on a
     * directory (RFC 1813 §3.3.12 permits it) the wrong procedure goes first and can
     * succeed. So supply it only where the type is trusted rather than guessed: a FUSE
     * opcode carries the kernel's cached dentry type, which the [TTL_SECONDS] window the
     * frontend advertises lets go stale, and the FUSE frontend passes it regardless, as
     * Linux's own v3 client picks the procedure off the syscall. A SAF documentId carries
     * no type at all.
     */
    fun remove(parentDocId: String, name: String, isDirectory: Boolean? = null)

    /**
     * Sets whichever of [size] and [modifiedMillis] is non-null and returns the
     * attributes as they stand afterwards, all in one round trip. Setting neither is a
     * plain read of them.
     */
    fun setAttributes(docId: String, size: Long? = null, modifiedMillis: Long? = null): NodeAttrs
}

/** The sequential view of an [NfsFile] that SAF's openDocument needs. */
private class NfsFileStream(private val file: NfsFile) : InputStream() {

    private var offset = 0L
    private val single = ByteArray(1)

    override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = file.readAt(offset, b, off, len)
        if (n <= 0) return -1
        offset += n
        return n
    }

    override fun close() = file.close()
}

/** The ordering [NfsSession.list] promises, kept in one place so the two do not drift. */
internal fun List<ChildEntry>.directoriesFirst(): List<ChildEntry> =
    sortedWith(compareBy({ !it.attributes.isDirectory }, { it.path.lowercase(Locale.ROOT) }))

/**
 * Version selection. v4.1 is tried first because it needs nothing but TCP 2049,
 * while v3 also needs rpcbind and mountd, which NFSv4-only servers do not run;
 * a v3-only server answers EXCHANGE_ID with an RPC program mismatch, which lands
 * in the fallback. The provider caches the session it gets back per [NfsTarget], so the
 * probe runs again only when the config or the claimed identity changes.
 */
object NfsSessions {

    fun open(target: NfsTarget): NfsSession = select(target, ::NfsV4Access, ::NfsAccess)

    /** [v4] and [v3] are injectable so version selection is testable without a server. */
    internal fun select(
        target: NfsTarget,
        v4: (NfsTarget) -> NfsSession,
        v3: (NfsTarget) -> NfsSession,
    ): NfsSession {
        val v4Failure: Throwable = try {
            return v4(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        } catch (e: LinkageError) {
            // The v4 stack pulls in third-party classes; one that cannot initialize
            // on a given device must degrade to v3, not take the provider down.
            e
        }
        try {
            return v3(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // v3 is what the user sees when both fail: its "portmap unreachable"
            // names the usual misconfiguration better than a v4 decode error.
            e.addSuppressed(v4Failure)
            throw e
        }
    }
}
