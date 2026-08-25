package app.mammon

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.dcache.nfs.ChimeraNFSException
import org.dcache.nfs.nfsstat
import org.dcache.nfs.v4.ClientSession
import org.dcache.nfs.v4.CompoundBuilder
import org.dcache.nfs.v4.Stateids
import org.dcache.nfs.v4.xdr.COMPOUND4args
import org.dcache.nfs.v4.xdr.COMPOUND4res
import org.dcache.nfs.v4.xdr.CREATE_SESSION4args
import org.dcache.nfs.v4.xdr.SEQUENCE4args
import org.dcache.nfs.v4.xdr.callback_sec_parms4
import org.dcache.nfs.v4.xdr.channel_attrs4
import org.dcache.nfs.v4.xdr.clientid4
import org.dcache.nfs.v4.xdr.count4
import org.dcache.nfs.v4.xdr.fattr4
import org.dcache.nfs.v4.xdr.nfs4_prot
import org.dcache.nfs.v4.xdr.nfs_argop4
import org.dcache.nfs.v4.xdr.nfs_fh4
import org.dcache.nfs.v4.xdr.nfs_ftype4
import org.dcache.nfs.v4.xdr.nfs_opnum4
import org.dcache.nfs.v4.xdr.nfs_resop4
import org.dcache.nfs.v4.xdr.slotid4
import org.dcache.nfs.v4.xdr.state_protect_how4
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
 * NFSv4.1 read-only operations over one long-lived session.
 *
 * nfs4j is a server library; only its XDR types and [CompoundBuilder] are used here,
 * with oncrpc4j carrying the RPC. CREATE_SESSION is built by hand because the
 * builder's own channel attributes cap a reply at 8 KiB, which would cut every READ
 * to a sixty-fourth of [NFS_READ_CHUNK].
 *
 * No OPEN state is taken — READ uses the anonymous stateid — so nothing here needs a
 * lease-renewal timer; a session the server has reaped is re-established in place by
 * [resilient] on the next call that trips over it.
 *
 * Every method blocks on network I/O: callers must stay off the main thread.
 */
class NfsV4Access(private val spec: ExportSpec) : NfsSession {

    private class Session(val clientId: clientid4, val slots: ClientSession, val maxOps: Int, val maxRead: Int)

    /**
     * Stable for this instance so a re-established session reclaims its predecessor's
     * state, unique between instances so a probe from the activity cannot make the
     * server treat the provider's live session as a rebooted client and reap it.
     */
    private val ownerId = "$CLIENT_NAME/${UUID.randomUUID()}@${spec.host}:${spec.export}"

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

