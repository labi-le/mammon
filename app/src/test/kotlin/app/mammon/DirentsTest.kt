package app.mammon

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the readdir wire format. The kernel walks a page by stepping each record's own
 * length from a fixed field offset, so a stride that is off by one byte does not lose one
 * entry — it desynchronises everything after it. Records are decoded back out of the
 * array in [Fuse.ORDER] and compared field by field.
 */
class DirentsTest {

    private val attrs = NodeAttrs(
        isDirectory = false,
        size = 4321L,
        lastModifiedMillis = 1_700_000_000_000L,
    )

    private class Dirent(bytes: ByteArray, at: Int) {
        private val buf = ByteBuffer.wrap(bytes).order(Fuse.ORDER)
        val ino: Long = buf.getLong(at)
        val cookie: Long = buf.getLong(at + 8)
        val namelen: Int = buf.getInt(at + 16)
        val type: Int = buf.getInt(at + 20)
        val name: ByteArray = bytes.copyOfRange(
            at + Fuse.DIRENT_HEADER_SIZE,
            at + Fuse.DIRENT_HEADER_SIZE + namelen,
        )
    }

    private data class EntryCall(val start: Int, val ino: Long, val attrs: NodeAttrs)

    private val noEntries: (ByteBuffer, Int, Long, NodeAttrs) -> Unit = { _, _, _, _ ->
        fail("a plain dirent has no fuse_entry_out prefix to fill")
    }

    private fun recorder(into: MutableList<EntryCall>): (ByteBuffer, Int, Long, NodeAttrs) -> Unit =
        { buf, start, ino, rowAttrs ->
            into += EntryCall(start, ino, rowAttrs)
            // nodeid is the first field of fuse_entry_out; writing it proves the zero a
            // dot name leaves behind is not merely an unwritten buffer.
            buf.putLong(start, ino)
        }

    private fun row(name: String, ino: Long, type: Int = Fuse.DT_REG, attrs: NodeAttrs? = null) =
        DirRow(name.toByteArray(), ino, type, attrs)

    private fun pack(
        into: ByteArray,
        rows: List<DirRow>,
        from: Int = 0,
        budget: Int = into.size,
        plus: Boolean = false,
        at: Int = 0,
        writeEntry: (ByteBuffer, Int, Long, NodeAttrs) -> Unit = noEntries,
    ) = Dirents.pack(rows, from, budget, plus, into, at, writeEntry)

    @Test fun `a plain record round-trips ino, cookie, name length, type and name bytes`() {
        val into = ByteArray(256)

        val page = pack(into, listOf(row("readme.txt", 42L, Fuse.DT_REG)))

        assertEquals(Dirents.Page(40, 1), page)
        val record = Dirent(into, 0)
        assertEquals(42L, record.ino)
        assertEquals(1L, record.cookie)
        assertEquals(10, record.namelen)
        assertEquals(Fuse.DT_REG, record.type)
        assertArrayEquals("readme.txt".toByteArray(), record.name)
    }

    @Test fun `a record's cookie is the index after it, so the next page resumes past it`() {
        val rows = listOf(row("one", 11L), row("two", 12L), row("three", 13L), row("four", 14L))
        val into = ByteArray(512)

        val page = pack(into, rows, from = 2)

        assertEquals(Dirents.Page(64, 4), page)
        val third = Dirent(into, 0)
        assertEquals(13L, third.ino)
        assertEquals(3L, third.cookie)
        val fourth = Dirent(into, 32)
        assertEquals(14L, fourth.ino)
        assertEquals(4L, fourth.cookie)
    }

    @Test fun `plain records stride by align8 of the header plus the name`() {
        val rows = listOf(row("a", 1L), row("bbbbbbb", 2L), row("ccccccccc", 3L))
        val into = ByteArray(512)

        val page = pack(into, rows)

        assertEquals(Dirents.Page(32 + 32 + 40, 3), page)
        assertArrayEquals("a".toByteArray(), Dirent(into, 0).name)
        assertArrayEquals("bbbbbbb".toByteArray(), Dirent(into, 32).name)
        assertArrayEquals("ccccccccc".toByteArray(), Dirent(into, 64).name)
        assertEquals(3L, Dirent(into, 64).ino)
        assertEquals(3L, Dirent(into, 64).cookie)
    }

    @Test fun `plus records stride by align8 of the prefix, the header and the name`() {
        val rows = listOf(
            row("a", 1L, attrs = attrs),
            row("bbbbbbb", 2L, attrs = attrs),
            row("ccccccccc", 3L, attrs = attrs),
        )
        val into = ByteArray(1024)
        val calls = mutableListOf<EntryCall>()

        val page = pack(into, rows, plus = true, writeEntry = recorder(calls))

        assertEquals(Dirents.Page(160 + 160 + 168, 3), page)
        assertArrayEquals("a".toByteArray(), Dirent(into, 128).name)
        assertArrayEquals("bbbbbbb".toByteArray(), Dirent(into, 288).name)
        assertArrayEquals("ccccccccc".toByteArray(), Dirent(into, 448).name)
        assertEquals(listOf(0, 160, 320), calls.map { it.start })
    }

