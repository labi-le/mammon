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
        if (!path.startsWith("/") || path.endsWith("/") || '\u0000' in path) return null
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) return null
        return segments.joinToString("/").ifEmpty { ROOT_ID }
    }

    /** Export-absolute path for a documentId, null when the id is malformed or escapes the export. */
    fun pathFor(docId: String): String? {
        if (docId.isEmpty()) return null
        if (docId == ROOT_ID) return "/"
        // A NUL truncates the name on the wire, and deleteDocument reaches the backend
        // with nameOf's raw split rather than through componentOf.
        if (docId.first() == '/' || docId.last() == '/' || '\u0000' in docId) return null
        val segments = docId.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        return "/$docId"
    }

    fun nameOf(docId: String): String =
        if (docId == ROOT_ID) ROOT_ID else docId.substringAfterLast('/')

    /**
     * documentId of the containing directory; null for the export root, which has none,
     * and null for an id [pathFor] refuses, so a split id cannot reach NFS unvalidated.
     */
    fun parentOf(docId: String): String? = when {
        docId == ROOT_ID || pathFor(docId) == null -> null
        '/' !in docId -> ROOT_ID
        else -> docId.substringBeforeLast('/')
    }

    /** documentId of [name] inside [parentDocId], null when either is unusable. */
    fun childDocId(parentDocId: String, name: String?): String? {
        val parentPath = pathFor(parentDocId) ?: return null
        return childPath(parentPath, name)?.let(::docIdFor)
    }

    /**
     * One directory entry name safe to send as a single NFS component: not empty, not a
     * dot name, and carrying neither a separator nor a NUL. Mutating operations take a
     * name rather than a path, so this is the only guard between a foreign display name
     * and the wire.
     */
    fun componentOf(name: String?): String? =
        name?.takeIf { it.isNotEmpty() && it != "." && it != ".." && '/' !in it && '\u0000' !in it }

    /**
     * Export-absolute path of a directory entry, null for the dot names every readdir
     * carries and for any other name [componentOf] refuses.
     */
    fun childPath(parentPath: String, name: String?): String? {
        val component = componentOf(name) ?: return null
        return if (parentPath.endsWith("/")) parentPath + component else "$parentPath/$component"
    }

    fun isChild(parentDocId: String, docId: String): Boolean =
        when {
            docId == parentDocId -> false
            parentDocId == ROOT_ID -> docId != ROOT_ID && pathFor(docId) != null
            else -> docId.startsWith("$parentDocId/") && pathFor(docId) != null
        }
}
