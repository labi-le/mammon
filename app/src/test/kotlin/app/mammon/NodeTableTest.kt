package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins nodeid accounting. The kernel sends FORGET only for ids it took a reference on, so
 * an id a plain READDIR merely allocated is the daemon's to drop; getting that wrong
 * either leaks an entry per listed child for the life of the mount, or frees an id the
 * kernel still addresses and turns every later request on it into ENOENT.
 */
class NodeTableTest {

    @Test fun `a fresh table holds only the root`() {
        val table = NodeTable()

        assertEquals(1, table.size)
        assertEquals("/", table.pathOf(Fuse.ROOT_ID))
    }

    @Test fun `interning the same path twice returns the same id and allocates nothing`() {
        val table = NodeTable()

        val first = table.intern("/dir/file")
        val again = table.intern("/dir/file")

        assertEquals(first, again)
        assertEquals(2, table.size)
        assertEquals("/dir/file", table.pathOf(first))
    }

    @Test fun `distinct paths get distinct ids`() {
        val table = NodeTable()

        val a = table.intern("/a")
        val b = table.intern("/b")

        assertNotEquals(a, b)
        assertEquals(3, table.size)
        assertEquals("/a", table.pathOf(a))
        assertEquals("/b", table.pathOf(b))
    }

    @Test fun `an interned id claims no reference, so releasing the directory drops it`() {
        val table = NodeTable()
        val id = table.intern("/dir/child")

        table.releaseUnreferenced(id)

        assertNull(table.pathOf(id))
        assertEquals(1, table.size)
    }

    @Test fun `a looked-up id survives the directory release and needs a forget`() {
        val table = NodeTable()
        val id = table.lookedUp("/dir/child")

        table.releaseUnreferenced(id)
        assertEquals("/dir/child", table.pathOf(id))
        assertEquals(2, table.size)

        table.forget(id, 1)
        assertNull(table.pathOf(id))
        assertEquals(1, table.size)
    }

    @Test fun `two lookups of one path need two forgets`() {
        val table = NodeTable()

        val first = table.lookedUp("/dir/child")
        val second = table.lookedUp("/dir/child")
        assertEquals(first, second)
        assertEquals(2, table.size)

        table.forget(first, 1)
        assertEquals("/dir/child", table.pathOf(first))

        table.forget(first, 1)
        assertNull(table.pathOf(first))
        assertEquals(1, table.size)
    }

    @Test fun `a forget for more references than were held still evicts and does not throw`() {
        val table = NodeTable()
        val id = table.lookedUp("/dir/child")

        table.forget(id, 9)

        assertNull(table.pathOf(id))
        assertEquals(1, table.size)
    }

    @Test fun `counting a lookup on an id that is already gone changes nothing`() {
        val table = NodeTable()
        val id = table.intern("/dir/child")
        table.releaseUnreferenced(id)

        table.countLookup(id)
        table.countLookup(4242L)

        assertNull(table.pathOf(id))
        assertNull(table.pathOf(4242L))
        assertEquals(1, table.size)
    }

    @Test fun `the root is never evicted, because nothing could look it up again`() {
        val table = NodeTable()

        table.forget(Fuse.ROOT_ID, 1)
        assertEquals("/", table.pathOf(Fuse.ROOT_ID))

        table.releaseUnreferenced(Fuse.ROOT_ID)
        assertEquals("/", table.pathOf(Fuse.ROOT_ID))
        assertEquals(1, table.size)
    }

    @Test fun `re-interning an evicted path gives a fresh id, so a stale nodeid stays dead`() {
        val table = NodeTable()
        val old = table.intern("/dir/child")
        table.releaseUnreferenced(old)

        val fresh = table.intern("/dir/child")

        assertNotEquals(old, fresh)
        assertNull(table.pathOf(old))
        assertEquals("/dir/child", table.pathOf(fresh))
        assertEquals(2, table.size)
    }

    @Test fun `an id that was never allocated resolves to no path`() {
        val table = NodeTable()

        assertNull(table.pathOf(Fuse.ROOT_ID + 1))
        assertNull(table.pathOf(0L))
        assertNull(table.pathOf(-1L))
    }
}
