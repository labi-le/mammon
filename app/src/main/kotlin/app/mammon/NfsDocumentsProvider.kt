package app.mammon

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Must match android:authorities on the provider; ManifestGuardTest pins the pair. */
internal const val SAF_AUTHORITY = "app.mammon.nfs"

/**
 * Exposes the configured NFS export through SAF.
 *
 * Row flags are optimistic — advisory by specification, and derivable only from a
 * per-child ACCESS storm — so the exception a mutation throws, not the flag, is what
 * tells a client the operation did not happen. SafContract.kt holds that mapping.
 *
 * documentId = export-absolute path with "/" separators (see [PathCodec]). [NFS_TIMEOUT_MS]
 * bounds a SAF call, not each round trip inside it — a listing pages and a de-duplicating
 * create retries — and the per-COMPOUND deadline is the backend's. One long-lived session
 * per config is kept in [nfsInstance], and since that entry holds the session it also
 * caches which protocol version [NfsSessions] settled on.
 */
class NfsDocumentsProvider : DocumentsProvider() {


    /** Pumps are bounded so one misbehaving client cannot monopolize IO threads;
     *  residual leak: a client holding its fd open keeps its pump alive. */
    private val pumpScope = CoroutineScope(Dispatchers.IO.limitedParallelism(4))

    override fun onCreate(): Boolean = true

    private fun target(): NfsTarget? {
        val prefs = Prefs(context as Context)
        return if (prefs.host.isBlank() || prefs.export.isBlank()) null else prefs.target()
    }

    private fun <T> nfsCall(block: (NfsSession) -> T): T =
        runBlocking {
            try {
                withTimeout(NFS_TIMEOUT_MS) { block(nfsInstance()) }
            } catch (e: OperationCanceledException) {
                throw e
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: NfsFailure) {
                // Ahead of the generic arm below, which would flatten Unsupported —
                // whose documented signal is a different exception type — into
                // FileNotFoundException.
                throw e.asSafException(readableMessage(e))
            } catch (e: Exception) {
                throw FileNotFoundException(readableMessage(e))
            }
        }

    private var cachedAccess: Pair<NfsTarget, NfsSession>? = null

