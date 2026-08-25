package app.mammon

import android.os.ParcelFileDescriptor
import android.system.Os
import kotlin.system.exitProcess

/**
 * Entry point of the FUSE bridge, started by [RootMount]'s root shell as
 * `app_process / app.mammon.FuseDaemonKt <fd> <host> <port> <export>`.
 *
 * There is no Android context here: this runs in a bare `app_process` VM as root, not
 * as the application. The mount already exists — the shell opened `/dev/fuse`, passed
 * that descriptor to `mount(2)`, and left it open across the exec — so all this does is
 * adopt the number and serve. See `docs/guides/architecture.md` for why the shell has
 * to own the descriptor rather than this process opening it.
 */
fun main(args: Array<String>) {
    if (args.size != 4) {
        fail("usage: <fd> <host> <port> <export>, got ${args.size} argument(s)")
    }
    val number = args[0].toIntOrNull() ?: fail("descriptor '${args[0]}' is not a number")
    val port = args[2].toIntOrNull() ?: fail("port '${args[2]}' is not a number")

    // Whether the shell's redirection survived the exec is the one link in the launch
    // chain that cannot be tested without root, so it gets named rather than left to
    // surface as an EBADF from the first read. readlink rather than a canonical path:
    // a descriptor on a socket or an anonymous inode has no resolvable path, and its
    // raw target is what separates a lost redirection from a closed descriptor.
    val target = runCatching { Os.readlink("/proc/self/fd/$number") }.getOrNull()
    if (target != FUSE_DEVICE) {
        fail("descriptor $number is ${target ?: "not open"}, expected $FUSE_DEVICE")
    }

    val spec = ExportSpec(args[1], args[3], port)
    val session = try {
        NfsSessions.open(spec)
    } catch (e: Exception) {
        fail("cannot reach ${spec.host}:${spec.port}${spec.export}: ${e.message}")
    }

    log("serving ${spec.host}:${spec.port}${spec.export} as ${session.javaClass.simpleName}")
    val descriptor = ParcelFileDescriptor.adoptFd(number)
    try {
        FuseNfsDaemon(session, FuseDevice(descriptor.fileDescriptor), UID, GID, WORKERS, ::log)
            .serve()
    } finally {
        runCatching { descriptor.close() }
        runCatching { session.close() }
    }
    // The NFS libraries leave non-daemon threads behind, and the umount that sent
    // FUSE_DESTROY must not wait on them.
    exitProcess(0)
}

private fun log(message: String) = System.err.println("mammon-fuse: $message")

private fun fail(message: String): Nothing {
    log(message)
    exitProcess(1)
}

private const val FUSE_DEVICE = "/dev/fuse"

/**
 * Matches `user_id`/`group_id` in the mount options, so the kernel sees one consistent
 * owner. NFS gives [NodeAttrs] no ownership to report, so this is the only answer that
 * is not a guess.
 */
private const val UID = 0
private const val GID = 0

/** Enough to keep several readers in flight without holding more than a few request
 *  buffers of [NFS_READ_CHUNK] each. */
private const val WORKERS = 4
