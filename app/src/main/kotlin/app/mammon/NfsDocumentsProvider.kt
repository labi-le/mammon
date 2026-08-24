package app.mammon

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import com.emc.ecs.nfsclient.nfs.NfsGetAttributes
import com.emc.ecs.nfsclient.nfs.NfsType
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Exposes the configured NFS export through SAF, read-only.
 *
 * documentId = export-absolute path with "/" separators (see [PathCodec]). Every NFS
 * round trip is bounded by [NFS_TIMEOUT_MS]; per-call connect makes a cold browse of
 * a large directory noticeably slow — accepted for v0.2 (see NfsAccess).
 */
class NfsDocumentsProvider : DocumentsProvider() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate(): Boolean = true

    private fun spec(): ExportSpec? {
        val prefs = Prefs(context as Context)
        return if (prefs.host.isBlank() || prefs.export.isBlank()) null else prefs.spec()
    }

    private fun <T> nfsCall(block: (NfsAccess) -> T): T =
        runBlocking {
            try {
                withTimeout(NFS_TIMEOUT_MS) { block(nfsInstance()) }
            } catch (e: Exception) {
                throw FileNotFoundException(readableMessage(e))
            }
        }

    private var cachedAccess: Pair<ExportSpec, NfsAccess>? = null

    @Synchronized
    private fun nfsInstance(): NfsAccess {
        val s = spec() ?: throw FileNotFoundException(context!!.getString(R.string.err_no_config))
        cachedAccess?.let { (spec, access) ->
            if (spec == s) return access
            runCatching { access.close() }
        }
        val access = NfsAccess(s)
        cachedAccess = s to access
        return access
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: DEFAULT_ROOT_PROJECTION
        val out = MatrixCursor(cols)
        val s = spec()
        if (s != null) {
            out.newRow().apply {
                add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
                add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, PathCodec.ROOT_ID)
                add(DocumentsContract.Root.COLUMN_TITLE, s.exportTail)
                add(DocumentsContract.Root.COLUMN_SUMMARY, s.host)
                add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
            }
        }
        return out
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cols = projection ?: DEFAULT_DOC_PROJECTION
        val out = MatrixCursor(cols)
        nfsCall { access ->
            access.list(parentDocumentId).forEach { (path, attr) ->
                addRowFor(out, path, attr)
            }
            Unit
        }
        return out
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cols = projection ?: DEFAULT_DOC_PROJECTION
        val out = MatrixCursor(cols)
        if (documentId == PathCodec.ROOT_ID) {
            spec()?.let { s ->
                out.newRow().apply {
                    add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, PathCodec.ROOT_ID)
                    add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, s.exportTail)
                    add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                    add(DocumentsContract.Document.COLUMN_FLAGS, 0)
                }
            }
            return out
        }
        nfsCall { access ->
            val attr = access.stat(documentId)
                ?: throw FileNotFoundException("no such document: $documentId")
            addRowFor(out, PathCodec.pathFor(documentId)!!, attr)
            Unit
        }
        return out
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        PathCodec.isChild(parentDocumentId, documentId)

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        require(mode == "r") { "only read-only mode is supported" }

        val input = runBlocking {
            try {
                withTimeout(NFS_TIMEOUT_MS) {
                    val access = nfsInstance()
                    access.stat(documentId)
                        ?: throw FileNotFoundException("no such document: $documentId")
                    access.streamFor(documentId)
                }
            } catch (e: Exception) {
                throw FileNotFoundException(readableMessage(e))
            }
        }

        val (readSide, writeSide) = ParcelFileDescriptor.createReliablePipe()
        scope.launch {
            val sink = ParcelFileDescriptor.AutoCloseOutputStream(writeSide)
            try {
                var sent = 0L
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sent += n
                    if (sent > MAX_TRANSFER_BYTES) throw IOException(
                        context!!.getString(R.string.err_file_too_large),
                    )
                    sink.write(buf, 0, n)
                }
                sink.flush()
            } catch (e: IOException) {
                // Reliable pipe surfaces this as EIO on the reading end.
                try { writeSide.closeWithError(e.message) } catch (_: IOException) {}
            } finally {
                runCatching { input.close() }
                runCatching { sink.close() }
            }
        }
        return readSide
    }

    private fun addRowFor(out: MatrixCursor, path: String, attr: NfsGetAttributes) {
        val docId = requireNotNull(PathCodec.docIdFor(path))
        val isDir = attr.type == NfsType.NFS_DIR
        val name = PathCodec.nameOf(docId)
        out.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name)
            add(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                if (isDir) DocumentsContract.Document.MIME_TYPE_DIR else guessMime(name),
            )
            add(DocumentsContract.Document.COLUMN_FLAGS, 0)
            add(DocumentsContract.Document.COLUMN_SIZE, attr.size)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, attr.mtime.timeInMillis)
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MIME_BY_EXT[ext] ?: "application/octet-stream"
    }

    private fun readableMessage(e: Throwable): String {
        val msg = when {
            e.message?.contains("timed out", ignoreCase = true) == true ||
                e.message?.contains("timeout", ignoreCase = true) == true ->
                context!!.getString(R.string.err_timeout)
            e.message?.contains("Connection refused", ignoreCase = true) == true ->
                context!!.getString(R.string.err_unreachable)
            e is java.util.concurrent.TimeoutException ->
                context!!.getString(R.string.err_timeout)
            else -> context!!.getString(R.string.err_generic) + (e.message ?: e.javaClass.simpleName)
        }
        return msg.substringBefore('\n')
    }

    override fun shutdown() {
        scope.cancel()
        synchronized(this) {
            cachedAccess?.second?.let { runCatching { it.close() } }
            cachedAccess = null
        }
    }

    private companion object {
        const val ROOT_ID = "nfs"
        const val NFS_TIMEOUT_MS = 15_000L

        /** SAF clients get EIO past this instead of an endless transfer. */
        const val MAX_TRANSFER_BYTES = 64L * 1024 * 1024

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
        )

        val DEFAULT_DOC_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        val MIME_BY_EXT = mapOf(
            "txt" to "text/plain",
            "md" to "text/markdown",
            "csv" to "text/csv",
            "html" to "text/html",
            "htm" to "text/html",
            "xml" to "application/xml",
            "json" to "application/json",
            "pdf" to "application/pdf",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "svg" to "image/svg+xml",
            "webp" to "image/webp",
            "mp3" to "audio/mpeg",
            "ogg" to "audio/ogg",
            "wav" to "audio/wav",
            "mp4" to "video/mp4",
            "mkv" to "video/x-matroska",
            "webm" to "video/webm",
            "zip" to "application/zip",
            "gz" to "application/gzip",
            "tar" to "application/x-tar",
            "apk" to "application/vnd.android.package-archive",
        )
    }
}
