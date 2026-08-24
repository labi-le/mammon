package app.mammon

import com.emc.ecs.nfsclient.nfs.NfsGetAttributes
import com.emc.ecs.nfsclient.nfs.NfsType
import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.io.NfsFileInputStream
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.rpc.CredentialNone
import java.io.Closeable
import java.io.IOException
import java.util.Locale

/**
 * NFSv3 operations over one long-lived [Nfs3] session. The library caches TCP
 * connections process-wide with no eviction hook, so the session is kept alive for
 * the lifetime of a configuration and rebuilt only when [spec] changes (the swap
 * lives in NfsDocumentsProvider.nfsInstance).
 *
 * Every method blocks on network I/O: callers must stay off the main thread.
 */
class NfsAccess(private val spec: ExportSpec) : Closeable {

    private val nfs = Nfs3(spec.host, spec.export, CredentialNone(), RETRIES)

    /** One directory child with its READDIRPLUS attributes; [path] is export-absolute. */
    data class ChildEntry(val path: String, val attributes: NfsGetAttributes)

    /** Export root attributes, null when the export is missing or not a directory. */
    fun probeRoot(): NfsGetAttributes? {
        val root = nfs.newFile("/")
        return if (root.exists() && root.isDirectory) root.attributes else null
    }

    fun stat(docId: String): NfsGetAttributes? {
        val path = PathCodec.pathFor(docId) ?: return null
        val f = fileFor(path)
        return if (f.exists()) f.attributes else null
    }

    /**
     * Children of the directory named by [docId], directories first. One READDIRPLUS
     * cookie loop carries every child's attributes, replacing the READDIR +
     * per-child exists()/GETATTR storm.
     */
    fun list(docId: String): List<ChildEntry> {
        val dirPath = requireNotNull(PathCodec.pathFor(docId))
        val dir = fileFor(dirPath)
        if (!dir.exists() || !dir.isDirectory) throw IOException("not a directory")

        val children = ArrayList<ChildEntry>()
        var cookie = 0L
        var cookieverf = 0L
        do {
            val page = dir.readdirplus(cookie, cookieverf, READDIRPLUS_DIR_COUNT, READDIRPLUS_MAX_COUNT)
            cookie = page.cookie
            cookieverf = page.cookieverf
            for (entry in page.entries) {
                val attrs = entry.attributes ?: continue
                val path = listableChild(dirPath, entry.fileName, attrs.type) ?: continue
                children += ChildEntry(path, attrs)
            }
        } while (!page.isEof && page.entries.isNotEmpty())
        return children.sortedWith(
            compareBy(
                { it.attributes.type != NfsType.NFS_DIR },
                { it.path.lowercase(Locale.ROOT) },
            ),
        )
    }

    fun streamFor(docId: String): java.io.InputStream {
        val f = fileFor(requireNotNull(PathCodec.pathFor(docId)))
        if (!f.exists()) throw IOException("no such file")
        if (!f.isFile) throw IOException("not a regular file")
        return NfsFileInputStream(f, READ_CHUNK)
    }

    private fun fileFor(absolutePath: String): Nfs3File {
        val rel = absolutePath.removePrefix("/")
        return if (rel.isEmpty()) nfs.newFile("/") else nfs.newFile(rel)
    }

    override fun close() {
        // Nfs3 has no close(); dropping the reference releases everything but NetMgr's
        // process-wide connection cache, which has no public eviction API either.
    }

    companion object {
        const val READ_CHUNK = 512 * 1024

        // Byte budgets per READDIRPLUS page: large enough that ordinary
        // directories need one round trip.
        private const val READDIRPLUS_DIR_COUNT = 32 * 1024
        private const val READDIRPLUS_MAX_COUNT = 64 * 1024

        /** Pure filter for one READDIRPLUS entry; returns the export-absolute child
         *  path, or null when the entry must be dropped (dot names, or a type that is
         *  neither directory nor regular file). Null attributes mean the server
         *  withheld them. */
        internal fun listableChild(parentPath: String, name: String?, type: NfsType?): String? {
            if (name.isNullOrEmpty() || name == "." || name == "..") return null
            if (type != NfsType.NFS_DIR && type != NfsType.NFS_REG) return null
            return if (parentPath.endsWith("/")) parentPath + name else "$parentPath/$name"
        }

        private const val RETRIES = 2
    }
}
