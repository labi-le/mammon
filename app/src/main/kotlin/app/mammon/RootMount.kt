package app.mammon

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Root mounts driven through `su`: kernel NFS first, then mammon's own FUSE daemon.
 *
 * Mounts must land in the GLOBAL mount namespace or nothing outside the su daemon
 * ever sees them, so commands run under `su --mount-master` (Magisk's global mode);
 * on su implementations without that flag we nsenter PID 1's namespace instead.
 * State checks read /proc/1/mounts (init = global namespace), never /proc/mounts,
 * which shows only our own namespace. Every user-controlled string is single-quoted
 * into the inner script; [quote] neutralizes embedded single quotes.
 *
 * Every rung best-effort modprobes its filesystem modules before mounting, because
 * Android kernels usually build nfs/fuse as loadable modules and /proc/filesystems
 * lists only what is already registered — an unloaded module reads like absent
 * support there. When the ladder still runs out with no fs registered, the module
 * dirs decide between "ships as a module but would not load" and truly absent.
 */
object RootMount {

    data class Result(
        val ok: Boolean,
        val message: String,

        /** Post-command state, classified from the /proc/1/mounts text captured by the same su run. */
        val stateAfter: State = State.UNKNOWN,

        /** Set only on a failed mount; names the cause so the UI stops guessing at one. */
        val diagnosis: MountDiagnosis? = null,

        /** The nfs/nfs4/fuse type found at the mountpoint, or null when nothing is mounted. */
        val fsType: String? = null,
    )

    /** What the FUSE rung needs from the app: the APK to put on the daemon's classpath
     *  and a file both sides can read, since the root shell cannot call back into us. */
    data class FuseLaunch(val apkPath: String, val logPath: String)

    enum class State { MOUNTED, NOT_MOUNTED, UNKNOWN }

    enum class MountDiagnosis { NO_ROOT, KERNEL_LACKS_FUSE, MODULE_FILES_PRESENT, FUSE_DAEMON_FAILED, GENERIC }

    /** How far the FUSE rung got, or null when the ladder never reached it. */
    internal enum class FuseOutcome { NO_DEVICE, MOUNT_REFUSED, DAEMON_SILENT, MOUNT_UNRESPONSIVE, FAILED }

    /** One failed kernel `mount` invocation, kept so a diagnosis can require that EVERY
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
            State.MOUNTED
        } else {
            State.NOT_MOUNTED
        }
    }

    /** The fs type the kernel actually registered, so the UI names the backing it got
     *  instead of assuming the rung we tried first. */
    private fun mountedFsType(mountpoint: String, mountsText: String): String? =
        MountsParser.parse(mountsText)
            .find { it.mountPoint == mountpoint && it.fsType in MOUNTED_FS_TYPES }
            ?.fsType

    /** Kept for tests and diagnostics; unprivileged view, NOT namespace-consistent. */
    fun readOwnNamespaceMounts(): List<MountEntry> =
        MountsParser.parse(File("/proc/mounts").readText())

    fun mount(host: String, export: String, port: Int, mountpoint: String, fuse: FuseLaunch): Result {
        val attempts = mutableListOf<Attempt>()
        for (vers in MOUNT_VERSIONS) {
            val r = runSu(kernelMountScript(host, export, port, mountpoint, vers))
            if (r.code == 0) return mounted(mountpoint, r.stdout)
            attempts += Attempt(r.code, r.stderr)
            // Neither a missing su nor an unanswered prompt gets better on the next
            // rung, and each spawn can block for SU_TIMEOUT_SECONDS.
            if (isRootUnavailable(r.code)) return failed(attempts, r, null, fuse)
        }
        val f = runSu(fuseMountScript(host, export, port, mountpoint, fuse))
        if (f.code == 0) return mounted(mountpoint, f.stdout)
        return failed(attempts, f, fuseOutcomeFor(f.code), fuse)
    }

    private fun mounted(mountpoint: String, mountsText: String?): Result =
        Result(true, "mounted at $mountpoint").withState(mountpoint, mountsText)

    /** The FUSE rung is deliberately absent from [attempts]: root is already proven by
     *  the time it runs, so its own timeout is a hung script, not a missing su. */
    private fun failed(
        attempts: List<Attempt>,
        last: SuResult,
        fuse: FuseOutcome?,
        launch: FuseLaunch,
    ): Result {
        val diagnosis = classifyMountFailure(
            attempts,
            readFilesystems(),
            if (attempts.any { isRootUnavailable(it.code) }) "" else readModuleDirs(),
            fuse,
        )
        return Result(
            ok = false,
            message = daemonMessage(diagnosis, launch)
                ?: firstLine(last.stderr)
                ?: firstLine(last.stdout.orEmpty())
                ?: "exit ${last.code}",
            diagnosis = diagnosis,
        )
    }

    /** The daemon's own first line is what separates a wrong classpath from an
     *  unreachable server; the root shell wrote it, so unreadable degrades to the code. */
    private fun daemonMessage(diagnosis: MountDiagnosis, launch: FuseLaunch): String? {
        if (diagnosis != MountDiagnosis.FUSE_DAEMON_FAILED) return null
        return runCatching { firstLine(File(launch.logPath).readText()) }.getOrNull()
    }

    private fun kernelMountScript(
        host: String,
        export: String,
        port: Int,
        mountpoint: String,
        vers: String,
    ): String = """
        ${preloadLine("nfs nfsv3 nfsv4")}
        mkdir -p ${quote(mountpoint)} &&
        mount -t nfs -o nolock,port=$port,tcp,vers=$vers ${quote("$host:$export")} ${quote(mountpoint)} &&
        cat /proc/1/mounts
    """.trimIndent()

    /** Android kernels usually build these filesystems as modules that nothing loads
     *  until a mount asks, and /proc/filesystems lists only what is already
     *  registered — so a loadable-but-unloaded module reads exactly like missing
     *  support. Best effort: failures stay silent so built-in kernels are unaffected. */
    private fun preloadLine(types: String): String =
        types.split(' ').joinToString(" ") { "modprobe $it 2>/dev/null || true" }

    /** The module directories a device may ship kernel modules under, listed once per
     *  failed mount() beside readFilesystems(); the classifier needs to tell an
     *  unloadable module apart from absent support, and this is the only evidence
     *  available for that. */
    private fun readModuleDirs(): String =
        runSu("ls /vendor/lib/modules /system/lib/modules 2>/dev/null | grep -i -E 'nfs|fuse' || true").stdout.orEmpty()

    /**
     * The proven launch chain: the shell owns /dev/fuse, hands the descriptor to
     * mount(2), and execs [FuseLaunch.apkPath] onto the same descriptor.
     *
     * The readiness probe must not touch the mountpoint before the daemon is known to
     * be serving — a FUSE request with nothing reading the device blocks forever — so
     * the daemon's own log line is the liveness signal. The log is truncated first so a
     * stale line cannot be read as success, and a mount whose daemon never answered is
     * unmounted rather than left wedged.
     */
    private fun fuseMountScript(
        host: String,
        export: String,
        port: Int,
        mountpoint: String,
        fuse: FuseLaunch,
    ): String {
        val mp = quote(mountpoint)
        val log = quote(fuse.logPath)
        // Lazy, and only lazy: measured, a plain umount of a fuse mount whose daemon is
        // not reading the device blocks indefinitely, because the kernel waits for a
        // FUSE_DESTROY reply nobody will send. An `umount || umount -l` chain never
        // reaches its fallback, and the whole su call dies on its timeout instead of
        // reporting why the daemon failed.
        fun teardown(code: Int) = "{ umount -l $mp 2>/dev/null; exit $code; }"
        return """
            : > $log 2>/dev/null
            chmod 0644 $log 2>/dev/null
            mkdir -p $mp || exit $FUSE_MKDIR_FAILED
            ${preloadLine("fuse")}
            exec 3<>/dev/fuse || exit $FUSE_NO_DEVICE
            mount -t fuse -o fd=3,rootmode=40000,user_id=0,group_id=0,allow_other /dev/fuse $mp || exit $FUSE_MOUNT_REFUSED
            if command -v setsid >/dev/null 2>&1; then S=setsid; else S=; fi
            CLASSPATH=${quote(fuse.apkPath)} ${'$'}S app_process --nice-name=app.mammon:fuse / app.mammon.FuseDaemonKt 3 ${quote(host)} ${quote(port.toString())} ${quote(export)} </dev/null >>$log 2>&1 &
            D=${'$'}!
            i=0
            while [ ${'$'}i -lt $FUSE_READY_TICKS ]; do
                grep -q 'serving ' $log 2>/dev/null && break
                kill -0 ${'$'}D 2>/dev/null || break
                i=${'$'}((i+1)); sleep $FUSE_TICK_SECONDS
            done
            grep -q 'serving ' $log 2>/dev/null || ${teardown(FUSE_DAEMON_SILENT)}
            timeout $FUSE_PROBE_SECONDS ls $mp >/dev/null 2>&1 || ${teardown(FUSE_MOUNT_UNRESPONSIVE)}
            cat /proc/1/mounts
        """.trimIndent()
    }

    /** World-readable, so the fs-type half of a diagnosis costs no root. */
    private fun readFilesystems(): String =
        runCatching { File("/proc/filesystems").readText() }.getOrDefault("")

    /** The kernel's fs list says whether the support is registered; a module file in a
     *  vendor/system dir says whether it exists at all. Absent + present file means the
     *  ladder failed where modprobe should have worked, which needs its own message. */
    internal fun classifyMountFailure(
        attempts: List<Attempt>,
        filesystemsText: String,
        moduleDirsText: String,
        fuse: FuseOutcome?,
    ): MountDiagnosis = when {
        attempts.any { isRootUnavailable(it.code) } -> MountDiagnosis.NO_ROOT
        fuse == FuseOutcome.NO_DEVICE || (fuse != null && !hasFilesystem(filesystemsText, "fuse")) ->
            MountDiagnosis.KERNEL_LACKS_FUSE
        fuse == FuseOutcome.DAEMON_SILENT || fuse == FuseOutcome.MOUNT_UNRESPONSIVE ->
            MountDiagnosis.FUSE_DAEMON_FAILED
        hasFilesystem(filesystemsText, "nfs") || hasFilesystem(filesystemsText, "nfs4") ||
            hasFilesystem(filesystemsText, "fuse") ->
            MountDiagnosis.GENERIC
        moduleDirsText.isNotBlank() -> MountDiagnosis.MODULE_FILES_PRESENT
        // Nothing registered and no module file anywhere: the rung-3 verdict keeps its
        // old name, while a ladder that stopped earlier never had a FUSE verdict.
        fuse != null -> MountDiagnosis.KERNEL_LACKS_FUSE
        else -> MountDiagnosis.GENERIC
    }

    /** Maps a FUSE script exit code to how far it got; null for success. */
    internal fun fuseOutcomeFor(code: Int): FuseOutcome? = when (code) {
        0 -> null
        FUSE_NO_DEVICE -> FuseOutcome.NO_DEVICE
        FUSE_MOUNT_REFUSED -> FuseOutcome.MOUNT_REFUSED
        FUSE_DAEMON_SILENT -> FuseOutcome.DAEMON_SILENT
        FUSE_MOUNT_UNRESPONSIVE -> FuseOutcome.MOUNT_UNRESPONSIVE
        else -> FuseOutcome.FAILED
    }

    /** Root never arrived: su could not be started, or it never answered. Both are
     *  reported as such rather than blamed on the kernel. */
    private fun isRootUnavailable(code: Int): Boolean =
        code == SU_NOT_EXECUTABLE_CODE || code == SU_TIMED_OUT_CODE

    /** Compares the fs-type column of /proc/filesystems whole, so "fuseblk" is not
     *  "fuse" and "nfsd" is not "nfs". */
    internal fun hasFilesystem(filesystemsText: String, type: String): Boolean =
        filesystemsText.lineSequence().any { it.substringAfterLast('\t').trim() == type }

    /** Serves both backings: a plain umount makes the kernel send FUSE_DESTROY, which
     *  is how the daemon learns to exit. */
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
                type != null -> State.MOUNTED
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

    /** Must outlast the FUSE script's own bounded wait — [FUSE_READY_TICKS] ticks of
     *  [FUSE_TICK_SECONDS] plus a [FUSE_PROBE_SECONDS] probe, ~11 s — or the happy path
     *  is killed; the rest is headroom for a pending su authorization dialog, which
     *  must not hold the mount thread forever either. */
    private const val SU_TIMEOUT_SECONDS = 30L

    /** Conventionally "timeout"; surfaced verbatim so callers can classify it. */
    private const val SU_TIMED_OUT_CODE = 124

    /** Bound on joining each drain future after a clean exit; the pipes sit at EOF
     *  by then, so this only guards a wedged reader. */
    private const val DRAIN_JOIN_SECONDS = 10L

    /** v4 first, mirroring NfsSessions.select: v4 needs only TCP 2049 while v3 also
     *  needs rpcbind and mountd, so the narrower requirement is tried first. */
    internal val MOUNT_VERSIONS = listOf("4.2", "3")

    /** Backings a mammon mount can have. Deliberately not shared with [hasFilesystem]:
     *  a kernel with fuse but no nfs must not read as nfs-capable. */
    private val MOUNTED_FS_TYPES = setOf("nfs", "nfs4", "fuse")

    /** How far the FUSE script got. [FUSE_MKDIR_FAILED] has no [FuseOutcome] of its own
     *  because it says nothing about FUSE. */
    private const val FUSE_MKDIR_FAILED = 71
    private const val FUSE_NO_DEVICE = 73
    private const val FUSE_MOUNT_REFUSED = 74
    private const val FUSE_DAEMON_SILENT = 75
    private const val FUSE_MOUNT_UNRESPONSIVE = 76

    private const val FUSE_READY_TICKS = 60
    private const val FUSE_TICK_SECONDS = "0.1"

    /** The first mountpoint touch after the daemon reports serving; bounded because a
     *  daemon that logged and then died would otherwise block the shell forever. */
    private const val FUSE_PROBE_SECONDS = 5

    /** Outside the 0-255 wait-status range, so a shell inside the mount script can
     *  never forge it: only [exec] failing to start su produces this. */
    private const val SU_NOT_EXECUTABLE_CODE = -1
}
