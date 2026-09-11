package app.mammon

import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * The mounted `/dev/fuse` connection.
 *
 * The descriptor is opened and handed to `mount(2)` by the privileged shell that
 * launches this process: neither `android.system.Os` nor libcore exposes `mount(2)`,
 * and a JVM child process cannot be given the descriptor either, because libcore
 * closes every fd above stderr before exec. So the shell holds the fd across both the
 * mount and the exec, and this class only adopts it.
 *
 * One syscall per message is mandatory, not an optimisation: FUSE frames requests and
 * replies by syscall boundary, with no in-band length a buffering layer could use to
 * re-split them.
 *
 * Not closeable on purpose: the descriptor's owner is whoever adopted it (a
 * [android.os.ParcelFileDescriptor] in [main]), and a second closer on the same number
 * would eventually close an unrelated reused descriptor.
 */
class FuseDevice(fd: FileDescriptor) {

    private val input = FileInputStream(fd)
    private val output = FileOutputStream(fd)

    /** Bytes of the next request, or -1 once the kernel has torn the connection down. */
    fun read(into: ByteArray): Int = input.read(into)

    fun write(from: ByteArray, len: Int) = output.write(from, 0, len)
}
