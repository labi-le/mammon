package app.mammon

import com.emc.ecs.nfsclient.nfs.NfsCreateMode
import com.emc.ecs.nfsclient.nfs.NfsDirectoryPlusEntry
import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsGetAttributes
import com.emc.ecs.nfsclient.nfs.NfsLookupResponse
import com.emc.ecs.nfsclient.nfs.NfsResponseBase
import com.emc.ecs.nfsclient.nfs.NfsSetAttributes
import com.emc.ecs.nfsclient.nfs.NfsStatus
import com.emc.ecs.nfsclient.nfs.NfsTime
import com.emc.ecs.nfsclient.nfs.NfsType
import com.emc.ecs.nfsclient.nfs.NfsWriteRequest
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.network.NetMgr
import com.emc.ecs.nfsclient.rpc.AcceptStatus
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import com.emc.ecs.nfsclient.rpc.RpcException
import com.emc.ecs.nfsclient.rpc.RpcStatus
import java.io.FileNotFoundException
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * NFSv3 operations over one long-lived [Nfs3] session. The library caches TCP
 * connections process-wide in NetMgr; [close] evicts this spec's entry so a rebuilt
 * session cannot silently reuse this one. Eviction leaves this session's channel
 * itself open (the library has no harder hook), so a query still holding this
 * instance across a config change finishes on its own connection instead of failing.
 *
 * CREATE, MKDIR, REMOVE and RMDIR go out through the library's naked calls, which try
 * once. The wrapped ones re-send on any network error without asking whether the
 * operation is idempotent, and NFSv3 is stateless — no session slot, no server-side
 * replay cache — so a resend is indistinguishable from a second request. A lost
 * mutation is therefore reported failed rather than silently performed twice.
 *
 * Everything here works from filehandles rather than the library's Nfs3File, which
 * swallows the reason a lookup failed and answers null. See [walk].
 *
 * Every method blocks on network I/O: callers must stay off the main thread.
 */
class NfsAccess(target: NfsTarget) : NfsSession {

    private val spec = target.spec

    // AUTH_NONE gets the MOUNT accepted and the first GETATTR refused with
    // NFS3ERR_ACCES on a stock Linux server; AUTH_SYS is what NfsV4Access sends too.
    private val nfs = failing({ "${spec.host}:${spec.export}" }) {
        Nfs3(
            spec.host,
            spec.export,
            with(target.identity) {
                CredentialUnix(uid.toInt(), gid.toInt(), auxGids.mapTo(LinkedHashSet(), Long::toInt))
            },
            RETRIES,
        )
    }

    /** Remembered for the life of the session so a server that refuses READDIRPLUS
     *  costs one refused call, not one per directory. */
    @Volatile private var readdirPlusRefused = false

    /** Zero until the write ceiling is resolved. */
    @Volatile private var writeCeiling = 0

    /** Export-absolute path to filehandle, so an operation on a directory this session
     *  already walked costs no LOOKUP. */
    private val handles = HandleCache()

    override val implementsWrites = true

    /**
     * Every wire call goes through here, the [nfs] constructor's portmap and MOUNT round
     * trips included, so no frontend classifies a failure by matching its message. [what]
     * is a lambda so a successful chunk builds no message for nobody to read.
     */
    private inline fun <T> failing(crossinline what: () -> String, op: () -> T): T =
        try {
            op()
        } catch (e: NfsFailure) {
            throw e
        } catch (e: FileNotFoundException) {
            // NFS3ERR_NOENT arrives as this rather than as an NfsException.
            throw NfsFailure.NotFound(what()).apply { initCause(e) }
        } catch (e: IOException) {
            throw failureFor(e, what())
        }

    /** The server saying the handle a call carried is not usable any more. */
    private fun handleRejected(status: Int): Boolean =
        status == NfsStatus.NFS3ERR_STALE.value || status == NfsStatus.NFS3ERR_BADHANDLE.value

    /** A naked call reports a rejected handle as a status rather than as an exception. */
    private fun forgetRejected(state: Int, path: String) {
        if (handleRejected(state)) handles.forgetTree(path)
    }

    /**
     * Runs [op] against [path]'s handle, dropping the cached entry the server rejected.
     * Never retries the operation: this session cannot know whether a rejected mutation
     * ran, and unfs3 answers NFS3ERR_STALE for an RMDIR on a file too.
     */
    private inline fun <T> withHandle(path: String, op: (ByteArray) -> T): T =
        try {
            op(handleOf(path))
        } catch (e: NfsException) {
            if (handleRejected(e.status.value)) handles.forgetTree(path)
            throw e
        }

