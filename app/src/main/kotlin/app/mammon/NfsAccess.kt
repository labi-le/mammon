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
 * NFSv3 operations over one [Nfs3] session. Sessions are cheap to open but the
 * library caches their TCP connections process-wide with no eviction hook, so every
 * provider call opens and closes its own (per-call connect) — known slow, ~15 s worst
 * case per browse; revisit if a session cache proves necessary.
 *
 * Every method blocks on network I/O: callers must stay off the main thread.
 */
class NfsAccess(private val spec: ExportSpec) : Closeable {

    private val nfs = Nfs3(spec.host, spec.export, CredentialNone(), RETRIES)

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

    /** Child paths (export-absolute) with attributes of the directory named by [docId]. */
    fun list(docId: String): List<Pair<String, NfsGetAttributes>> {
        val dir = fileFor(requireNotNull(PathCodec.pathFor(docId)))
        if (!dir.exists() || !dir.isDirectory) throw IOException("not a directory")
        return dir.listFiles()
            .filter { it.exists() }
            .map { child -> child.path to child.attributes }
            .sortedWith(
                compareBy<Pair<String, NfsGetAttributes>>(
                    { it.second.type != NfsType.NFS_DIR },
                    { it.first.lowercase(Locale.ROOT) },
                ),
            )
    }

    fun streamFor(docId: String): java.io.InputStream {
        val f = fileFor(requireNotNull(PathCodec.pathFor(docId)))
        if (!f.exists()) throw IOException("no such file")
        if (!f.isFile) throw IOException("not a regular file")
        return NfsFileInputStream(f, READ_CHUNK)
    }

    /**
     * Streams the file into [sink]; false when it exceeds [maxBytes]. Download-to-fd
     * capped rather than pipe-streamed: SAF clients mostly read small files, and the
     * cap turns an unbounded transfer into an immediate, explainable error instead of
     * a client that hangs on a multi-GiB read.
     */
    fun readFile(docId: String, maxBytes: Long, sink: (ByteArray, Int) -> Unit): Boolean {
        streamFor(docId).use { input ->
            var copied = 0L
            val buf = ByteArray(READ_CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                copied += n
                if (copied > maxBytes) return false
                sink(buf, n)
            }
        }
        return true
    }

    private fun fileFor(absolutePath: String): Nfs3File {
        val rel = absolutePath.removePrefix("/")
        return if (rel.isEmpty()) nfs.newFile("/") else nfs.newFile(rel)
    }

    override fun close() {
        // Nfs3 has no close(); dropping the reference releases everything but NetMgr's
        // process-wide connection cache, which has no public eviction API either.
    }

    private companion object {
        const val RETRIES = 2
        const val READ_CHUNK = 512 * 1024
    }
}
