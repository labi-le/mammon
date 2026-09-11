package app.mammon

import android.annotation.SuppressLint
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

        /** Non-null only for a mount routed through shared storage: the path the user can
         *  browse, as opposed to the master path the mount is really made at. */
        val appVisible: String? = null,
    )

    /** What the FUSE rung needs from the app: the APK to put on the daemon's classpath
     *  and a file both sides can read, since the root shell cannot call back into us. */
    data class FuseLaunch(val apkPath: String, val logPath: String, val identity: AuthIdentity)

    enum class State { MOUNTED, NOT_MOUNTED, UNKNOWN }

    enum class MountDiagnosis {
        NO_ROOT,
        KERNEL_LACKS_FUSE,
        MODULE_FILES_PRESENT,
        FUSE_DAEMON_FAILED,

        /** This device's emulated view has no shared peer, so no path exists where a
         *  mount would become visible to apps. */
        EMULATED_NO_SHARED_PEER,

        /** The mount landed on the master path, the kernel never replicated it into the
         *  view the user was promised, and it was removed again. */
        EMULATED_NOT_PROPAGATED,

        /** Same, except the removal failed, so a mount no app can see is still standing
         *  at the master path. */
        EMULATED_NOT_PROPAGATED_STUCK,
        GENERIC,
    }

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
    fun mountedState(mountpoint: String, mountsText: String): State =
        mountSnapshot(mountpoint, mountsText).state

    private data class MountSnapshot(
        val state: State,
        val fsType: String?,
        val hasExactEntry: Boolean,
    )

    /** The exact mountpoint entry drives unmount verdicts; only mammon's own fs types
     *  fill [fsType], so the UI names backings it actually created. */
    private fun mountSnapshot(mountpoint: String, entries: List<MountEntry>?): MountSnapshot {
        if (entries == null) return MountSnapshot(State.UNKNOWN, null, false)
        var hasExactEntry = false
        for (entry in entries) {
            if (entry.mountPoint != mountpoint) continue
            hasExactEntry = true
            if (entry.fsType in MOUNTED_FS_TYPES) {
                return MountSnapshot(State.MOUNTED, entry.fsType, true)
            }
        }
        return MountSnapshot(State.NOT_MOUNTED, null, hasExactEntry)
    }

    private fun mountSnapshot(mountpoint: String, mountsText: String?): MountSnapshot =
        mountSnapshot(mountpoint, entries(mountsText))

    /** Null keeps a blank or unreadable capture from reading as "nothing is mounted";
     *  a verdict asking about two paths parses the text once and scans the list twice. */
    private fun entries(mountsText: String?): List<MountEntry>? =
        mountsText?.takeIf { it.isNotBlank() }?.let { MountsParser.parse(it) }

    /** Kept for tests and diagnostics; unprivileged view, NOT namespace-consistent. */
    fun readOwnNamespaceMounts(): List<MountEntry> =
        MountsParser.parse(File("/proc/mounts").readText())

    fun mount(host: String, export: String, port: Int, mountpoint: String, fuse: FuseLaunch): Result =
        mount(host, export, port, mountpoint, fuse, ::runSu)

    /** Same injected-su seam as [unmount], and for the same reason: the refusals this
     *  entrance makes are the ones a hand-edited pref reaches, so they have to be
     *  provable without a device. [SuResult] is internal, hence an overload rather than
     *  a default parameter on the public one. */
    internal fun mount(
        host: String,
        export: String,
        port: Int,
        mountpoint: String,
        fuse: FuseLaunch,
        su: (String) -> SuResult,
    ): Result {
        val target = when (val routing = resolveTarget(mountpoint, su)) {
            is Routing.Refused -> return routing.result
            is Routing.Ready -> routing.target
        }
        val attempts = mutableListOf<Attempt>()
        for (vers in MOUNT_VERSIONS) {
            val r = su(kernelMountScript(host, export, port, target.mountAt, vers, target.lowerDir))
            if (r.code == 0) return mounted(target, mountsFromRoot(r.stdout))
            attempts += Attempt(r.code, r.stderr)
            // Neither a missing su nor an unanswered prompt gets better on the next
            // rung, and each spawn can block for SU_TIMEOUT_SECONDS.
            if (isRootUnavailable(r.code)) return failed(attempts, r, null, fuse, target)
        }
        val f = su(fuseMountScript(host, export, port, target.mountAt, fuse, target.lowerDir))
        if (f.code == 0) return mounted(target, mountsFromRoot(f.stdout))
        return failed(attempts, f, fuseOutcomeFor(f.code), fuse, target)
    }

    /** Where the mount is really made, what the user is told, and the lower directory
     *  that must exist first; [lowerDir] is null when no routing was needed. */
    internal data class Target(val mountAt: String, val visible: String, val lowerDir: String?) {
        val routed: Boolean get() = lowerDir != null
    }

    private sealed interface Routing {
        data class Ready(val target: Target) : Routing
        data class Refused(val result: Result) : Routing
    }

    /** Routing is decided before any mount runs, because it needs the GLOBAL namespace's
     *  propagation state — /proc/1/mountinfo, read through the same su ladder as the rest.
     *  The path-only half comes from [MountpointPolicy.refusalFor], the same function the
     *  mountpoint field calls: this entrance is reachable without the UI. */
    private fun resolveTarget(mountpoint: String, su: (String) -> SuResult): Routing {
        MountpointPolicy.refusalFor(mountpoint)?.let { return Routing.Refused(refused(mountpoint, it)) }
        if (EmulatedMount.localRefusal(mountpoint) == EmulatedMount.Refusal.NOT_EMULATED) {
            return Routing.Ready(Target(mountpoint, mountpoint, null))
        }
        val r = su("cat /proc/1/mountinfo")
        val mountinfo = r.stdout
        if (r.code != 0 || mountinfo.isNullOrBlank()) {
            return Routing.Refused(
                Result(
                    ok = false,
                    message = "cannot read the global mount table: ${firstLine(r.stderr) ?: "exit ${r.code}"}",
                    diagnosis = if (isRootUnavailable(r.code)) MountDiagnosis.NO_ROOT else MountDiagnosis.GENERIC,
                ),
            )
        }
        return when (val routed = EmulatedMount.route(mountinfo, mountpoint)) {
            is EmulatedMount.Result.Routed -> Routing.Ready(
                Target(routed.route.mountAt, routed.route.appVisible, routed.route.lowerDir),
            )
            is EmulatedMount.Result.Refused -> Routing.Refused(refused(mountpoint, routed.refusal))
        }
    }

    /** A refusal reaching here got past the caller's own check, so it still has to say
     *  something actionable rather than an exit code. */
    private fun refused(mountpoint: String, refusal: EmulatedMount.Refusal): Result = when (refusal) {
        EmulatedMount.Refusal.NO_SHARED_PEER -> Result(
            false,
            "this device's shared storage has no propagating peer, so no mount there could become visible",
            diagnosis = MountDiagnosis.EMULATED_NO_SHARED_PEER,
        )
        EmulatedMount.Refusal.IS_TREE_ROOT -> Result(
            false,
            "$mountpoint is the shared-storage tree root; mount a subdirectory of it instead",
            diagnosis = MountDiagnosis.GENERIC,
        )
        EmulatedMount.Refusal.RESERVED_NAME -> Result(
            false,
            "$mountpoint is under Android/, which vold owns; choose another name",
            diagnosis = MountDiagnosis.GENERIC,
        )
        EmulatedMount.Refusal.NOT_EMULATED -> Result(
            false,
            "$mountpoint is not a path inside shared storage",
            diagnosis = MountDiagnosis.GENERIC,
        )
        EmulatedMount.Refusal.HAS_DOT_COMPONENT -> Result(
            false,
            "$mountpoint has a . or .. step in it; mount the directory it really names instead",
            diagnosis = MountDiagnosis.GENERIC,
        )
        // Quoted, unlike the rest: the whitespace that earns this refusal is otherwise
        // invisible in a status line.
        EmulatedMount.Refusal.NOT_ABSOLUTE -> Result(
            false,
            "'$mountpoint' is not an absolute path; a mountpoint must start with /",
            diagnosis = MountDiagnosis.GENERIC,
        )
        EmulatedMount.Refusal.IS_FILESYSTEM_ROOT -> Result(
            false,
            "'$mountpoint' names the filesystem root; mount a directory below it instead",
            diagnosis = MountDiagnosis.GENERIC,
        )
    }

    /** A routed mount is only real once the global namespace shows it at the app-visible
     *  path: that is the claim made to the user. A blank capture stays UNKNOWN, since an
     *  unreadable /proc/1/mounts is no evidence either way.
     *
     *  A mount that stayed on the master path is torn down rather than left behind: the
     *  verdict is FAILED, and leaving our own mount up — with, on the FUSE rung, the
     *  daemon only a umount stops — bills the user for a path he never got. It is not
     *  the last chance to remove it: [unmount] reaches the same master path, which is why
     *  EMULATED_NOT_PROPAGATED_STUCK can honestly name the Unmount button.
     *  [teardown] is a seam only because the verdict must stay testable without a device. */
    internal fun mounted(
        target: Target,
        mountsText: String?,
        teardown: (String) -> Boolean = ::umountMaster,
    ): Result {
        val entries = entries(mountsText)
        val visible = mountSnapshot(target.visible, entries)
        if (target.routed && visible.state == State.NOT_MOUNTED) {
            val master = mountSnapshot(target.mountAt, entries)
            if (master.state != State.MOUNTED) {
                return Result(
                    ok = false,
                    message = "mount reported success but nothing is mounted at ${target.visible}",
                    diagnosis = MountDiagnosis.GENERIC,
                    appVisible = target.visible,
                )
            }
            val stranded = "mounted at ${target.mountAt} but it never appeared at ${target.visible}"
            val removed = teardown(target.mountAt)
            return Result(
                ok = false,
                message = if (removed) "$stranded, so it was removed again" else "$stranded, and removing it failed",
                diagnosis = if (removed) {
                    MountDiagnosis.EMULATED_NOT_PROPAGATED
                } else {
                    MountDiagnosis.EMULATED_NOT_PROPAGATED_STUCK
                },
                appVisible = target.visible,
            )
        }
        return Result(true, "mounted at ${target.visible}", appVisible = target.visible.takeIf { target.routed })
            .withState(visible)
    }

    /** Same su ladder as the mount, so the umount lands in the namespace the mount was
     *  made in; a slave never propagates to its master, so only the master path can
     *  withdraw it. */
    private fun umountMaster(path: String): Boolean = runSu(unmountScript(path)).code == 0

    /** The FUSE rung is deliberately absent from [attempts]: root is already proven by
     *  the time it runs, so its own timeout is a hung script, not a missing su. */
    private fun failed(
        attempts: List<Attempt>,
        last: SuResult,
        fuse: FuseOutcome?,
        launch: FuseLaunch,
        target: Target,
    ): Result {
        val diagnosis = classifyMountFailure(
            attempts,
            fsListFromRoot(last.stdout).ifBlank { readFilesystems() },
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
            appVisible = target.visible.takeIf { target.routed },
        )
    }

    /** The daemon's own first line is what separates a wrong classpath from an
     *  unreachable server; the root shell wrote it, so unreadable degrades to the code. */
    private fun daemonMessage(diagnosis: MountDiagnosis, launch: FuseLaunch): String? {
        if (diagnosis != MountDiagnosis.FUSE_DAEMON_FAILED) return null
        return runCatching { firstLine(File(launch.logPath).readText()) }.getOrNull()
    }

    internal fun kernelMountScript(
        host: String,
        export: String,
        port: Int,
        mountpoint: String,
        vers: String,
        lowerDir: String? = null,
    ): String = """
        ${preloadLine("nfs nfsv3 nfsv4")}
        ${mountpointDirLine(mountpoint, lowerDir)}
        mount -t nfs -o nolock,port=$port,tcp,vers=$vers ${quote("$host:$export")} ${quote(mountpoint)}
        S=${'$'}?
        { cat /proc/1/mounts
          echo $FS_LIST_MARKER
          cat /proc/filesystems 2>/dev/null || true
        }
        exit ${'$'}S
    """.trimIndent()

    /** A routed mount creates its directory in the LOWER tree, not at the mountpoint:
     *  the master-side path is only the fuse view of that directory, and going through
     *  the view would force the MediaProvider daemon's own 0775 ownership and need it
     *  running. MediaProvider resolves LOOKUP by lstat(2) on the lower path, so the name
     *  is there for the mount immediately — no scan, no wait. */
    private fun mountpointDirLine(mountpoint: String, lowerDir: String?): String =
        "mkdir -p ${quote(lowerDir ?: mountpoint)}"

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
     * The launch chain: the shell opens /dev/fuse, hands the descriptor to mount(2),
     * and execs [FuseLaunch.apkPath] onto the same descriptor. The descriptor rides a
     * group redirection because Android's /system/bin/sh (mksh) sets close-on-exec on
     * fds >= 3 opened by `exec` redirection, which would make mount(2) fail EINVAL.
     *
     * Proven end to end on a rooted phone (v0.6.6): the rooted-phone chain — su, the
     * kernel FUSE mount, the descriptor surviving exec — has a real-device proof on
     * Android 16 (KernelSU-Next); see docs/guides/architecture.md "Verification status".
     * `--nice-name` sits between "/" and the class name: leading dash-args feed ART
     * (unknown ones exit before main) and a trailing flag leaks into main()'s argv.
     *
     * The readiness probe must not touch the mountpoint before the daemon is known to
     * be serving — a FUSE request with nothing reading the device blocks forever — so
     * the daemon's own log line is the liveness signal. The log is truncated first so a
     * stale line cannot be read as success, and a mount whose daemon never answered is
     * unmounted rather than left wedged.
     */
    internal fun fuseMountScript(
        host: String,
        export: String,
        port: Int,
        mountpoint: String,
        fuse: FuseLaunch,
        lowerDir: String? = null,
    ): String {
        val mp = quote(mountpoint)
        val log = quote(fuse.logPath)
        // Lazy, and only lazy: measured, a plain umount of a fuse mount whose daemon is
        // not reading the device blocks indefinitely, because the kernel waits for a
        // FUSE_DESTROY reply nobody will send. An `umount || umount -l` chain never
        // reaches its fallback, and the whole su call dies on its timeout instead of
        // reporting why the daemon failed.
        fun teardown(code: Int) = "{ umount -l $mp 2>/dev/null; mammon_dump; exit $code; }"
        return """
            : > $log 2>/dev/null
            chmod 0644 $log 2>/dev/null
            mammon_dump() {
                cat /proc/1/mounts
                echo $FS_LIST_MARKER
                cat /proc/filesystems 2>/dev/null || true
            }
            ${mountpointDirLine(mountpoint, lowerDir)} || { mammon_dump; exit $FUSE_MKDIR_FAILED; }
            ${preloadLine("fuse")}
            # The fd must ride a GROUP redirection, not `exec 3<>`: Android's
            # /system/bin/sh is mksh, and mksh marks `exec`-opened fds >= 3
            # close-on-exec, so the mount child would lose the device and mount(2)
            # fails EINVAL. A failed group redirection would kill the shell before its
            # body, so openability is probed in a throwaway subshell first and still
            # classified as NO_DEVICE. fslib.sh's automount carries the same pattern.
            (exec 3<>/dev/fuse) 2>/dev/null || exit $FUSE_NO_DEVICE
            {
            mount -t fuse -o fd=3,rootmode=40000,user_id=0,group_id=0,allow_other /dev/fuse $mp || { mammon_dump; exit $FUSE_MOUNT_REFUSED; }
            if command -v setsid >/dev/null 2>&1; then S=setsid; else S=; fi
            # app_process feeds leading dash-args to ART (unknown ones exit before main)
            # and parses --nice-name only between "/" and the class. Same in fslib.sh.
            CLASSPATH=${quote(fuse.apkPath)} ${'$'}S app_process / --nice-name=app.mammon:fuse app.mammon.FuseDaemonKt 3 ${quote(host)} ${quote(port.toString())} ${quote(export)} ${quote(fuse.identity.toString())} </dev/null >>$log 2>&1 &
            D=${'$'}!
            i=0
            while [ ${'$'}i -lt $FUSE_READY_TICKS ]; do
                grep -q 'serving ' $log 2>/dev/null && break
                kill -0 ${'$'}D 2>/dev/null || break
                i=${'$'}((i+1)); sleep $FUSE_TICK_SECONDS
            done
            grep -q 'serving ' $log 2>/dev/null || ${teardown(FUSE_DAEMON_SILENT)}
            timeout $FUSE_PROBE_SECONDS ls $mp >/dev/null 2>&1 || ${teardown(FUSE_MOUNT_UNRESPONSIVE)}
            mammon_dump
            } 3<>/dev/fuse
        """.trimIndent()
    }

    /** Unprivileged fallback only: the mount scripts capture the same list as root,
     *  and an app-process read can be denied — blank here must stay a non-verdict. */
    private fun readFilesystems(): String =
        runCatching { File("/proc/filesystems").readText() }.getOrDefault("")

    /** Splits the captured su output on the fs-list marker: the mount scripts dump
     *  /proc/filesystems in the SAME root context as the mounts, because the
     *  unprivileged app process can be denied that read and a blank text must
     *  never read as a kernel verdict. */
    internal fun fsListFromRoot(stdout: String?): String =
        stdout.orEmpty().substringAfter(FS_LIST_MARKER, "").trim()

    internal fun mountsFromRoot(stdout: String?): String? {
        val s = stdout ?: return null
        return if (FS_LIST_MARKER in s) s.substringBefore(FS_LIST_MARKER) else s
    }

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
        // A blank fs list says nothing either way: a denied or filtered read of
        // /proc/filesystems must never harden into a kernel claim, so every
        // inferred verdict below requires actual text.
        fuse == FuseOutcome.NO_DEVICE ||
            (fuse != null && filesystemsText.isNotBlank() && !hasFilesystem(filesystemsText, "fuse")) ->
            MountDiagnosis.KERNEL_LACKS_FUSE
        fuse == FuseOutcome.DAEMON_SILENT || fuse == FuseOutcome.MOUNT_UNRESPONSIVE ->
            MountDiagnosis.FUSE_DAEMON_FAILED
        hasFilesystem(filesystemsText, "nfs") || hasFilesystem(filesystemsText, "nfs4") ||
            hasFilesystem(filesystemsText, "fuse") ->
            MountDiagnosis.GENERIC
        moduleDirsText.isNotBlank() && filesystemsText.isNotBlank() ->
            MountDiagnosis.MODULE_FILES_PRESENT
        // Nothing registered and no module file anywhere: the rung-3 verdict keeps its
        // old name, while a ladder that stopped earlier never had a FUSE verdict.
        fuse != null && filesystemsText.isNotBlank() -> MountDiagnosis.KERNEL_LACKS_FUSE
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

    /**
     * Serves both backings: a plain umount makes the kernel send FUSE_DESTROY, which
     * is how the daemon learns to exit.
     *
     * A routed mount must be unmounted at the MASTER path it was made at — a slave does
     * not propagate to its master, so a umount inside the /storage view would leave the
     * real mount standing. A refusal about the PATH still falls back to the literal one:
     * every verdict [MountpointPolicy.refusalFor] can give is reached before any su
     * runs, so the refusal knows nothing about what is mounted, and a mount put there
     * by another entrance can still be standing.
     *
     * NO_ROOT is the one diagnosis this function ever returns, and the one no second
     * spawn can improve on; [mount] short-circuits its ladder on the same predicate
     * rather than block another SU_TIMEOUT_SECONDS with both buttons disabled. A
     * mountinfo read that fails with root present is GENERIC and falls through on
     * purpose — the routing decision is exactly what it could not make.
     */
    fun unmount(mountpoint: String): Result = unmount(mountpoint, ::runSu)

    internal fun unmount(mountpoint: String, su: (String) -> SuResult): Result {
        val routing = resolveTarget(mountpoint, su)
        if (routing is Routing.Refused && routing.result.diagnosis == MountDiagnosis.NO_ROOT) {
            return routing.result
        }
        val target = (routing as? Routing.Ready)?.target ?: Target(mountpoint, mountpoint, null)
        return classifyUnmountResult(target, su(unmountScript(target.mountAt)))
    }

    internal fun classifyUnmountResult(mountpoint: String, result: SuResult): Result =
        classifyUnmountResult(Target(mountpoint, mountpoint, null), result)

    internal fun classifyUnmountResult(target: Target, result: SuResult): Result {
        val snapshot = unmountSnapshot(target, entries(mountsFromRoot(result.stdout)))
        if (result.code == 0) {
            return Result(true, "unmounted ${target.visible}", appVisible = target.visible.takeIf { target.routed })
                .withState(snapshot)
        }
        return when {
            snapshot.hasExactEntry -> classifyUmountError(result.code, result.stderr).withState(snapshot)
            snapshot.state == State.NOT_MOUNTED -> Result(true, "not mounted").withState(snapshot)
            else -> classifyUmountError(result.code, result.stderr)
        }
    }

    /** Only the master side can still hold a routed mount, so the app-visible path is
     *  consulted just to catch a copy the kernel failed to withdraw. */
    private fun unmountSnapshot(target: Target, entries: List<MountEntry>?): MountSnapshot {
        val at = mountSnapshot(target.mountAt, entries)
        if (!target.routed || at.state != State.NOT_MOUNTED) return at
        val visible = mountSnapshot(target.visible, entries)
        return if (visible.state == State.MOUNTED) {
            visible
        } else {
            at.copy(hasExactEntry = at.hasExactEntry || visible.hasExactEntry)
        }
    }

    internal fun unmountScript(mountpoint: String): String = """
        umount ${quote(mountpoint)}
        S=${'$'}?
        cat /proc/1/mounts
        exit ${'$'}S
    """.trimIndent()

    private fun Result.withState(snapshot: MountSnapshot): Result =
        copy(stateAfter = snapshot.state, fsType = snapshot.fsType)

    private fun classifyUmountError(code: Int, err: String?): Result {
        val e = err.orEmpty()
        return when {
            e.contains("busy", true) || e.contains("EBUSY", true) ->
                Result(false, "target is busy — close apps using it first")
            else -> Result(false, "umount failed: ${firstLine(e) ?: "exit $code"}")
        }
    }

    internal data class SuResult(val code: Int, val stdout: String?, val stderr: String)

    /** Read-only probes share the ladder's su chain (--mount-master plus nsenter fallback),
     *  so their verdict sees the same namespace and degrades the same way on odd su builds. */
    internal fun probe(script: String): SuResult = runSu(script)

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

    /** Separates the /proc/1/mounts dump from the trailing /proc/filesystems dump in
     *  a mount script's stdout; nothing this app mounts can contain it, and even a
     *  hostile path only shifts the split point — the fallback still degrades safely. */
    private const val FS_LIST_MARKER = "__MAMMON_FS_LIST__"

    private const val SU_NOT_EXECUTABLE_CODE = -1
}

