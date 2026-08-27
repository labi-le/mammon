package app.mammon

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.dcache.nfs.ChimeraNFSException
import org.dcache.nfs.nfsstat
import org.dcache.nfs.v4.ClientSession
import org.dcache.nfs.v4.CompoundBuilder
import org.dcache.nfs.v4.Stateids
import org.dcache.nfs.v4.xdr.CLOSE4args
import org.dcache.nfs.v4.xdr.COMPOUND4args
import org.dcache.nfs.v4.xdr.COMPOUND4res
import org.dcache.nfs.v4.xdr.CREATE_SESSION4args
import org.dcache.nfs.v4.xdr.OPEN4args
import org.dcache.nfs.v4.xdr.SEQUENCE4args
import org.dcache.nfs.v4.xdr.SETATTR4args
import org.dcache.nfs.v4.xdr.attrlist4
import org.dcache.nfs.v4.xdr.bitmap4
import org.dcache.nfs.v4.xdr.callback_sec_parms4
import org.dcache.nfs.v4.xdr.channel_attrs4
import org.dcache.nfs.v4.xdr.clientid4
import org.dcache.nfs.v4.xdr.component4
import org.dcache.nfs.v4.xdr.count4
import org.dcache.nfs.v4.xdr.createhow4
import org.dcache.nfs.v4.xdr.createmode4
import org.dcache.nfs.v4.xdr.fattr4
import org.dcache.nfs.v4.xdr.nfs4_prot
import org.dcache.nfs.v4.xdr.nfs_argop4
import org.dcache.nfs.v4.xdr.nfs_fh4
import org.dcache.nfs.v4.xdr.nfs_ftype4
import org.dcache.nfs.v4.xdr.nfs_opnum4
import org.dcache.nfs.v4.xdr.nfs_resop4
import org.dcache.nfs.v4.xdr.nfstime4
import org.dcache.nfs.v4.xdr.open_claim4
import org.dcache.nfs.v4.xdr.open_claim_type4
import org.dcache.nfs.v4.xdr.open_owner4
import org.dcache.nfs.v4.xdr.openflag4
import org.dcache.nfs.v4.xdr.opentype4
import org.dcache.nfs.v4.xdr.seqid4
import org.dcache.nfs.v4.xdr.settime4
import org.dcache.nfs.v4.xdr.slotid4
import org.dcache.nfs.v4.xdr.state_owner4
import org.dcache.nfs.v4.xdr.state_protect_how4
import org.dcache.nfs.v4.xdr.stateid4
import org.dcache.nfs.v4.xdr.time_how4
import org.dcache.nfs.v4.xdr.uint32_t
import org.dcache.nfs.v4.xdr.utf8str_cs
import org.dcache.nfs.v4.xdr.verifier4
import org.dcache.oncrpc4j.rpc.IoStrategy
import org.dcache.oncrpc4j.rpc.OncRpcClient
import org.dcache.oncrpc4j.rpc.RpcAuth
import org.dcache.oncrpc4j.rpc.RpcAuthType
import org.dcache.oncrpc4j.rpc.RpcAuthVerifier
import org.dcache.oncrpc4j.rpc.RpcCall
import org.dcache.oncrpc4j.rpc.net.IpProtocolType
import org.dcache.oncrpc4j.xdr.Xdr
import org.dcache.oncrpc4j.xdr.XdrDecodingStream
import org.dcache.oncrpc4j.xdr.XdrEncodingStream
import javax.security.auth.Subject

/**
 * NFSv4.1 operations over one long-lived session.
 *
 * nfs4j is a server library; only its XDR types and [CompoundBuilder] are used here,
 * with oncrpc4j carrying the RPC. CREATE_SESSION is built by hand because the
 * builder's own channel attributes cap a reply at 8 KiB, which would cut every READ
 * to a sixty-fourth of [NFS_READ_CHUNK].
 *
 * READ, WRITE and SETATTR use the anonymous stateid, so nothing here needs a
 * lease-renewal timer; only file creation takes OPEN state, and it hands that state
 * straight back with a CLOSE in the same COMPOUND. A session the server has reaped is
 * re-established in place by [resilient] on the next call that trips over it.
 *
 * Every method blocks on network I/O: callers must stay off the main thread.
 */
class NfsV4Access(target: NfsTarget) : NfsSession {

    private val spec = target.spec

    private class Session(
        val clientId: clientid4,
        val slots: ClientSession,
        val maxOps: Int,
        val maxRead: Int,
        val maxWrite: Int,
    )

