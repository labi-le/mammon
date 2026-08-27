package app.mammon

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The slice of the FUSE kernel ABI (`linux/fuse.h`) this bridge speaks.
 *
 * Sizes and offsets are constants rather than computed: the ABI is versioned and
 * frozen per minor version, so a mismatch should be auditable by reading this file
 * against the header, not inferred at runtime. Every field is native-endian.
 */
internal object Fuse {

    val ORDER: ByteOrder = ByteOrder.nativeOrder()

    const val ROOT_ID = 1L

    /** 7.28 is the floor for FUSE_MAX_PAGES, which is what lifts reads past 128 KiB. */
    const val MAJOR = 7
    const val MINOR = 31

    const val OP_LOOKUP = 1
    const val OP_FORGET = 2
    const val OP_GETATTR = 3
    const val OP_SETATTR = 4
    const val OP_READLINK = 5
    const val OP_SYMLINK = 6
    const val OP_MKNOD = 8
    const val OP_MKDIR = 9
    const val OP_UNLINK = 10
    const val OP_RMDIR = 11
    const val OP_RENAME = 12
    const val OP_LINK = 13
    const val OP_OPEN = 14
    const val OP_READ = 15
    const val OP_WRITE = 16
    const val OP_STATFS = 17
    const val OP_RELEASE = 18
    const val OP_FSYNC = 20
    const val OP_SETXATTR = 21
    const val OP_GETXATTR = 22
    const val OP_LISTXATTR = 23
    const val OP_REMOVEXATTR = 24
    const val OP_FLUSH = 25
    const val OP_INIT = 26
    const val OP_OPENDIR = 27
    const val OP_READDIR = 28
    const val OP_RELEASEDIR = 29
    const val OP_FSYNCDIR = 30
    const val OP_GETLK = 31
    const val OP_SETLK = 32
    const val OP_SETLKW = 33
    const val OP_ACCESS = 34
    const val OP_CREATE = 35
    const val OP_INTERRUPT = 36
    const val OP_BMAP = 37
    const val OP_DESTROY = 38
    const val OP_IOCTL = 39
    const val OP_POLL = 40
    const val OP_NOTIFY_REPLY = 41
    const val OP_BATCH_FORGET = 42
    const val OP_FALLOCATE = 43
    const val OP_READDIRPLUS = 44
    const val OP_RENAME2 = 45
    const val OP_LSEEK = 46
    const val OP_COPY_FILE_RANGE = 47

    const val IN_HEADER_SIZE = 40
    const val OUT_HEADER_SIZE = 16
    const val INIT_SIZE = 64
    const val ATTR_SIZE = 88
    const val ENTRY_OUT_SIZE = 128
    const val ATTR_OUT_SIZE = 104
    const val OPEN_OUT_SIZE = 16
    const val DIRENT_HEADER_SIZE = 24
    const val KSTATFS_SIZE = 80

    const val SETATTR_IN_SIZE = 88
    const val CREATE_IN_SIZE = 16
    const val MKDIR_IN_SIZE = 8
    const val WRITE_IN_SIZE = 40
    const val WRITE_OUT_SIZE = 8

    /**
     * A CREATE reply is two structs back to back, which the kernel spells as
     * `out_numargs = 2` in `fuse_create_open`. Any other length is refused by
     * `copy_out_args` and the open fails EIO, so the sum is named once here.
     */
    const val CREATE_OUT_SIZE = ENTRY_OUT_SIZE + OPEN_OUT_SIZE

    const val FATTR_MODE = 1 shl 0
    const val FATTR_SIZE = 1 shl 3
    const val FATTR_MTIME = 1 shl 5
    const val FATTR_FH = 1 shl 6
    const val FATTR_MTIME_NOW = 1 shl 8

    const val INIT_FLAG_ASYNC_READ = 1 shl 0
    const val INIT_FLAG_BIG_WRITES = 1 shl 5
    const val INIT_FLAG_DO_READDIRPLUS = 1 shl 13
    const val INIT_FLAG_READDIRPLUS_AUTO = 1 shl 14
    const val INIT_FLAG_MAX_PAGES = 1 shl 22
    const val INIT_FLAG_PARALLEL_DIROPS = 1 shl 18

    /**
     * Keeps the page cache across opens. Local writes go through that same cache and
     * stay coherent with it; what this does not survive is another client changing the
     * file, which [FuseNfsDaemon]'s attribute TTL bounds rather than prevents.
     */
    const val FOPEN_KEEP_CACHE = 1 shl 1

    const val DT_UNKNOWN = 0
    const val DT_DIR = 4
    const val DT_REG = 8

    const val S_IFDIR = 0x4000
    const val S_IFREG = 0x8000

    const val O_ACCMODE = 3

    const val ENOENT = 2
    const val EIO = 5
    const val EBADF = 9
    const val EACCES = 13
    const val EEXIST = 17
    const val EINVAL = 22
    const val ENOSPC = 28
    const val EROFS = 30
    const val ENOSYS = 38
    const val ENOTEMPTY = 39
    const val EPROTO = 71

    const val PAGE_SIZE = 4096

    fun align8(n: Int): Int = (n + 7) and 7.inv()

    /**
     * Writes a `fuse_out_header` in front of the [payload] bytes already sitting at
     * [OUT_HEADER_SIZE], and returns the length to hand the kernel.
     *
     * [errno] is given positive and goes on the wire negated, which is the one detail
     * that decides between an answered request and a wedged filesystem. A refusal never
     * carries a payload, so the length ignores it rather than trusting the caller to
     * pass zero.
     */
    fun reply(into: ByteArray, unique: Long, payload: Int, errno: Int = 0): Int {
        val length = OUT_HEADER_SIZE + if (errno == 0) payload else 0
        val out = ByteBuffer.wrap(into).order(ORDER)
        out.putInt(0, length)
        out.putInt(4, -errno)
        out.putLong(8, unique)
        return length
    }

    /**
     * The errno an opcode this daemon does not serve is refused with.
     *
     * ENOSYS rather than EROFS, now that the mount does carry writes: EROFS would be a
     * false claim about the filesystem where ENOSYS is a true one about the operation.
     * For RENAME2, FALLOCATE, SETXATTR and REMOVEXATTR the kernel additionally caches
     * ENOSYS per connection and stops asking — MKNOD, LINK, SYMLINK and plain RENAME have
     * no such flag, so for those it is simply the honest answer.
     *
     * READLINK is EINVAL because no symlink is ever listed, so the kernel is asking
     * about something that cannot be one.
     */
    fun refusal(opcode: Int): Int = if (opcode == OP_READLINK) EINVAL else ENOSYS
}