    /**
     * Walks an export-absolute path and answers the last LOOKUP reply, which carries both
     * the handle and the object's attributes; null for the export root, whose handle the
     * MOUNT already gave us. Prefixes come from [handles] and every component's handle is
     * remembered on the way, but the leaf is looked up however well known it is: callers
     * come here for attributes off the wire.
     *
     * A rejected prefix earns one re-walk, as in [handleOf] and for the same reason:
     * resolution is pure LOOKUP. Every other status passes through, so [statPath] still
     * reads NFS3ERR_NOTDIR and a missing name as null.
     *
     * The library's Nfs3File walks the same chain, but it pays a GETATTR per component
     * on top and it catches every IOException on the way and leaves the handle null, so
     * a refused or unreachable parent would be indistinguishable from a missing one.
     *
     * A component that is itself a symlink is not followed: no listing offers one, and
     * following one could leave the export.
     */
    private fun walk(path: String): NfsLookupResponse? =
        try {
            walkOnce(path)
        } catch (e: NfsException) {
            if (!handleRejected(e.status.value)) throw e
            walkOnce(path)
        }

    private fun walkOnce(path: String): NfsLookupResponse? {
        var fh = nfs.rootFileHandle
        var dirPath: String? = null
        var leafFrom = -1
        var leafEnd = -1
        eachComponent(path) { from, end ->
            if (leafFrom >= 0) {
                val prefix = path.substring(0, leafEnd)
                fh = handles[prefix]
                    ?: lookedUp(fh, path.substring(leafFrom, leafEnd), dirPath).fileHandle
                        .also { handles.remember(prefix, it) }
                dirPath = prefix
            }
            leafFrom = from
            leafEnd = end
        }
        if (leafFrom < 0) return null
        val leaf = lookedUp(fh, path.substring(leafFrom, leafEnd), dirPath)
        handles.remember(path.substring(0, leafEnd), leaf.fileHandle)
        return leaf
    }

    /** Each component of an export-absolute path as its `[from, end)` bounds, so a caller
     *  whose prefix hits [handles] never builds the component string it did not need.
     *  `path.substring(0, end)` is the prefix key [handles] holds, and for the leaf it is
     *  [path] itself, so the same path hits its own entry. */
    private inline fun eachComponent(path: String, body: (from: Int, end: Int) -> Unit) {
        var from = 0
        while (from < path.length) {
            if (path[from] == '/') {
                from++
                continue
            }
            val end = path.indexOf('/', from).let { if (it < 0) path.length else it }
            body(from, end)
            from = end + 1
        }
    }

    /** What [dirPath]'s own resolution holds in [handles]: one entry per component, the
     *  export root's handle coming from the MOUNT rather than from the cache. */
    private fun componentsIn(dirPath: String): Int {
        var components = 0
        eachComponent(dirPath) { _, _ -> components++ }
        return components
    }

    /**
     * The handle for an export-absolute path, taken from [handles] for as much of the
     * path as is already known. Resolution is pure LOOKUP, so the one retry a rejected
     * cached prefix earns is safe even for a caller about to mutate: nothing destructive
     * has gone out yet. A second rejection surfaces, with the bad entry already dropped.
     */
    private fun handleOf(path: String): ByteArray {
        handles[path]?.let { return it }
        return try {
            resolved(path)
        } catch (e: NfsException) {
            if (!handleRejected(e.status.value)) throw e
            resolved(path)
        }
    }

    private fun resolved(path: String): ByteArray {
        var fh = nfs.rootFileHandle
        var dirPath: String? = null
        eachComponent(path) { from, end ->
            val prefix = path.substring(0, end)
            fh = handles[prefix]
                ?: lookedUp(fh, path.substring(from, end), dirPath).fileHandle
                    .also { handles.remember(prefix, it) }
            dirPath = prefix
        }
        return fh
    }

    /** One LOOKUP, dropping the cached handle it went out on when the server rejects it;
     *  [dirPath] is null for the export root, whose handle this session did not cache. */
    private fun lookedUp(dirFh: ByteArray, component: String, dirPath: String?): NfsLookupResponse =
        try {
            nfs.wrapped_getLookup(nfs.makeLookupRequest(dirFh, component))
        } catch (e: NfsException) {
            if (dirPath != null) forgetRejected(e.status.value, dirPath)
            throw e
        }

    /** One LOOKUP, or null when the name is not there; every other reason stays an
     *  exception. The reply carries the object's handle and its attributes both. */
    private fun lookup(dirFh: ByteArray, name: String): NfsLookupResponse? =
        try {
            nfs.wrapped_getLookup(nfs.makeLookupRequest(dirFh, name))
        } catch (e: FileNotFoundException) {
            null
        }

    /** GETATTR on a handle already in hand; its attributes are mandatory in the reply
     *  rather than post-operation, so this always answers. */
    private fun attrsOf(fh: ByteArray): NfsGetAttributes =
        nfs.wrapped_getAttr(nfs.makeGetAttrRequest(fh)).attributes