    init {
        try {
            call = RpcCall(
                nfs4_prot.NFS4_PROGRAM,
                RPC_VERSION,
                AuthSys(UID, GID, CLIENT_NAME),
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
            if (e.status in ABSENT) throw IOException("no such file") else throw e
        }
        val attrs = Fattr4Codec.decode(lastOf(res, nfs_opnum4.OP_GETATTR).opgetattr.resok4.obj_attributes)
        if (attrs == null || !attrs.isRegular) throw IOException("not a regular file")
        return Handle(lastOf(res, nfs_opnum4.OP_GETFH).opgetfh.resok4.`object`)
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
     * Runs [ops] against the export-absolute [path], reached from the export root
     * handle by LOOKUP inside the same COMPOUND, so one operation costs one round
     * trip. A path with more components than the server's negotiated operation budget
     * is walked in batches, which costs a round trip per batch and nothing else.
     *
     * The parameter is not called `build`: inside a CompoundBuilder-receiver lambda
     * that name resolves to the builder's own terminal `build()`, which silently
     * drops the caller's operation.
     */
    private fun compoundAt(path: String, tag: String, ops: CompoundBuilder.() -> Unit): COMPOUND4res {
        val components = path.split('/').filter { it.isNotEmpty() }
        return resilient {
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
                },
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
     * Prepends the SEQUENCE that every NFSv4.1 COMPOUND but session setup needs, and
     * holds one session slot for the round trip. Consumes [args]: its operation array
     * is replaced, so a retry must rebuild it.
     */
    private fun inSession(args: COMPOUND4args, on: Session = session): COMPOUND4res {
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
            return raw(args)
        } finally {
            on.slots.releaseSlot(slot)
        }
    }

    private fun raw(args: COMPOUND4args): COMPOUND4res {
        val res = COMPOUND4res()
        try {
            call.call(nfs4_prot.NFSPROC4_COMPOUND_4, args, res, CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw IOException("nfs4 call timed out", e)
        }
        nfsstat.throwIfNeeded(res.status)
        return res
    }

    /**
     * At most three attempts: one after recovering from a session or handle the server
     * has forgotten, one after a server-requested delay. [op] must rebuild its
     * COMPOUND on every attempt because [inSession] consumes it.
     */
    private fun <T> resilient(op: () -> T): T {
        var recovered = false
        var delayed = false
        while (true) {
            val before = session
            try {
                return op()
            } catch (e: ChimeraNFSException) {
                when {
                    !recovered && e.status in RECOVERABLE -> {
                        recovered = true
                        recover(before, e.status)
                    }
                    !delayed && e.status in RETRY_AFTER_DELAY -> {
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
            // The reply headers share the negotiated response budget with the data.
            (fore.ca_maxresponsesize.value - REPLY_OVERHEAD).coerceIn(1, NFS_READ_CHUNK),
        )
        inSession(compound("reclaim_complete") { withReclaimComplete() }, fresh)
        return fresh
    }

    /**
     * PUTFH + READ in one COMPOUND, so a read costs one round trip and needs no OPEN
     * state; the anonymous stateid is what makes the handle usable from any thread.
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

        override fun close() = Unit
    }

    private companion object {
        const val RPC_VERSION = 4
        const val MINOR_VERSION = 1
        const val CALL_TIMEOUT_MS = 15_000L
        const val RETRY_DELAY_MS = 500L

        /** AUTH_SYS root; an export with root_squash maps it to nobody, which is all a reader needs. */
        const val UID = 0
        const val GID = 0
        const val CLIENT_NAME = "mammon"
        const val CLIENT_DOMAIN = "mammon.app"

        const val MAX_REQUEST_SIZE = 128 * 1024
        const val MAX_RESPONSE_SIZE = 1024 * 1024
        const val BACK_CHANNEL_SIZE = 4096
        const val FORE_MAX_OPS = 64
        const val FORE_MAX_REQUESTS = 8
        const val REPLY_OVERHEAD = 4096

        /** SEQUENCE + PUTFH + the operation the caller asked for. */
        const val OPS_AROUND_LOOKUPS = 3

        const val READDIR_DIR_COUNT = 32 * 1024
        const val READDIR_MAX_COUNT = 64 * 1024

        val ANONYMOUS = Stateids.ZeroStateId()

        val ABSENT = intArrayOf(nfsstat.NFSERR_NOENT, nfsstat.NFSERR_NOTDIR)

        val SESSION_LOST = intArrayOf(
            nfsstat.NFSERR_BADSESSION,
            nfsstat.NFSERR_DEADSESSION,
            nfsstat.NFSERR_STALE_CLIENTID,
            nfsstat.NFSERR_EXPIRED,
            nfsstat.NFSERR_SEQ_MISORDERED,
            nfsstat.NFSERR_BADSLOT,
        )

        val RECOVERABLE = SESSION_LOST + intArrayOf(nfsstat.NFSERR_STALE, nfsstat.NFSERR_FHEXPIRED)

        val RETRY_AFTER_DELAY = intArrayOf(nfsstat.NFSERR_DELAY, nfsstat.NFSERR_GRACE)

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
            res.resarray.lastOrNull { it.resop == opnum }
                ?: throw IOException("nfs4 reply carries no result for operation $opnum")
    }
}

/** The three attributes mammon asks for, before the SAF-facing narrowing to [NodeAttrs]. */
internal data class Attrs4(val type: Int, val size: Long, val mtimeMillis: Long) {
    val isDirectory: Boolean get() = type == nfs_ftype4.NF4DIR
    val isRegular: Boolean get() = type == nfs_ftype4.NF4REG

    /** Symlinks and device nodes are neither, and the provider never lists them. */
    val isListable: Boolean get() = isDirectory || isRegular

    fun toNode(): NodeAttrs = NodeAttrs(isDirectory, size, mtimeMillis)
}

/**
 * Decodes a fattr4 carrying exactly the attributes [Fattr4Codec.REQUEST] asks for.
 * Values are concatenated in ascending bitmap order with no per-attribute length, so
 * one unexpected attribute makes everything after it unparseable: such a fattr4 is
 * reported undecodable rather than guessed at, and its entry is dropped.
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
}

/**
 * AUTH_SYS credential, encoded per RFC 5531 appendix A.
 *
 * oncrpc4j's own RpcAuthTypeUnix cannot be used here: its constructor builds a
 * Subject out of com.sun.security.auth principals, which Android does not ship, so
 * merely constructing it throws NoClassDefFoundError on a device.
 */
internal class AuthSys(private val uid: Int, private val gid: Int, machine: String) : RpcAuth {

    private val machineBytes = machine.toByteArray(StandardCharsets.US_ASCII)
    private val stamp = (System.currentTimeMillis() / 1000).toInt()
    private val verifier = RpcAuthVerifier(RpcAuthType.NONE, ByteArray(0))
    private val subject = Subject()

    /** Body length the opaque_auth header declares: every field below, XDR-padded. */
    private val length =
        4 + 4 + machineBytes.size + ((4 - (machineBytes.size and 3)) and 3) + 4 + 4 + 4 + 4

    override fun type(): Int = RpcAuthType.UNIX

    override fun getVerifier(): RpcAuthVerifier = verifier

    /** Empty on purpose: only oncrpc4j's server side consults it, and mammon is a client. */
    override fun getSubject(): Subject = subject

    override fun xdrEncode(xdr: XdrEncodingStream) {
        xdr.xdrEncodeInt(RpcAuthType.UNIX)
        xdr.xdrEncodeInt(length)
        xdr.xdrEncodeInt(stamp)
        xdr.xdrEncodeDynamicOpaque(machineBytes)
        xdr.xdrEncodeInt(uid)
        xdr.xdrEncodeInt(gid)
        xdr.xdrEncodeIntVector(intArrayOf(gid))
        verifier.xdrEncode(xdr)
    }

    override fun xdrDecode(xdr: XdrDecodingStream): Unit =
        throw UnsupportedOperationException("mammon never receives AUTH_SYS credentials")
}
