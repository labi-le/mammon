package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins what UNLINK and RMDIR need from the node table: a removed name must stop
 * resolving, and must not hand its nodeid to whatever is created there next.
 *
 * The failure this defends against is not a missing feature. Without invalidation the
 * table keys `byPath` on a path that no longer exists, so re-creating the name returns
 * the DEAD nodeid — and the kernel, which still has that inode cached with the old size
 * and mtime, serves the new file out of the old one's cache. The kernel may also still
 * hold references to the detached id, so the id has to outlive the name rather than being
 * deleted with it.
 */
class NodeTableDetachTest {

    @Test fun `a detached name stops resolving`() {
        val table = NodeTable()
        val id = table.lookedUp("/dir/file")

        table.detach("/dir/file", subtree = false)

        assertNull("the nodeid must no longer answer with a path", table.pathOf(id))
    }

    @Test fun `re-creating a detached name allocates a fresh nodeid`() {
        val table = NodeTable()
        val before = table.lookedUp("/dir/file")

        table.detach("/dir/file", subtree = false)
        val after = table.lookedUp("/dir/file")

        assertNotEquals("a re-created name must not inherit the dead id", before, after)
        assertEquals("/dir/file", table.pathOf(after))
    }

    /** The kernel owes a FORGET for every id it referenced, so a referenced id survives
     *  detach as a tombstone rather than being dropped underneath it. */
    @Test fun `a referenced id survives detach until it is forgotten`() {
        val table = NodeTable()
        val id = table.lookedUp("/dir/file")

        table.detach("/dir/file", subtree = false)
        assertEquals("the id must still exist for the pending FORGET", 2, table.size)

        table.forget(id, 1)
        assertEquals(1, table.size)
    }

    @Test fun `an unreferenced id is dropped by detach immediately`() {
        val table = NodeTable()
        table.intern("/dir/file")

        table.detach("/dir/file", subtree = false)

        assertEquals(1, table.size)
    }

    /**
     * The eviction that follows a FORGET must not delete the name if a newer node owns
     * it. This is the aliasing bug in its second form: the old node's path field still
     * remembers the string, so a blind `byPath.remove(node.path)` would unbind the LIVE
     * node and the next lookup of that name would allocate a third id.
     */
    @Test fun `forgetting a detached id leaves a re-created name bound`() {
        val table = NodeTable()
        val dead = table.lookedUp("/dir/file")
        table.detach("/dir/file", subtree = false)
        val live = table.lookedUp("/dir/file")

        table.forget(dead, 1)

        assertEquals("the live node must keep its name", "/dir/file", table.pathOf(live))
        assertEquals(live, table.lookedUp("/dir/file"))
    }

    @Test fun `a file detach leaves unrelated names alone`() {
        val table = NodeTable()
        val keep = table.lookedUp("/dir/other")
        table.lookedUp("/dir/file")

        table.detach("/dir/file", subtree = false)

        assertEquals("/dir/other", table.pathOf(keep))
    }

    /**
     * Every removal takes the subtree form, UNLINK included: the opcode carries the type
     * a cached dentry believed, and a permissive server carries a directory away through
     * REMOVE. A listing may have interned the children, and leaving those names bound
     * would alias them onto a re-created directory's contents.
     */
    @Test fun `a subtree detach retires interned descendants`() {
        val table = NodeTable()
        val dir = table.lookedUp("/dir")
        val child = table.intern("/dir/child")
        val deeper = table.intern("/dir/child/deeper")
        val sibling = table.lookedUp("/dirt")

        table.detach("/dir", subtree = true)

        assertNull(table.pathOf(dir))
        assertNull(table.pathOf(child))
        assertNull(table.pathOf(deeper))
        assertEquals("a name that merely shares a prefix must survive", "/dirt", table.pathOf(sibling))
    }

    /** The narrow form outlived the removals: CREATE and LOOKUP use it to drop one name
     *  that went stale without retiring what a listing interned beneath it. */
    @Test fun `a non-subtree detach leaves descendants bound`() {
        val table = NodeTable()
        val child = table.intern("/dir/child")

        table.detach("/dir", subtree = false)

        assertEquals("/dir/child", table.pathOf(child))
    }

    @Test fun `detaching a name that was never allocated is a no-op`() {
        val table = NodeTable()

        table.detach("/absent", subtree = true)

        assertEquals(1, table.size)
    }

    /** The root has no name to re-create and nothing can look it up again. */
    @Test fun `the root survives a subtree detach of itself`() {
        val table = NodeTable()

        table.detach("/", subtree = true)

        assertEquals("/", table.pathOf(Fuse.ROOT_ID))
    }
}