    /** Attributes of one entry of an already-resolved directory, or null when it is
     *  not there. The LOOKUP carries them, so it costs one round trip. */
    private fun statIn(dirFh: ByteArray, name: String): NfsGetAttributes? {
        val found = lookup(dirFh, name) ?: return null
        return found.attributes ?: attrsOf(found.fileHandle)
    }

    /**
     * Attributes for one export-absolute path, or null when nothing of that name is
     * there. A component that is not a directory says the same thing as one that is
     * missing, and [NfsV4Access] reads it that way too, so the seam's promise that
     * [stat] answers null for what does not exist holds on both backends.
     */
    private fun statPath(path: String): NfsGetAttributes? {
        val found = try {
            walk(path)
        } catch (e: FileNotFoundException) {
            return null
        } catch (e: NfsException) {
            if (e.status.value != NfsStatus.NFS3ERR_NOTDIR.value) throw e
            return null
        } ?: return attrsOf(nfs.rootFileHandle)
        return found.attributes ?: attrsOf(found.fileHandle)
    }

    override fun probeRoot(): NodeAttrs? = failing({ "/" }) {
        val attrs = try {
            attrsOf(nfs.rootFileHandle)
        } catch (e: FileNotFoundException) {
            return@failing null
        }
        attrs.takeIf { it.type == NfsType.NFS_DIR }?.toNode()
    }

    override fun stat(docId: String): NodeAttrs? = failing({ docId }) {
        val path = PathCodec.pathFor(docId) ?: return@failing null
        statPath(path)?.toNode()
    }

    override fun list(docId: String): List<ChildEntry> = failing({ docId }) {
        val dirPath = requireNotNull(PathCodec.pathFor(docId))
        childrenOf(dirPath).directoriesFirst()
    }

    /** A listing changes nothing, so a cached handle the server rejects is worth one
     *  re-walk; the rejection of a handle walked for this call is the answer itself. */
    private fun childrenOf(dirPath: String): List<ChildEntry> {
        val cached = handles[dirPath]
        if (cached != null) {
            try {
                return children(cached, dirPath)
            } catch (e: NfsException) {
                if (!handleRejected(e.status.value)) throw e
                handles.forgetTree(dirPath)
            }
        }
        return children(handleOf(dirPath), dirPath)
    }

    private fun children(dirFh: ByteArray, dirPath: String): List<ChildEntry> =
        if (readdirPlusRefused) plainChildren(dirFh, dirPath) else plusChildren(dirFh, dirPath)

    /**
     * One READDIRPLUS cookie loop carries every child's attributes, replacing the
     * READDIR + per-child LOOKUP storm; entries whose attributes the server withheld
     * fall back to a single lookup instead of being dropped.
     *
     * READDIRPLUS is optional in RFC 1813 and unfs3, among others, refuses it outright,
     * so a refusal drops the session to [plainChildren] instead of reaching the user as
     * an empty directory.
     *
     * The handle harvest spends [HandleCache.harvestBudget] in scan order and then stops:
     * an unbounded one would insert past the cap and evict its own head along with the
     * prefix chain the walk that reached this directory paid for.
     */
    private fun plusChildren(dirFh: ByteArray, dirPath: String): List<ChildEntry> {
        val children = ArrayList<ChildEntry>()
        val stat: (String) -> NfsGetAttributes? = { statIn(dirFh, it) }
        val budget = HandleCache.harvestBudget(componentsIn(dirPath))
        var cookie = 0L
        var cookieverf = 0L
        var harvested = 0
        do {
            val page = try {
                nfs.wrapped_getReaddirplus(
                    nfs.makeReaddirplusRequest(
                        dirFh,
                        cookie,
                        cookieverf,
                        READDIRPLUS_DIR_COUNT,
                        READDIRPLUS_MAX_COUNT,
                    ),
                )
            } catch (e: NfsException) {
                if (!refusesReaddirPlus(e)) throw e
                readdirPlusRefused = true
                return plainChildren(dirFh, dirPath)
            }
            cookie = page.cookie
            cookieverf = page.cookieverf
            for (entry in page.entries) {
                val child = resolveEntry(dirPath, entry, stat) ?: continue
                children += child
                if (harvested < budget && harvestHandle(child.path, entry)) harvested++
            }
            // A page with no entries and no eof means the server could not fit even
            // one: continuing would spin on the same cookie forever.
        } while (!page.isEof && page.entries.isNotEmpty())
        return children
    }

    /**
     * Takes one listed child's post_op_fh3 (RFC 1813 3.3.17), sparing the LOOKUP this
     * name's next use would pay, and answers whether it spent a harvest budget slot.
     *
     * A zero-length handle is a server's framing of an absent one, and caching it would
     * ship an empty filehandle for the whole TTL.
     */
    private fun harvestHandle(path: String, entry: NfsDirectoryPlusEntry): Boolean {
        val fh = entry.fileHandle?.takeIf { it.isNotEmpty() } ?: return false
        handles.remember(path, fh)
        return true
    }