    private fun nfsInstance(): NfsSession {
        val t = target() ?: throw FileNotFoundException(context!!.getString(R.string.err_no_config))
        synchronized(this) {
            cachedAccess?.let { (target, session) -> if (target == t) return session }
        }
        // Built OUTSIDE the monitor on purpose: opening a session does real network
        // I/O (a v4.1 EXCHANGE_ID/CREATE_SESSION handshake, or v3 portmap + mountd +
        // LOOKUP), so holding the lock through it would park every other SAF call
        // uninterruptibly past their withTimeout. Two threads missing at once
        // therefore build concurrently; only the compare-and-swap is serialized.
        val candidate = NfsSessions.open(t)
        synchronized(this) {
            cachedAccess?.let { (target, session) ->
                if (target == t) {
                    runCatching { candidate.close() }
                    return session
                }
                runCatching { session.close() }
            }
            cachedAccess = t to candidate
            return candidate
        }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: DEFAULT_ROOT_PROJECTION
        val out = MatrixCursor(cols)
        val s = target()?.spec
        if (s != null) {
            out.newRow().apply {
                add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
                add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, PathCodec.ROOT_ID)
                add(DocumentsContract.Root.COLUMN_TITLE, s.exportTail)
                add(DocumentsContract.Root.COLUMN_SUMMARY, s.host)
                add(DocumentsContract.Root.COLUMN_FLAGS, SafFlags.ROOT)
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
        // The client's cursor observes this URI, so a mutation's notifyChange reaches
        // nobody without it: the whole symptom is a listing that never refreshes.
        out.setNotificationUri(
            context!!.contentResolver,
            DocumentsContract.buildChildDocumentsUri(SAF_AUTHORITY, parentDocumentId),
        )
        nfsCall { access ->
            val writable = access.implementsWrites
            access.list(parentDocumentId).forEach { (path, attr) ->
                addRowFor(out, path, attr, writable)
            }
            Unit
        }
        return out
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cols = projection ?: DEFAULT_DOC_PROJECTION
        val out = MatrixCursor(cols)
        out.setNotificationUri(
            context!!.contentResolver,
            DocumentsContract.buildDocumentUri(SAF_AUTHORITY, documentId),
        )
        if (documentId == PathCodec.ROOT_ID) {
            // This branch answers without a round trip, so a sleeping server still shows a
            // browsable root; that is also why its create flag cannot consult the backend.
            target()?.spec?.let { s ->
                out.newRow().apply {
                    add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, PathCodec.ROOT_ID)
                    add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, s.exportTail)
                    add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                    add(DocumentsContract.Document.COLUMN_FLAGS, SafFlags.EXPORT_ROOT)
                }
            }
            return out
        }
        nfsCall { access ->
            val attr = access.stat(documentId)
                ?: throw FileNotFoundException("no such document: $documentId")
            addRowFor(out, PathCodec.pathFor(documentId)!!, attr, access.implementsWrites)
            Unit
        }
        return out
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        PathCodec.isChild(parentDocumentId, documentId)

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String?,
        displayName: String?,
    ): String {
        if (PathCodec.pathFor(parentDocumentId) == null) {
            throw FileNotFoundException("no such document: $parentDocumentId")
        }
        val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        // Both extras are declared non-null but the framework reads them out of a Bundle
        // unchecked, so a hand-built resolver.call can omit either; the refusal below is
        // the answer, not the NullPointerException a non-null parameter would raise.
        val wanted = PathCodec.componentOf(displayName?.let { SafNaming.createdName(mimeType, it) })
            ?: throw FileNotFoundException(
                context!!.getString(R.string.err_bad_display_name, displayName ?: ""),
            )
        val name = nfsCall { access -> createUnique(access, parentDocumentId, wanted, isDirectory) }
        notifyChildrenOf(parentDocumentId)
        return requireNotNull(PathCodec.childDocId(parentDocumentId, name))
    }

    /**
     * GUARDED4 and MKDIR fail a collision instead of truncating, so de-duplicating by
     * retry is race-free where a pre-flight existence check would not be. The contract
     * licenses altering displayName precisely for this.
     */
    private fun createUnique(
        access: NfsSession,
        parentDocumentId: String,
        wanted: String,
        isDirectory: Boolean,
    ): String {
        var attempt = 1
        while (true) {
            val name = if (attempt == 1) wanted else SafNaming.retryName(wanted, attempt)
            try {
                // The handle is closed straight away: createDocument returns an id, and
                // the client opens the document itself when it wants the bytes.
                if (isDirectory) access.makeDirectory(parentDocumentId, name)
                else access.createFile(parentDocumentId, name).file.close()
                return name
            } catch (e: NfsFailure.AlreadyExists) {
                if (++attempt > MAX_NAME_ATTEMPTS) throw e
            }
        }
    }

    /**
     * Non-recursive: NFS REMOVE refuses a non-empty directory and that refusal is the
     * user's answer. Nothing is removed as a side effect, so no descendant URI grant is
     * owed a revoke — the framework revokes the named document itself.
     */
    override fun deleteDocument(documentId: String) {
        if (documentId == PathCodec.ROOT_ID) {
            throw UnsupportedOperationException("the export root cannot be deleted")
        }
        val parentDocumentId = PathCodec.parentOf(documentId)
            ?: throw FileNotFoundException("no such document: $documentId")
        nfsCall { access -> access.remove(parentDocumentId, PathCodec.nameOf(documentId)) }
        notifyChildrenOf(parentDocumentId)
    }

    /** A listing, not a row: an appearance or a disappearance is only visible in the parent. */
    private fun notifyChildrenOf(parentDocumentId: String) {
        context?.contentResolver?.notifyChange(
            DocumentsContract.buildChildDocumentsUri(SAF_AUTHORITY, parentDocumentId),
            null,
        )
    }

    /** The row carries the new size and mtime, and so does the listing that shows it. */
    private fun notifyWritten(documentId: String) {
        val resolver = context?.contentResolver ?: return
        resolver.notifyChange(DocumentsContract.buildDocumentUri(SAF_AUTHORITY, documentId), null)
        PathCodec.parentOf(documentId)?.let {
            resolver.notifyChange(DocumentsContract.buildChildDocumentsUri(SAF_AUTHORITY, it), null)
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        // Outside nfsCall: that wrapper rewrites what it catches, and the documented
        // signal for an unsupported mode is UnsupportedOperationException.
        val open = SafOpenMode.parse(mode)
            ?: throw UnsupportedOperationException("unsupported open mode: $mode")
        return if (open.write) openForWrite(documentId, open, signal)
        else openForRead(documentId, signal)
    }

    /**
     * A proxy fd, not ParcelFileDescriptor.open plus an OnCloseListener: that listener
     * fires after the client's fd is gone, so a server refusal would have nowhere to be
     * reported and the write would be lost silently.
     */
    private fun openForWrite(
        documentId: String,
        open: SafOpenMode,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        signal?.throwIfCancellationRequestedCompat()
        val opened = nfsCall { access ->
            if (!access.implementsWrites) throw NfsFailure.Unsupported("write")
            // The open reply already carries the size, so no separate stat is owed.
            access.openFile(documentId)
        }
        val descriptor = try {
            storageManager().openProxyFileDescriptor(
                open.proxyMode,
                NfsProxyFile(
                    documentId,
                    opened.file,
                    if (open.truncate) 0L else opened.attributes.size,
                    truncating = open.truncate,
                ),
                proxyHandler(),
            )
        } catch (e: Throwable) {
            runCatching { opened.file.close() }
            throw if (e is IOException) FileNotFoundException(readableMessage(e)) else e
        }
        // Last, because it is the only step that destroys content: anything failing after
        // it hands the client a refusal for a file already emptied. openProxyFileDescriptor
        // itself throws IOException when the app has too many proxy fds registered.
        if (open.truncate) {
            try {
                nfsCall { access -> access.setAttributes(documentId, size = 0L) }
            } catch (e: Throwable) {
                runCatching { descriptor.close() }
                throw e
            }
        }
        return descriptor
    }

    private fun openForRead(
        documentId: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        signal?.throwIfCancellationRequestedCompat()
        val input = runBlocking {
            try {
                withTimeout(NFS_TIMEOUT_MS) {
                    val access = nfsInstance()
                    signal?.throwIfCancellationRequestedCompat()
                    access.streamFor(documentId)
                }
            } catch (e: OperationCanceledException) {
                throw e
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw FileNotFoundException(readableMessage(e))
            }
        }
        signal?.throwIfCancellationRequestedCompat()

        val (readSide, writeSide) = ParcelFileDescriptor.createReliablePipe()
        pumpScope.launch {
            val sink = ParcelFileDescriptor.AutoCloseOutputStream(writeSide)
            try {
                var sent = 0L
                val buf = ByteArray(NFS_READ_CHUNK)
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
            } catch (e: Throwable) {
                // Reliable pipe surfaces this as EIO on the reading end; catching
                // Throwable keeps a non-IO crash from masquerading as clean EOF.
                try { writeSide.closeWithError(e.message ?: e.javaClass.name) } catch (_: Throwable) {}
            } finally {
                runCatching { input.close() }
                runCatching { sink.close() }
            }
        }
        return readSide
    }

    private fun storageManager(): StorageManager =
        context!!.getSystemService(StorageManager::class.java)

    private val proxyThreads = arrayOfNulls<HandlerThread>(PROXY_LOOPERS)
    private var nextProxyLooper = 0

    /**
     * A few loopers rather than one per open document, and rather than one for all of
     * them: the session grants several request slots and the FUSE daemon already drives
     * one session from several threads, so concurrent documents genuinely overlap — while
     * a single looper would queue one document's release behind another's write for the
     * backend's whole retry budget. Kept alive until [shutdown], since an idle looper is
     * cheaper than starting one per open.
     */
    private fun proxyHandler(): Handler = synchronized(this) {
        val slot = nextProxyLooper
        nextProxyLooper = (slot + 1) % proxyThreads.size
        val thread = proxyThreads[slot] ?: HandlerThread("mammon-saf-write-$slot").also {
            it.start()
            proxyThreads[slot] = it
        }
        Handler(thread.looper)
    }

    /**
     * Every callback for one fd arrives on that fd's single looper, so the mutable size
     * and change flag need no guarding.
     *
     * [truncating] seeds that flag from the emptying its opener is about to perform, so a
     * client that truncates and then writes nothing still refreshes the listing. A
     * truncate that then fails costs one wasted refresh, which is the safe direction.
     */
    private inner class NfsProxyFile(
        private val documentId: String,
        private val file: NfsFile,
        private var size: Long,
        truncating: Boolean,
    ) : ProxyFileDescriptorCallback() {

        private var changed = truncating

        override fun onGetSize(): Long = size

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int = errnoGuarded("read") {
            var done = 0
            while (done < size) {
                val n = file.readAt(offset + done, data, done, size - done)
                if (n <= 0) break
                done += n
            }
            done
        }

        override fun onWrite(offset: Long, size: Int, data: ByteArray): Int = errnoGuarded("write") {
            var done = 0
            while (done < size) {
                val n = file.writeAt(offset + done, data, done, size - done)
                if (n <= 0) throw NfsFailure.Server("server took no bytes at ${offset + done}")
                done += n
                // Recorded per chunk: a refusal partway through still leaves the bytes the
                // server already took, and onGetSize must not answer with the old size.
                changed = true
                this.size = maxOf(this.size, offset + done)
            }
            done
        }

        /** Every WRITE this seam sends is FILE_SYNC4, so the server has already committed. */
        override fun onFsync() = Unit

        override fun onRelease() {
            runCatching { file.close() }
            if (changed) notifyWritten(documentId)
        }
    }

    /** The errno reaches the client's own write(2), which is the point of the proxy fd. */
    private inline fun errnoGuarded(op: String, block: () -> Int): Int =
        try {
            block()
        } catch (e: NfsFailure) {
            throw ErrnoException(op, errnoOf(e.safErrno()))
        } catch (e: IOException) {
            throw ErrnoException(op, OsConstants.EIO)
        }

    private fun errnoOf(e: SafErrno): Int = when (e) {
        SafErrno.ACCESS -> OsConstants.EACCES
        SafErrno.NOENT -> OsConstants.ENOENT
        SafErrno.EXISTS -> OsConstants.EEXIST
        SafErrno.NOTEMPTY -> OsConstants.ENOTEMPTY
        SafErrno.NOSPC -> OsConstants.ENOSPC
        SafErrno.ROFS -> OsConstants.EROFS
        SafErrno.IO -> OsConstants.EIO
    }

    private fun addRowFor(out: MatrixCursor, path: String, attr: NodeAttrs, writable: Boolean) {
        val docId = requireNotNull(PathCodec.docIdFor(path))
        val name = PathCodec.nameOf(docId)
        out.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name)
            add(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                if (attr.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR
                else SafNaming.mimeFor(name),
            )
            add(
                DocumentsContract.Document.COLUMN_FLAGS,
                SafFlags.forNode(attr.isDirectory, writable),
            )
            add(DocumentsContract.Document.COLUMN_SIZE, attr.size)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, attr.lastModifiedMillis)
        }
    }

    /** A refusal a user can act on; only the residual [NfsFailure.Server] falls back to the status name. */
    private fun failureMessage(e: NfsFailure): String = when (e) {
        is NfsFailure.PermissionDenied -> context!!.getString(R.string.err_nfs_denied)
        is NfsFailure.NotFound -> context!!.getString(R.string.err_nfs_missing)
        is NfsFailure.AlreadyExists -> context!!.getString(R.string.err_nfs_exists)
        is NfsFailure.DirectoryNotEmpty -> context!!.getString(R.string.err_nfs_not_empty)
        is NfsFailure.OutOfSpace -> context!!.getString(R.string.err_nfs_no_space)
        is NfsFailure.Unsupported -> context!!.getString(R.string.err_nfs_read_only)
        is NfsFailure.Server ->
            context!!.getString(R.string.err_generic, e.message ?: e.javaClass.simpleName)
    }

    private fun readableMessage(e: Throwable): String {
        val msg = when {
            e is NfsFailure -> failureMessage(e)
            e.message?.contains("timed out", ignoreCase = true) == true ||
                e.message?.contains("timeout", ignoreCase = true) == true ->
                context!!.getString(R.string.err_timeout)
            e.message?.contains("Connection refused", ignoreCase = true) == true ||
                e.message?.contains("waiting for connection", ignoreCase = true) == true ->
                context!!.getString(R.string.err_unreachable)
            e is java.util.concurrent.TimeoutException ->
                context!!.getString(R.string.err_timeout)
            else -> context!!.getString(R.string.err_generic, e.message ?: e.javaClass.simpleName)
        }
        return msg.substringBefore('\n')
    }

    private fun CancellationSignal.throwIfCancellationRequestedCompat() {
        if (isCanceled) throw android.os.OperationCanceledException()
    }

    override fun shutdown() {
        pumpScope.cancel()
        synchronized(this) {
            proxyThreads.forEach { it?.quitSafely() }
            proxyThreads.fill(null)
            cachedAccess?.second?.let { runCatching { it.close() } }
            cachedAccess = null
        }
    }

    private companion object {
        const val ROOT_ID = "nfs"
        const val NFS_TIMEOUT_MS = 15_000L

        /** A de-duplicating create gives up rather than walking a directory forever. */
        const val MAX_NAME_ATTEMPTS = 32

        /** Matched to the read pumps' parallelism: one misbehaving client cannot own them all. */
        const val PROXY_LOOPERS = 4

        /** The pipe pump gives EIO past this; a proxy-fd read is demand-driven and unbounded. */
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
    }
}
