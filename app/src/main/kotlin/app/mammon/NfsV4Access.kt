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
import org.dcache.nfs.v4.xdr.GETATTR4args
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
 * lease-renewal timer. Only file creation takes OPEN state, and it gives that state
 * straight back — in the same COMPOUND where the server accepts a CLOSE there, in a
 * second one where it does not.
 *
 * The connection outlives neither an idle period nor a server restart, so [resilient]
 * rebuilds it — a new client, a new session, no cached root handle — both before a
 * COMPOUND that would go out on a socket already closed and after one that died in
 * flight. A non-idempotent operation is never re-sent by that recovery: it surfaces a
 * mapped failure instead, since a loss cannot say whether the server applied it.
 *
 * [close] is the end of that: it is idempotent, and a rebuild attempted afterwards is
 * refused, so a discarded instance cannot hold a client or a server-side session alive.
 * A session whose setup fails is destroyed where it was made, since nothing will ever
 * hold the instance to call [close] on.
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
     * Stable for this instance so a rebuild reads as the same client restarting: nfs4j
     * derives co_verifier from the whole second EXCHANGE_ID was built in, so a rebuild a
     * second later arrives with a changed verifier, and RFC 8881 section 18.35.4 case 5
     * has the server release the predecessor's state at CREATE_SESSION instead of holding
     * it to lease expiry. Unique between instances so a probe from the activity is a
     * different client owner and cannot aim that release at the provider's live session.
     */
    private val ownerId = "$CLIENT_NAME/${UUID.randomUUID()}@${spec.host}:${spec.export}"

    private val openOwner = ownerId.toByteArray(StandardCharsets.UTF_8)

    private val server = InetSocketAddress(spec.host.removeSurrounding("[", "]"), spec.port)

    private val auth = AuthSys(target.identity, CLIENT_NAME)

    private val lock = Any()

    private class Transport(val client: OncRpcClient, val call: RpcCall)

    @Volatile private var rpc: OncRpcClient

    @Volatile private var call: RpcCall

    @Volatile private var session: Session

    @Volatile private var rootFh: nfs_fh4? = null

    /**
     * Bumped wherever [rootFh] is cleared, so a resolution already in flight can tell
     * that the session it resolved against is gone and decline to publish.
     */
    @Volatile private var rootEpoch = 0

    /**
     * Cleared once a server refuses a CLOSE beside its OPEN. It saves no round trip —
     * such a server needs two either way — only the doomed CLOSE operation inside the
     * first COMPOUND, and with it the error reply that [createFile] would otherwise have
     * to interpret on every create.
     */
    @Volatile private var closeBesideOpen = true

    /**
     * One-way. A thread still inside [resilient] when [close] runs finds its socket
     * dead, and without this latch it would establish a fresh client and a fresh
     * server-side session on an instance nobody holds a reference to any more.
     */
    @Volatile private var closed = false

    override val implementsWrites = true

    init {
        val fresh = connect()
        rpc = fresh.client
        call = fresh.call
        var established: Session? = null
        try {
            established = establish(fresh.call)
            session = established
            // Resolving the export here is what makes a wrong export or a v3-only
            // server fail during construction, where version selection can fall back.
            rootHandle(established)
        } catch (e: Throwable) {
            // The constructor throws, so nothing can reach [close]: a session the server
            // already holds would otherwise survive until its lease expired.
            established?.let { destroy(it, fresh.call) }
            runCatching { fresh.client.close() }
            throw e
        }
    }

    /** One spelling of the transport, shared by construction and [reconnect]. */
    private fun connect(): Transport {
        val client = OncRpcClient.newBuilder()
            .withProtocol(IpProtocolType.TCP)
            .withIoStrategy(IoStrategy.SAME_THREAD)
            .withSelectorThreadPoolSize(1)
            .withWorkerThreadPoolSize(1)
            .withTcpNoDelay(true)
            .withConnectTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build(server)
        return try {
            val transport = client.connect(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            Transport(client, RpcCall(nfs4_prot.NFS4_PROGRAM, RPC_VERSION, auth, transport))
        } catch (e: Throwable) {
            runCatching { client.close() }
            throw e
        }
    }

    override fun probeRoot(): NodeAttrs? = attrsAt("/")?.takeIf { it.isDirectory }?.toNode()

    override fun stat(docId: String): NodeAttrs? {
        val path = PathCodec.pathFor(docId) ?: return null
        return attrsAt(path)?.toNode()
    }

    /**
     * Every page is its own [compoundAt], so a transport rebuild can land between two of
     * them. The cookie and its verifier survive that — an enumeration's anchor belongs to
     * a directory on a server instance, not to a session — but not a server restart,
     * after which a resumed listing draws NFS4ERR_NOT_SAME or NFS4ERR_BAD_COOKIE (RFC
     * 8881 section 18.23.3) that no arm of [resilient] retries. Enumerating again from
     * the start is the only answer that keeps the anchor consistent, and it is taken
     * once: a server that keeps invalidating it must surface the error, not be spun on.
     */
    override fun list(docId: String): List<ChildEntry> = failing(docId) {
        val dirPath = requireNotNull(PathCodec.pathFor(docId))
        val children = try {
            readdirAll(dirPath)
        } catch (e: ChimeraNFSException) {
            if (e.status !in COOKIE_LOST) throw e
            readdirAll(dirPath)
        }
        children.directoriesFirst()
    }

    /** One full enumeration: the partial result of a failed one is dropped with it. */
    private fun readdirAll(dirPath: String): List<ChildEntry> {
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
        return children
    }

    override fun openFile(docId: String): OpenedFile = failing(docId) {
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
        OpenedFile(Handle(lastOf(res, nfs_opnum4.OP_GETFH).opgetfh.resok4.`object`), attrs.toNode())
    }

    /**
     * Both attributes ride one SETATTR, and SETATTR on the anonymous stateid needs no
     * OPEN: setting the same size or time twice is the same result, so this keeps the
     * full recovery set. SETATTR leaves the current filehandle alone, so the GETATTR
     * after it reads back the object just set without a second walk.
     */
    override fun setAttributes(docId: String, size: Long?, modifiedMillis: Long?): NodeAttrs {
        val path = requireNotNull(PathCodec.pathFor(docId))
        if (size == null && modifiedMillis == null) {
            return attrsAt(path)?.toNode() ?: throw NfsFailure.NotFound(docId)
        }
        return failing(docId) {
            val res = compoundAt(
                path,
                "setattr",
                arrayOf(setattr(Fattr4Codec.encodeSetattr(size, modifiedMillis)), getattr()),
            )
            decoded(res, docId)
        }
    }

    /**
     * The mode is nfs4j's, not ours: withMakedir hardcodes 0755, where a created file
     * gets [CREATE_MODE] from this file.
     *
     * CREATE sets the current filehandle to the new directory, so the GETATTR rides the
     * same COMPOUND and the caller's attributes cost no extra round trip.
     */
    override fun makeDirectory(parentDocId: String, name: String): NodeAttrs {
        val parent = requireNotNull(PathCodec.pathFor(parentDocId))
        val child = component(name)
        return failing("$parent/$child") {
            val res = compoundAt(parent, "mkdir", nonIdempotent = true) {
                withMakedir(child)
                withGetattr(*Fattr4Codec.REQUEST)
            }
            decoded(res, "$parent/$child")
        }
    }

    /** [isDirectory] is ignored: one REMOVE serves both types, so there is nothing to pick. */
    override fun remove(parentDocId: String, name: String, isDirectory: Boolean?) {
        val parent = requireNotNull(PathCodec.pathFor(parentDocId))
        val child = component(name)
        failing("$parent/$child") {
            compoundAt(parent, "remove", nonIdempotent = true) { withRemove(child) }
        }
    }

    /**
     * OPEN(OPEN4_CREATE, GUARDED4) + GETFH + CLOSE in one COMPOUND, so a create costs one
     * round trip on a server that accepts the CLOSE there, and two on one that does not.
     * GUARDED4 rather than UNCHECKED4 because a file manager must answer "it already
     * exists", never truncate.
     *
     * A server that refuses the CLOSE beside the OPEN has still created the file — a
     * failing operation terminates the COMPOUND, so everything before it ran — so that
     * reply is read operation by operation and the CLOSE is retried on its own. Once the
     * file exists, nothing about the CLOSE may fail the create: an unclosed open state
     * costs a lease, while a create reported as failed strands a file the caller will
     * then be told already exists.
     */
    override fun createFile(parentDocId: String, name: String): CreatedFile {
        val parent = requireNotNull(PathCodec.pathFor(parentDocId))
        val child = component(name)
        return failing("$parent/$child") {
            val fused = closeBesideOpen
            val openOp = openCreate(child)
            // OPEN leaves the new file as the current filehandle, so GETATTR reads it
            // here rather than costing the caller a second walk from the export root.
            val tail =
                if (fused) arrayOf(openOp, getfh(), getattr(), closeOp(Stateids.currentStateId()))
                else arrayOf(openOp, getfh(), getattr())
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
                // Reachable, and the reason this branch exists: `tolerate` admits any
                // reply whose OPEN succeeded, GETFH's own failure included. The open
                // state cannot be closed without a handle to PUTFH, so it is left for
                // the server's lease to reap.
                closeBesideOpen = false
                throw NfsFailure.Server("nfs4 create reply carries no filehandle")
            }
            val fh = handle.resok4.`object`
            if (res.status != nfsstat.NFS_OK) closeBesideOpen = false
            if (!fused || res.status != nfsstat.NFS_OK) {
                // Outside [resilient] on purpose: nobody reads this result, and a rebuild
                // takes a new clientid, leaving the open state for the lease to reap
                // exactly as the no-filehandle branch above relies on.
                runCatching {
                    inSession(compound("close") { withPutfh(fh); withClose(stateid, 0) }, session)
                }
            }
            CreatedFile(Handle(fh), decoded(res, "$parent/$child"))
        }
    }

    /**
     * Idempotent: the provider closes a session both when its target changes and at
     * shutdown. The latch goes up before the lock, so a rebuild short of its publication
     * point is refused rather than allowed to resurrect this instance.
     *
     * The lock is taken only to snapshot what to tear down: a rebuild publishes under it,
     * and an unlocked read could miss the client it published and leave that one running.
     * The destroy COMPOUNDs and the client go out with the lock released, because the
     * provider calls this from inside its own monitor.
     */
    override fun close() {
        if (closed) return
        closed = true
        val (doomed, s) = synchronized(lock) { Transport(rpc, call) to session }
        destroy(s, doomed.call)
        runCatching { doomed.client.close() }
    }

    /** Best-effort: a server that has already forgotten the session answers an error. */
    private fun destroy(s: Session, on: RpcCall) {
        runCatching { raw(compound("destroy_session") { withDestroysession(s.slots.sessionId()) }, on) }
        runCatching { raw(compound("destroy_clientid") { withDestroyclientid(s.clientId) }, on) }
    }

    private fun attrsAt(path: String): Attrs4? = failing(path) {
        val res = try {
            compoundAt(path, "getattr") { withGetattr(*Fattr4Codec.REQUEST) }
        } catch (e: ChimeraNFSException) {
            if (e.status in ABSENT) return@failing null
            throw e
        }
        Fattr4Codec.decode(lastOf(res, nfs_opnum4.OP_GETATTR).opgetattr.resok4.obj_attributes)
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
        return resilient(nonIdempotent) { s ->
            var base = rootHandle(s)
            var walked = 0
            val budget = (s.maxOps - OPS_AROUND_LOOKUPS).coerceAtLeast(1)
            while (components.size - walked > budget) {
                val hop = components.subList(walked, walked + budget).joinToString("/")
                val res = inSession(compound("walk") { withPutfh(base); withLookup(hop); withGetfh() }, s)
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
                s,
                tolerate = tolerate,
            )
        }
    }

    private fun rootHandle(on: Session): nfs_fh4 {
        rootFh?.let { return it }
        val epoch = rootEpoch
        val res = inSession(
            compound("export_root") {
                withPutrootfh()
                if (spec.export != "/") withLookup(spec.export)
                withGetfh()
            },
            on,
        )
        val fh = lastOf(res, nfs_opnum4.OP_GETFH).opgetfh.resok4.`object`
        // The round trip stays out of the monitor; only the publication needs it, so that
        // a rebuild which cleared the cache mid-resolution cannot have it written back.
        synchronized(lock) { if (rootEpoch == epoch) rootFh = fh }
        return fh
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
     * [on] has no default: a size or batch budget read from one [Session] and sent on
     * another is a mismatch the server answers with a status nothing retries. [over]
     * keeps one, because the freshest open transport is always the right one to send on
     * — RFC 8881 section 2.10.13.1.2 needs no BIND_CONN_TO_SESSION under SP4_NONE, so an
     * older session on a newer connection is either served or answered BADSESSION.
     *
     * `sa_cachethis` stays false: the reply cache only answers a requester that replays
     * the same slot and sequence ID, and every attempt here takes a fresh pair.
     */
    private fun inSession(
        args: COMPOUND4args,
        on: Session,
        over: RpcCall = call,
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
            return raw(args, over, tolerate)
        } finally {
            on.slots.releaseSlot(slot)
        }
    }

    /** [on] is explicit wherever the field is not the transport to use: [close], [establish]. */
    private fun raw(
        args: COMPOUND4args,
        on: RpcCall = call,
        tolerate: (COMPOUND4res) -> Boolean = TOLERATE_NOTHING,
    ): COMPOUND4res {
        val res = COMPOUND4res()
        try {
            on.call(nfs4_prot.NFSPROC4_COMPOUND_4, args, res, CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw IOException("nfs4 call timed out", e)
        }
        if (res.status != nfsstat.NFS_OK && !tolerate(res)) nfsstat.throwIfNeeded(res.status)
        return res
    }

    /**
     * At most four attempts: one after rebuilding a transport, one after recovering from
     * a session or handle the server has forgotten, one after a server-requested delay.
     * At most ONE of those rebuilds a transport — the pre-flight probe and the mid-flight
     * loss arm share one latch, so a connection dying twice inside a single call fails
     * instead of handshaking again. [op] must rebuild its COMPOUND on every attempt
     * because [inSession] consumes it.
     *
     * [op] is handed the [Session] to send on, read once per attempt and after any
     * pre-flight rebuild: a budget or a size taken from one session and sent on another
     * draws NFS4ERR_TOO_MANY_OPS or NFS4ERR_REP_TOO_BIG, which nothing retries, where a
     * stale but consistent pair draws BADSESSION, which [recoverable] does. That same
     * snapshot is what [recover] is guarded against, so the guard tests the session the
     * attempt actually failed on rather than one a rebuild has since replaced.
     *
     * [RECOVERY_BUDGET_NS] bounds the same sequence in wall clock, which an attempt count
     * cannot: every blocking call inside carries the full [CALL_TIMEOUT_MS], so four
     * attempts run to minutes. Past the deadline a rebuild, a recovery and the delay are
     * each refused: a refused recovery throws the status it already holds, while a
     * connection this call will not remake is [NfsFailure.Unreachable] with the loss that
     * proved it. The deadline is carried into [reconnect] and [recover] because the wait
     * on their monitor is not under it.
     *
     * What the budget bounds is where recovery may START inside one call of this
     * function, not what the call costs. An attempt already running is never cut short, a
     * blocking COMPOUND being uninterruptible from here, and an attempt is no constant:
     * under [compoundAt] it is a root-handle resolution plus one round trip per LOOKUP
     * batch, so O(path depth) at [CALL_TIMEOUT_MS] each. Nor is there one budget per SAF
     * operation — [list] arms a fresh one per READDIR page, a copy one per chunk — so no
     * single-number ceiling holds past a shallow path.
     *
     * [nonIdempotent] narrows recovery to statuses that prove the operation never ran,
     * and stops a lost connection from re-sending at all, so a create, MKDIR or REMOVE
     * cannot be executed twice by a retry. The one case neither a status nor a socket can
     * settle — a reply lost in transit — arrives as a TimeoutException mapped to
     * [IOException], which [TransportLoss.isLoss] rejects and no branch below retries.
     */
    private fun <T> resilient(nonIdempotent: Boolean = false, op: (Session) -> T): T {
        val deadline = System.nanoTime() + RECOVERY_BUDGET_NS
        var recovered = false
        var rebuilt = false
        var delayed = false
        var loss: IOException? = null
        while (true) {
            val on = call
            if (!on.transport.isOpen) {
                if (rebuilt || spent(deadline)) {
                    // The loss says why the socket died, which the note cannot.
                    throw unreachable(
                        if (rebuilt) "nfs4 transport closed twice in one call"
                        else "nfs4 transport closed with the recovery budget spent",
                        loss,
                    )
                }
                // Nothing has reached the wire yet, so a create or a delete is as safe to
                // send on the rebuilt connection as a read is.
                rebuilt = true
                rebuild(on, deadline, "nfs4 could not rebuild a transport found closed", null)
            }
            val s = session
            try {
                return op(s)
            } catch (e: ChimeraNFSException) {
                when {
                    !recovered && recoverable(e.status, nonIdempotent) && !spent(deadline) -> {
                        recovered = true
                        val retry = try {
                            recover(s, e.status, deadline)
                        } catch (broken: Throwable) {
                            // A handshake on the connection the status came back on can
                            // die under it; an NfsFailure decided already, and isLoss
                            // walks causes.
                            if (broken is NfsFailure || !TransportLoss.isLoss(broken)) {
                                throw broken.afterLoss(loss)
                            }
                            throw unreachable(
                                "nfs4 lost the transport recovering the session",
                                broken,
                                loss,
                            )
                        }
                        // A recovery refused after the lock wait spent the budget leaves
                        // this status as the best answer there is.
                        if (!retry) throw e.afterLoss(loss)
                    }
                    !delayed && retryAfterDelay(e.status) && !spent(deadline) -> {
                        delayed = true
                        Thread.sleep(RETRY_DELAY_MS)
                    }
                    else -> throw e.afterLoss(loss)
                }
            } catch (e: IOException) {
                // Below the ChimeraNFSException arm on purpose: a status is an IOException too.
                if (!TransportLoss.isLoss(e)) throw e
                if (rebuilt || spent(deadline)) {
                    throw unreachable(
                        if (rebuilt) "nfs4 transport lost twice in one call"
                        else "nfs4 transport lost with the recovery budget spent",
                        e,
                        loss,
                    )
                }
                rebuilt = true
                loss = e
                rebuild(on, deadline, "nfs4 lost the transport and could not rebuild it", e)
                if (!TransportLoss.mayResend(nonIdempotent)) {
                    // Rethrowing would show the user a class name: the measured loss is a
                    // message-less EOFException.
                    throw unreachable("nfs4 lost the transport on an operation that may not be re-sent", e)
                }
            }
        }
    }

    /** Subtraction, not comparison: [System.nanoTime] is signed and does wrap. */
    private fun spent(deadline: Long) = deadline - System.nanoTime() <= 0

    /**
     * Attaches the transport loss a failure came after: the loss says why the connection
     * went, the failure only says what came back. [addSuppressed] refuses a self-
     * reference, which the loss arm's own rethrow would otherwise make.
     */
    private fun <E : Throwable> E.afterLoss(loss: IOException?): E =
        apply { loss?.let { if (it !== this) addSuppressed(it) } }

    /**
     * The server could not be reached, or the connection to it could not be remade. What
     * proved it rides along for a log: the case, not the message, is what a frontend shows.
     */
    private fun unreachable(what: String, cause: Throwable? = null, also: Throwable? = null) =
        NfsFailure.Unreachable(what).apply {
            cause?.let { initCause(it) }
            also?.let { if (it !== cause) addSuppressed(it) }
        }

    /**
     * [reconnect] with its own failure cased, so no rebuild hands [resilient] a raw socket
     * exception. A rebuild that decided its own case — the closed latch, the spent budget —
     * is that case and is rethrown as itself; anything else came out of the handshake over
     * the fresh connection, which means the server stayed out of reach. [loss] is the
     * mid-flight loss this rebuild answers, null when the probe found the socket closed
     * with nothing sent.
     */
    private fun rebuild(stale: RpcCall, deadline: Long, note: String, loss: IOException?) {
        runCatching { reconnect(stale, deadline) }.onFailure { failed ->
            throw if (failed is NfsFailure || failed is ChimeraNFSException) {
                failed.afterLoss(loss)
            } else {
                unreachable(note, loss ?: failed, failed)
            }
        }
    }

    /**
     * Re-establishes what [status] says the server has forgotten, and answers whether the
     * caller may retry.
     *
     * The identity guard precedes the budget gate: a loser whose session the winner has
     * already replaced has nothing left to do, and refusing it would report BADSESSION on
     * a healthy connection. False is left for the one case that needs a handshake and has
     * no time for it, where the status the caller holds says more than a note would.
     */
    private fun recover(stale: Session, status: Int, deadline: Long): Boolean {
        synchronized(lock) {
            // Refused like a rebuild: an establish here is server-side state on an
            // instance nobody can close again.
            if (closed) throw NfsFailure.Server("nfs4 session closed")
            // Above both gates: a root handle resolved against a session the server has
            // forgotten is worse than none, and dropping it costs nothing.
            rootFh = null
            rootEpoch++
            if (status !in SESSION_LOST || session !== stale) return true
            if (spent(deadline)) return false
            session = establish(call)
        }
        return true
    }

    /**
     * Replaces the transport [stale] was sent on, then re-establishes the session over it.
     *
     * The identity guard is what makes N racing SAF threads rebuild once: a loser finds
     * [call] already replaced and returns onto the winner's connection. The whole client
     * goes rather than just its connection because reusing a closed [OncRpcClient] is not
     * a documented capability of oncrpc4j, and the extra handshake is unmeasurable on a
     * path that only runs when the connection is already gone.
     *
     * The handshake runs with the lock held, as does [recover]'s — both are the two lock
     * regions that do network I/O — unlike the session opened outside the provider's
     * monitor: either one presents this instance's [ownerId] again on purpose, so two
     * concurrent EXCHANGE_IDs would race on one client identity and the loser's would
     * reap the session the winner had just created. [deadline] is re-checked after that
     * monitor for the same reason: waiting behind a predecessor's handshake can spend all
     * of it, which the caller's gate cannot see.
     *
     * After [close] this only throws. The second check covers a close that latched while
     * [connect] was already in flight, whose client would otherwise be published live on
     * an instance nothing will ever close again.
     *
     * The transport and the session handshook over it are published last and together, so
     * a failed handshake leaves the pair as it was — an already closed transport, which
     * the next attempt's probe rebuilds.
     */
    private fun reconnect(stale: RpcCall, deadline: Long) {
        synchronized(lock) {
            if (closed) throw NfsFailure.Server("nfs4 session closed")
            if (call !== stale) return
            if (spent(deadline)) {
                throw unreachable("nfs4 rebuild refused with the recovery budget spent")
            }
            runCatching { rpc.close() }
            val fresh = try {
                connect()
            } catch (e: IOException) {
                // Decided by the site, not by the cause: a connect() that threw is a
                // server this call could not reach.
                throw unreachable("nfs4 could not connect to $server", e)
            }
            if (closed) {
                runCatching { fresh.client.close() }
                throw NfsFailure.Server("nfs4 session closed")
            }
            rpc = fresh.client
            rootFh = null
            rootEpoch++
            val next = establish(fresh.call)
            call = fresh.call
            session = next
        }
    }

    /** [over] is explicit because [reconnect] handshakes before it publishes [call]. */
    private fun establish(over: RpcCall): Session {
        val exchange = raw(
            compound("exchange_id") {
                withExchangeId(CLIENT_DOMAIN, CLIENT_NAME, ownerId, 0, state_protect_how4.SP4_NONE)
            },
            over,
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
            over,
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
        return try {
            // Two EXCHANGE_IDs in the same wall-clock second carry the same co_verifier,
            // so the server hands back the client ID whose reclaim is already complete:
            // RFC 8881 section 18.35.4 case 2, then section 18.51.4.
            inSession(
                compound("reclaim_complete") { withReclaimComplete() },
                fresh,
                over,
                tolerate = { it.status == nfsstat.NFSERR_COMPLETE_ALREADY },
            )
            fresh
        } catch (e: Throwable) {
            // No retry, not even for DELAY. When SEQUENCE itself answered the error the
            // client's slot sequence has advanced and the server's has not (RFC 8881
            // section 18.46.3), so a second attempt draws SEQ_MISORDERED; when the error
            // came from RECLAIM_COMPLETE the two views agree and a retry would be ordered,
            // but it would sleep inside the handshake and spend the caller's recovery
            // budget, and the caller's own next rebuild is that retry.
            if (e is ChimeraNFSException && e.status !in TEARDOWN_POINTLESS) {
                // CREATE_SESSION took, and a throwing establish() hands the session to
                // nobody who could destroy it later.
                runCatching { destroy(fresh, over) }
            }
            throw e
        }
    }

    /**
     * The attributes from a GETATTR that rode another operation's COMPOUND. The result is
     * checked rather than dereferenced: a `tolerate` predicate can admit a reply whose
     * leading operation succeeded and whose GETATTR did not, and an unguarded resok4
     * there is a NullPointerException that no caller is typed to catch.
     */
    private fun decoded(res: COMPOUND4res, what: String): NodeAttrs {
        val ok = resultOf(res, nfs_opnum4.OP_GETATTR)
            ?.opgetattr
            ?.takeIf { it.status == nfsstat.NFS_OK }
            ?: throw NfsFailure.Server("nfs4 reply carries no attributes: $what")
        return Fattr4Codec.decode(ok.resok4.obj_attributes)?.toNode()
            ?: throw NfsFailure.Server("nfs4 reply carries undecodable attributes: $what")
    }

    /**
     * Maps a failure onto the seam's cases, so no frontend parses prose. A status the
     * server returned is [failureForV4]'s decision; this wrapper owns the rest.
     *
     * Anything else arriving as an [IOException] is mapped here too: the timeout wrapper
     * [resilient] deliberately never retries earns its own case because both frontends
     * answer a call that outran its deadline differently, and every transport loss is
     * cased as [NfsFailure.Unreachable] before it reaches here, including the one on an
     * operation no retry may repeat and the one a rebuild or a recovery handshake meets.
     * What is left is an RPC fault below the status layer, where the message is the only
     * description there is, so the cause rides along: a log and anything re-classifying
     * downstream have nothing else to go on. The arm is what keeps a bare IOException out
     * of either frontend.
     */
    private inline fun <T> failing(what: String, op: () -> T): T =
        try {
            op()
        } catch (e: ChimeraNFSException) {
            throw failureForV4(e.status, what)
        } catch (e: NfsFailure) {
            // Already mapped, and an NfsFailure is an IOException: without this it would
            // be caught below and lose the case a frontend acts on.
            throw e
        } catch (e: IOException) {
            // By cause, not by message: no frontend may match on prose.
            val mapped = when {
                e.cause is TimeoutException -> NfsFailure.Timeout(what)
                else -> NfsFailure.Server("${e.message ?: e.javaClass.simpleName}: $what")
            }
            throw mapped.apply { initCause(e) }
        }

    /**
     * PUTFH + READ or WRITE in one COMPOUND, so either costs one round trip and needs no
     * OPEN state; the anonymous stateid is what makes the handle usable from any thread.
     *
     * [fh] deliberately outlives the session a rebuild replaces: an NFSv4 filehandle is
     * per-export and the anonymous stateid binds no state to it, so an fd SAF still holds
     * keeps working. A volatile-filehandle server is out of scope, and degrades there to
     * one FHEXPIRED retry and a mapped failure.
     */
    private inner class Handle(private val fh: nfs_fh4) : NfsFile {

        /**
         * The chunk is clamped against the session [resilient] hands this attempt, which
         * is the one the COMPOUND goes out on: a rebuild replaces the session, and a
         * reply sized to the grant of the one that is gone can overrun the new channel's
         * ca_maxresponsesize, which RFC 5661 answers with NFS4ERR_REP_TOO_BIG — not
         * recoverable, so it would fail a read that re-chunking completes.
         */
        override fun readAt(offset: Long, dst: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            return failing("read at $offset") {
                val res = resilient { s ->
                    val want = minOf(len, s.maxRead)
                    inSession(compound("read") { withPutfh(fh); withRead(want, offset, ANONYMOUS) }, s)
                }
                val ok = lastOf(res, nfs_opnum4.OP_READ).opread.resok4
                val n = ok.data.remaining()
                if (n > 0) ok.data.get(dst, off, n)
                n
            }
        }

        /**
         * Clamped inside [resilient] for the reason [readAt] gives, against
         * NFS4ERR_REQ_TOO_BIG on this side. A WRITE at a fixed offset is idempotent,
         * which is why it keeps the full recovery set.
         */
        override fun writeAt(offset: Long, src: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            return failing("write at $offset") {
                val res = resilient { s ->
                    val want = minOf(len, s.maxWrite)
                    inSession(
                        compound("write") {
                            withPutfh(fh)
                            withWrite(offset, ByteBuffer.wrap(src, off, want), ANONYMOUS)
                        },
                        s,
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
            // Snapshotted outside the retry loop, so a rebuilt attempt can carry a
            // superseded clientid. Harmless, and the same reason seqid below is 0: RFC
            // 8881 section 2.4 takes a 4.1 operation's client identity from its session.
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

        /** The 20 s one [resilient] call may keep starting recovery work in: a shade over
         *  [CALL_TIMEOUT_MS], so a single slow round trip cannot forfeit the one rebuild
         *  the call is allowed — a healthy rebuild is a TCP handshake and three round
         *  trips. It bounds where recovery may start, not what a call costs: the attempt a
         *  gate admits runs to completion at a round trip per LOOKUP batch, and one SAF
         *  operation arms this budget once per [resilient] call — per READDIR page, per
         *  copied chunk. An attempt count alone would bound none of it. */
        const val RECOVERY_BUDGET_NS = 20_000L * 1_000_000L

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
         *  (OPEN + GETFH + GETATTR + CLOSE), so a batched LOOKUP walk cannot overrun the
         *  server's negotiated operation budget. */
        const val OPS_AROUND_LOOKUPS = 6

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

        /**
         * A foreign display name reaches the wire as one component, or not at all. This
         * is a client-side rejection — no server was asked — so it is not an
         * [NfsFailure]: it matches the argument checks the same methods already make on
         * a malformed documentId, and a frontend maps it to EINVAL or its own
         * illegal-argument answer.
         */
        fun component(name: String): String =
            requireNotNull(PathCodec.componentOf(name)) { "unusable name" }

        fun getfh(): nfs_argop4 = nfs_argop4().apply { argop = nfs_opnum4.OP_GETFH }

        /** Hand-built because it has to follow a hand-built operation in the same tail. */
        fun getattr(): nfs_argop4 = nfs_argop4().apply {
            argop = nfs_opnum4.OP_GETATTR
            opgetattr = GETATTR4args().apply { attr_request = bitmap4.of(*Fattr4Codec.REQUEST) }
        }

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

/**
 * Statuses for which destroying a just-built session buys nothing, so [establish] drops
 * it instead: a session ID "unknown to the server" or one that "does not accept new
 * requests" (RFC 8881 section 15.1.11), and a principal barred from the state it named,
 * which DESTROY_SESSION under that same principal answers again (sections 15.1.6, 15.2).
 * Every other status the RECLAIM_COMPLETE and SEQUENCE rows of section 15.2 allow leaves
 * a live session behind, and that one is destroyed.
 */
private val TEARDOWN_POINTLESS = intArrayOf(
    nfsstat.NFSERR_DEADSESSION,
    nfsstat.NFSERR_WRONG_CRED,
    nfsstat.NFSERR_BADSESSION,
)

private val RECOVERABLE = SESSION_LOST + intArrayOf(nfsstat.NFSERR_STALE, nfsstat.NFSERR_FHEXPIRED)

private val RETRY_AFTER_DELAY = intArrayOf(nfsstat.NFSERR_DELAY, nfsstat.NFSERR_GRACE)

/** What a server answers a READDIR whose cookie its verifier no longer vouches for. */
private val COOKIE_LOST = intArrayOf(nfsstat.NFSERR_NOT_SAME, nfsstat.NFSERR_BAD_COOKIE)

/**
 * Whether a COMPOUND that came back with [status] may be rebuilt and re-sent.
 *
 * With [nonIdempotent] the answer narrows to statuses the server produces BEFORE the
 * operation can have had any effect, which is what makes a retry safe rather than a
 * second execution: a session error rejects the COMPOUND at its leading SEQUENCE, and
 * STALE or FHEXPIRED says the object the operation names does not exist. RFC 5661
 * section 15 terminates a COMPOUND at its first failing operation and section 16.2.3
 * makes the COMPOUND status that operation's status, so a replied error is always an
 * operation that did not apply.
 *
 * Note the test is "produced before any effect", NOT "only SEQUENCE, PUTFH or LOOKUP
 * return it" — section 15.4 lists STALE and FHEXPIRED as valid returns of REMOVE,
 * SETATTR and WRITE too. NFS4ERR_EXPIRED is the exclusion because it is the one
 * recoverable status that says nothing about effect: section 15.2 has OPEN, SETATTR and
 * WRITE return it themselves, on a lease that expired around the operation.
 */
internal fun recoverable(status: Int, nonIdempotent: Boolean): Boolean =
    status in RECOVERABLE && !(nonIdempotent && status == nfsstat.NFSERR_EXPIRED)

/** Whether the server asked for a plain retry, which means it performed nothing. */
internal fun retryAfterDelay(status: Int): Boolean = status in RETRY_AFTER_DELAY

/**
 * Maps the server's status onto the seam's failures. Every status a frontend has to act
 * on differently gets its own case: NOTEMPTY because a recursive delete treats it as
 * ordinary, NOSPC and DQUOT because they must not look like an I/O fault. What is left
 * in [NfsFailure.Server] carries the status name in its message for a human, not for a
 * caller to match on.
 *
 * Top-level rather than a member of the wrapper that calls it so a test can hold it
 * against the v3 table with no server and no session.
 */
internal fun failureForV4(status: Int, what: String): NfsFailure = when (status) {
    nfsstat.NFSERR_ACCESS, nfsstat.NFSERR_PERM, nfsstat.NFSERR_ROFS ->
        NfsFailure.PermissionDenied(what)
    nfsstat.NFSERR_NOENT, nfsstat.NFSERR_NOTDIR -> NfsFailure.NotFound(what)
    nfsstat.NFSERR_EXIST -> NfsFailure.AlreadyExists(what)
    nfsstat.NFSERR_NOTEMPTY -> NfsFailure.DirectoryNotEmpty(what)
    nfsstat.NFSERR_NOSPC, nfsstat.NFSERR_DQUOT -> NfsFailure.OutOfSpace(what)
    nfsstat.NFSERR_NOTSUPP -> NfsFailure.Unsupported(what)
    else -> NfsFailure.Server("${nfsstat.toString(status)}: $what")
}

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
