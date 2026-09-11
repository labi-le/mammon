package app.mammon

import android.annotation.SuppressLint

/**
 * Routes a mountpoint inside Android's emulated storage to the path a mount can really
 * be made at.
 *
 * `/storage/emulated` is usually a SLAVE of its propagation peer group: it receives
 * mounts and never sends them, so a mount made there stays in the namespace that made it
 * and reaches no app. The same fuse view is also mounted at that group's shared MASTER —
 * on a stock tree `/mnt/user/<u>/emulated` — and a mount made there is replicated by the
 * kernel into every slave, which is how vold's own `Android/data` bind mounts appear
 * inside the emulated tree. Mounting at the master and telling the user about the
 * slave-side path is the usual case; a view that is shared and slaved to nothing
 * propagates on its own and is mounted where it stands.
 *
 * The master path is derived per device from `/proc/1/mountinfo`, never hardcoded: it
 * varies by OEM, by user profile and by release. Propagation is kernel-generic and the
 * `Android/data` precedent is stock AOSP, but the mountinfo shape this was read off came
 * from a waydroid Android 13 instance with SELinux disabled, not from a rooted phone.
 *
 * Pure by design — mountinfo text and a path in, a decision out — so the routing rule is
 * testable without a device. The "/sdcard" literal is a spelling to recognise, never a
 * path to open, so SdCardPath's suggestion does not apply.
 */
@SuppressLint("SdCardPath")
internal object EmulatedMount {

    /** Where a mount must be made, where its directory must first exist, and where users will see it. */
    data class Route(val mountAt: String, val lowerDir: String, val appVisible: String)

    /**
     * Why a requested emulated path cannot be routed on this device — plus the three
     * verdicts only [MountpointPolicy.refusalFor] reaches. [route] never answers
     * NOT_ABSOLUTE or IS_FILESYSTEM_ROOT: a routed mount targets a path derived from
     * the NORMALIZED request, so how the request was spelled cannot reach root, while
     * a non-emulated one is handed to root exactly as saved. Nor HAS_DOT_COMPONENT:
     * `recognize` answers a dot component with NOT_EMULATED, and telling those two
     * apart takes the whole storage surface, which only that composition sees.
     */
    enum class Refusal {
        NOT_EMULATED,
        IS_TREE_ROOT,
        NO_SHARED_PEER,
        RESERVED_NAME,
        NOT_ABSOLUTE,
        IS_FILESYSTEM_ROOT,
        HAS_DOT_COMPONENT,
    }

    sealed interface Result {
        data class Routed(val route: Route) : Result
        data class Refused(val refusal: Refusal) : Result
    }

    /** [mountinfo] is the verbatim content of /proc/1/mountinfo. */
    fun route(mountinfo: String, requested: String): Result {
        val target = when (val r = recognize(requested)) {
            is Recognition.Rejected -> return Result.Refused(r.refusal)
            is Recognition.Target -> r
        }
        val site = mountSite(mountinfo) ?: return Result.Refused(Refusal.NO_SHARED_PEER)
        val sub = "/${target.mediaId}/${target.subPath}"
        val visible = EMULATED_VIEW + sub
        return Result.Routed(
            Route(
                mountAt = when (site) {
                    is MountSite.Master -> site.path + sub
                    MountSite.InPlace -> visible
                },
                lowerDir = MEDIA_BACKING + sub,
                appVisible = visible,
            ),
        )
    }

    /** The part of [route]'s verdict that needs no device, for callers on a thread with
     *  no root: never NO_SHARED_PEER, and null only for a path still routable. */
    fun localRefusal(requested: String): Refusal? =
        (recognize(requested) as? Recognition.Rejected)?.refusal

    private sealed interface Recognition {
        data class Target(val mediaId: String, val subPath: String) : Recognition
        data class Rejected(val refusal: Refusal) : Recognition
    }

    /** The device-free half of the decision: which media directory the path names, what
     *  sits below it, and the three refusals a path alone can earn. */
    private fun recognize(requested: String): Recognition {
        val path = MountpointPolicy.normalize(requested)
        val below = when {
            // /sdcard is the symlink to /storage/self/primary, the primary user's view;
            // it carries no id component to read.
            path == SDCARD_VIEW -> ""
            path.startsWith("$SDCARD_VIEW/") -> "/$PRIMARY_USER/" + path.substring(SDCARD_VIEW.length + 1)
            path == EMULATED_VIEW -> ""
            path.startsWith("$EMULATED_VIEW/") -> path.substring(EMULATED_VIEW.length)
            // The <u> here is deliberately unused: the master target comes from
            // mountinfo, so this spelling contributes only the media id below it.
            else -> MASTER_SPELLING.matchEntire(path)?.groupValues?.get(1)
                ?: return Recognition.Rejected(Refusal.NOT_EMULATED)
        }
        val components = below.split('/').filter { it.isNotEmpty() }
        val mediaId = components.firstOrNull() ?: return Recognition.Rejected(Refusal.IS_TREE_ROOT)
        // Digit-shaped, then kept VERBATIM: fslib.sh gates the same component with
        // `case $mid in '' | *[!0-9]*)` and re-emits it unchanged, so parsing it would
        // rewrite "00" to "0" and "010" to profile 10's storage. Char.isDigit spans
        // Unicode, which that class does not.
        if (mediaId.any { it !in '0'..'9' }) return Recognition.Rejected(Refusal.NOT_EMULATED)
        val subPath = components.drop(1)
        return when {
            subPath.isEmpty() -> Recognition.Rejected(Refusal.IS_TREE_ROOT)
            // Resolving these would make appVisible name a directory the mount is not
            // at; which refusal the user is shown is MountpointPolicy's to decide.
            subPath.any { it == "." || it == ".." } -> Recognition.Rejected(Refusal.NOT_EMULATED)
            subPath.first() == VOLD_OWNED -> Recognition.Rejected(Refusal.RESERVED_NAME)
            else -> Recognition.Target(mediaId, subPath.joinToString("/"))
        }
    }

