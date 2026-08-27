package app.mammon

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Serves the FUSE kernel protocol on an already-mounted connection, answering each
 * request out of an [NfsSession]. Reads and writes both: an opcode the backend cannot
 * serve is refused per [Fuse.refusal], and one the SERVER refuses answers the errno its
 * [NfsFailure] maps to.
 *
 * Write-through by design — see WANTED_FLAGS for why FUSE_WRITEBACK_CACHE is absent.
 * Every WRITE becomes one synchronous backend write, so a failure is reported by the
 * WRITE that caused it, which is where `write(2)` sees it. Nothing is deferred to FLUSH,
 * whose return value is `close(2)`'s, and nothing to RELEASE, whose return value the VFS
 * discards.
 *
 * Paths live in [NodeTable], because the protocol addresses objects by nodeid and never
 * by path. Open files and open directories are keyed by handles this class hands out,
 * and a directory's entries are snapshotted at OPENDIR so a page can be served without
 * the export having to hold still.
 *
 * Workers read the device concurrently, which is safe by construction: each request
 * arrives whole in one read, each reply leaves whole in one write, the node table and the
 * two handle maps are only ever touched under a monitor, and a handle's own fields are
 * immutable and safely published by the monitor that inserted it — so reading them
 * afterwards needs no lock, and must not take one across a network call.
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

    /** The access mode rides the handle: a WRITE against a read-only open is EBADF, and
     *  only the handle knows which kind of open it came from. */
    private class OpenFile(val file: NfsFile, val writable: Boolean)

    private val nodes = NodeTable()

    private val handles = Any()
    private val openFiles = HashMap<Long, OpenFile>()
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
            Fuse.OP_WRITE -> write(head, request, unique, reply)
            Fuse.OP_RELEASE -> release(head, unique, reply)
            Fuse.OP_SETATTR -> setattr(head, nodeid, unique, reply)
            Fuse.OP_CREATE -> create(head, request, nodeid, size, unique, reply)
            Fuse.OP_MKDIR -> mkdir(request, nodeid, size, unique, reply)
            Fuse.OP_UNLINK -> unlink(nodeid, cstring(request, body, size), unique, reply)
            Fuse.OP_RMDIR -> rmdir(nodeid, cstring(request, body, size), unique, reply)
            Fuse.OP_STATFS -> statfs(unique, reply)
            Fuse.OP_ACCESS -> access(head.getInt(body), unique, reply)
            Fuse.OP_FORGET -> forget(nodeid, head.getLong(body))
            Fuse.OP_BATCH_FORGET -> batchForget(head)
            // Distinct handlers on purpose: FLUSH's status is what close(2) returns and
            // FSYNC's what fsync(2) does, so collapsing them hides which one answered.
            Fuse.OP_FLUSH -> flush(head, unique, reply)
            Fuse.OP_FSYNC -> fsync(head, unique, reply)
            Fuse.OP_FSYNCDIR -> ok(unique, reply)
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

    /**
     * O_TRUNC never arrives: the kernel strips it unless FUSE_ATOMIC_O_TRUNC was
     * negotiated, and it deliberately was not, so a truncating open reaches us as a
     * separate SETATTR with FATTR_SIZE. O_APPEND is likewise resolved by the kernel into
     * an absolute offset before the WRITE is built.
     */
    private fun open(nodeid: Long, flags: Int, unique: Long, reply: ByteArray) {
        val writable = flags and Fuse.O_ACCMODE != 0
        if (writable && !session.implementsWrites) return fail(unique, Fuse.EROFS, reply)
        val path = nodes.pathOf(nodeid) ?: return fail(unique, Fuse.ENOENT, reply)
        val docId = PathCodec.docIdFor(path) ?: return fail(unique, Fuse.ENOENT, reply)
        val opened = try {
            session.openFile(docId)
        } catch (e: IOException) {
            return fail(unique, errnoFor(e), reply)
        }
        val handle = synchronized(handles) {
            nextHandle++.also { openFiles[it] = OpenFile(opened.file, writable) }
        }
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
        val open = synchronized(handles) { openFiles[handle] }
            ?: return fail(unique, Fuse.EBADF, reply)

        var got = 0
        while (got < wanted) {
            val n = open.file.readAt(offset + got, reply, Fuse.OUT_HEADER_SIZE + got, wanted - got)
            if (n <= 0) break
            got += n
        }
        reads.incrementAndGet()
        readBytes.addAndGet(got.toLong())
        largestRead.accumulateAndGet(got.toLong(), ::maxOf)
        send(unique, got, reply)
    }

    /** The VFS discards this status, which is exactly why nothing is reported here. */
    private fun release(head: ByteBuffer, unique: Long, reply: ByteArray) {
        val handle = head.getLong(Fuse.IN_HEADER_SIZE)
        synchronized(handles) { openFiles.remove(handle) }?.file?.close()
        ok(unique, reply)
    }

    /**
     * Writes are synchronous: the backend commits before replying, so the count in this
     * reply is the whole truth about the write and `write(2)` sees any failure directly.
     *
     * The loop is what makes a short backend write invisible to the caller. The kernel
     * does not re-issue the remainder: buffered writes turn a short reply into EIO
     * (`fuse_perform_write` breaks out of its loop and sets it), and direct writes report
     * a genuinely short `write(2)`. So a server that takes fewer bytes than asked must be
     * looped over here or `cp` sees an error for a write that was merely partial.
     */
    private fun write(head: ByteBuffer, request: ByteArray, unique: Long, reply: ByteArray) {
        val body = Fuse.IN_HEADER_SIZE
        val handle = head.getLong(body)
        val offset = head.getLong(body + 8)
        val size = head.getInt(body + 16)
        val from = body + Fuse.WRITE_IN_SIZE
        val open = synchronized(handles) { openFiles[handle] }
            ?: return fail(unique, Fuse.EBADF, reply)
        if (!open.writable) return fail(unique, Fuse.EBADF, reply)

        var done = 0
        try {
            while (done < size) {
                val n = open.file.writeAt(offset + done, request, from + done, size - done)
                if (n <= 0) break
                done += n
            }
        } catch (e: IOException) {
            return fail(unique, errnoFor(e), reply)
        }
        // A reply of zero for a non-zero request is not a short write the caller can
        // retry: on the direct path the kernel hands that 0 straight to write(2), and a
        // write-until-done caller spins on it. Nothing moved, so it is an error.
        if (done < size) return fail(unique, Fuse.EIO, reply)
        val out = zeroed(reply, Fuse.WRITE_OUT_SIZE)
        out.putInt(Fuse.OUT_HEADER_SIZE, done)
        send(unique, Fuse.WRITE_OUT_SIZE, reply)
    }

    /**
     * FATTR_MODE, FATTR_UID and FATTR_GID are accepted and ignored rather than refused:
     * the backend stores none of them, and `cp -p` and every archive extractor send mode
     * beside size. Failing the request would break them for an attribute this filesystem
     * never claimed to keep. Unknown bits are ignored on the same grounds.
     */
    private fun setattr(head: ByteBuffer, nodeid: Long, unique: Long, reply: ByteArray) {
        val body = Fuse.IN_HEADER_SIZE
        val valid = head.getInt(body)
        val path = nodes.pathOf(nodeid) ?: return fail(unique, Fuse.ENOENT, reply)
        val docId = PathCodec.docIdFor(path) ?: return fail(unique, Fuse.ENOENT, reply)

        val size =
            if (valid and Fuse.FATTR_SIZE != 0) head.getLong(body + Fuse.SETATTR_SIZE_AT) else null
        val mtime = when {
            valid and Fuse.FATTR_MTIME != 0 ->
                head.getLong(body + Fuse.SETATTR_MTIME_AT) * 1000L +
                    head.getInt(body + Fuse.SETATTR_MTIMENSEC_AT) / 1_000_000L
            valid and Fuse.FATTR_MTIME_NOW != 0 -> System.currentTimeMillis()
            else -> null
        }
        val attrs = try {
            session.setAttributes(docId, size, mtime)
        } catch (e: IOException) {
            return fail(unique, errnoFor(e), reply)
        }
        val out = zeroed(reply, Fuse.ATTR_OUT_SIZE)
        val at = Fuse.OUT_HEADER_SIZE
        out.putLong(at, TTL_SECONDS)
        putAttr(out, at + 16, nodeid, attrs)
        send(unique, Fuse.ATTR_OUT_SIZE, reply)
    }

    /**
     * CREATE answers TWO structs back to back — fuse_entry_out then fuse_open_out, which
     * the kernel spells as `out_numargs = 2`. Any other reply length is refused by
     * copy_out_args and the open fails EIO, so the size comes from one named constant.
     *
     * The kernel only sends CREATE after its own LOOKUP found nothing, so the GUARDED4
     * create underneath is right: EEXIST here means the name appeared underneath us, and
     * the kernel invalidates its entry and reports it.
     */
    private fun create(
        head: ByteBuffer,
        request: ByteArray,
        parent: Long,
        size: Int,
        unique: Long,
        reply: ByteArray,
    ) {
        val name = cstring(request, Fuse.IN_HEADER_SIZE + Fuse.CREATE_IN_SIZE, size)
        val writable = head.getInt(Fuse.IN_HEADER_SIZE) and Fuse.O_ACCMODE != 0
        val parentPath = nodes.pathOf(parent) ?: return fail(unique, Fuse.ENOENT, reply)
        val parentId = PathCodec.docIdFor(parentPath) ?: return fail(unique, Fuse.ENOENT, reply)
        val path = PathCodec.childPath(parentPath, name) ?: return fail(unique, Fuse.EINVAL, reply)

        // One round trip: the create COMPOUND carries the handle this open needs AND the
        // attributes the reply owes, so nothing here re-walks the path.
        val created = try {
            session.createFile(parentId, name)
        } catch (e: IOException) {
            return fail(unique, errnoFor(e), reply)
        } catch (e: IllegalArgumentException) {
            return fail(unique, Fuse.EINVAL, reply)
        }
        // The kernel rejects a CREATE reply whose attributes are not a regular file.
        if (created.attributes.isDirectory) {
            created.file.close()
            return fail(unique, Fuse.EIO, reply)
        }
        // Retiring the name here, not only in removeEntry, is what actually holds the
        // no-aliasing invariant: a remove that failed, or one still between its reply and
        // its detach on another worker, leaves the old binding in place. The kernel only
        // sends CREATE after its own LOOKUP found the name absent, so any binding that
        // survives to this point is stale by construction and safe to drop.
        nodes.detach(path, subtree = false)
        val id = nodes.lookedUp(path)
        val handle = synchronized(handles) {
            nextHandle++.also { openFiles[it] = OpenFile(created.file, writable = writable) }
        }
        val out = zeroed(reply, Fuse.CREATE_OUT_SIZE)
        putEntry(out, Fuse.OUT_HEADER_SIZE, id, created.attributes)
        out.putLong(Fuse.OUT_HEADER_SIZE + Fuse.ENTRY_OUT_SIZE, handle)
        out.putInt(Fuse.OUT_HEADER_SIZE + Fuse.ENTRY_OUT_SIZE + 8, Fuse.FOPEN_KEEP_CACHE)
        send(unique, Fuse.CREATE_OUT_SIZE, reply)
    }

    /** The reply's mode must carry S_IFDIR, or create_new_entry answers EIO; [putAttr]
     *  derives it from the attributes the server reports for the new directory. */
    private fun mkdir(
        request: ByteArray,
        parent: Long,
        size: Int,
        unique: Long,
        reply: ByteArray,
    ) {
        val name = cstring(request, Fuse.IN_HEADER_SIZE + Fuse.MKDIR_IN_SIZE, size)
        val parentPath = nodes.pathOf(parent) ?: return fail(unique, Fuse.ENOENT, reply)
        val parentId = PathCodec.docIdFor(parentPath) ?: return fail(unique, Fuse.ENOENT, reply)
        val path = PathCodec.childPath(parentPath, name) ?: return fail(unique, Fuse.EINVAL, reply)

        val attrs = try {
            session.makeDirectory(parentId, name)
        } catch (e: IOException) {
            return fail(unique, errnoFor(e), reply)
        } catch (e: IllegalArgumentException) {
            return fail(unique, Fuse.EINVAL, reply)
        }
        if (!attrs.isDirectory) return fail(unique, Fuse.EIO, reply)
        // Same reasoning as create: a surviving binding for a name the kernel just found
        // absent is stale, and reusing its id would hand the kernel a dead node.
        nodes.detach(path, subtree = false)
        val id = nodes.lookedUp(path)
        val out = zeroed(reply, Fuse.ENTRY_OUT_SIZE)
        putEntry(out, Fuse.OUT_HEADER_SIZE, id, attrs)
        send(unique, Fuse.ENTRY_OUT_SIZE, reply)
    }

    /**
     * The VFS resolves the type before FUSE is asked — `do_unlinkat` answers EISDIR for a
     * directory and `vfs_rmdir` requires one — so neither handler re-checks it, and the
     * backend's single REMOVE serves both.
     *
     * [NodeTable.detach] is what stops the removed name resolving. Without it a re-created
     * name would keep answering with the dead nodeid.
     */
    private fun unlink(parent: Long, name: String, unique: Long, reply: ByteArray) =
        removeEntry(parent, name, subtree = false, unique, reply)

    private fun rmdir(parent: Long, name: String, unique: Long, reply: ByteArray) =
        removeEntry(parent, name, subtree = true, unique, reply)

    private fun removeEntry(
        parent: Long,
        name: String,
        subtree: Boolean,
        unique: Long,
        reply: ByteArray,
    ) {
        val parentPath = nodes.pathOf(parent) ?: return fail(unique, Fuse.ENOENT, reply)
        val parentId = PathCodec.docIdFor(parentPath) ?: return fail(unique, Fuse.ENOENT, reply)
        val path = PathCodec.childPath(parentPath, name) ?: return fail(unique, Fuse.EINVAL, reply)
        try {
            session.remove(parentId, name)
        } catch (e: IOException) {
            return fail(unique, errnoFor(e), reply)
        } catch (e: IllegalArgumentException) {
            return fail(unique, Fuse.EINVAL, reply)
        }
        nodes.detach(path, subtree)
        ok(unique, reply)
    }

    /**
     * Nothing is outstanding by the time this arrives: every write was committed by the
     * WRITE that carried it, so there is no deferred failure for close(2) to learn about
     * here. The handle is still validated, because a FLUSH on a handle this daemon does
     * not know is a protocol error rather than a success.
     */
    private fun flush(head: ByteBuffer, unique: Long, reply: ByteArray) {
        val handle = head.getLong(Fuse.IN_HEADER_SIZE)
        if (synchronized(handles) { openFiles[handle] } == null) {
            return fail(unique, Fuse.EBADF, reply)
        }
        ok(unique, reply)
    }

    /** Same reasoning as [flush]: write-through leaves nothing to push. */
    private fun fsync(head: ByteBuffer, unique: Long, reply: ByteArray) =
        flush(head, unique, reply)

    /**
     * Maps the seam's typed failures onto errnos. The inner `when` has no `else`, so the
     * sealed hierarchy really is exhaustive here and a new failure type cannot compile
     * into a silent EIO; the outer one keeps a reachable branch for the plain IOExceptions
     * the RPC stack raises.
     *
     * [NfsFailure.Unsupported] is EROFS rather than ENOSYS: it only arrives from a
     * read-only backend, where the filesystem cannot be written at all, which is what
     * `open()` and `access()` already answer. ENOSYS would instead say the operation does
     * not exist, and `mkdir` would print "Function not implemented" for a mount whose real
     * problem is that it is read-only.
     */
    private fun errnoFor(e: IOException): Int = when (e) {
        is NfsFailure -> when (e) {
            is NfsFailure.PermissionDenied -> Fuse.EACCES
            is NfsFailure.NotFound -> Fuse.ENOENT
            is NfsFailure.AlreadyExists -> Fuse.EEXIST
            is NfsFailure.DirectoryNotEmpty -> Fuse.ENOTEMPTY
            is NfsFailure.OutOfSpace -> Fuse.ENOSPC
            is NfsFailure.Unsupported -> Fuse.EROFS
            is NfsFailure.Server -> Fuse.EIO
        }
        else -> Fuse.EIO
    }

    /**
     * The capacity is synthetic and says UNKNOWN, not a measured figure: [NfsSession]
     * exposes no FSSTAT, so the daemon has nothing real to report. It must not be zero —
     * cp, rsync and several file managers read free space before issuing a single write
     * and would refuse a copy the server would have accepted. The truthful answer stays
     * on the write path, where the server's own out-of-space arrives as ENOSPC.
     */
    private fun statfs(unique: Long, reply: ByteArray) {
        val out = zeroed(reply, Fuse.KSTATFS_SIZE)
        val at = Fuse.OUT_HEADER_SIZE
        out.putLong(at, SYNTHETIC_BLOCKS)
        out.putLong(at + 8, SYNTHETIC_BLOCKS)
        out.putLong(at + 16, SYNTHETIC_BLOCKS)
        // files and ffree for the same reason: `df -i` is the same pre-flight check.
        out.putLong(at + 24, SYNTHETIC_BLOCKS)
        out.putLong(at + 32, SYNTHETIC_BLOCKS)
        out.putInt(at + 40, Fuse.PAGE_SIZE)
        out.putInt(at + 44, MAX_NAME_LENGTH)
        out.putInt(at + 48, Fuse.PAGE_SIZE)
        send(unique, Fuse.KSTATFS_SIZE, reply)
    }

    /**
     * Optimistic, and it has to be: the mount carries no `default_permissions`, so the
     * kernel enforces nothing here, and the only authority on whether this identity may
     * write is the server, which answers when the write is attempted. Refusing W_OK is
     * what made every file manager declare the tree read-only.
     */
    private fun access(mask: Int, unique: Long, reply: ByteArray) {
        if (mask and W_OK != 0 && !session.implementsWrites) fail(unique, Fuse.EROFS, reply)
        else ok(unique, reply)
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
     * NFS reports only type, size and mtime, so the mode is this daemon's own policy and
     * ownership is the mounting identity. The mount carries no `default_permissions`, so
     * the kernel enforces none of it — the bits exist for userspace that stats before
     * acting, which is why they have to say writable now that writes are served.
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

        /**
         * Matched to [NFS_READ_CHUNK], so one WRITE carries exactly what one backend
         * WRITE can take, and a large copy costs four times fewer round trips than the
         * 128 KiB this used to advertise.
         */
        const val MAX_WRITE = NFS_READ_CHUNK

        /**
         * Must hold the largest message either way: one WRITE request of [MAX_WRITE] plus
         * its two headers, or one READ reply of the same payload plus one. Written in
         * terms of [MAX_WRITE] rather than checked against it, because the kernel sends
         * requests as large as INIT promised: a buffer too small for one would not fail,
         * it would silently truncate the request and write the wrong bytes. Derived this
         * way, raising [MAX_WRITE] cannot produce that, and the 64 KiB of slack covers
         * both headers many times over along with every smaller opcode.
         */
        const val BUFFER = MAX_WRITE + 64 * 1024
        const val MAX_BACKGROUND: Short = 16
        const val CONGESTION_THRESHOLD: Short = 12
        const val MAX_NAME_LENGTH = 255
        const val W_OK = 2

        /** NFS carries mtime in milliseconds, so finer kernel timestamps would be a lie. */
        const val TIME_GRAN_NS = 1_000_000

        /** Long enough to collapse the GETATTR storm behind `ls -l`, short enough to notice a change. */
        const val TTL_SECONDS = 5L

        /** 0755 and 0644. The w and x bits are what access(2) pre-flights consult
         *  before a create or an unlink, so a read-only pair would refuse writes the
         *  server accepts. The server remains the only real authority. */
        const val DIR_MODE = 0b111_101_101
        const val FILE_MODE = 0b110_100_100

        /** 1 TiB of PAGE_SIZE blocks: large enough that no pre-flight check refuses,
         *  and never presented as measured — see [statfs]. */
        const val SYNTHETIC_BLOCKS = 1L shl 28

        /**
         * FUSE_WRITEBACK_CACHE is deliberately absent, and stays absent. Without it the
         * kernel is write-through: one write(2) becomes one WRITE becomes one committed
         * backend write, ordering is the kernel's, and the daemon holds no dirty state to
         * lose. With it the kernel would defer dirty pages past close(2) and take over
         * i_size — for a backend with no locking and no delegations that trades
         * throughput for silent loss, and it is what makes FLUSH able to report nothing.
         */
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