    /**
     * A server declining READDIRPLUS, as a status or below it. RPC answers an optional
     * procedure it does not serve with accept_stat PROC_UNAVAIL (RFC 1831), which the
     * library's retry wrapper rewrites into NFS3ERR_IO carrying the RpcException, so that
     * refusal is reachable only through the cause chain.
     */
    private fun refusesReaddirPlus(e: NfsException): Boolean =
        e.status.value == NfsStatus.NFS3ERR_NOTSUPP.value ||
            rpcErrorIn(e) { unavailableProcedure(it.status) } != null

    /** Plain READDIR carries names only, so every kept entry costs its own LOOKUP. */
    private fun plainChildren(dirFh: ByteArray, dirPath: String): List<ChildEntry> {
        val children = ArrayList<ChildEntry>()
        val stat: (String) -> NfsGetAttributes? = { statIn(dirFh, it) }
        var cookie = 0L
        var cookieverf = 0L
        do {
            val page = nfs.wrapped_getReaddir(
                nfs.makeReaddirRequest(dirFh, cookie, cookieverf, READDIR_COUNT),
            )
            cookie = page.cookie
            cookieverf = page.cookieverf
            for (entry in page.entries) {
                resolveNamed(dirPath, entry.fileName, stat)?.let { children += it }
            }
        } while (!page.isEof && page.entries.isNotEmpty())
        return children
    }

    override fun openFile(docId: String): OpenedFile = failing({ docId }) {
        val found = walk(requireNotNull(PathCodec.pathFor(docId)))
        val fh = found?.fileHandle ?: nfs.rootFileHandle
        val attrs = found?.attributes ?: attrsOf(fh)
        if (attrs.type != NfsType.NFS_REG) throw NfsFailure.Server("not a regular file: $docId")
        OpenedFile(Handle(fh), attrs.toNode())
    }

    /**
     * Creates the file open, with no LOOKUP behind it: CREATE answers with the new
     * filehandle and its attributes. GUARDED so a name already taken comes back as
     * NFS3ERR_EXIST instead of truncating what is there.
     */
    override fun createFile(parentDocId: String, name: String): CreatedFile {
        val parent = requireNotNull(PathCodec.pathFor(parentDocId))
        val child = component(name)
        val path = requireNotNull(PathCodec.childPath(parent, child))
        return failing({ path }) {
            withHandle(parent) { parentFh ->
                val res = nfs.sendCreate(
                    nfs.makeCreateRequest(
                        NfsCreateMode.GUARDED,
                        parentFh,
                        child,
                        NfsSetAttributes().apply { setMode(CREATE_MODE) },
                        null,
                    ),
                )
                forgetRejected(res.state, parent)
                checkStatus(res.state, path)
                val fh = madeHandle(res, parentFh, child, path)
                handles.remember(path, fh)
                CreatedFile(Handle(fh), (res.attributes ?: attrsOf(fh)).toNode())
            }
        }
    }

    /** Mode 0755, matching what the NFSv4.1 backend's CREATE sends. */
    override fun makeDirectory(parentDocId: String, name: String): NodeAttrs {
        val parent = requireNotNull(PathCodec.pathFor(parentDocId))
        val child = component(name)
        val path = requireNotNull(PathCodec.childPath(parent, child))
        return failing({ path }) {
            withHandle(parent) { parentFh ->
                val res = nfs.sendMkdir(
                    nfs.makeMkdirRequest(
                        parentFh,
                        child,
                        NfsSetAttributes().apply { setMode(DIRECTORY_MODE) },
                    ),
                )
                forgetRejected(res.state, parent)
                checkStatus(res.state, path)
                val fh = madeHandle(res, parentFh, child, path)
                handles.remember(path, fh)
                (res.attributes ?: attrsOf(fh)).toNode()
            }
        }
    }

    /** Both halves of what a CREATE or MKDIR reply carries are optional in RFC 1813,
     *  so a server that withholds the handle costs one LOOKUP. */
    private fun madeHandle(
        res: NfsResponseBase,
        parentFh: ByteArray,
        child: String,
        path: String,
    ): ByteArray =
        res.fileHandle
            ?: lookup(parentFh, child)?.fileHandle
            ?: throw NfsFailure.Server("created but no longer there: $path")