    @Test fun `a budget that fits two records stops after them and resumes at the third`() {
        val rows = listOf(row("a", 1L), row("bbbbbbb", 2L), row("ccccccccc", 3L))
        val into = ByteArray(512)

        assertEquals(Dirents.Page(64, 2), pack(into, rows, budget = 64))
        // 65 is still short of the third record, so the boundary must not be lenient.
        assertEquals(Dirents.Page(64, 2), pack(into, rows, budget = 65))
    }

    @Test fun `a budget one byte short of the first record writes nothing, keeping the index`() {
        val rows = listOf(row("a", 1L), row("bbbbbbb", 2L))
        val into = ByteArray(512)

        assertEquals(Dirents.Page(0, 0), pack(into, rows, from = 0, budget = 31))
        assertEquals(Dirents.Page(0, 1), pack(into, rows, from = 1, budget = 31))
    }

    @Test fun `alignment padding is zeroed, so a reused buffer cannot leak into a name`() {
        val into = ByteArray(128) { 0xFF.toByte() }

        val page = pack(into, listOf(row("abc", 9L)))

        assertEquals(Dirents.Page(32, 1), page)
        assertArrayEquals("abc".toByteArray(), Dirent(into, 0).name)
        assertArrayEquals(ByteArray(5), into.copyOfRange(27, 32))
        // Only the record is cleared: the rest of a shared reply buffer is not the
        // packer's to touch.
        assertArrayEquals(ByteArray(96) { 0xFF.toByte() }, into.copyOfRange(32, 128))
    }

    @Test fun `in plus mode every record carries the prefix, the dot names included`() {
        val rows = listOf(
            row(".", Fuse.ROOT_ID, Fuse.DT_DIR),
            row("file", 5L, Fuse.DT_REG, attrs),
        )
        val into = ByteArray(1024)
        val calls = mutableListOf<EntryCall>()

        val page = pack(into, rows, plus = true, writeEntry = recorder(calls))

        assertEquals(Dirents.Page(320, 2), page)

        val dot = Dirent(into, Fuse.ENTRY_OUT_SIZE)
        assertEquals(Fuse.ROOT_ID, dot.ino)
        assertEquals(1L, dot.cookie)
        assertEquals(Fuse.DT_DIR, dot.type)
        assertArrayEquals(".".toByteArray(), dot.name)

        val second = Fuse.align8(Fuse.ENTRY_OUT_SIZE + Fuse.DIRENT_HEADER_SIZE + 1)
        val file = Dirent(into, second + Fuse.ENTRY_OUT_SIZE)
        assertEquals(5L, file.ino)
        assertEquals(2L, file.cookie)
        assertEquals(Fuse.DT_REG, file.type)
        assertArrayEquals("file".toByteArray(), file.name)

        assertEquals(listOf(EntryCall(160, 5L, attrs)), calls)
    }

    @Test fun `a plus record without attributes keeps a zeroed nodeid, skipping the lookup`() {
        val into = ByteArray(1024) { 0xFF.toByte() }
        val calls = mutableListOf<EntryCall>()
        val rows = listOf(
            row("..", Fuse.ROOT_ID, Fuse.DT_DIR),
            row("file", 5L, Fuse.DT_REG, attrs),
        )

        pack(into, rows, plus = true, writeEntry = recorder(calls))

        val buf = ByteBuffer.wrap(into).order(Fuse.ORDER)
        assertEquals(0L, buf.getLong(0))
        assertEquals(5L, buf.getLong(160))
    }

    @Test fun `packing starts at the given offset and spends the budget only on records`() {
        val into = ByteArray(256)

        val page = pack(into, listOf(row("x", 7L)), budget = 32, at = Fuse.OUT_HEADER_SIZE)

        assertEquals(Dirents.Page(32, 1), page)
        assertEquals(7L, Dirent(into, Fuse.OUT_HEADER_SIZE).ino)
        assertArrayEquals(
            ByteArray(Fuse.OUT_HEADER_SIZE),
            into.copyOfRange(0, Fuse.OUT_HEADER_SIZE),
        )
    }

    @Test fun `a from at or past the end of the rows yields an empty page, not a throw`() {
        val rows = listOf(row("a", 1L), row("bbbbbbb", 2L))
        val into = ByteArray(256)

        assertEquals(Dirents.Page(0, 2), pack(into, rows, from = 2))
        // A cookie past the snapshot is echoed back rather than clamped; the daemon only
        // ever resumes from a nextIndex it was given, so nothing reads this value again.
        assertEquals(Dirents.Page(0, 5), pack(into, rows, from = 5))
    }
}
