package app.mammon

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Kernel NFS mounts driven through `su`.
 *
 * Mounts must land in the GLOBAL mount namespace or nothing outside the su daemon
 * ever sees them, so commands run under `su --mount-master` (Magisk's global mode);
 * on su implementations without that flag we nsenter PID 1's namespace instead.
 * State checks read /proc/1/mounts (init = global namespace), never /proc/mounts,
 * which shows only our own namespace. Every user-controlled string is single-quoted
 * into the inner script; [quote] neutralizes embedded single quotes.
 */
object RootMount {

    data class Result(
        val ok: Boolean,
        val message: String,

        /** Post-command state, classified from the /proc/1/mounts text captured by the same su run. */
        val stateAfter: State = State.UNKNOWN,

        /** Set only on a failed mount; names the cause so the UI stops guessing at one. */
        val diagnosis: MountDiagnosis? = null,

        /** The nfs/nfs4 type found at the mountpoint, or null when nothing is mounted. */
        val fsType: String? = null,
    )

    enum class State { MOUNTED_NFS, NOT_MOUNTED, UNKNOWN }

    enum class MountDiagnosis { NO_ROOT, KERNEL_LACKS_NFS, VERSION_MODULE_MISSING, GENERIC }

    /** One failed `mount` invocation, kept so a diagnosis can require that EVERY
     *  attempted version failed the same way rather than just the last one. */
    internal data class Attempt(val code: Int, val stderr: String)

    fun mountedState(mountpoint: String): State {
        val mountsText = runSu("cat /proc/1/mounts").stdout ?: return State.UNKNOWN
        return mountedState(mountpoint, mountsText)
    }

    /** Overload so callers that just ran a script can classify from the captured
     *  /proc/1/mounts text instead of spawning another su. */
    fun mountedState(mountpoint: String, mountsText: String): State {
        if (mountsText.isBlank()) return State.UNKNOWN
        return if (mountedFsType(mountpoint, mountsText) != null) {
            State.MOUNTED_NFS
        } else {
            State.NOT_MOUNTED
        }
    }

    /** The fs type the kernel actually registered, so the UI names the version it got
     *  instead of assuming the one we asked for first. */
    private fun mountedFsType(mountpoint: String, mountsText: String): String? =
        MountsParser.parse(mountsText)
            .find { it.mountPoint == mountpoint && it.fsType in NFS_FS_TYPES }
            ?.fsType

    /** Kept for tests and diagnostics; unprivileged view, NOT namespace-consistent. */
    fun readOwnNamespaceMounts(): List<MountEntry> =
        MountsParser.parse(File("/proc/mounts").readText())

    fun mount(host: String, export: String, port: Int, mountpoint: String): Result {
        val failures = mutableListOf<SuResult>()
        for (vers in MOUNT_VERSIONS) {
            val r = runSu(mountScript(host, export, port, mountpoint, vers))
            if (r.code == 0) {
                return Result(true, "mounted at $mountpoint").withState(mountpoint, r.stdout)
            }
            failures += r
            // Neither a missing su nor an unanswered prompt gets better on the next
            // version, and each spawn can block for SU_TIMEOUT_SECONDS.
            if (isRootUnavailable(r.code)) break
        }
        val last = failures.last()
        return Result(
            ok = false,
            message = firstLine(last.stderr)
                ?: firstLine(last.stdout.orEmpty())
                ?: "exit ${last.code}",
            diagnosis = classifyMountFailure(
                failures.map { Attempt(it.code, it.stderr) },
                readFilesystems(),
            ),
        )
    }

    private fun mountScript(
        host: String,
        export: String,
        port: Int,
        mountpoint: String,
        vers: String,
    ): String = """
        mkdir -p ${quote(mountpoint)} &&
        mount -t nfs -o nolock,port=$port,tcp,vers=$vers ${quote("$host:$export")} ${quote(mountpoint)} &&
        cat /proc/1/mounts
    """.trimIndent()

    /** World-readable, so the fs-type half of a diagnosis costs no root. */
    private fun readFilesystems(): String =
        runCatching { File("/proc/filesystems").readText() }.getOrDefault("")

    internal fun classifyMountFailure(
        attempts: List<Attempt>,
        filesystemsText: String,
    ): MountDiagnosis = when {
        attempts.any { isRootUnavailable(it.code) } -> MountDiagnosis.NO_ROOT
        !hasNfsFilesystem(filesystemsText) -> MountDiagnosis.KERNEL_LACKS_NFS
        attempts.isNotEmpty() && attempts.all { isUnknownFsType(it.stderr) } ->
            MountDiagnosis.VERSION_MODULE_MISSING
        else -> MountDiagnosis.GENERIC
    }

    /** Root never arrived: su could not be started, or it never answered. Both are
     *  reported as such rather than blamed on the kernel. */
    private fun isRootUnavailable(code: Int): Boolean =
        code == SU_NOT_EXECUTABLE_CODE || code == SU_TIMED_OUT_CODE

    private fun isUnknownFsType(stderr: String): Boolean =
        stderr.contains("no such device", true) ||
            stderr.contains("unknown filesystem type", true)

    /** Compares the fs-type column of /proc/filesystems whole, so "nfsd" is not "nfs". */
    private fun hasNfsFilesystem(filesystemsText: String): Boolean =
        filesystemsText.lineSequence().any { it.substringAfterLast('\t').trim() in NFS_FS_TYPES }

    fun unmount(mountpoint: String): Result {
        val script = "umount ${quote(mountpoint)} && cat /proc/1/mounts"
        val r = runSu(script)
        return when {
            r.code == 0 -> Result(true, "unmounted $mountpoint").withState(mountpoint, r.stdout)
            isUnknownSuFlag(r.stderr) -> {
                val (c2, out2, e2) = runSuViaNsenter(script)
                if (c2 == 0) Result(true, "unmounted $mountpoint").withState(mountpoint, out2)
                else classifyUmountError(c2, e2)
            }
            else -> classifyUmountError(r.code, r.stderr, r.stdout)
        }
    }

    private fun Result.withState(mountpoint: String, mountsText: String?): Result {
        val text = mountsText.orEmpty()
        val type = mountedFsType(mountpoint, text)
        return copy(
            stateAfter = when {
                text.isBlank() -> State.UNKNOWN
                type != null -> State.MOUNTED_NFS
                else -> State.NOT_MOUNTED
            },
            fsType = type,
        )
    }

    private fun classifyUmountError(code: Int, err: String?, out: String? = null): Result {
        val e = err.orEmpty()
        return when {
            e.contains("not mounted", true) || e.contains("EINVAL", true) ->
                Result(true, "not mounted")
            e.contains("busy", true) || e.contains("EBUSY", true) ->
                Result(false, "target is busy — close apps using it first")
            else -> Result(
                false,
                "umount failed: ${firstLine(e) ?: firstLine(out.orEmpty()) ?: "exit $code"}",
            )
        }
    }

    private data class SuResult(val code: Int, val stdout: String?, val stderr: String)

    private fun runSu(script: String): SuResult {
        val r = exec("su", "--mount-master", "-c", script)
        // Only an option/usage complaint justifies the fallback; any other failure
        // (denial, real mount error) must surface as-is.
        if (r.code != 0 && isUnknownSuFlag(r.stderr + r.stdout)) {
            val alt = exec("su", "-c", "nsenter -t 1 -m -- sh -c ${quote(script)}")
            return SuResult(alt.code, alt.stdout, alt.stderr)
        }
        return r
    }

    private fun runSuViaNsenter(script: String): SuResult =
        exec("su", "-c", "nsenter -t 1 -m -- sh -c ${quote(script)}")

    private fun isUnknownSuFlag(text: String?): Boolean =
        text != null && (
            text.contains("unknown option", ignoreCase = true) ||
                text.contains("invalid option", ignoreCase = true) ||
                text.contains("unrecognized option", ignoreCase = true) ||
                text.contains("usage:", ignoreCase = true)
            )

    private fun quote(s: String): String = "'${s.replace("'", "'\\''")}'"

    private fun firstLine(s: String?): String? =
        s?.lineSequence()?.firstOrNull { it.isNotBlank() }

    private fun exec(vararg cmd: String): SuResult = try {
        val proc = ProcessBuilder(*cmd)
            .redirectErrorStream(false)
            .start()
        // Both pipes are drained asynchronously BEFORE waiting on exit: reading them
        // sequentially deadlocks when the child fills the stdout pipe while stderr
        // is silent, and draining inline before waitFor would block a hung su past
        // its timeout instead of ever reaching destroyForcibly.
        val outText = java.util.concurrent.CompletableFuture.supplyAsync {
            proc.inputStream.bufferedReader().use { it.readText() }
        }
        val errText = java.util.concurrent.CompletableFuture.supplyAsync {
            proc.errorStream.bufferedReader().use { it.readText() }
        }
        if (!proc.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            runCatching { proc.destroyForcibly() }
            SuResult(SU_TIMED_OUT_CODE, null, "su timed out")
        } else {
            val out = runCatching { outText.get(DRAIN_JOIN_SECONDS, TimeUnit.SECONDS) }.getOrNull()
            val err = runCatching { errText.get(DRAIN_JOIN_SECONDS, TimeUnit.SECONDS) }.getOrDefault("")
            SuResult(proc.exitValue(), out?.takeIf { it.isNotBlank() }, err)
        }
    } catch (e: IOException) {
        SuResult(SU_NOT_EXECUTABLE_CODE, null, e.message ?: "su not available")
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        SuResult(130, null, "interrupted")
    }

    /** A pending su authorization dialog must not hold the UI hostage forever. */
    private const val SU_TIMEOUT_SECONDS = 15L

    /** Conventionally "timeout"; surfaced verbatim so callers can classify it. */
    private const val SU_TIMED_OUT_CODE = 124

    /** Bound on joining each drain future after a clean exit; the pipes sit at EOF
     *  by then, so this only guards a wedged reader. */
    private const val DRAIN_JOIN_SECONDS = 10L

    /** v4 first, mirroring NfsSessions.select: v4 needs only TCP 2049 while v3 also
     *  needs rpcbind and mountd, so the narrower requirement is tried first. */
    internal val MOUNT_VERSIONS = listOf("4.2", "3")

    private val NFS_FS_TYPES = setOf("nfs", "nfs4")

    /** Outside the 0-255 wait-status range, so a shell inside the mount script can
     *  never forge it: only [exec] failing to start su produces this. */
    private const val SU_NOT_EXECUTABLE_CODE = -1
}
