package app.mammon

import java.io.File
import java.io.IOException

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

    data class Result(val ok: Boolean, val message: String)

    enum class State { MOUNTED_NFS, NOT_MOUNTED, UNKNOWN }

    fun mountedState(mountpoint: String): State {
        val mountsText = runSu("cat /proc/1/mounts").stdout ?: return State.UNKNOWN
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
            mount -t nfs -o nolock,port=$port,tcp,vers=3 ${quote("$host:$export")} ${quote(mountpoint)}
        """.trimIndent()
        val r = runSu(script)
        return when {
            r.code == 0 -> Result(true, "mounted at $mountpoint")
            isUnknownSuFlag(r.stderr) -> {
                val (c2, _, e2) = runSuViaNsenter(script)
                if (c2 == 0) Result(true, "mounted at $mountpoint")
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
        val r = runSu("umount ${quote(mountpoint)}")
        return when {
            r.code == 0 -> Result(true, "unmounted $mountpoint")
            isUnknownSuFlag(r.stderr) -> {
                val (c2, _, e2) = runSuViaNsenter("umount ${quote(mountpoint)}")
                if (c2 == 0) Result(true, "unmounted $mountpoint")
                else classifyUmountError(c2, e2)
            }
            else -> classifyUmountError(r.code, r.stderr, r.stdout)
        }
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

    private fun exec(vararg cmd: String): SuResult = try {
        val proc = ProcessBuilder(*cmd)
            .redirectErrorStream(false)
            .start()
        val err = proc.errorStream.bufferedReader().use { it.readText() }
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        SuResult(proc.waitFor(), out.ifBlank { null }, err)
    } catch (e: IOException) {
        SuResult(127, null, e.message ?: "su not available")
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        SuResult(130, null, "interrupted")
    }

    private fun firstLine(s: String?): String? =
        s?.lineSequence()?.firstOrNull { it.isNotBlank() }

    private fun quote(s: String): String = "'${s.replace("'", "'\\''")}'"
}