    /** The peer-group tags routing needs, per mountinfo line. */
    private data class Peer(
        val mountPoint: String,
        val fsType: String,
        val sharedGroup: String?,
        val masterGroup: String?,
        val propagateFrom: String?,
    )

    /** Where a mount has to land for the kernel to show it in the emulated view. */
    private sealed interface MountSite {
        data class Master(val path: String) : MountSite

        /** The view is itself the shared mount, so it sends propagation. */
        data object InPlace : MountSite
    }

    /**
     * Peer-group equality already proves the two entries are one fs instance, so no
     * device-number check is needed on top of it.
     *
     * `propagate_from:` is tried FIRST and `master:` only as a fallback, never as one
     * set: `master:` names the immediate master group, which may have no mount visible
     * under the reading root, and letting either match would hand the choice to
     * mountinfo line order. Within a group the FIRST candidate wins. The module decides
     * both the same way in `mammon_emulated_peer` and `mammon_emulated_master`
     * (magisk-module/fslib.sh); one rule, two languages.
     */
    private fun mountSite(mountinfo: String): MountSite? {
        val peers = peers(mountinfo)
        // Last wins: a later mount at the same point is the one visible there now.
        val view = peers.lastOrNull { it.mountPoint == EMULATED_VIEW && it.fsType == FUSE } ?: return null
        for (group in listOfNotNull(view.propagateFrom, view.masterGroup)) {
            val master = peers.firstOrNull {
                it.fsType == FUSE &&
                    it.sharedGroup == group &&
                    it.mountPoint != EMULATED_VIEW &&
                    it.mountPoint.endsWith(EMULATED_LEAF)
            }
            if (master != null) return MountSite.Master(master.mountPoint)
        }
        // Slave of nothing but in a peer group of its own: a mount made here reaches
        // every peer and slave, so refusing would deny a device that works — and a wrong
        // guess is withdrawn again by RootMount's propagation check.
        if (view.masterGroup == null && view.propagateFrom == null && view.sharedGroup != null) {
            return MountSite.InPlace
        }
        return null
    }

    /**
     * `id parent major:minor root mountPoint options optional... - fsType source super`.
     *
     * The optional fields are a variable-length list terminated by a literal `-`, and
     * that is where the propagation tags live. The kernel emits zero to four of
     * `shared:N master:N propagate_from:N unbindable` (`show_mountinfo`, Linux 6.6), any
     * of them absent, so counting columns reads the wrong field; the separator search
     * starting at the first optional slot also keeps a directory named `-` out of it.
     *
     * Field 5 is escaped by the kernel for space, tab, newline and backslash only, which
     * is why splitting on spaces is safe but comparing without unescaping is not — this
     * app's mountpoints are user-typed.
     */
    private fun peers(mountinfo: String): List<Peer> =
        mountinfo.lineSequence().mapNotNull { line ->
            val f = line.split(' ')
            val sep = (OPTIONAL_FIRST until f.size).firstOrNull { f[it] == SEPARATOR } ?: return@mapNotNull null
            if (sep + 1 == f.size) return@mapNotNull null
            Peer(
                mountPoint = MountsParser.unescape(f[MOUNT_POINT]),
                fsType = f[sep + 1],
                sharedGroup = tag(f, sep, "shared:"),
                masterGroup = tag(f, sep, "master:"),
                propagateFrom = tag(f, sep, "propagate_from:"),
            )
        }.toList()

    private fun tag(fields: List<String>, separator: Int, prefix: String): String? =
        (OPTIONAL_FIRST until separator).firstNotNullOfOrNull { i ->
            if (fields[i].startsWith(prefix)) fields[i].substring(prefix.length) else null
        }

    /** The slave-side view every app sees, and the on-disk tree below the fuse daemon
     *  where a mountpoint must exist before it can appear in that view. */
    private const val EMULATED_VIEW = "/storage/emulated"
    private const val MEDIA_BACKING = "/data/media"
    private const val SDCARD_VIEW = "/sdcard"

    private val MASTER_SPELLING = Regex("""^/mnt/user/\d+/emulated(/.*)?$""")

    private const val EMULATED_LEAF = "/emulated"
    private const val PRIMARY_USER = "0"
    private const val VOLD_OWNED = "Android"
    private const val FUSE = "fuse"
    private const val SEPARATOR = "-"
    private const val MOUNT_POINT = 4
    private const val OPTIONAL_FIRST = 6
}