/**
 * Android's own storage surface: /storage is tmpfs, /storage/emulated is the
 * MediaProvider FUSE view, /sdcard is a symlink into it, and /data/media is its on-disk
 * backing.
 *
 * Membership is NOT a refusal, and the old names said it was: a subdirectory of the
 * emulated tree is mountable, and [EmulatedMount] routes it to the shared master where
 * the kernel replicates it back into this view. What stays refused is the tree root
 * itself, the slave-side view as a mount target, /data/media, which sits below the
 * FUSE daemon rather than in the propagating view, and a saved spelling root could not
 * use as a mountpoint. [refusalFor] is that whole composition and the only spelling
 * of it either entrance uses.
 *
 * The "/sdcard" literal is a mountpoint prefix to match, never a path to open, so the
 * SdCardPath detector's getExternalStorageDirectory() suggestion does not apply.
 */
@SuppressLint("SdCardPath")
internal object MountpointPolicy {

    /** Roots whose entire tree is that surface, checked at path-component boundaries so
     *  /storageroom or /data/mediafoo stay outside it. */
    val STORAGE_SURFACE_ROOTS = listOf("/storage", "/sdcard", "/data/media")

    /** Trims, collapses // runs and drops a trailing / so "/storage/" and "//storage/x"
     *  still match; empty collapses to "/". The trim is load-bearing: without it
     *  "/storage/emulated/0/Android " misses [EmulatedMount]'s RESERVED_NAME refusal. */
    fun normalize(path: String): String =
        path.trim().replace(Regex("/+"), "/").trimEnd('/').ifEmpty { "/" }

