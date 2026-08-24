package app.mammon

/**
 * documentId <-> export-absolute-path codec. Document ids arriving from foreign SAF
 * clients are hostile input: every id goes through [pathFor] before it reaches NFS.
 */
object PathCodec {

    const val ROOT_ID = "."

    /** Canonical documentId for an export-absolute path ("/" or "/a/b"), null when malformed. */
    fun docIdFor(path: String): String? {
        if (path == "/") return ROOT_ID
        if (!path.startsWith("/") || path.endsWith("/")) return null
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) return null
        return segments.joinToString("/").ifEmpty { ROOT_ID }
    }

    /** Export-absolute path for a documentId, null when the id is malformed or escapes the export. */
    fun pathFor(docId: String): String? {
        if (docId.isEmpty()) return null
        if (docId == ROOT_ID) return "/"
        if (docId.first() == '/' || docId.last() == '/') return null
        val segments = docId.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        return "/$docId"
    }

    fun nameOf(docId: String): String =
        if (docId == ROOT_ID) ROOT_ID else docId.substringAfterLast('/')

    fun isChild(parentDocId: String, docId: String): Boolean =
        when {
            docId == parentDocId -> false
            parentDocId == ROOT_ID -> docId != ROOT_ID && pathFor(docId) != null
            else -> docId.startsWith("$parentDocId/") && pathFor(docId) != null
        }
}
