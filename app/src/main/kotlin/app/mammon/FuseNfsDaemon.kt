package app.mammon

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Serves the FUSE kernel protocol on an already-mounted connection, answering each
 * request out of an [NfsSession]. Read-only: every opcode that would change the export
 * is refused, per [Fuse.refusal].
 *
 * Paths live in [NodeTable], because the protocol addresses objects by nodeid and never
 * by path. Open files and open directories are keyed by handles this class hands out,
 * and a directory's entries are snapshotted at OPENDIR so a page can be served without
 * the export having to hold still.
 *
 * Workers read the device concurrently, which is safe by construction: each request
 * arrives whole in one read, each reply leaves whole in one write, and the only shared
 * mutable state is the node table and the two handle maps.
 */
class FuseNfsDaemon(
    private val session: NfsSession,
    private val device: FuseDevice,
    private val uid: Int,
    private val gid: Int,
    private val workers: Int = 4,
    private val log: (String) -> Unit = {},
) {

    private class Listing(val rows: List<DirRow>, val allocated: List<Long>)

    private val nodes = NodeTable()

    private val handles = Any()
    private val openFiles = HashMap<Long, NfsFile>()
    private val openDirs = HashMap<Long, Listing>()
    private var nextHandle = 1L

    @Volatile private var running = true

    private val reads = AtomicLong()
    private val readBytes = AtomicLong()
    private val largestRead = AtomicLong()

    /**
     * Answers FUSE_INIT on the calling thread before any worker starts, so the
     * negotiated limits are in force for every request that follows, then serves until
     * the kernel closes the connection.
     */
    fun serve() {
        val request = ByteArray(BUFFER)
        val reply = ByteArray(BUFFER)
        val n = device.read(request)
        if (n < Fuse.IN_HEADER_SIZE) throw IOException("fuse connection closed before init")
        dispatch(request, n, reply)

        val extra = (2..workers).map { i -> thread(name = "fuse-$i", isDaemon = false) { loop() } }
        loop()
        extra.forEach { it.join() }
        log("served ${reads.get()} READs, ${readBytes.get()} bytes, largest ${largestRead.get()}")
    }

    private fun loop() {
        val request = ByteArray(BUFFER)
        val reply = ByteArray(BUFFER)
        while (running) {
            val n = try {
                device.read(request)
            } catch (e: IOException) {
                log("device closed: ${e.message}")
                break
            }
            if (n < Fuse.IN_HEADER_SIZE) break
            try {
                dispatch(request, n, reply)
            } catch (e: Throwable) {
                val unique = ByteBuffer.wrap(request).order(Fuse.ORDER).getLong(8)
                log("op failed: ${e.stackTraceToString()}")
                runCatching { fail(unique, Fuse.EIO, reply) }
            }
        }
        running = false
    }

    private fun dispatch(request: ByteArray, size: Int, reply: ByteArray) {
        val head = ByteBuffer.wrap(request).order(Fuse.ORDER)
        val opcode = head.getInt(4)
        val unique = head.getLong(8)
        val nodeid = head.getLong(16)
        val body = Fuse.IN_HEADER_SIZE
        if (TRACE) log("op=$opcode nodeid=$nodeid unique=$unique size=$size")

        when (opcode) {
            Fuse.OP_INIT -> init(head, unique, reply)
            Fuse.OP_LOOKUP -> lookup(nodeid, cstring(request, body, size), unique, reply)
            Fuse.OP_GETATTR -> getattr(nodeid, unique, reply)
            Fuse.OP_OPENDIR -> opendir(nodeid, unique, reply)
            Fuse.OP_READDIR -> readdir(head, unique, reply, plus = false)
            Fuse.OP_READDIRPLUS -> readdir(head, unique, reply, plus = true)
            Fuse.OP_RELEASEDIR -> releasedir(head, unique, reply)
            Fuse.OP_OPEN -> open(nodeid, head.getInt(body), unique, reply)
            Fuse.OP_READ -> read(head, unique, reply)
            Fuse.OP_RELEASE -> release(head, unique, reply)
            Fuse.OP_STATFS -> statfs(unique, reply)
            Fuse.OP_ACCESS -> access(head.getInt(body), unique, reply)
            Fuse.OP_FORGET -> forget(nodeid, head.getLong(body))
            Fuse.OP_BATCH_FORGET -> batchForget(head)
            Fuse.OP_FLUSH, Fuse.OP_FSYNC, Fuse.OP_FSYNCDIR -> ok(unique, reply)
            Fuse.OP_INTERRUPT, Fuse.OP_NOTIFY_REPLY -> Unit
            Fuse.OP_DESTROY -> {
                ok(unique, reply)
                running = false
            }
            else -> fail(unique, Fuse.refusal(opcode), reply)
        }
    }

    private fun init(head: ByteBuffer, unique: Long, reply: ByteArray) {
        val body = Fuse.IN_HEADER_SIZE
        val major = head.getInt(body)
        val minor = head.getInt(body + 4)
        val readahead = head.getInt(body + 8)
        val flags = head.getInt(body + 12)
        if (major < Fuse.MAJOR) {
            fail(unique, Fuse.EPROTO, reply)
            return
        }
        val out = zeroed(reply, Fuse.INIT_SIZE)
        val at = Fuse.OUT_HEADER_SIZE
        out.putInt(at, Fuse.MAJOR)
        out.putInt(at + 4, minOf(minor, Fuse.MINOR))
        out.putInt(at + 8, minOf(readahead, NFS_READ_CHUNK))
        out.putInt(at + 12, WANTED_FLAGS and flags)
        out.putShort(at + 16, MAX_BACKGROUND)
        out.putShort(at + 18, CONGESTION_THRESHOLD)
        out.putInt(at + 20, MAX_WRITE)
        out.putInt(at + 24, TIME_GRAN_NS)
        out.putShort(at + 28, (NFS_READ_CHUNK / Fuse.PAGE_SIZE).toShort())
        send(unique, Fuse.INIT_SIZE, reply)
        log("init: kernel $major.$minor, flags 0x${Integer.toHexString(flags)}, serving as $uid:$gid")
    }

    private fun lookup(parent: Long, name: String, unique: Long, reply: ByteArray) {
        val parentPath = nodes.pathOf(parent) ?: return fail(unique, Fuse.ENOENT, reply)
        val path = PathCodec.childPath(parentPath, name) ?: return fail(unique, Fuse.ENOENT, reply)
        val docId = PathCodec.docIdFor(path) ?: return fail(unique, Fuse.ENOENT, reply)
        val attrs = session.stat(docId) ?: return fail(unique, Fuse.ENOENT, reply)
        val id = nodes.lookedUp(path)
        val out = zeroed(reply, Fuse.ENTRY_OUT_SIZE)
        putEntry(out, Fuse.OUT_HEADER_SIZE, id, attrs)
        send(unique, Fuse.ENTRY_OUT_SIZE, reply)
    }

    private fun getattr(nodeid: Long, unique: Long, reply: ByteArray) {
        val path = nodes.pathOf(nodeid) ?: return fail(unique, Fuse.ENOENT, reply)
        val docId = PathCodec.docIdFor(path) ?: return fail(unique, Fuse.ENOENT, reply)
        val attrs = session.stat(docId) ?: return fail(unique, Fuse.ENOENT, reply)
        val out = zeroed(reply, Fuse.ATTR_OUT_SIZE)
        val at = Fuse.OUT_HEADER_SIZE
        out.putLong(at, TTL_SECONDS)
        putAttr(out, at + 16, nodeid, attrs)
        send(unique, Fuse.ATTR_OUT_SIZE, reply)
    }

    private fun opendir(nodeid: Long, unique: Long, reply: ByteArray) {
        val path = nodes.pathOf(nodeid) ?: return fail(unique, Fuse.ENOENT, reply)
        val docId = PathCodec.docIdFor(path) ?: return fail(unique, Fuse.ENOENT, reply)
        val children = session.list(docId)

        val rows = ArrayList<DirRow>(children.size + 2)
        rows += DirRow(DOT, nodeid, Fuse.DT_DIR, null)
        rows += DirRow(DOTDOT, nodeid, Fuse.DT_DIR, null)
        val allocated = ArrayList<Long>(children.size)
        for (child in children) {
            val id = nodes.intern(child.path)
            allocated += id
            rows += DirRow(
                child.path.substringAfterLast('/').toByteArray(StandardCharsets.UTF_8),
                id,
                if (child.attributes.isDirectory) Fuse.DT_DIR else Fuse.DT_REG,
                child.attributes,
            )
        }
        val handle = synchronized(handles) {
            nextHandle++.also { openDirs[it] = Listing(rows, allocated) }
        }
        val out = zeroed(reply, Fuse.OPEN_OUT_SIZE)
        out.putLong(Fuse.OUT_HEADER_SIZE, handle)
        send(unique, Fuse.OPEN_OUT_SIZE, reply)
    }

    /**
     * Serves one page of a snapshot taken at OPENDIR: the offset the kernel echoes
     * back is the index after the last entry it accepted, so paging needs no server
     * cookie and a concurrent change on the export cannot desynchronise the walk.
     */
    private fun readdir(head: ByteBuffer, unique: Long, reply: ByteArray, plus: Boolean) {
        val body = Fuse.IN_HEADER_SIZE
        val handle = head.getLong(body)
        val offset = head.getLong(body + 8).toInt()
        val budget = head.getInt(body + 16)
        val listing = synchronized(handles) { openDirs[handle] }
            ?: return fail(unique, Fuse.EBADF, reply)

        val page = Dirents.pack(
            listing.rows,
            offset,
            budget,
            plus,
            reply,
            Fuse.OUT_HEADER_SIZE,
        ) { out, at, ino, attrs ->
            // Each READDIRPLUS entry the kernel accepts is a lookup it owes a FORGET for.
            nodes.countLookup(ino)
            putEntry(out, at, ino, attrs)
        }
        send(unique, page.bytes, reply)
    }

    private fun releasedir(head: ByteBuffer, unique: Long, reply: ByteArray) {
        val handle = head.getLong(Fuse.IN_HEADER_SIZE)
        val listing = synchronized(handles) { openDirs.remove(handle) }
        listing?.allocated?.forEach(nodes::releaseUnreferenced)
        ok(unique, reply)
    }

    private fun open(nodeid: Long, flags: Int, unique: Long, reply: ByteArray) {
        if (flags and Fuse.O_ACCMODE != 0) return fail(unique, Fuse.EROFS, reply)
        val path = nodes.pathOf(nodeid) ?: return fail(unique, Fuse.ENOENT, reply)
        val docId = PathCodec.docIdFor(path) ?: return fail(unique, Fuse.ENOENT, reply)
        val file = try {
            session.openFile(docId)
        } catch (e: IOException) {
            return fail(unique, Fuse.ENOENT, reply)
        }
        val handle = synchronized(handles) { nextHandle++.also { openFiles[it] = file } }
        val out = zeroed(reply, Fuse.OPEN_OUT_SIZE)
        out.putLong(Fuse.OUT_HEADER_SIZE, handle)
        out.putInt(Fuse.OUT_HEADER_SIZE + 8, Fuse.FOPEN_KEEP_CACHE)
        send(unique, Fuse.OPEN_OUT_SIZE, reply)
    }

    /**
     * A short reply is how FUSE spells end of file, so anything the server truncates
     * mid-file must be refetched here; handing back the server's own short READ would
     * silently zero-fill the caller's page.
     */
    private fun read(head: ByteBuffer, unique: Long, reply: ByteArray) {
        val body = Fuse.IN_HEADER_SIZE
        val handle = head.getLong(body)
        val offset = head.getLong(body + 8)
        val wanted = minOf(head.getInt(body + 16), BUFFER - Fuse.OUT_HEADER_SIZE)
        val file = synchronized(handles) { openFiles[handle] }
            ?: return fail(unique, Fuse.EBADF, reply)

        var got = 0
        while (got < wanted) {
            val n = file.readAt(offset + got, reply, Fuse.OUT_HEADER_SIZE + got, wanted - got)
            if (n <= 0) break
            got += n
        }
        reads.incrementAndGet()
        readBytes.addAndGet(got.toLong())
        largestRead.accumulateAndGet(got.toLong(), ::maxOf)
        send(unique, got, reply)
    }

    private fun release(head: ByteBuffer, unique: Long, reply: ByteArray) {
        val handle = head.getLong(Fuse.IN_HEADER_SIZE)
        synchronized(handles) { openFiles.remove(handle) }?.close()
        ok(unique, reply)
    }

    private fun statfs(unique: Long, reply: ByteArray) {
        val out = zeroed(reply, Fuse.KSTATFS_SIZE)
        val at = Fuse.OUT_HEADER_SIZE
        out.putInt(at + 40, Fuse.PAGE_SIZE)
        out.putInt(at + 44, MAX_NAME_LENGTH)
        out.putInt(at + 48, Fuse.PAGE_SIZE)
        send(unique, Fuse.KSTATFS_SIZE, reply)
    }

    private fun access(mask: Int, unique: Long, reply: ByteArray) {
        if (mask and W_OK != 0) fail(unique, Fuse.EROFS, reply) else ok(unique, reply)
    }

    private fun forget(nodeid: Long, count: Long) = nodes.forget(nodeid, count)

    private fun batchForget(head: ByteBuffer) {
        val body = Fuse.IN_HEADER_SIZE
        val count = head.getInt(body)
        for (i in 0 until count) {
            val at = body + 8 + i * 16
            nodes.forget(head.getLong(at), head.getLong(at + 8))
        }
    }

    /**
     * NFS reports only type, size and mtime, so mode is synthesised read-only and
     * ownership is the mounting identity; nothing downstream of SAF ever needed more.
     */
    private fun putAttr(out: ByteBuffer, at: Int, ino: Long, attrs: NodeAttrs) {
        val seconds = Math.floorDiv(attrs.lastModifiedMillis, 1000L)
        val nanos = (Math.floorMod(attrs.lastModifiedMillis, 1000L) * 1_000_000L).toInt()
        out.putLong(at, ino)
        out.putLong(at + 8, attrs.size)
        out.putLong(at + 16, (attrs.size + 511) / 512)
        out.putLong(at + 24, seconds)
        out.putLong(at + 32, seconds)
        out.putLong(at + 40, seconds)
        out.putInt(at + 48, nanos)
        out.putInt(at + 52, nanos)
        out.putInt(at + 56, nanos)
        out.putInt(at + 60, if (attrs.isDirectory) Fuse.S_IFDIR or DIR_MODE else Fuse.S_IFREG or FILE_MODE)
        out.putInt(at + 64, if (attrs.isDirectory) 2 else 1)
        out.putInt(at + 68, uid)
        out.putInt(at + 72, gid)
        out.putInt(at + 80, Fuse.PAGE_SIZE)
    }

    private fun putEntry(out: ByteBuffer, at: Int, nodeid: Long, attrs: NodeAttrs) {
        out.putLong(at, nodeid)
        out.putLong(at + 16, TTL_SECONDS)
        out.putLong(at + 24, TTL_SECONDS)
        putAttr(out, at + 40, nodeid, attrs)
    }

    private fun zeroed(reply: ByteArray, payload: Int): ByteBuffer {
        reply.fill(0, Fuse.OUT_HEADER_SIZE, Fuse.OUT_HEADER_SIZE + payload)
        return ByteBuffer.wrap(reply).order(Fuse.ORDER)
    }

    private fun ok(unique: Long, reply: ByteArray) = send(unique, 0, reply)

    private fun send(unique: Long, payload: Int, reply: ByteArray) =
        device.write(reply, Fuse.reply(reply, unique, payload))

    private fun fail(unique: Long, errno: Int, reply: ByteArray) =
        device.write(reply, Fuse.reply(reply, unique, 0, errno))

    private fun cstring(buf: ByteArray, from: Int, limit: Int): String {
        var end = from
        while (end < limit && buf[end] != 0.toByte()) end++
        return String(buf, from, end - from, StandardCharsets.UTF_8)
    }

    private companion object {
        val TRACE = System.getenv("FUSE_TRACE") != null

        /** Must hold the largest reply, which is one READ of a full [NFS_READ_CHUNK]. */
        const val BUFFER = NFS_READ_CHUNK + 64 * 1024

        const val MAX_WRITE = 128 * 1024
        const val MAX_BACKGROUND: Short = 16
        const val CONGESTION_THRESHOLD: Short = 12
        const val MAX_NAME_LENGTH = 255
        const val W_OK = 2

        /** NFS carries mtime in milliseconds, so finer kernel timestamps would be a lie. */
        const val TIME_GRAN_NS = 1_000_000

        /** Long enough to collapse the GETATTR storm behind `ls -l`, short enough to notice a change. */
        const val TTL_SECONDS = 5L

        const val DIR_MODE = 0b101_101_101
        const val FILE_MODE = 0b100_100_100

        val WANTED_FLAGS = Fuse.INIT_FLAG_ASYNC_READ or
            Fuse.INIT_FLAG_BIG_WRITES or
            Fuse.INIT_FLAG_MAX_PAGES or
            Fuse.INIT_FLAG_DO_READDIRPLUS or
            Fuse.INIT_FLAG_READDIRPLUS_AUTO or
            Fuse.INIT_FLAG_PARALLEL_DIROPS

        val DOT = ".".toByteArray(StandardCharsets.UTF_8)
        val DOTDOT = "..".toByteArray(StandardCharsets.UTF_8)
    }
}
