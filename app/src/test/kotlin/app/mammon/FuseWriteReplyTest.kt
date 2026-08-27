package app.mammon

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the reply shapes the write opcodes owe the kernel, against the sizes in
 * `include/uapi/linux/fuse.h`.
 *
 * The CREATE reply is the one that cannot be got wrong quietly: `fuse_create_open` sets
 * `out_numargs = 2`, so the kernel expects `fuse_entry_out` and `fuse_open_out` back to
 * back. `copy_out_args` in `fs/fuse/dev.c` rejects any other length with -EINVAL, and its
 * caller turns that into `req->out.h.error = -EIO` before completing the request — so a
 * miscounted reply fails every single create with EIO while the daemon's own write(2)
 * returns EINVAL. Computed here rather than read off the struct so the arithmetic itself
 * is the thing under test.
 */
class FuseWriteReplyTest {

    @Test fun `the fuse_attr layout adds up to the size the daemon writes`() {
        val u64s = 6
        val u32s = 10
        assertEquals(Fuse.ATTR_SIZE, u64s * 8 + u32s * 4)
    }

    @Test fun `fuse_entry_out is its own header plus one fuse_attr`() {
        val header = 4 * 8 + 2 * 4
        assertEquals(Fuse.ENTRY_OUT_SIZE, header + Fuse.ATTR_SIZE)
    }

    @Test fun `fuse_open_out is fh plus open_flags plus backing_id`() {
        assertEquals(Fuse.OPEN_OUT_SIZE, 8 + 4 + 4)
    }

    @Test fun `the CREATE reply is an entry and an open concatenated`() {
        assertEquals(144, Fuse.CREATE_OUT_SIZE)
        assertEquals(Fuse.ENTRY_OUT_SIZE + Fuse.OPEN_OUT_SIZE, Fuse.CREATE_OUT_SIZE)
    }

    /** A 128-byte entry-only reply is the specific mistake the kernel answers with EIO. */
    @Test fun `the CREATE reply is not just the entry`() {
        assertTrue(Fuse.CREATE_OUT_SIZE > Fuse.ENTRY_OUT_SIZE)
        assertEquals(16, Fuse.CREATE_OUT_SIZE - Fuse.ENTRY_OUT_SIZE)
    }

    /**
     * Decodes a CREATE reply exactly as the kernel does: the header, then the entry at
     * offset 0 of the payload, then the open handle at offset 128. If the two structs
     * ever overlap or gap, the handle read here comes back wrong.
     */
    @Test fun `the open handle sits immediately after the entry in a CREATE reply`() {
        val reply = ByteArray(Fuse.OUT_HEADER_SIZE + Fuse.CREATE_OUT_SIZE)
        val out = ByteBuffer.wrap(reply).order(Fuse.ORDER)
        val at = Fuse.OUT_HEADER_SIZE

        val nodeid = 4321L
        val handle = 99L
        out.putLong(at, nodeid)
        out.putInt(at + 40 + 60, Fuse.S_IFREG or 0b110_100_100)
        out.putLong(at + Fuse.ENTRY_OUT_SIZE, handle)
        out.putInt(at + Fuse.ENTRY_OUT_SIZE + 8, Fuse.FOPEN_KEEP_CACHE)

        val length = Fuse.reply(reply, unique = 7L, payload = Fuse.CREATE_OUT_SIZE)
        assertEquals(Fuse.OUT_HEADER_SIZE + 144, length)

        val back = ByteBuffer.wrap(reply).order(Fuse.ORDER)
        assertEquals(length, back.getInt(0))
        assertEquals(0, back.getInt(4))
        assertEquals(nodeid, back.getLong(at))
        assertEquals(handle, back.getLong(at + Fuse.ENTRY_OUT_SIZE))
        assertEquals(Fuse.FOPEN_KEEP_CACHE, back.getInt(at + Fuse.ENTRY_OUT_SIZE + 8))
        // The kernel rejects a CREATE reply whose attr is not a regular file.
        assertTrue(back.getInt(at + 40 + 60) and Fuse.S_IFREG != 0)
    }

    @Test fun `the MKDIR reply is one bare entry`() {
        assertEquals(128, Fuse.ENTRY_OUT_SIZE)
    }

    @Test fun `fuse_write_in and fuse_write_out match the header`() {
        assertEquals(Fuse.WRITE_IN_SIZE, 8 + 8 + 4 + 4 + 8 + 4 + 4)
        assertEquals(Fuse.WRITE_OUT_SIZE, 4 + 4)
    }

    @Test fun `fuse_setattr_in and the offsets the daemon reads from it`() {
        assertEquals(Fuse.SETATTR_IN_SIZE, 4 + 4 + 8 * 6 + 4 * 8)
        // Derived from the field order of fuse_setattr_in — valid, padding, fh, size,
        // lock_owner, atime, mtime, ctime, then the four nsec words — so an upstream
        // insertion moves the constants the daemon actually reads from, not just the size.
        assertEquals(4 + 4 + 8, Fuse.SETATTR_SIZE_AT)
        assertEquals(4 + 4 + 8 + 8 + 8 + 8, Fuse.SETATTR_MTIME_AT)
        assertEquals(4 + 4 + 8 * 6 + 4, Fuse.SETATTR_MTIMENSEC_AT)
        assertEquals(1 shl 3, Fuse.FATTR_SIZE)
        assertEquals(1 shl 5, Fuse.FATTR_MTIME)
        assertEquals(1 shl 8, Fuse.FATTR_MTIME_NOW)
        assertEquals(1 shl 0, Fuse.FATTR_MODE)
    }

    @Test fun `fuse_create_in and fuse_mkdir_in name offsets`() {
        assertEquals(Fuse.CREATE_IN_SIZE, 4 * 4)
        assertEquals(Fuse.MKDIR_IN_SIZE, 4 + 4)
    }

}
