package app.mammon

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/** Bytes per NFS READ, and the pump buffer size the provider matches to it. */
const val NFS_READ_CHUNK = 512 * 1024

/**
 * Bytes per NFS WRITE the app is willing to send. Only a ceiling: the session clamps
 * every payload to what CREATE_SESSION actually granted, which may be smaller.
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

    /**
     * Refusal, not failure: this backend has no implementation, so no server was asked.
     * Callers that must not present a writable surface consult [NfsSession.supportsWrites].
     */
    class Unsupported(operation: String) : NfsFailure("$operation is not supported by this NFS backend")

    /** Everything else the server said, so the `when` above it can stay exhaustive. */
    class Server(what: String) : NfsFailure(what)
}

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
    fun writeAt(offset: Long, src: ByteArray, off: Int, len: Int): Int =
        throw NfsFailure.Unsupported("write")
}

/**
 * The operations [NfsDocumentsProvider] and the FUSE daemon perform, over one long-lived
 * server session. Every method blocks on network I/O: callers must stay off the main
 * thread.
 *
 * The mutating half defaults to refusing, so a backend that cannot serve it says so by
 * omission rather than by pretending. [supportsWrites] has no default on purpose: a new
 * backend must state its answer instead of inheriting one.
 */
interface NfsSession : Closeable {

    /** False when every mutating member below refuses; true when all of them work. */
    val supportsWrites: Boolean

    /** Export root attributes, null when the export is missing or not a directory. */
    fun probeRoot(): NodeAttrs?

    /** Attributes for one documentId, null when it does not exist. */
    fun stat(docId: String): NodeAttrs?

    /** Children of the directory named by [docId], directories first. */
    fun list(docId: String): List<ChildEntry>

    /**
     * Opens [docId]; throws when it is missing or not a regular file. The handle also
     * serves [NfsFile.writeAt] on a backend whose [supportsWrites] is true.
     */
    fun openFile(docId: String): NfsFile

    fun streamFor(docId: String): InputStream = NfsFileStream(openFile(docId))

    /**
     * Creates [name] under [parentDocId] and returns it open, failing with
     * [NfsFailure.AlreadyExists] rather than truncating an existing file.
     */
    fun createFile(parentDocId: String, name: String): NfsFile =
        throw NfsFailure.Unsupported("create")

    fun makeDirectory(parentDocId: String, name: String): Unit =
        throw NfsFailure.Unsupported("mkdir")

    /** Removes [name] under [parentDocId], whether a file or an empty directory. */
    fun remove(parentDocId: String, name: String): Unit =
        throw NfsFailure.Unsupported("remove")

    /** Sets whichever of [size] and [modifiedMillis] is non-null, in one round trip. */
    fun setAttributes(docId: String, size: Long? = null, modifiedMillis: Long? = null): Unit =
        throw NfsFailure.Unsupported("setattr")
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
