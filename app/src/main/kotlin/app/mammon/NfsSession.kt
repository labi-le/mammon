package app.mammon

import java.io.Closeable
import java.io.InputStream
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/** Bytes per NFS READ, and the pump buffer size the provider matches to it. */
const val NFS_READ_CHUNK = 512 * 1024

/** The attributes a SAF row needs, in the shape both protocol versions can produce. */
data class NodeAttrs(val isDirectory: Boolean, val size: Long, val lastModifiedMillis: Long)

/** One directory child with its attributes; [path] is export-absolute. */
data class ChildEntry(val path: String, val attributes: NodeAttrs)

/**
 * One open regular file. FUSE issues reads at arbitrary offsets from arbitrary
 * threads, which a sequential [InputStream] cannot serve, so the offset lives in the
 * call rather than in the handle.
 */
interface NfsFile : Closeable {

    /** Reads up to [len] bytes at [offset] into [dst]; returns the count read, 0 at end of file. */
    fun readAt(offset: Long, dst: ByteArray, off: Int, len: Int): Int
}

/**
 * The read-only operations [NfsDocumentsProvider] performs, over one long-lived
 * server session. Every method blocks on network I/O: callers must stay off the
 * main thread.
 */
interface NfsSession : Closeable {

    /** Export root attributes, null when the export is missing or not a directory. */
    fun probeRoot(): NodeAttrs?

    /** Attributes for one documentId, null when it does not exist. */
    fun stat(docId: String): NodeAttrs?

    /** Children of the directory named by [docId], directories first. */
    fun list(docId: String): List<ChildEntry>

    /** Opens [docId] for reading; throws when it is missing or not a regular file. */
    fun openFile(docId: String): NfsFile

    fun streamFor(docId: String): InputStream = NfsFileStream(openFile(docId))
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
 * in the fallback. The provider caches the session it gets back per [ExportSpec],
 * so the probe runs again only when the config changes.
 */
object NfsSessions {

    fun open(spec: ExportSpec): NfsSession = select(spec, ::NfsV4Access, ::NfsAccess)

    /** [v4] and [v3] are injectable so version selection is testable without a server. */
    internal fun select(
        spec: ExportSpec,
        v4: (ExportSpec) -> NfsSession,
        v3: (ExportSpec) -> NfsSession,
    ): NfsSession {
        val v4Failure: Throwable = try {
            return v4(spec)
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
            return v3(spec)
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
