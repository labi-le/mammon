package app.mammon

import com.emc.ecs.nfsclient.nfs.NfsDirectoryPlusEntry
import com.emc.ecs.nfsclient.nfs.NfsGetAttributes
import com.emc.ecs.nfsclient.nfs.NfsType
import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.network.NetMgr
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import java.net.InetSocketAddress

/**
 * NFSv3 operations over one long-lived [Nfs3] session. The library caches TCP
 * connections process-wide in NetMgr; [close] evicts this spec's entry so a rebuilt
 * session cannot silently reuse this one. Eviction leaves this session's channel
 * itself open (the library has no harder hook), so a query still holding this
 * instance across a config change finishes on its own connection instead of failing.
 *
 * Read-only by type: this backend exists as the fallback for servers too old for v4.1,
 * and [ReadOnlyNfsSession] is where the mutating half's refusals live.
 *
 * Every method blocks on network I/O: callers must stay off the main thread.
 */
class NfsAccess(target: NfsTarget) : ReadOnlyNfsSession {

    private val spec = target.spec

    // AUTH_NONE gets the MOUNT accepted and the first GETATTR refused with
    // NFS3ERR_ACCES on a stock Linux server; AUTH_SYS is what NfsV4Access sends too.
    private val nfs = Nfs3(
        spec.host,
        spec.export,
        with(target.identity) {
            CredentialUnix(uid.toInt(), gid.toInt(), auxGids.mapTo(LinkedHashSet(), Long::toInt))
        },
        RETRIES,
    )

    override fun probeRoot(): NodeAttrs? {
        val root = nfs.newFile("/")
        return if (root.exists() && root.isDirectory) root.attributes.toNode() else null
    }

    override fun stat(docId: String): NodeAttrs? {
        val path = PathCodec.pathFor(docId) ?: return null
        return statPath(path)?.toNode()
    }

    /** Attributes for one export-absolute child path, or null when missing. */
    private fun statPath(path: String): NfsGetAttributes? {
        val f = fileFor(path)
        return if (f.exists()) f.attributes else null
    }

    /**
     * One READDIRPLUS cookie loop carries every child's attributes, replacing the
     * READDIR + per-child exists()/GETATTR storm; entries whose attributes the server
     * withheld fall back to a single stat instead of being dropped.
     */
    override fun list(docId: String): List<ChildEntry> {
        val dirPath = requireNotNull(PathCodec.pathFor(docId))
        val dir = fileFor(dirPath)
        if (!dir.exists()) throw NfsFailure.NotFound(docId)
        if (!dir.isDirectory) throw NfsFailure.Server("not a directory: $docId")

        val children = ArrayList<ChildEntry>()
        var cookie = 0L
        var cookieverf = 0L
        do {
            val page = dir.readdirplus(cookie, cookieverf, READDIRPLUS_DIR_COUNT, READDIRPLUS_MAX_COUNT)
            cookie = page.cookie
            cookieverf = page.cookieverf
            for (entry in page.entries) {
                resolveEntry(dirPath, entry, ::statPath)?.let { children += it }
            }
        } while (!page.isEof && page.entries.isNotEmpty())
        return children.directoriesFirst()
    }

    override fun openFile(docId: String): NfsFile {
        val f = fileFor(requireNotNull(PathCodec.pathFor(docId)))
        if (!f.exists()) throw NfsFailure.NotFound(docId)
        if (!f.isFile) throw NfsFailure.Server("not a regular file: $docId")
        return Handle(f)
    }

    /** One READ per call against a resolved handle; the server caps [len] at its rtmax. */
    private class Handle(private val file: Nfs3File) : NfsFile {

        override fun readAt(offset: Long, dst: ByteArray, off: Int, len: Int): Int =
            file.read(offset, minOf(len, NFS_READ_CHUNK), dst, off).bytesRead.coerceAtLeast(0)

        override fun close() = Unit
    }

    /**
     * The path must stay export-absolute: [Nfs3File] builds its parent chain by
     * trimming the last separator, and a relative path never reaches a root, so the
     * constructor recurses until the stack is gone.
     */
    private fun fileFor(absolutePath: String): Nfs3File = nfs.newFile(absolutePath)

    override fun close() {
        // NetMgr keys its maps with createUnresolved addresses; a resolved one would
        // miss the cache. Best-effort: the portmapper (111) connection stays cached.
        runCatching {
            NetMgr.getInstance().dropConnection(InetSocketAddress.createUnresolved(spec.host, spec.port))
        }
    }

    companion object {
        // Byte budgets per READDIRPLUS page: large enough that ordinary
        // directories need one round trip.
        private const val READDIRPLUS_DIR_COUNT = 32 * 1024
        private const val READDIRPLUS_MAX_COUNT = 64 * 1024

        /** Pure filter for one READDIRPLUS entry: returns the export-absolute child
         *  path, or null when the entry must be dropped (dot names, or a type that is
         *  neither directory nor regular file). */
        internal fun listableChild(parentPath: String, name: String?, type: NfsType?): String? =
            PathCodec.childPath(parentPath, name)?.takeIf { type == NfsType.NFS_DIR || type == NfsType.NFS_REG }

        internal fun NfsGetAttributes.toNode(): NodeAttrs =
            NodeAttrs(type == NfsType.NFS_DIR, size, mtime.timeInMillis)

        /** Resolves one READDIRPLUS entry into a kept child, or null when the entry
         *  must be dropped: dot names (before any stat), a type that ends up neither
         *  directory nor regular file, or a failed fallback stat when the server
         *  withheld the attributes. At most one stat per entry. [stat] stands in for
         *  instance stat so tests can run this without an NFS session. */
        internal fun resolveEntry(
            parentPath: String,
            entry: NfsDirectoryPlusEntry,
            stat: (String) -> NfsGetAttributes?,
        ): ChildEntry? {
            val name = entry.fileName
            val attrs = entry.attributes
                ?: stat(PathCodec.childPath(parentPath, name) ?: return null)
                ?: return null
            val path = listableChild(parentPath, name, attrs.type) ?: return null
            return ChildEntry(path, attrs.toNode())
        }

        private const val RETRIES = 2
    }
}