    /** Membership only; what it earns is [refusalFor]'s answer, and for an emulated path
     *  [EmulatedMount] speaks first. */
    fun isInStorageSurface(path: String): Boolean {
        val p = normalize(path)
        return STORAGE_SURFACE_ROOTS.any { p == it || p.startsWith("$it/") }
    }

    /**
     * The refusal a path earns on its own, or null when only the device can decide.
     *
     * [EmulatedMount]'s NOT_EMULATED means "not routable through the emulated view", so
     * it is an answer only once crossed with this set: /mnt/nas is mountable, a physical
     * volume under /storage and /data/media typed directly are not. Both entrances —
     * the mountpoint field and [RootMount.mount] — must apply the same cross, or the
     * refusal holds only for whoever remembers it.
     *
     * That same arm is the one whose target is the saved string itself, so it is also
     * where [verbatimRefusal] applies. An emulated path is exempt because its target is
     * derived from the normalized request and its spelling never reaches root.
     */
    fun refusalFor(path: String): EmulatedMount.Refusal? =
        when (val refusal = EmulatedMount.localRefusal(path)) {
            EmulatedMount.Refusal.NOT_EMULATED ->
                surfaceRefusal(path).takeIf { isInStorageSurface(path) } ?: verbatimRefusal(path)
            else -> refusal
        }

