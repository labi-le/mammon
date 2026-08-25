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
    )

    enum class State { MOUNTED_NFS, NOT_MOUNTED, UNKNOWN }

    fun mountedState(mountpoint: String): State {
        val mountsText = runSu("cat /proc/1/mounts").stdout ?: return State.UNKNOWN
        return mountedState(mountpoint, mountsText)
    }

    /** Overload so callers that just ran a script can classify from the captured
     *  /proc/1/mounts text instead of spawning another su. */
    fun mountedState(mountpoint: String, mountsText: String): State {
        if (mountsText.isBlank()) return State.UNKNOWN
        val entry = MountsParser.parse(mountsText).find {
            it.mountPoint == mountpoint && (it.fsType == "nfs" || it.fsType == "nfs4")
        }
        return if (entry != null) State.MOUNTED_NFS else State.NOT_MOUNTED
    }

    /** Kept for tests and diagnostics; unprivileged view, NOT namespace-consistent. */
    fun readOwnNamespaceMounts(): List<MountEntry> =
        MountsParser.parse(File("/proc/mounts").readText())

    fun mount(host: String, export: String, port: Int, mountpoint: String): Result {
        val script = """
            mkdir -p ${quote(mountpoint)} &&
            mount -t nfs -o nolock,port=$port,tcp,vers=3 ${quote("$host:$export")} ${quote(mountpoint)} &&
            cat /proc/1/mounts
        """.trimIndent()
        val r = runSu(script)
        return when {
            r.code == 0 -> Result(true, "mounted at $mountpoint").withState(mountpoint, r.stdout)
            isUnknownSuFlag(r.stderr) -> {
                val (c2, out2, e2) = runSuViaNsenter(script)
                if (c2 == 0) Result(true, "mounted at $mountpoint").withState(mountpoint, out2)
                else Result(false, "mount failed: ${firstLine(e2) ?: "exit $c2"}")
            }
            r.stderr.contains("no such device", true) ||
                r.stderr.contains("unknown filesystem type", true) ->
                Result(false, "kernel lacks NFS support (no nfs.ko)")
            else -> Result(
                false,
                "mount failed: ${firstLine(r.stderr) ?: firstLine(r.stdout.orEmpty()) ?: "exit ${r.code}"}",
            )
        }
    }

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

    private fun Result.withState(mountpoint: String, mountsText: String?): Result =
        copy(stateAfter = mountedState(mountpoint, mountsText.orEmpty()))

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
        SuResult(127, null, e.message ?: "su not available")
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
}
