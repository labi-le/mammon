package app.mammon

import java.nio.ByteBuffer

/**
 * The nodeid <-> path table, which is the only place a path exists once the mount is up:
 * the FUSE protocol addresses everything by nodeid plus a name, never by a path.
 *
 * Allocation and referencing are deliberately separate operations. The kernel promises a
 * FORGET only for ids it took a reference on — LOOKUP and READDIRPLUS do, a plain
 * READDIR does not, yet READDIR still needs an inode number per child. Reusing the
 * nodeid as that inode number keeps `ls -i` agreeing with a later GETATTR, and the price
 * is that ids a listing merely allocated must be dropped by whoever opened the
 * directory. [releaseUnreferenced] is that drop.
 *
 * A removed name and a forgotten id are also separate, which is what [detach] exists
 * for. The kernel may still hold references to a nodeid whose name UNLINK has just
 * deleted, so the id has to outlive the name: it becomes a tombstone, [pathOf] stops
 * answering, and the name is free for a fresh id. Skipping that step is not a missing
 * feature but a correctness bug — a re-created name would resolve to the dead id, and
 * the kernel would serve it out of the old inode's cache.
 */
internal class NodeTable {

    /** [path] is null once the name is gone: the id survives for the kernel's FORGET,
     *  and is a var rather than a val so a later RENAME can re-key a subtree. */
    private class Node(var path: String?) {
        var lookups = 0L
    }

    private val lock = Any()
    private val byId = HashMap<Long, Node>()
    private val byPath = HashMap<String, Long>()
    private var next = Fuse.ROOT_ID + 1

    init {
        byId[Fuse.ROOT_ID] = Node("/")
        byPath["/"] = Fuse.ROOT_ID
    }

    fun pathOf(nodeid: Long): String? = synchronized(lock) { byId[nodeid]?.path }

    /**
     * Retires the name [path] so a later allocation of it gets a fresh id, keeping the
     * tombstoned node alive while the kernel still holds references to it.
     *
     * [subtree] also retires every descendant name. UNLINK never needs it — a file has
     * no descendants — while RMDIR does, because a listing may have interned children
     * that would otherwise keep aliasing a re-created directory.
     */
    fun detach(path: String, subtree: Boolean) = synchronized(lock) {
        retire(path)
        if (subtree) {
            val prefix = if (path.endsWith("/")) path else "$path/"
            byPath.keys.filter { it.startsWith(prefix) }.forEach(::retire)
        }
    }

    /** Allocates an id without claiming a kernel reference. */
    fun intern(path: String): Long = synchronized(lock) { allocate(path) }

    /** Allocates and claims one kernel reference, which is what a LOOKUP reply owes. */
    fun lookedUp(path: String): Long = synchronized(lock) {
        allocate(path).also { byId[it]!!.lookups++ }
    }

    /** Claims a reference on an already-allocated id, as each READDIRPLUS entry does. */
    fun countLookup(nodeid: Long) = synchronized(lock) {
        byId[nodeid]?.let { it.lookups++ }
        Unit
    }

    fun forget(nodeid: Long, count: Long) = synchronized(lock) {
        byId[nodeid]?.let { it.lookups -= count }
        evictIfUnreferenced(nodeid)
    }

    fun releaseUnreferenced(nodeid: Long) = synchronized(lock) { evictIfUnreferenced(nodeid) }

    /** Every id still addressable by the kernel, tombstones included: leaking one is the
     *  failure mode this table can have, and a tombstone leaks exactly as badly as a
     *  live entry if its FORGET never arrives. */
    val size: Int get() = synchronized(lock) { byId.size }

    private fun allocate(path: String): Long =
        byPath.getOrPut(path) { next++.also { byId[it] = Node(path) } }

    /** The root keeps its name for the same reason it is never evicted: nothing can look
     *  it up again, and an unbound root breaks every path in the mount. */
    private fun retire(path: String) {
        val nodeid = byPath[path] ?: return
        if (nodeid == Fuse.ROOT_ID) return
        byPath.remove(path)
        byId[nodeid]?.path = null
        evictIfUnreferenced(nodeid)
    }

    /**
     * The root is never evicted: nothing can look it up again to get it back.
     *
     * The name is only dropped when it still points at THIS id. Today [retire] always
     * nulls the path first, so the check cannot fire; it is here for the subtree re-key
     * `Node.path` was made a var for, where a node keeps a live path another node may
     * already have taken over. Without it that re-key would silently unbind a live name.
     */
    private fun evictIfUnreferenced(nodeid: Long) {
        if (nodeid == Fuse.ROOT_ID) return
        val node = byId[nodeid] ?: return
        if (node.lookups > 0) return
        byId.remove(nodeid)
        node.path?.let { if (byPath[it] == nodeid) byPath.remove(it) }
    }
}

/** One directory entry ready to be packed; [attrs] is null for the dot names. */
internal class DirRow(
    val name: ByteArray,
    val ino: Long,
    val type: Int,
    val attrs: NodeAttrs?,
)

/**
 * Packs `fuse_dirent` / `fuse_direntplus` records into a reply buffer.
 *
 * The cookie a record carries is the index after itself, so the kernel's next request
 * resumes from a snapshot position rather than a server cookie; that is what lets a
 * listing be taken once at OPENDIR and paged without the export having to hold still.
 */
internal object Dirents {

    /** Bytes written, and the row index the next page must start from. */
    data class Page(val bytes: Int, val nextIndex: Int)

    /**
     * In READDIRPLUS mode every record is a `fuse_direntplus`, the dot names included:
     * the kernel walks the buffer by each record's own length from a fixed field offset,
     * so a bare `fuse_dirent` mixed in desynchronises the whole page. [writeEntry] fills
     * the 128-byte `fuse_entry_out` prefix for rows that have attributes; rows without
     * them keep a zeroed prefix, whose nodeid of 0 tells the kernel to skip the lookup.
     */
    fun pack(
        rows: List<DirRow>,
        from: Int,
        budget: Int,
        plus: Boolean,
        into: ByteArray,
        at: Int,
        writeEntry: (ByteBuffer, Int, Long, NodeAttrs) -> Unit,
    ): Page {
        val out = ByteBuffer.wrap(into).order(Fuse.ORDER)
        val prefix = if (plus) Fuse.ENTRY_OUT_SIZE else 0
        var used = 0
        var index = from.coerceAtLeast(0)
        while (index < rows.size) {
            val row = rows[index]
            val record = Fuse.align8(prefix + Fuse.DIRENT_HEADER_SIZE + row.name.size)
            if (used + record > budget) break
            val start = at + used
            // The buffer is reused across requests, so the alignment padding and any
            // skipped prefix must be cleared or a previous reply's bytes leak into a name.
            into.fill(0, start, start + record)
            if (plus && row.attrs != null) writeEntry(out, start, row.ino, row.attrs)
            val dirent = start + prefix
            out.putLong(dirent, row.ino)
            out.putLong(dirent + 8, (index + 1).toLong())
            out.putInt(dirent + 16, row.name.size)
            out.putInt(dirent + 20, row.type)
            row.name.copyInto(into, dirent + Fuse.DIRENT_HEADER_SIZE)
            used += record
            index++
        }
        return Page(used, index)
    }
}
