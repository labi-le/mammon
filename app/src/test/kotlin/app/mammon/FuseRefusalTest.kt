package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins which errno an unserved opcode is refused with. The distinction is user-visible:
 * EROFS tells a writer the export is read-only, while ENOSYS is cached per connection so
 * the kernel stops asking. Getting them the wrong way round either hides the real reason
 * for a failed write or permanently disables an opcode the daemon could answer.
 *
 * The mutating set is spelled out here rather than read from production, so moving an
 * opcode between buckets has to be agreed in two places.
 */
class FuseRefusalTest {

    private val mutating = intArrayOf(
        Fuse.OP_SETATTR,
        Fuse.OP_MKNOD,
        Fuse.OP_MKDIR,
        Fuse.OP_UNLINK,
        Fuse.OP_RMDIR,
        Fuse.OP_RENAME,
        Fuse.OP_RENAME2,
        Fuse.OP_LINK,
        Fuse.OP_SYMLINK,
        Fuse.OP_WRITE,
        Fuse.OP_CREATE,
        Fuse.OP_FALLOCATE,
        Fuse.OP_SETXATTR,
        Fuse.OP_REMOVEXATTR,
    )

    @Test fun `every opcode that would change the export is refused with EROFS`() {
        assertEquals(Fuse.EROFS, Fuse.refusal(Fuse.OP_SETATTR))
        assertEquals(Fuse.EROFS, Fuse.refusal(Fuse.OP_MKNOD))
        assertEquals(Fuse.EROFS, Fuse.refusal(Fuse.OP_MKDIR))
        assertEquals(Fuse.EROFS, Fuse.refusal(Fuse.OP_UNLINK))
        assertEquals(Fuse.EROFS, Fuse.refusal(Fuse.OP_RMDIR))
        assertEquals(Fuse.EROFS, Fuse.refusal(Fuse.OP_RENAME))
        assertEquals(Fuse.EROFS, Fuse.refusal(Fuse.OP_RENAME2))
        assertEquals(Fuse.EROFS, Fuse.refusal(Fuse.OP_LINK))
        assertEquals(Fuse.EROFS, Fuse.refusal(Fuse.OP_SYMLINK))
        assertEquals(Fuse.EROFS, Fuse.refusal(Fuse.OP_WRITE))
        assertEquals(Fuse.EROFS, Fuse.refusal(Fuse.OP_CREATE))
        assertEquals(Fuse.EROFS, Fuse.refusal(Fuse.OP_FALLOCATE))
        assertEquals(Fuse.EROFS, Fuse.refusal(Fuse.OP_SETXATTR))
        assertEquals(Fuse.EROFS, Fuse.refusal(Fuse.OP_REMOVEXATTR))
    }

    @Test fun `READLINK is refused with EINVAL because no symlink is ever listed`() {
        assertEquals(Fuse.EINVAL, Fuse.refusal(Fuse.OP_READLINK))
    }

    @Test fun `opcodes the daemon declines but does not mutate are refused with ENOSYS`() {
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

    @Test fun `across the whole opcode range exactly the mutating opcodes answer EROFS`() {
        for (opcode in 1..Fuse.OP_COPY_FILE_RANGE) {
            val expected = when {
                opcode in mutating -> Fuse.EROFS
                opcode == Fuse.OP_READLINK -> Fuse.EINVAL
                else -> Fuse.ENOSYS
            }
            assertEquals("opcode $opcode", expected, Fuse.refusal(opcode))
        }
    }

    @Test fun `the mutating set is exactly fourteen opcodes`() {
        assertEquals(14, mutating.size)
        assertEquals(14, mutating.distinct().size)
        assertEquals(14, (1..Fuse.OP_COPY_FILE_RANGE).count { Fuse.refusal(it) == Fuse.EROFS })
    }
}