    /**
     * NFSv3 splits what the seam presents as one removal, so the entry's type picks the
     * call: [isDirectory] is what the caller already knew, and without it the type costs a
     * LOOKUP. The type can change under that choice, and the retry is decided by re-reading
     * it rather than by the status, because the mismatch statuses were never mandated: RFC
     * 1813 section 3.3.12's ERRORS list contains no NFS3ERR_ISDIR and its IMPLEMENTATION
     * section permits REMOVE on a directory, so unfs3 answering RMDIR on a file with
     * NFS3ERR_STALE and deleting a directory through REMOVE are both conformant.
     *
     * A refused call performed nothing, which is what makes sending the other one safe. A
     * policy refusal ends it there: a server that answered ACCES, PERM, ROFS or NOTEMPTY
     * would refuse the other call too, so sending it could only destroy an object that took
     * this name in the meantime and that the user never asked about. Otherwise the second
     * call goes out once the fresh type contradicts the one that was tried, so at no point
     * are two destructive calls in flight.
     *
     * A rejection of the first call is not the parent's to answer for: unfs3's
     * NFS3ERR_STALE for a type mismatch is about the target, and the corrective statIn
     * goes out on that same parent handle, so [withHandle] drops it when it is genuinely
     * rejected. The second call follows a LOOKUP that answered, so its own rejection is
     * the parent's.
     */
    override fun remove(parentDocId: String, name: String, isDirectory: Boolean?) {
        val parent = requireNotNull(PathCodec.pathFor(parentDocId))
        val child = component(name)
        val path = requireNotNull(PathCodec.childPath(parent, child))
        failing({ path }) {
            withHandle(parent) { parentFh ->
                val directory = isDirectory
                    ?: ((statIn(parentFh, child)?.type ?: throw NfsFailure.NotFound(path))
                        == NfsType.NFS_DIR)
                var state = unlink(parentFh, child, directory)
                if (state != NfsStatus.NFS3_OK.value) {
                    if (state in POLICY_REFUSALS) throw failureFor(state, path)
                    val fresh = statIn(parentFh, child)?.type ?: throw NfsFailure.NotFound(path)
                    if ((fresh == NfsType.NFS_DIR) == directory) throw failureFor(state, path)
                    state = unlink(parentFh, child, !directory)
                    forgetRejected(state, parent)
                    checkStatus(state, path)
                }
                // Whatever succeeded, not whichever procedure did: a REMOVE the caller
                // believed aimed at a file can carry away a directory, children and all.
                handles.forgetTree(path)
            }
        }
    }

    private fun unlink(parentFh: ByteArray, child: String, directory: Boolean): Int =
        if (directory) nfs.sendRmdir(nfs.makeRmdirRequest(parentFh, child)).state
        else nfs.sendRemove(nfs.makeRemoveRequest(parentFh, child)).state

    /**
     * SETATTR answers with the object's post-operation attributes, so the read back
     * rides the same round trip. No guard time: an unguarded SETATTR of a fixed size or
     * mtime is idempotent, which is what lets it keep the library's retrying path.
     */
    override fun setAttributes(docId: String, size: Long?, modifiedMillis: Long?): NodeAttrs {
        val path = requireNotNull(PathCodec.pathFor(docId))
        return failing({ docId }) {
            if (size == null && modifiedMillis == null) {
                val found = walk(path)
                val fh = found?.fileHandle ?: nfs.rootFileHandle
                return@failing (found?.attributes ?: attrsOf(fh)).toNode()
            }
            val attrs = NfsSetAttributes().apply {
                size?.let { setSize(it) }
                modifiedMillis?.let { setMtime(NfsTime(it)) }
            }
            withHandle(path) { fh ->
                val res = nfs.wrapped_setAttr(nfs.makeSetAttrRequest(fh, attrs, null))
                (res.objectWccData.attributes ?: attrsOf(fh)).toNode()
            }
        }
    }

    /** A naked call skips the library's own status check, so the reply is checked here. */
    private fun checkStatus(state: Int, what: String) {
        if (state != NfsStatus.NFS3_OK.value) throw failureFor(state, what)
    }

    /** One READ or WRITE per call against a resolved filehandle. */
    private inner class Handle(private val fh: ByteArray) : NfsFile {

        /** Short by design for a large [len]: see [UNFRAGMENTED_REPLY_MAX]. */
        override fun readAt(offset: Long, dst: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            return failing({ "read at $offset" }) {
                val request = nfs.makeReadRequest(fh, offset, minOf(len, UNFRAGMENTED_REPLY_MAX))
                nfs.wrapped_getRead(request, dst, off).bytesRead.coerceAtLeast(0)
            }
        }

        /**
         * The library sends whatever it is given and a request over the server's wtmax
         * comes back as a short write, so the chunking is this side's job.
         *
         * FILE_SYNC: the server has committed before the reply, which is what spares
         * this backend a COMMIT and the write-verifier restart dance. A WRITE at a fixed
         * offset is idempotent, so it keeps the library's retrying path.
         */
        override fun writeAt(offset: Long, src: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            return failing({ "write at $offset" }) {
                val want = minOf(len, writeChunk())
                val payload = listOf(ByteBuffer.wrap(src, off, want))
                val request = nfs.makeWriteRequest(fh, offset, payload, NfsWriteRequest.FILE_SYNC)
                nfs.wrapped_sendWrite(request).count.coerceIn(0, want)
            }
        }

        override fun close() = Unit
    }