    /**
     * Stable for this instance so a re-established session reclaims its predecessor's
     * state, unique between instances so a probe from the activity cannot make the
     * server treat the provider's live session as a rebooted client and reap it.
     */
    private val ownerId = "$CLIENT_NAME/${UUID.randomUUID()}@${spec.host}:${spec.export}"

    private val openOwner = ownerId.toByteArray(StandardCharsets.UTF_8)

    private val rpc = OncRpcClient.newBuilder()
        .withProtocol(IpProtocolType.TCP)
        .withIoStrategy(IoStrategy.SAME_THREAD)
        .withSelectorThreadPoolSize(1)
        .withWorkerThreadPoolSize(1)
        .withTcpNoDelay(true)
        .withConnectTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build(InetSocketAddress(spec.host.removeSurrounding("[", "]"), spec.port))

    private val call: RpcCall
    private val lock = Any()

    @Volatile private var session: Session

    @Volatile private var rootFh: nfs_fh4? = null

    /**
     * Cleared for the rest of this instance's life the first time a server refuses a
     * CLOSE beside its OPEN, so the extra round trip is paid once rather than per create.
     */
    @Volatile private var closeBesideOpen = true

    override val supportsWrites = true

    init {
        try {
            call = RpcCall(
                nfs4_prot.NFS4_PROGRAM,
                RPC_VERSION,
                AuthSys(target.identity, CLIENT_NAME),
                rpc.connect(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            )
            session = establish()
            // Resolving the export here is what makes a wrong export or a v3-only
            // server fail during construction, where version selection can fall back.
            rootHandle()
        } catch (e: Throwable) {
            runCatching { rpc.close() }
            throw e
        }
    }

    override fun probeRoot(): NodeAttrs? = attrsAt("/")?.takeIf { it.isDirectory }?.toNode()

    override fun stat(docId: String): NodeAttrs? {
        val path = PathCodec.pathFor(docId) ?: return null
        return attrsAt(path)?.toNode()
    }

    override fun list(docId: String): List<ChildEntry> {
        val dirPath = requireNotNull(PathCodec.pathFor(docId))
        val children = ArrayList<ChildEntry>()
        var cookie = 0L
        var verifier = verifier4(ByteArray(nfs4_prot.NFS4_VERIFIER_SIZE))
        while (true) {
            val page = compoundAt(dirPath, "readdir") {
                withReaddir(cookie, verifier, READDIR_DIR_COUNT, READDIR_MAX_COUNT, *Fattr4Codec.REQUEST)
            }
            val reply = lastOf(page, nfs_opnum4.OP_READDIR).opreaddir.resok4
            verifier = reply.cookieverf
            var entry = reply.reply.entries
            var seen = false
            while (entry != null) {
                seen = true
                cookie = entry.cookie.value
                val attrs = Fattr4Codec.decode(entry.attrs)
                if (attrs != null && attrs.isListable) {
                    val name = String(entry.name.value, StandardCharsets.UTF_8)
                    PathCodec.childPath(dirPath, name)?.let { children += ChildEntry(it, attrs.toNode()) }
                }
                entry = entry.nextentry
            }
            // A page with no entries and no eof means the server could not fit even
            // one: continuing would spin on the same cookie forever.
            if (reply.reply.eof || !seen) break
        }
        return children.directoriesFirst()
    }

    override fun openFile(docId: String): NfsFile {
        val path = requireNotNull(PathCodec.pathFor(docId))
        val res = try {
            compoundAt(path, "open") {
                withGetattr(*Fattr4Codec.REQUEST)
                withGetfh()
            }
        } catch (e: ChimeraNFSException) {
            if (e.status in ABSENT) throw NfsFailure.NotFound(docId) else throw e
        }
        val attrs = Fattr4Codec.decode(lastOf(res, nfs_opnum4.OP_GETATTR).opgetattr.resok4.obj_attributes)
        if (attrs == null || !attrs.isRegular) throw NfsFailure.Server("not a regular file: $docId")
        return Handle(lastOf(res, nfs_opnum4.OP_GETFH).opgetfh.resok4.`object`)
    }

    override fun setAttributes(docId: String, size: Long?, modifiedMillis: Long?) {
        if (size == null && modifiedMillis == null) return
        val path = requireNotNull(PathCodec.pathFor(docId))
        // Both attributes ride one SETATTR, and SETATTR on the anonymous stateid needs
        // no OPEN: setting the same size or time twice is the same result, so this keeps
        // the full recovery set.
        failing(docId) {
            compoundAt(path, "setattr", arrayOf(setattr(Fattr4Codec.encodeSetattr(size, modifiedMillis))))
        }
    }

    override fun makeDirectory(parentDocId: String, name: String) {
        val parent = requireNotNull(PathCodec.pathFor(parentDocId))
        val child = component(name)
        failing("$parent/$child") {
            compoundAt(parent, "mkdir", nonIdempotent = true) { withMakedir(child) }
        }
    }

    override fun remove(parentDocId: String, name: String) {
        val parent = requireNotNull(PathCodec.pathFor(parentDocId))
        val child = component(name)
        failing("$parent/$child") {
            compoundAt(parent, "remove", nonIdempotent = true) { withRemove(child) }
        }
    }

    /**
     * OPEN(OPEN4_CREATE, GUARDED4) + GETFH + CLOSE in one COMPOUND, so a create costs one
     * round trip and leaves no open state behind. GUARDED4 rather than UNCHECKED4 because
     * a file manager must answer "it already exists", never truncate.
     *
     * A server that refuses the CLOSE beside the OPEN has still created the file — a
     * failing operation terminates the COMPOUND, so everything before it ran — so that
     * reply is read operation by operation and the CLOSE is retried on its own.
     */
    override fun createFile(parentDocId: String, name: String): NfsFile {
        val parent = requireNotNull(PathCodec.pathFor(parentDocId))
        val child = component(name)
        return failing("$parent/$child") {
            val fused = closeBesideOpen
            val openOp = openCreate(child)
            val tail =
                if (fused) arrayOf(openOp, getfh(), closeOp(Stateids.currentStateId()))
                else arrayOf(openOp, getfh())
            val res = compoundAt(
                parent,
                "create",
                tail,
                nonIdempotent = true,
                tolerate = { fused && openSucceeded(it) },
            )
            val stateid = lastOf(res, nfs_opnum4.OP_OPEN).opopen.resok4.stateid
            val handle = resultOf(res, nfs_opnum4.OP_GETFH)?.opgetfh?.takeIf { it.status == nfsstat.NFS_OK }
            if (handle == null) {
                // Unreachable in practice: GETFH cannot fail behind a successful OPEN.
                // The open state cannot be closed without a handle to PUTFH, so it is
                // left for the server's lease to reap.
                closeBesideOpen = false
                throw NfsFailure.Server("nfs4 create reply carries no filehandle")
            }
            val fh = handle.resok4.`object`
            if (!fused || res.status != nfsstat.NFS_OK) {
                closeBesideOpen = false
                resilient { inSession(compound("close") { withPutfh(fh); withClose(stateid, 0) }) }
            }
            Handle(fh)
        }
    }

    override fun close() {
        val s = session
        runCatching { raw(compound("destroy_session") { withDestroysession(s.slots.sessionId()) }) }
        runCatching { raw(compound("destroy_clientid") { withDestroyclientid(s.clientId) }) }
        runCatching { rpc.close() }
    }

    private fun attrsAt(path: String): Attrs4? {
        val res = try {
            compoundAt(path, "getattr") { withGetattr(*Fattr4Codec.REQUEST) }
        } catch (e: ChimeraNFSException) {
            if (e.status in ABSENT) return null
            throw e
        }
        return Fattr4Codec.decode(lastOf(res, nfs_opnum4.OP_GETATTR).opgetattr.resok4.obj_attributes)
    }

    /**
     * Runs [ops] and then [tail] against the export-absolute [path], reached from the
     * export root handle by LOOKUP inside the same COMPOUND, so one operation costs one
     * round trip. A path with more components than the server's negotiated operation
     * budget is walked in batches, which costs a round trip per batch and nothing else.
     *
     * The parameter is not called `build`: inside a CompoundBuilder-receiver lambda
     * that name resolves to the builder's own terminal `build()`, which silently
     * drops the caller's operation.
     */
    private fun compoundAt(
        path: String,
        tag: String,
        tail: Array<nfs_argop4> = NO_TAIL,
        nonIdempotent: Boolean = false,
        tolerate: (COMPOUND4res) -> Boolean = TOLERATE_NOTHING,
        ops: CompoundBuilder.() -> Unit = {},
    ): COMPOUND4res {
        val components = path.split('/').filter { it.isNotEmpty() }
        return resilient(nonIdempotent) {
            var base = rootHandle()
            var walked = 0
            val budget = (session.maxOps - OPS_AROUND_LOOKUPS).coerceAtLeast(1)
            while (components.size - walked > budget) {
                val hop = components.subList(walked, walked + budget).joinToString("/")
                val res = inSession(compound("walk") { withPutfh(base); withLookup(hop); withGetfh() })
                base = lastOf(res, nfs_opnum4.OP_GETFH).opgetfh.resok4.`object`
                walked += budget
            }
            val rest = components.subList(walked, components.size).joinToString("/")
            inSession(
                compound(tag) {
                    withPutfh(base)
                    if (rest.isNotEmpty()) withLookup(rest)
                    ops()
                }.followedBy(tail),
                tolerate = tolerate,
            )
        }
    }

    private fun rootHandle(): nfs_fh4 {
        rootFh?.let { return it }
        val res = inSession(
            compound("export_root") {
                withPutrootfh()
                if (spec.export != "/") withLookup(spec.export)
                withGetfh()
            },
        )
        return lastOf(res, nfs_opnum4.OP_GETFH).opgetfh.resok4.`object`.also { rootFh = it }
    }

    private fun compound(tag: String, ops: CompoundBuilder.() -> Unit): COMPOUND4args =
        CompoundBuilder().apply(ops).withTag(tag).build()

    /**
     * Appends operations nfs4j's [CompoundBuilder] has no method for. Its own operation
     * list is private and reflecting into it would not survive R8, so the built argarray
     * — public, and already rewritten by [inSession] — is where they go. Builder
     * operations therefore always precede hand-built ones.
     */
    private fun COMPOUND4args.followedBy(tail: Array<nfs_argop4>): COMPOUND4args {
        if (tail.isEmpty()) return this
        val head = argarray
        argarray = Array(head.size + tail.size) { if (it < head.size) head[it] else tail[it - head.size] }
        return this
    }

    /**
     * Prepends the SEQUENCE that every NFSv4.1 COMPOUND but session setup needs, and
     * holds one session slot for the round trip. Consumes [args]: its operation array
     * is replaced, so a retry must rebuild it.
     *
     * `sa_cachethis` stays false: the reply cache only answers a requester that replays
     * the same slot and sequence ID, and every attempt here takes a fresh pair.
     */
    private fun inSession(
        args: COMPOUND4args,
        on: Session = session,
        tolerate: (COMPOUND4res) -> Boolean = TOLERATE_NOTHING,
    ): COMPOUND4res {
        val slot = on.slots.acquireSlot()
        try {
            val sequence = nfs_argop4().apply {
                argop = nfs_opnum4.OP_SEQUENCE
                opsequence = SEQUENCE4args().apply {
                    sa_cachethis = false
                    sa_slotid = slot.id
                    sa_highest_slotid = slotid4(on.slots.maxRequests() - 1)
                    sa_sequenceid = slot.nextSequenceId()
                    sa_sessionid = on.slots.sessionId()
                }
            }
            val ops = args.argarray
            args.argarray = Array(ops.size + 1) { if (it == 0) sequence else ops[it - 1] }
            return raw(args, tolerate)
        } finally {
            on.slots.releaseSlot(slot)
        }
    }

    private fun raw(
        args: COMPOUND4args,
        tolerate: (COMPOUND4res) -> Boolean = TOLERATE_NOTHING,
    ): COMPOUND4res {
        val res = COMPOUND4res()
        try {
            call.call(nfs4_prot.NFSPROC4_COMPOUND_4, args, res, CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw IOException("nfs4 call timed out", e)
        }
        if (res.status != nfsstat.NFS_OK && !tolerate(res)) nfsstat.throwIfNeeded(res.status)
        return res
    }

    /**
     * At most three attempts: one after recovering from a session or handle the server
     * has forgotten, one after a server-requested delay. [op] must rebuild its
     * COMPOUND on every attempt because [inSession] consumes it.
     *
     * [nonIdempotent] narrows recovery to statuses that prove the operation never ran,
     * so a create, MKDIR or REMOVE cannot be executed twice by a retry. The one case a
     * status cannot settle — a reply lost in transit — arrives as a TimeoutException
     * mapped to [IOException], which no branch below retries.
     */
    private fun <T> resilient(nonIdempotent: Boolean = false, op: () -> T): T {
        var recovered = false
        var delayed = false
        while (true) {
            val before = session
            try {
                return op()
            } catch (e: ChimeraNFSException) {
                when {
                    !recovered && recoverable(e.status, nonIdempotent) -> {
                        recovered = true
                        recover(before, e.status)
                    }
                    !delayed && retryAfterDelay(e.status) -> {
                        delayed = true
                        Thread.sleep(RETRY_DELAY_MS)
                    }
                    else -> throw e
                }
            }
        }
    }

    private fun recover(stale: Session, status: Int) {
        synchronized(lock) {
            rootFh = null
            if (status in SESSION_LOST && session === stale) session = establish()
        }
    }

    private fun establish(): Session {
        val exchange = raw(
            compound("exchange_id") {
                withExchangeId(CLIENT_DOMAIN, CLIENT_NAME, ownerId, 0, state_protect_how4.SP4_NONE)
            },
        ).resarray[0].opexchange_id.eir_resok4

        val create = nfs_argop4().apply {
            argop = nfs_opnum4.OP_CREATE_SESSION
            opcreate_session = CREATE_SESSION4args().apply {
                csa_clientid = exchange.eir_clientid
                csa_sequence = exchange.eir_sequenceid
                csa_flags = uint32_t(0)
                csa_fore_chan_attrs = channelAttrs(MAX_REQUEST_SIZE, MAX_RESPONSE_SIZE, FORE_MAX_OPS, FORE_MAX_REQUESTS)
                csa_back_chan_attrs = channelAttrs(BACK_CHANNEL_SIZE, BACK_CHANNEL_SIZE, 2, 1)
                csa_cb_program = uint32_t(0)
                csa_sec_parms = arrayOf(callback_sec_parms4().apply { cb_secflavor = nfs4_prot.AUTH_NONE })
            }
        }
        val ok = raw(
            COMPOUND4args().apply {
                tag = utf8str_cs("create_session")
                minorversion = uint32_t(MINOR_VERSION)
                argarray = arrayOf(create)
            },
        ).resarray[0].opcreate_session.csr_resok4

        val fore = ok.csr_fore_chan_attrs
        val fresh = Session(
            exchange.eir_clientid,
            ClientSession(ok.csr_sessionid, fore.ca_maxrequests.value),
            fore.ca_maxoperations.value,
            // What the server GRANTED, not what was asked for: keeping the app's own
            // constant here is an eightfold loss on every copy when the grant is larger.
            grantedChunk(fore.ca_maxresponsesize.value, NFS_READ_CHUNK),
            grantedChunk(fore.ca_maxrequestsize.value, NFS_WRITE_CHUNK),
        )
        inSession(compound("reclaim_complete") { withReclaimComplete() }, fresh)
        return fresh
    }

    /** Maps the server's status onto the seam's failures, so no frontend parses prose. */
    private inline fun <T> failing(what: String, op: () -> T): T =
        try {
            op()
        } catch (e: ChimeraNFSException) {
            throw when (e.status) {
                nfsstat.NFSERR_ACCESS, nfsstat.NFSERR_PERM, nfsstat.NFSERR_ROFS ->
                    NfsFailure.PermissionDenied(what)
                nfsstat.NFSERR_NOENT, nfsstat.NFSERR_NOTDIR -> NfsFailure.NotFound(what)
                nfsstat.NFSERR_EXIST -> NfsFailure.AlreadyExists(what)
                else -> NfsFailure.Server("${nfsstat.toString(e.status)}: $what")
            }
        }

    /**
     * PUTFH + READ or WRITE in one COMPOUND, so either costs one round trip and needs no
     * OPEN state; the anonymous stateid is what makes the handle usable from any thread.
     */
    private inner class Handle(private val fh: nfs_fh4) : NfsFile {

        override fun readAt(offset: Long, dst: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val want = minOf(len, session.maxRead)
            val res = resilient { inSession(compound("read") { withPutfh(fh); withRead(want, offset, ANONYMOUS) }) }
            val ok = lastOf(res, nfs_opnum4.OP_READ).opread.resok4
            val n = ok.data.remaining()
            if (n > 0) ok.data.get(dst, off, n)
            return n
        }

        /**
         * [session].maxWrite is the negotiated request budget, so the payload can never
         * outgrow what the server granted. A WRITE at a fixed offset is idempotent, which
         * is why it keeps the full recovery set.
         */
        override fun writeAt(offset: Long, src: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val want = minOf(len, session.maxWrite)
            return failing("write at $offset") {
                val res = resilient {
                    inSession(
                        compound("write") {
                            withPutfh(fh)
                            withWrite(offset, ByteBuffer.wrap(src, off, want), ANONYMOUS)
                        },
                    )
                }
                lastOf(res, nfs_opnum4.OP_WRITE).opwrite.resok4.count.value
            }
        }

        override fun close() = Unit
    }

    /**
     * The builder's own withOpenCreate cannot be used: it does not offer GUARDED4, and
     * an UNCHECKED4 create truncates a file that already exists.
     */
    private fun openCreate(name: String): nfs_argop4 {
        val owner = state_owner4().apply {
            clientid = session.clientId
            owner = openOwner
        }
        val how = createhow4().apply {
            mode = createmode4.GUARDED4
            createattrs = Fattr4Codec.encodeMode(CREATE_MODE)
            createverf = verifier4(ByteArray(nfs4_prot.NFS4_VERIFIER_SIZE))
        }
        val claim = open_claim4().apply {
            this.claim = open_claim_type4.CLAIM_NULL
            file = component4(name)
        }
        return nfs_argop4().apply {
            argop = nfs_opnum4.OP_OPEN
            opopen = OPEN4args().apply {
                seqid = seqid4(0)
                share_access = uint32_t(nfs4_prot.OPEN4_SHARE_ACCESS_BOTH)
                share_deny = uint32_t(nfs4_prot.OPEN4_SHARE_DENY_NONE)
                this.owner = open_owner4(owner)
                openhow = openflag4().apply {
                    opentype = opentype4.OPEN4_CREATE
                    this.how = how
                }
                this.claim = claim
            }
        }
    }

    private companion object {
        const val RPC_VERSION = 4
        const val MINOR_VERSION = 1
        const val CALL_TIMEOUT_MS = 15_000L
        const val RETRY_DELAY_MS = 500L

        const val CLIENT_NAME = "mammon"
        const val CLIENT_DOMAIN = "mammon.app"

        /** 0644, spelled in binary because Kotlin has no octal literal. */
        const val CREATE_MODE = 0b110_100_100

        /** Asked for, not granted: high enough that a server willing to take
         *  [NFS_WRITE_CHUNK] of payload is not capped by our own request. */
        const val MAX_REQUEST_SIZE = NFS_WRITE_CHUNK + COMPOUND_OVERHEAD
        const val MAX_RESPONSE_SIZE = 1024 * 1024
        const val BACK_CHANNEL_SIZE = 4096
        const val FORE_MAX_OPS = 64
        const val FORE_MAX_REQUESTS = 8

        /** SEQUENCE + PUTFH, plus the longest operation list any caller appends here
         *  (OPEN + GETFH + CLOSE), so a batched LOOKUP walk cannot overrun the budget. */
        const val OPS_AROUND_LOOKUPS = 5

        const val READDIR_DIR_COUNT = 32 * 1024
        const val READDIR_MAX_COUNT = 64 * 1024

        val NO_TAIL = emptyArray<nfs_argop4>()

        val TOLERATE_NOTHING: (COMPOUND4res) -> Boolean = { false }

        val ANONYMOUS = Stateids.ZeroStateId()

        val ABSENT = intArrayOf(nfsstat.NFSERR_NOENT, nfsstat.NFSERR_NOTDIR)

        fun channelAttrs(requestSize: Int, responseSize: Int, ops: Int, requests: Int) =
            channel_attrs4().apply {
                ca_headerpadsize = count4(0)
                ca_maxrequestsize = count4(requestSize)
                ca_maxresponsesize = count4(responseSize)
                ca_maxresponsesize_cached = count4(0)
                ca_maxoperations = count4(ops)
                ca_maxrequests = count4(requests)
                ca_rdma_ird = emptyArray()
            }

        fun lastOf(res: COMPOUND4res, opnum: Int): nfs_resop4 =
            resultOf(res, opnum)
                ?: throw IOException("nfs4 reply carries no result for operation $opnum")

        /** Null when the operation never ran: a failed COMPOUND stops at its first error. */
        fun resultOf(res: COMPOUND4res, opnum: Int): nfs_resop4? =
            res.resarray.lastOrNull { it.resop == opnum }

        /** A foreign display name reaches the wire as one component, or not at all. */
        fun component(name: String): String =
            PathCodec.componentOf(name) ?: throw NfsFailure.Server("unusable name")

        fun getfh(): nfs_argop4 = nfs_argop4().apply { argop = nfs_opnum4.OP_GETFH }

        fun closeOp(stateid: stateid4): nfs_argop4 = nfs_argop4().apply {
            argop = nfs_opnum4.OP_CLOSE
            opclose = CLOSE4args().apply {
                seqid = seqid4(0)
                open_stateid = stateid
            }
        }

        fun setattr(attrs: fattr4): nfs_argop4 = nfs_argop4().apply {
            argop = nfs_opnum4.OP_SETATTR
            opsetattr = SETATTR4args().apply {
                stateid = ANONYMOUS
                obj_attributes = attrs
            }
        }
    }
}

/** Headers around the payload in either direction, taken out of the server's grant. */
private const val COMPOUND_OVERHEAD = 4096

/**
 * The payload budget for one READ or WRITE: what CREATE_SESSION granted, less the
 * headers that share the same budget, and never more than the app's own ceiling. The
 * granted size is a hard limit rather than advice, so the headers come out of it — a
 * request built to the granted size itself would overrun it by exactly those headers.
 */
internal fun grantedChunk(granted: Int, ceiling: Int): Int =
    (granted - COMPOUND_OVERHEAD).coerceIn(1, ceiling)

private val SESSION_LOST = intArrayOf(
    nfsstat.NFSERR_BADSESSION,
    nfsstat.NFSERR_DEADSESSION,
    nfsstat.NFSERR_STALE_CLIENTID,
    nfsstat.NFSERR_EXPIRED,
    nfsstat.NFSERR_SEQ_MISORDERED,
    nfsstat.NFSERR_BADSLOT,
)

private val RECOVERABLE = SESSION_LOST + intArrayOf(nfsstat.NFSERR_STALE, nfsstat.NFSERR_FHEXPIRED)

private val RETRY_AFTER_DELAY = intArrayOf(nfsstat.NFSERR_DELAY, nfsstat.NFSERR_GRACE)

/**
 * Whether a COMPOUND that came back with [status] may be rebuilt and re-sent.
 *
 * With [nonIdempotent] the answer narrows to statuses only SEQUENCE, PUTFH or LOOKUP can
 * produce, which prove the operation itself never ran: RFC 5661 section 15 terminates a
 * COMPOUND at its first failing operation, and section 16.2.3 makes the COMPOUND status
 * that operation's status. NFS4ERR_EXPIRED is the one exclusion, because section 15.2
 * lists OPEN, SETATTR and WRITE among the operations that return it themselves — so it
 * cannot prove anything about whether a create or a REMOVE took effect.
 */
internal fun recoverable(status: Int, nonIdempotent: Boolean): Boolean =
    status in RECOVERABLE && !(nonIdempotent && status == nfsstat.NFSERR_EXPIRED)

/** Whether the server asked for a plain retry, which means it performed nothing. */
internal fun retryAfterDelay(status: Int): Boolean = status in RETRY_AFTER_DELAY

/**
 * Whether the OPEN inside a create COMPOUND succeeded, read from its own result rather
 * than the COMPOUND status: a create whose trailing CLOSE was refused still made the
 * file, and reporting that as a failure would strand it.
 */
internal fun openSucceeded(res: COMPOUND4res): Boolean =
    res.resarray.lastOrNull { it.resop == nfs_opnum4.OP_OPEN }?.opopen?.status == nfsstat.NFS_OK

/** The three attributes mammon asks for, before the SAF-facing narrowing to [NodeAttrs]. */
internal data class Attrs4(val type: Int, val size: Long, val mtimeMillis: Long) {
    val isDirectory: Boolean get() = type == nfs_ftype4.NF4DIR
    val isRegular: Boolean get() = type == nfs_ftype4.NF4REG

    /** Symlinks and device nodes are neither, and the provider never lists them. */
    val isListable: Boolean get() = isDirectory || isRegular

    fun toNode(): NodeAttrs = NodeAttrs(isDirectory, size, mtimeMillis)
}

/**
 * Decodes and builds the fattr4 shapes mammon uses. Values are concatenated in ascending
 * bitmap order with no per-attribute length, so one unexpected attribute makes everything
 * after it unparseable: such a fattr4 is reported undecodable rather than guessed at, and
 * its entry is dropped. The same rule binds the encoders below — every write must stay in
 * ascending bit order.
 */
internal object Fattr4Codec {

    val REQUEST = intArrayOf(nfs4_prot.FATTR4_TYPE, nfs4_prot.FATTR4_SIZE, nfs4_prot.FATTR4_TIME_MODIFY)

    fun decode(attrs: fattr4?): Attrs4? {
        if (attrs == null) return null
        val mask = attrs.attrmask.value
        var type = -1
        var size = 0L
        var mtime = 0L
        Xdr(attrs.attr_vals.value).use { xdr ->
            xdr.beginDecoding()
            for (bit in 0 until mask.size * Int.SIZE_BITS) {
                if ((mask[bit / Int.SIZE_BITS] ushr (bit % Int.SIZE_BITS)) and 1 == 0) continue
                when (bit) {
                    nfs4_prot.FATTR4_TYPE -> type = xdr.xdrDecodeInt()
                    nfs4_prot.FATTR4_SIZE -> size = xdr.xdrDecodeLong()
                    nfs4_prot.FATTR4_TIME_MODIFY ->
                        mtime = xdr.xdrDecodeLong() * 1000L + xdr.xdrDecodeInt() / 1_000_000L
                    else -> return null
                }
            }
        }
        return if (type < 0) null else Attrs4(type, size, mtime)
    }

    /** The createattrs of a new regular file. */
    fun encodeMode(mode: Int): fattr4 =
        fattrOf(intArrayOf(nfs4_prot.FATTR4_MODE), encoded { it.xdrEncodeInt(mode) })

    /**
     * SETATTR attributes. FATTR4_SIZE sorts before FATTR4_TIME_MODIFY_SET, which is the
     * order they must be written in, and TIME_MODIFY_SET rather than TIME_MODIFY because
     * only the settable form carries a client-supplied time.
     */
    fun encodeSetattr(size: Long?, mtimeMillis: Long?): fattr4 {
        val bits = ArrayList<Int>(2)
        if (size != null) bits += nfs4_prot.FATTR4_SIZE
        if (mtimeMillis != null) bits += nfs4_prot.FATTR4_TIME_MODIFY_SET
        return fattrOf(
            bits.toIntArray(),
            encoded { xdr ->
                if (size != null) xdr.xdrEncodeLong(size)
                if (mtimeMillis != null) clientTime(mtimeMillis).xdrEncode(xdr)
            },
        )
    }

    private fun clientTime(millis: Long): settime4 = settime4().apply {
        set_it = time_how4.SET_TO_CLIENT_TIME4
        time = nfstime4().apply {
            seconds = millis.floorDiv(1000L)
            nseconds = (millis.mod(1000L) * 1_000_000L).toInt()
        }
    }

    /** nfs4j's bitmap4.of rejects an empty list, so an attribute-less SETATTR is named
     *  here rather than surfacing as "No values provided" from inside the library. */
    private fun fattrOf(bits: IntArray, values: ByteArray): fattr4 {
        require(bits.isNotEmpty()) { "a fattr4 must carry at least one attribute" }
        return fattr4().apply {
            attrmask = bitmap4.of(*bits)
            attr_vals = attrlist4(values)
        }
    }

    private fun encoded(write: (XdrEncodingStream) -> Unit): ByteArray =
        Xdr(ENCODE_CAPACITY).use { xdr ->
            xdr.beginEncoding()
            write(xdr)
            xdr.endEncoding()
            xdr.getBytes()
        }

    /** One fattr4 mammon builds never exceeds a handful of attributes. */
    private const val ENCODE_CAPACITY = 256
}

/**
 * AUTH_SYS credential, encoded per RFC 5531 appendix A.
 *
 * oncrpc4j's own RpcAuthTypeUnix cannot be used here: its constructor builds a
 * Subject out of com.sun.security.auth principals, which Android does not ship, so
 * merely constructing it throws NoClassDefFoundError on a device.
 */
internal class AuthSys(private val identity: AuthIdentity, machine: String) : RpcAuth {

    private val machineBytes = machine.toByteArray(StandardCharsets.US_ASCII)
    private val auxGids = identity.auxGids.map(Long::toInt).toIntArray()
    private val stamp = (System.currentTimeMillis() / 1000).toInt()
    private val verifier = RpcAuthVerifier(RpcAuthType.NONE, ByteArray(0))
    private val subject = Subject()

    /** Body length the opaque_auth header declares: every field below, XDR-padded. */
    private val length =
        4 + 4 + machineBytes.size + ((4 - (machineBytes.size and 3)) and 3) +
            4 + 4 + 4 + 4 * auxGids.size

    override fun type(): Int = RpcAuthType.UNIX

    override fun getVerifier(): RpcAuthVerifier = verifier

    /** Empty on purpose: only oncrpc4j's server side consults it, and mammon is a client. */
    override fun getSubject(): Subject = subject

    override fun xdrEncode(xdr: XdrEncodingStream) {
        xdr.xdrEncodeInt(RpcAuthType.UNIX)
        xdr.xdrEncodeInt(length)
        xdr.xdrEncodeInt(stamp)
        xdr.xdrEncodeDynamicOpaque(machineBytes)
        xdr.xdrEncodeInt(identity.uid.toInt())
        xdr.xdrEncodeInt(identity.gid.toInt())
        // The supplementary list is what a root_squash export checks against the file's
        // group; a one-element copy of the primary gid cannot carry a second group.
        xdr.xdrEncodeIntVector(auxGids)
        verifier.xdrEncode(xdr)
    }

    override fun xdrDecode(xdr: XdrDecodingStream): Unit =
        throw UnsupportedOperationException("mammon never receives AUTH_SYS credentials")
}
