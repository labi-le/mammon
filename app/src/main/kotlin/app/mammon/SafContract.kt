package app.mammon

import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Row flags. Optimistic by design: advisory by specification, and derivable only from a
 * per-child ACCESS on every listing, so [asSafException] — not any flag here — is what
 * tells a client a mutation did not happen.
 *
 * This file exists rather than a private companion because a JVM unit test can invoke no
 * member of a [android.provider.DocumentsProvider], and these are the decisions worth
 * pinning.
 */
internal object SafFlags {

    /**
     * [writable] is the BACKEND's [NfsSession.implementsWrites], which costs nothing to
     * consult and rules out a surface no server would ever be asked about.
     */
    fun forNode(isDirectory: Boolean, writable: Boolean): Int = when {
        !writable -> 0
        isDirectory -> Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE
        else -> Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
    }

    /** Topology, not permission: the export root cannot be removed through its own share. */
    val EXPORT_ROOT: Int = Document.FLAG_DIR_SUPPORTS_CREATE

    /** ACTION_CREATE_DOCUMENT filters the roots list first, before any query reaches us. */
    val ROOT: Int = Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_CREATE
}

/** One decoded `openDocument` mode string. */
internal data class SafOpenMode(val read: Boolean, val write: Boolean, val truncate: Boolean) {

    /** openProxyFileDescriptor takes only the access bits; truncation is ours to apply. */
    val proxyMode: Int
        get() = if (read) ParcelFileDescriptor.MODE_READ_WRITE else ParcelFileDescriptor.MODE_WRITE_ONLY

    companion object {
        /**
         * null for a mode this provider refuses, which openDocument turns into the
         * documented UnsupportedOperationException. `a` is refused rather than
         * approximated: a proxy fd carries no O_APPEND, so an appending client's first
         * write would silently land at offset 0.
         */
        fun parse(mode: String): SafOpenMode? {
            var read = false
            var write = false
            var truncate = false
            for (c in mode) when (c) {
                'r' -> read = true
                'w' -> write = true
                't' -> truncate = true
                else -> return null
            }
            if (!write) return if (read && !truncate) SafOpenMode(true, false, false) else null
            // openOutputStream(uri) asks for bare "w"; leaving a longer file's old tail
            // behind would hand the caller back a corrupt file that looks intact.
            return SafOpenMode(read, true, truncate || !read)
        }
    }
}

/** Names and MIME types, in both directions, over the one table. */
internal object SafNaming {

    const val OCTET_STREAM = "application/octet-stream"

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

    /** Derived, never transcribed; first wins, so image/jpeg answers jpg rather than jpeg. */
    private val EXT_BY_MIME: Map<String, String> =
        HashMap<String, String>(MIME_BY_EXT.size).apply {
            MIME_BY_EXT.forEach { (ext, mime) -> putIfAbsent(mime, ext) }
        }

    fun mimeFor(name: String): String =
        MIME_BY_EXT[name.substringAfterLast('.', "").lowercase()] ?: OCTET_STREAM

    /**
     * The provider owns the extension — createDocument licenses altering displayName — and
     * must use it: [mimeFor] re-derives the row's MIME type from the name, so a created
     * file whose extension contradicts the requested type reads back as something else.
     *
     * A null type (the framework reads it out of a Bundle unchecked) and
     * [Document.MIME_TYPE_DIR] both fall through untouched, the latter because a directory
     * has no entry in the table and therefore no extension to own.
     */
    fun createdName(mimeType: String?, displayName: String): String {
        val ext = mimeType?.let { EXT_BY_MIME[it] } ?: return displayName
        return if (mimeFor(displayName) == mimeType) displayName else "$displayName.$ext"
    }

    /** GUARDED4 leaves collision detection to the server, so de-duplication is a retry. */
    fun retryName(name: String, attempt: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "$name ($attempt)"
        else name.substring(0, dot) + " ($attempt)" + name.substring(dot)
    }
}

/**
 * The errno a proxy-fd callback answers with, named rather than numeric:
 * [android.system.OsConstants] carries no `ConstantValue` and is filled by native init, so
 * the numbers read zero off-device and only a named mapping can be pinned by a test.
 */
internal enum class SafErrno { ACCESS, NOENT, EXISTS, NOTEMPTY, NOSPC, NOSYS, IO }

internal fun NfsFailure.safErrno(): SafErrno = when (this) {
    is NfsFailure.PermissionDenied -> SafErrno.ACCESS
    is NfsFailure.NotFound -> SafErrno.NOENT
    is NfsFailure.AlreadyExists -> SafErrno.EXISTS
    is NfsFailure.DirectoryNotEmpty -> SafErrno.NOTEMPTY
    is NfsFailure.OutOfSpace -> SafErrno.NOSPC
    // ENOSYS is a true claim about the operation where EROFS would be a false one about
    // the filesystem, which both backends do write to.
    is NfsFailure.Unsupported -> SafErrno.NOSYS
    // Neither gets an errno of its own: a caller holding a proxy fd can do nothing with a
    // read that timed out or lost its connection that it does not already do with a
    // failed one. Both pay off in the message a user sees, not here.
    is NfsFailure.Timeout -> SafErrno.IO
    is NfsFailure.Unreachable -> SafErrno.IO
    is NfsFailure.Server -> SafErrno.IO
}

/**
 * One type for all of them: FileNotFoundException is what the write methods declare and
 * the only refusal the system UI renders — DocumentsUI drops an
 * UnsupportedOperationException from New folder and from SAVE without telling the user
 * anything. The `when` stays exhaustive so a case added later is decided rather than
 * inheriting this one.
 */
internal fun NfsFailure.asSafException(message: String): Exception = when (this) {
    is NfsFailure.Unsupported,
    is NfsFailure.PermissionDenied,
    is NfsFailure.NotFound,
    is NfsFailure.AlreadyExists,
    is NfsFailure.DirectoryNotEmpty,
    is NfsFailure.OutOfSpace,
    is NfsFailure.Timeout,
    is NfsFailure.Unreachable,
    is NfsFailure.Server,
    -> FileNotFoundException(message)
}

/**
 * What a failed `openProxyFileDescriptor` owes the client.
 *
 * [FILE_ERROR] is the bridge failing to mount, being unmounted under this app, or the
 * binder to the system server dying — the three the call declares as IOException, and
 * where descriptor exhaustion arrives too. [NO_PROXY_FD] is the device unable to give this
 * app a proxy descriptor at all — a property of the kernel, not of the export, so its
 * message must describe the device rather than the share. [RETHROW] keeps everything
 * else, a bug of ours in this open path included: one that read as a file error would be
 * invisible. The proxy callback is not among them — it runs on its own looper, where the
 * framework turns a failure into an errno reply instead.
 */
internal enum class ProxyOpenOutcome { FILE_ERROR, NO_PROXY_FD, RETHROW }

internal fun Throwable.proxyOpenOutcome(): ProxyOpenOutcome = when (this) {
    is IOException -> ProxyOpenOutcome.FILE_ERROR
    is IllegalStateException -> ProxyOpenOutcome.NO_PROXY_FD
    else -> ProxyOpenOutcome.RETHROW
}