    /**
     * FSINFO wtmax is a ceiling on the payload, not on the request, so [grantedChunk]'s
     * header subtraction would not apply here: a WRITE of exactly wtmax bytes is legal.
     *
     * Two threads arriving on a cold ceiling each send one FSINFO and store the same
     * value, so the round trip is spared per chunk rather than strictly once per session.
     *
     * A failed FSINFO caches the fallback too. The library neither caches nor gives up
     * quickly on it, so re-asking a server that just refused would cost a retry and its
     * backoff sleep before every chunk of every write.
     */
    private fun writeChunk(): Int {
        val cached = writeCeiling
        if (cached != 0) return cached
        val wtmax = runCatching { nfs.nfsFsInfo.wtmax }.getOrNull()?.takeIf { it > 0 }
        val chunk = wtmax?.coerceAtMost(NFS_WRITE_CHUNK.toLong())?.toInt() ?: WRITE_CEILING_FALLBACK
        writeCeiling = chunk
        return chunk
    }

    override fun close() {
        // An NfsFile outliving the session keeps this instance, and this map, reachable.
        handles.clear()
        // NetMgr keys its maps with createUnresolved addresses; a resolved one would
        // miss the cache. Best-effort: the portmapper (111) connection stays cached.
        runCatching {
            NetMgr.getInstance().dropConnection(InetSocketAddress.createUnresolved(spec.host, spec.port))
        }
    }

    companion object {
        /**
         * Ceiling on one v3 reply, payload and XDR overhead together, keeping it inside a
         * single RPC record fragment. Raising it corrupts data:
         * `RecordMarkingUtil.removeRecordMarking` advances its cursor by the fragment size
         * and not by header-plus-size, so every fragment after the first is read 4 bytes
         * early. libtirpc and its ntirpc fork (unfs3, nfs-ganesha) close a fragment every
         * 65532 bytes; a READ reply adds 128 bytes of RPC and NFS header plus up to 3 of
         * padding, and 61440 is the largest page multiple that fits in what is left.
         */
        internal const val UNFRAGMENTED_REPLY_MAX = 61440

        /** Directory information only, so it stays a sub-budget of the whole page. */
        private const val READDIRPLUS_DIR_COUNT = 32 * 1024
        private const val READDIRPLUS_MAX_COUNT = UNFRAGMENTED_REPLY_MAX

        /** A truncated page costs a cookie round trip, which the loop already does. */
        private const val READDIR_COUNT = UNFRAGMENTED_REPLY_MAX

        /** 0644 and 0755, spelled in binary because Kotlin has no octal literal. */
        private const val CREATE_MODE = 0b110_100_100L
        private const val DIRECTORY_MODE = 0b111_101_101L

        /** RFC 1813 makes FSINFO mandatory, so this is reached only when it fails;
         *  32 KiB is the classic NFSv3 transfer size no server refuses. */
        private const val WRITE_CEILING_FALLBACK = 32 * 1024

        /** Statuses refusing a removal on policy rather than on the entry's type, so the
         *  call for the other type would be refused too. */
        private val POLICY_REFUSALS = intArrayOf(
            NfsStatus.NFS3ERR_ACCES.value,
            NfsStatus.NFS3ERR_PERM.value,
            NfsStatus.NFS3ERR_ROFS.value,
            NfsStatus.NFS3ERR_NOTEMPTY.value,
        )

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
         *  withheld the attributes. At most one stat per entry. [stat] takes the bare
         *  name, as [resolveNamed]'s does, so tests can run this without an NFS session. */
        internal fun resolveEntry(
            parentPath: String,
            entry: NfsDirectoryPlusEntry,
            stat: (String) -> NfsGetAttributes?,
        ): ChildEntry? {
            val name = entry.fileName
            val attrs = entry.attributes
                ?: stat(PathCodec.componentOf(name) ?: return null)
                ?: return null
            val path = listableChild(parentPath, name, attrs.type) ?: return null
            return ChildEntry(path, attrs.toNode())
        }

        /** The same filter for one plain READDIR entry, which never carries attributes.
         *  [stat] takes the bare name so the caller can look it up against the directory
         *  it already resolved. */
        internal fun resolveNamed(
            parentPath: String,
            name: String?,
            stat: (String) -> NfsGetAttributes?,
        ): ChildEntry? {
            val child = PathCodec.componentOf(name) ?: return null
            val attrs = stat(child) ?: return null
            val path = listableChild(parentPath, child, attrs.type) ?: return null
            return ChildEntry(path, attrs.toNode())
        }

        /** The only guard between a foreign display name and the wire. */
        private fun component(name: String): String =
            requireNotNull(PathCodec.componentOf(name)) { "unusable name" }

        private const val RETRIES = 2
    }
}