    /** Inside the surface, a dot component is its own refusal: what the SD-card-and-
     *  /data/media wording claims is false of "/storage/emulated/0/../nfs", which is
     *  neither. The component decides and not the subtree, since a dot in the media-id
     *  slot reads as a non-numeric id and one below it as unroutable — NOT_EMULATED
     *  both times. fslib.sh splits its own storage-surface arm the same two ways
     *  (`fslib.sh:527-533`), and the two were measured against each other over one
     *  corpus of spellings rather than asserted here. */
    private fun surfaceRefusal(path: String): EmulatedMount.Refusal =
        if (normalize(path).split('/').any { it == "." || it == ".." }) {
            EmulatedMount.Refusal.HAS_DOT_COMPONENT
        } else {
            EmulatedMount.Refusal.NOT_EMULATED
        }

    /** What root cannot be handed as a mountpoint: [normalize] decides but never
     *  rewrites, so a spelling that is not absolute AS SAVED would become a `mkdir -p`
     *  in the su shell's own working directory. fslib.sh re-asks the same question of
     *  the same saved string in its own non-emulated branch. */
    private fun verbatimRefusal(path: String): EmulatedMount.Refusal? = when {
        !path.startsWith("/") -> EmulatedMount.Refusal.NOT_ABSOLUTE
        normalize(path) == "/" -> EmulatedMount.Refusal.IS_FILESYSTEM_ROOT
        else -> null
    }
}
