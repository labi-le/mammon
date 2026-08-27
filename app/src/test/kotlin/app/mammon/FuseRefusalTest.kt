package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins which errno an unserved opcode is refused with, now that the mount carries writes.
 *
 * EROFS is gone from this path on purpose. It was the right answer while every mutation
 * was refused; on a mount that does accept writes it is a false statement about the
 * filesystem, where ENOSYS is a true one about the operation. For RENAME2, FALLOCATE,
 * SETXATTR and REMOVEXATTR the kernel additionally caches ENOSYS per connection, so
 * answering EROFS there would suppress a kernel feature as well as lie. MKNOD, LINK,
 * SYMLINK and RENAME have no such flag, and for those ENOSYS is simply honest.
 *
 * Which opcodes the daemon serves is deliberately NOT asserted here. That is decided by a
 * `when` inside a private method needing a mounted /dev/fuse to reach, so any check this
 * file could write would compare one local list against another and pass whatever
 * production actually did. The live mount is what covers it.
 */
class FuseRefusalTest {

    @Test fun `no opcode is refused with EROFS any more`() {
        for (opcode in 1..Fuse.OP_COPY_FILE_RANGE) {
            assertNotEquals("opcode $opcode", Fuse.EROFS, Fuse.refusal(opcode))
        }
    }

    /**
     * The kernel keys its CREATE-to-MKNOD fallback on ENOSYS specifically
     * (`fuse_atomic_open`: `if (err == -ENOSYS) { fc->no_create = 1; goto mknod; }`), so
     * a daemon that stops serving CREATE must land on MKNOD's refusal, not on a write
     * error the kernel will propagate instead.
     */
    @Test fun `the operations with a kernel-side ENOSYS fallback get ENOSYS`() {
        assertEquals(Fuse.ENOSYS, Fuse.refusal(Fuse.OP_MKNOD))
        assertEquals(Fuse.ENOSYS, Fuse.refusal(Fuse.OP_RENAME2))
        assertEquals(Fuse.ENOSYS, Fuse.refusal(Fuse.OP_FALLOCATE))
        assertEquals(Fuse.ENOSYS, Fuse.refusal(Fuse.OP_SETXATTR))
        assertEquals(Fuse.ENOSYS, Fuse.refusal(Fuse.OP_REMOVEXATTR))
    }

    /** No `no_link`/`no_symlink`/`no_rename` flag exists in the kernel, so these are
     *  propagated verbatim; ENOSYS is the honest answer rather than a cached one. */
    @Test fun `the namespace operations with no backend get ENOSYS`() {
        assertEquals(Fuse.ENOSYS, Fuse.refusal(Fuse.OP_LINK))
        assertEquals(Fuse.ENOSYS, Fuse.refusal(Fuse.OP_SYMLINK))
        assertEquals(Fuse.ENOSYS, Fuse.refusal(Fuse.OP_RENAME))
    }

    @Test fun `READLINK is refused with EINVAL because no symlink is ever listed`() {
        assertEquals(Fuse.EINVAL, Fuse.refusal(Fuse.OP_READLINK))
    }

    @Test fun `every other declined opcode is ENOSYS`() {
        assertEquals(Fuse.ENOSYS, Fuse.refusal(Fuse.OP_GETXATTR))
        assertEquals(Fuse.ENOSYS, Fuse.refusal(Fuse.OP_LISTXATTR))
        assertEquals(Fuse.ENOSYS, Fuse.refusal(Fuse.OP_GETLK))
        assertEquals(Fuse.ENOSYS, Fuse.refusal(Fuse.OP_SETLK))
        assertEquals(Fuse.ENOSYS, Fuse.refusal(Fuse.OP_SETLKW))
        assertEquals(Fuse.ENOSYS, Fuse.refusal(Fuse.OP_BMAP))
        assertEquals(Fuse.ENOSYS, Fuse.refusal(Fuse.OP_IOCTL))
        assertEquals(Fuse.ENOSYS, Fuse.refusal(Fuse.OP_POLL))
        assertEquals(Fuse.ENOSYS, Fuse.refusal(Fuse.OP_LSEEK))
        assertEquals(Fuse.ENOSYS, Fuse.refusal(Fuse.OP_COPY_FILE_RANGE))
    }

    @Test fun `refusal is exactly READLINK-EINVAL and ENOSYS everywhere else`() {
        for (opcode in 1..Fuse.OP_COPY_FILE_RANGE) {
            val expected = if (opcode == Fuse.OP_READLINK) Fuse.EINVAL else Fuse.ENOSYS
            assertEquals("opcode $opcode", expected, Fuse.refusal(opcode))
        }
    }

}