/**
 * Export-absolute path to filehandle for one session, access-ordered and capped at
 * [MAX_ENTRIES]. Two frontends share one session, so every read and write holds this
 * map's own monitor and nothing iterates it outside one.
 *
 * An entry older than [TTL_NANOS] answers absent. A handle survives a foreign rename —
 * it stays valid and merely stops denoting the path it is keyed under — so no server
 * error can expose a binding gone wrong and only time can bound it. The bound is the entry
 * TTL the FUSE layer advertises ([TTL_SECONDS]) and the order a kernel NFS client bounds
 * the same hazard with (acdirmin), so a binding cannot outlive the coherence window the
 * layers above assume. The residual is that window: inside it a mutation can still land
 * on an object renamed away under its path, and a copy's create, write and setattr still
 * fall inside one, which is what the cache is for.
 *
 * [clock] must be monotonic: a wall clock stepped backwards would hold an entry for as
 * long as it stepped.
 */
internal class HandleCache(private val clock: NanoClock = NanoClock { System.nanoTime() }) {

    private class Entry(val fh: ByteArray, val stampNanos: Long)

    private val entries = object : LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > MAX_ENTRIES
    }

    operator fun get(path: String): ByteArray? {
        synchronized(entries) {
            val hit = entries[path] ?: return null
            if (clock.nanos() - hit.stampNanos <= TTL_NANOS) return hit.fh
            entries.remove(path)
        }
        return null
    }

    fun remember(path: String, fh: ByteArray) {
        synchronized(entries) { entries[path] = Entry(fh, clock.nanos()) }
    }

    /** [path] and everything under it: a name that is gone takes its descendants with it,
     *  and a handle the server rejected vouches for nothing resolved through it. */
    fun forgetTree(path: String) {
        val prefix = "$path/"
        synchronized(entries) { entries.keys.removeAll { it == path || it.startsWith(prefix) } }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    internal companion object {
        /** The cache holds two kinds of entry: the prefixes a path walk resolves, and the
         *  children a READDIRPLUS harvests, files included. A few hundred is enough for
         *  the directories one copy walks and for one listed directory's children, and it
         *  is a memory bound and nothing more: an over-cap listing still evicts, by LRU,
         *  whatever earlier work left behind. What [harvestBudget] reserves is room for
         *  one path's worth of prefixes, which is that listing's own chain only where the
         *  listing resolved it: one answered from a cached directory handle reads no
         *  ancestor, so they keep their old LRU rank and the reserved room can go to them
         *  instead, at one LOOKUP per component on some later cold walk. */
        internal const val MAX_ENTRIES = 256

        /** Children one listing may remember: the cap less the [prefixes] entries its own
         *  resolution holds, because an insert past the cap drops the eldest entry and
         *  after a walk that is the shallowest prefix. */
        internal fun harvestBudget(prefixes: Int): Int = (MAX_ENTRIES - prefixes).coerceAtLeast(0)

        /** Derived rather than restated: the two drifting apart is what would make a
         *  binding outlive the window the layers above assume. */
        internal val TTL_NANOS: Long = TimeUnit.SECONDS.toNanos(TTL_SECONDS)
    }
}

/** Monotonic nanoseconds. A `fun interface` and not `() -> Long`, whose `Function0<Long>`
 *  boxes a Long on every cache read and every write. */
internal fun interface NanoClock {
    fun nanos(): Long
}

/**
 * An RFC 1813 status as the seam's refusal, applying the table [NfsV4Access] applies to
 * NFSv4 statuses so both backends answer a user the same way, with two rows of its own.
 * NFS3ERR_NOTSUPP: v3 makes whole procedures optional, and a server refusing one is
 * refusing a capability rather than failing a call. NFS3ERR_JUKEBOX is a timeout because
 * this backend deliberately does not wait and retry it the way [NfsV4Access] retries
 * NFS4ERR_DELAY: that retry rides a recovery budget v3 has no equivalent of, and the naked
 * mutations may not be re-sent at all, so transient with the outcome unknown is the honest
 * answer here.
 */
internal fun failureFor(status: Int, what: String): NfsFailure = when (status) {
    NfsStatus.NFS3ERR_PERM.value, NfsStatus.NFS3ERR_ACCES.value, NfsStatus.NFS3ERR_ROFS.value ->
        NfsFailure.PermissionDenied(what)
    NfsStatus.NFS3ERR_NOENT.value, NfsStatus.NFS3ERR_NOTDIR.value -> NfsFailure.NotFound(what)
    NfsStatus.NFS3ERR_EXIST.value -> NfsFailure.AlreadyExists(what)
    NfsStatus.NFS3ERR_NOTEMPTY.value -> NfsFailure.DirectoryNotEmpty(what)
    NfsStatus.NFS3ERR_NOSPC.value, NfsStatus.NFS3ERR_DQUOT.value -> NfsFailure.OutOfSpace(what)
    NfsStatus.NFS3ERR_NOTSUPP.value -> NfsFailure.Unsupported(what)
    NfsStatus.NFS3ERR_JUKEBOX.value -> NfsFailure.Timeout(what)
    else -> NfsFailure.Server("${statusName(status)}: $what")
}

private fun failureFor(e: IOException, what: String): NfsFailure {
    val lost = networkErrorIn(e)
    val failure = when {
        lost != null -> if (lost.timedOut()) NfsFailure.Timeout(what) else NfsFailure.Unreachable(what)
        e is NfsException -> failureFor(e.status.value, what)
        else -> NfsFailure.Server("${e.message ?: e.javaClass.simpleName}: $what")
    }
    return failure.apply { initCause(e) }
}

/** The first RpcException in [e]'s cause chain that [matches]. */
private inline fun rpcErrorIn(e: Throwable, matches: (RpcException) -> Boolean): RpcException? {
    var cause: Throwable? = e
    var depth = 0
    while (cause != null && depth < MAX_CAUSE_DEPTH) {
        if (cause is RpcException && matches(cause)) return cause
        val next = cause.cause
        cause = if (next === cause) null else next
        depth++
    }
    return null
}

/**
 * The RpcException under [e] when the library reported a transport failure rather than a
 * server answer. The whole chain is walked because a wrapped call that exhausted its
 * retries buries that exception under an NfsException wearing NFS3ERR_IO — a lost
 * connection dressed as a status.
 *
 * [TransportLoss] cannot answer this: it looks for the socket exception in the chain, and
 * this library never chains one, it formats the text into an RpcException of its own.
 */
private fun networkErrorIn(e: Throwable): RpcException? =
    rpcErrorIn(e) { it.status == RpcStatus.NETWORK_ERROR }

/** RFC 1831 accept_stat PROC_UNAVAIL, spelled out because the library keeps its own
 *  AcceptStatus constants package-private. */
private fun unavailableProcedure(status: RpcStatus): Boolean =
    status is AcceptStatus && status.value == PROC_UNAVAIL

private const val PROC_UNAVAIL = 3

/**
 * A deadline and a dead socket are both NETWORK_ERROR with no cause attached: the
 * library puts the distinction in the message and nowhere else. Read here, off the
 * pinned dependency's own format string, so that the frontends never have to.
 */
private fun RpcException.timedOut(): Boolean = message?.contains(RPC_TIMEOUT) == true

private const val RPC_TIMEOUT = "rpc request timeout"

/** Guards against a cause chain that loops back on itself. */
private const val MAX_CAUSE_DEPTH = 16

/** RFC 1813 spelling for the statuses no frontend acts on, so the message a human reads
 *  names the error instead of numbering it. Keyed off the library's own constants. */
private val STATUS_NAMES: Map<Int, String> = mapOf(
    NfsStatus.NFS3ERR_IO.value to "NFS3ERR_IO",
    NfsStatus.NFS3ERR_NXIO.value to "NFS3ERR_NXIO",
    NfsStatus.NFS3ERR_XDEV.value to "NFS3ERR_XDEV",
    NfsStatus.NFS3ERR_NODEV.value to "NFS3ERR_NODEV",
    NfsStatus.NFS3ERR_ISDIR.value to "NFS3ERR_ISDIR",
    NfsStatus.NFS3ERR_INVAL.value to "NFS3ERR_INVAL",
    NfsStatus.NFS3ERR_FBIG.value to "NFS3ERR_FBIG",
    NfsStatus.NFS3ERR_MLINK.value to "NFS3ERR_MLINK",
    NfsStatus.NFS3ERR_NAMETOOLONG.value to "NFS3ERR_NAMETOOLONG",
    NfsStatus.NFS3ERR_STALE.value to "NFS3ERR_STALE",
    NfsStatus.NFS3ERR_REMOTE.value to "NFS3ERR_REMOTE",
    NfsStatus.NFS3ERR_BADHANDLE.value to "NFS3ERR_BADHANDLE",
    NfsStatus.NFS3ERR_NOT_SYNC.value to "NFS3ERR_NOT_SYNC",
    NfsStatus.NFS3ERR_BAD_COOKIE.value to "NFS3ERR_BAD_COOKIE",
    NfsStatus.NFS3ERR_TOOSMALL.value to "NFS3ERR_TOOSMALL",
    NfsStatus.NFS3ERR_SERVERFAULT.value to "NFS3ERR_SERVERFAULT",
    NfsStatus.NFS3ERR_BADTYPE.value to "NFS3ERR_BADTYPE",
    NfsStatus.NFS3ERR_JUKEBOX.value to "NFS3ERR_JUKEBOX",
)

private fun statusName(status: Int): String = STATUS_NAMES[status] ?: "nfs3 status $status"
