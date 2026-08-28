package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing a mountpoint inside emulated storage to the path a mount can be made at.
 *
 * The fixtures follow the shape read off a waydroid Android 13 instance: `/storage/emulated`
 * is a fuse SLAVE, the same device is mounted shared at `/mnt/user/<u>/emulated`, and vold's
 * `Android/data` bind mount appears in both views through peer group 795. No real phone has
 * executed this path yet, so these pin the rule, not a device.
 */
class EmulatedMountTest {

    private val stock = """
        22 28 0:21 / /dev rw,nosuid,relatime shared:2 - tmpfs tmpfs rw,seclabel,mode=755
        957 977 0:98 /user/0 /storage rw,nosuid,nodev,noexec,relatime master:796 - tmpfs tmpfs rw,seclabel,mode=751
        2860 918 0:122 / /mnt/user/0/emulated rw,nosuid,nodev,noexec,relatime shared:805 - fuse /dev/fuse rw,lazytime,user_id=0,group_id=0,allow_other
        2890 957 0:122 / /storage/emulated rw,nosuid,nodev,noexec,relatime master:805 - fuse /dev/fuse rw,lazytime,user_id=0,group_id=0,allow_other
        4277 2860 259:3 /media/0/Android/data /mnt/user/0/emulated/0/Android/data rw,relatime shared:795 master:1 - ext4 /dev/block/dm-1 rw,seclabel
        4354 2890 259:3 /media/0/Android/data /storage/emulated/0/Android/data rw,relatime master:795 - ext4 /dev/block/dm-1 rw,seclabel
    """.trimIndent()

    @Test fun `every accepted spelling of one directory routes to the same master path`() {
        for (spelling in listOf("/storage/emulated/0/nfs", "/sdcard/nfs", "/mnt/user/0/emulated/0/nfs")) {
            assertEquals(
                spelling,
                EmulatedMount.Route(
                    mountAt = "/mnt/user/0/emulated/0/nfs",
                    lowerDir = "/data/media/0/nfs",
                    appVisible = "/storage/emulated/0/nfs",
                ),
                routed(stock, spelling),
            )
        }
    }

    /** The master path is the whole point of the exercise: a literal would freeze at a
     *  value that varies by OEM, profile and release. */
    @Test fun `the master path comes from mountinfo, not from a literal`() {
        val relocated = stock.replace("/mnt/user/0/emulated", "/mnt/androidwritable/0/emulated")

        assertEquals("/mnt/androidwritable/0/emulated/0/nfs", routed(relocated, "/storage/emulated/0/nfs").mountAt)
    }

    @Test fun `shadowing the whole tree is refused, only a subdirectory routes`() {
        for (root in listOf(
            "/storage/emulated",
            "/storage/emulated/0",
            "/storage/emulated/0/",
            "/sdcard",
            "/sdcard/",
            "/mnt/user/0/emulated",
            "/mnt/user/0/emulated/0",
        )) {
            assertEquals(root, EmulatedMount.Refusal.IS_TREE_ROOT, refused(stock, root))
        }
    }

    /** vold bind-mounts ext4 over Android/data and Android/obb itself; mounting there
     *  would fight it. */
    @Test fun `Android and everything under it is reserved`() {
        for (path in listOf(
            "/storage/emulated/0/Android",
            "/storage/emulated/0/Android/data",
            "/sdcard/Android/obb/app.mammon",
            "/mnt/user/0/emulated/0/Android/data",
        )) {
            assertEquals(path, EmulatedMount.Refusal.RESERVED_NAME, refused(stock, path))
        }
        // Only the first component is vold's; the name merely starting with it is not.
        assertEquals("/data/media/0/Androids", routed(stock, "/storage/emulated/0/Androids").lowerDir)
        assertEquals("/data/media/0/nfs/Android", routed(stock, "/storage/emulated/0/nfs/Android").lowerDir)
    }

    @Test fun `a nested path routes with its whole chain under data media`() {
        val route = routed(stock, "/storage/emulated/0/nas/export/data")

        assertEquals("/mnt/user/0/emulated/0/nas/export/data", route.mountAt)
        assertEquals("/data/media/0/nas/export/data", route.lowerDir)
        assertEquals("/storage/emulated/0/nas/export/data", route.appVisible)
    }

    /** A secondary profile has its own view and its own peer group; assuming user 0
     *  would mount into the wrong user's storage. The table is synthetic — stock AOSP
     *  mounts one fuse view per user and init's /storage is user 0's tmpfs subtree, so
     *  the view is never slaved to user 10's group — and it defends the rule that every
     *  field follows the path's user id, not a device. */
    @Test fun `a non-zero user id is taken from the path and kept in every field`() {
        val secondary = """
            2860 918 0:122 / /mnt/user/10/emulated rw,relatime shared:905 - fuse /dev/fuse rw,user_id=0
            2890 957 0:122 / /storage/emulated rw,relatime master:905 - fuse /dev/fuse rw,user_id=0
        """.trimIndent()

        assertEquals(
            EmulatedMount.Route(
                mountAt = "/mnt/user/10/emulated/10/nfs",
                lowerDir = "/data/media/10/nfs",
                appVisible = "/storage/emulated/10/nfs",
            ),
            routed(secondary, "/storage/emulated/10/nfs"),
        )
    }

    /**
     * The shape a real multi-user device produces: init's /storage is user 0's tmpfs
     * subtree and vold mounts one fuse view per user (`system/vold/Utils.cpp:1631`), so
     * /storage/emulated is slaved to USER 0's group whatever profile the path names, and
     * user 10's own view is not in that group at all.
     *
     * appVisible is the path in the GLOBAL namespace, which is also what profile 10's
     * apps see as their own /sdcard; a user-0 app cannot traverse into it.
     */
    @Test fun `a secondary profile routes through the master the view is really slaved to`() {
        val multiUser = """
            957 977 0:98 /user/0 /storage rw,relatime master:796 - tmpfs tmpfs rw,mode=751
            2860 918 0:122 / /mnt/user/0/emulated rw,relatime shared:805 - fuse /dev/fuse rw,user_id=0
            2890 957 0:122 / /storage/emulated rw,relatime master:805 - fuse /dev/fuse rw,user_id=0
            3120 918 0:151 / /mnt/user/10/emulated rw,relatime shared:905 - fuse /dev/fuse rw,user_id=0
        """.trimIndent()

        assertEquals(
            EmulatedMount.Route(
                mountAt = "/mnt/user/0/emulated/10/nfs",
                lowerDir = "/data/media/10/nfs",
                appVisible = "/storage/emulated/10/nfs",
            ),
            routed(multiUser, "/storage/emulated/10/nfs"),
        )
    }

    @Test fun `a device whose emulated view has no shared peer is refused, not guessed at`() {
        val noPeer = """
            957 977 0:98 /user/0 /storage rw,relatime master:796 - tmpfs tmpfs rw,mode=751
            2890 957 0:122 / /storage/emulated rw,relatime master:805 - fuse /dev/fuse rw,user_id=0
        """.trimIndent()

        assertEquals(EmulatedMount.Refusal.NO_SHARED_PEER, refused(noPeer, "/storage/emulated/0/nfs"))
        assertEquals(EmulatedMount.Refusal.NO_SHARED_PEER, refused("", "/storage/emulated/0/nfs"))
    }

    /** A view carrying only `shared:` is slave to nobody and SENDS propagation, so a
     *  mount made where it stands reaches every peer and slave of that group. Refusing
     *  would deny a device that works, and a wrong guess is withdrawn again by
     *  RootMount's propagation check. */
    @Test fun `a view that is shared and slaved to nothing is mounted where it stands`() {
        val sendsOnly = stock.replace(
            "/storage/emulated rw,nosuid,nodev,noexec,relatime master:805",
            "/storage/emulated rw,nosuid,nodev,noexec,relatime shared:806",
        )

        assertEquals(
            EmulatedMount.Route(
                mountAt = "/storage/emulated/0/nfs",
                lowerDir = "/data/media/0/nfs",
                appVisible = "/storage/emulated/0/nfs",
            ),
            routed(sendsOnly, "/storage/emulated/0/nfs"),
        )
    }

    /**
     * `master:` is the immediate master's group id, which may have no line under the
     * reading root; `propagate_from:` is the nearest one that does. Keying on `master:`
     * alone refuses a device where the route works, which is the worst failure this
     * change can ship.
     */
    @Test fun `propagate_from names the reachable peer group when master's is invisible`() {
        val dominated = """
            2860 918 0:122 / /mnt/user/0/emulated rw,relatime shared:795 - fuse /dev/fuse rw,user_id=0
            2890 957 0:122 / /storage/emulated rw,relatime master:1 propagate_from:795 - fuse /dev/fuse rw,user_id=0
        """.trimIndent()

        assertEquals("/mnt/user/0/emulated/0/nfs", routed(dominated, "/storage/emulated/0/nfs").mountAt)
    }

    /** Both groups have a candidate here and the file lists the master: group's first,
     *  so accepting either group would pick it. The dominating group must win on its own
     *  merit, not on where the kernel happened to print the line. */
    @Test fun `the propagate_from group wins even when the master group also has a candidate`() {
        val both = """
            2860 918 0:122 / /mnt/user/0/emulated rw,relatime shared:1 - fuse /dev/fuse rw,user_id=0
            2861 918 0:122 / /mnt/androidwritable/0/emulated rw,relatime shared:795 - fuse /dev/fuse rw,user_id=0
            2890 957 0:122 / /storage/emulated rw,relatime master:1 propagate_from:795 - fuse /dev/fuse rw,user_id=0
        """.trimIndent()

        assertEquals("/mnt/androidwritable/0/emulated/0/nfs", routed(both, "/storage/emulated/0/nfs").mountAt)
    }

    /** Two candidates in one group is off-stock — vold mounts one fuse view per user —
     *  but the tie-break exists in two languages, so both take the FIRST. fslib.sh's
     *  peer loop stops on its first match too. */
    @Test fun `the first candidate of a peer group wins the tie-break`() {
        val twoCandidates = """
            2860 918 0:122 / /mnt/user/0/emulated rw,relatime shared:805 - fuse /dev/fuse rw,user_id=0
            2861 918 0:122 / /mnt/androidwritable/0/emulated rw,relatime shared:805 - fuse /dev/fuse rw,user_id=0
            2890 957 0:122 / /storage/emulated rw,relatime master:805 - fuse /dev/fuse rw,user_id=0
        """.trimIndent()

        assertEquals("/mnt/user/0/emulated/0/nfs", routed(twoCandidates, "/storage/emulated/0/nfs").mountAt)
    }

    /** The tag list is variable, so a fixed column reads a tag or the separator itself as
     *  the fs type and finds no fuse peer at all. Three tags is the most the kernel can
     *  emit at once — `shared:` excludes `unbindable`, and `propagate_from:` appears only
     *  after `master:` and only when the two ids differ — so this is the widest real head. */
    @Test fun `extra optional fields before the separator do not shift the parsed fields`() {
        val tagged = """
            2860 918 0:122 / /mnt/user/0/emulated rw,relatime shared:805 - fuse /dev/fuse rw,user_id=0
            2890 957 0:122 / /storage/emulated rw,relatime shared:806 master:805 propagate_from:800 - fuse /dev/fuse rw,user_id=0
            3001 918 0:140 / /mnt/appfuse/1000/2 rw,nosuid,nodev,noexec,relatime unbindable - fuse /dev/fuse rw,user_id=1000
        """.trimIndent()

        assertEquals("/mnt/user/0/emulated/0/nfs", routed(tagged, "/storage/emulated/0/nfs").mountAt)
    }

    /** The tail is variable too — show_options appends fs-specific fields — so the fs
     *  type must be read forward from the separator, never backward from the line end. */
    @Test fun `extra fields after the separator do not shift the parsed fs type`() {
        val trailing = """
            2860 918 0:122 / /mnt/user/0/emulated rw,relatime shared:805 - fuse /dev/fuse rw,user_id=0 extra tail fields
            2890 957 0:122 / /storage/emulated rw,relatime master:805 - fuse /dev/fuse rw,user_id=0 extra tail fields
        """.trimIndent()

        assertEquals("/mnt/user/0/emulated/0/nfs", routed(trailing, "/storage/emulated/0/nfs").mountAt)
    }

    /** The kernel octal-escapes space, tab, newline and backslash in the mount-point
     *  field; an unescaped comparison would build a mount target nothing resolves. */
    @Test fun `an escaped space in the master mount point is unescaped into the target`() {
        val spaced = stock.replace("/mnt/user/0/emulated", """/mnt/user\0400/emulated""")

        assertEquals("/mnt/user 0/emulated/0/nfs", routed(spaced, "/storage/emulated/0/nfs").mountAt)
    }

    /** The text arrives as captured su stdout, which can be cut off mid-file by a
     *  timeout or a killed shell, so a short line must cost a skip and not a crash. */
    @Test fun `a line cut short at or before the separator is skipped, never crashed on`() {
        val truncated = """
            2860 918 0:122 / /mnt/user/0/emulated rw,relatime shared:805
            2861 918 0:122 / /mnt/user/0/emulated rw,relatime shared:805 -
            2890 957 0:122 / /storage/emulated rw,relatime master:805 - fuse /dev/fuse rw,user_id=0
        """.trimIndent()

        assertEquals(EmulatedMount.Refusal.NO_SHARED_PEER, refused(truncated, "/storage/emulated/0/nfs"))
    }

    @Test fun `paths outside the emulated tree are not this object's business`() {
        for (path in listOf(
            "/mnt/nas",
            "/storage",
            "/storage/0123-4567/nfs",
            "/data/media",
            "/data/media/0/nfs",
            "/storage/self/primary/nfs",
            "/storage/emulated/obb/x",
            "/",
            "",
        )) {
            assertEquals(path, EmulatedMount.Refusal.NOT_EMULATED, refused(stock, path))
        }
    }

    /** A dot component would make appVisible name a directory the mount is not at. Every
     *  spelling below is NOT_EMULATED here and MountpointPolicy decides which refusal
     *  the user reads, so this verdict must not split: two of them arrive as a
     *  non-numeric media id rather than through the subPath check, and the /mnt/user one
     *  is outside the storage surface, where app and module both mount it verbatim. */
    @Test fun `a path that walks out of the tree is not treated as inside it`() {
        for (path in listOf(
            "/storage/emulated/0/../nfs",
            "/storage/emulated/0/./nfs",
            "/storage/emulated/0/nfs/..",
            "/sdcard/../../data/local/nfs",
            "/storage/emulated/./0/nfs",
            "/storage/emulated/../data/media/0/nfs",
            "/mnt/user/0/emulated/0/../nfs",
        )) {
            assertEquals(path, EmulatedMount.Refusal.NOT_EMULATED, refused(stock, path))
        }
    }

    /** The UI thread has no root, so the path-only half of the verdict must be callable
     *  without mountinfo — and must never invent the device-shaped refusal. */
    @Test fun `localRefusal answers everything decidable from the path alone`() {
        assertNull(EmulatedMount.localRefusal("/storage/emulated/0/nfs"))
        assertNull(EmulatedMount.localRefusal("/sdcard/nfs"))
        assertEquals(EmulatedMount.Refusal.IS_TREE_ROOT, EmulatedMount.localRefusal("/sdcard"))
        assertEquals(EmulatedMount.Refusal.RESERVED_NAME, EmulatedMount.localRefusal("/sdcard/Android"))
        assertEquals(EmulatedMount.Refusal.NOT_EMULATED, EmulatedMount.localRefusal("/mnt/nas"))
        assertEquals(EmulatedMount.Refusal.NOT_EMULATED, EmulatedMount.localRefusal("/storage"))
    }

    /** Slashes a user typed must not change the verdict; normalize is shared with
     *  MountpointPolicy so the two cannot drift. */
    @Test fun `slash runs and trailing slashes do not change the route`() {
        assertEquals(
            routed(stock, "/storage/emulated/0/nfs"),
            routed(stock, "//storage//emulated/0/nfs/"),
        )
    }

    /**
     * A saved "010" that this side renumbers to 10 retargets the user into the WORK
     * PROFILE's storage, where his own apps cannot read what he mounted and the profile's
     * can. fslib.sh keeps the component, so the pref would also mount two different
     * directories on one phone: the button at profile 10's, the boot automount at 010's.
     */
    @Test fun `a media id is carried verbatim, never renumbered`() {
        for (mediaId in listOf("0", "10", "00", "010", "2147483648")) {
            assertEquals(
                mediaId,
                EmulatedMount.Route(
                    mountAt = "/mnt/user/0/emulated/$mediaId/nfs",
                    lowerDir = "/data/media/$mediaId/nfs",
                    appVisible = "/storage/emulated/$mediaId/nfs",
                ),
                routed(stock, "/storage/emulated/$mediaId/nfs"),
            )
        }
    }

    /** The master's own id comes from mountinfo, so the media id must not leak into it —
     *  fslib.sh appends `/$mid/$name` to whatever `mammon_emulated_master` printed. */
    @Test fun `a non-canonical media id does not reach the master path's own id`() {
        assertEquals("/mnt/user/0/emulated/00/nfs", routed(stock, "/storage/emulated/00/nfs").mountAt)
    }

    /**
     * The gate is a decimal-digit RANGE, not Char.isDigit: fslib.sh spells it
     * `case $mid in '' | *[!0-9]*)`, which refuses U+0660 while Char.isDigit accepts it,
     * and a component one side routes and the other refuses is the whole defect.
     */
    @Test fun `a media id outside ASCII 0-9 is not emulated storage`() {
        for (mediaId in listOf("+0", "-1", "\u0660", "0x0", "1_0", " 0", "0 ")) {
            val path = "/storage/emulated/$mediaId/nfs"
            assertEquals(path, EmulatedMount.Refusal.NOT_EMULATED, refused(stock, path))
            assertEquals(path, EmulatedMount.Refusal.NOT_EMULATED, EmulatedMount.localRefusal(path))
        }
    }

    /** A negative id used to route here and refuse in the module, and the app's own
     *  script had already `mkdir -p`'d /data/media/-1/nfs by then — teardown only
     *  umounts, so nothing ever removes it. */
    @Test fun `a negative media id is refused before any lower directory is named`() {
        assertEquals(EmulatedMount.Refusal.NOT_EMULATED, refused(stock, "/storage/emulated/-1/nfs"))
    }

    private fun routed(mountinfo: String, path: String): EmulatedMount.Route {
        val result = EmulatedMount.route(mountinfo, path)
        assertTrue("expected $path to route, got $result", result is EmulatedMount.Result.Routed)
        return (result as EmulatedMount.Result.Routed).route
    }

    private fun refused(mountinfo: String, path: String): EmulatedMount.Refusal {
        val result = EmulatedMount.route(mountinfo, path)
        assertTrue("expected $path to be refused, got $result", result is EmulatedMount.Result.Refused)
        return (result as EmulatedMount.Result.Refused).refusal
    }
}

/**
 * What RootMount does with a route: the mount lands on the master path, the directory is
 * created in the lower tree, and the user is told about the path he can browse.
 */
class RootMountEmulatedRouteTest {

    private val target = RootMount.Target(
        mountAt = "/mnt/user/0/emulated/0/nfs",
        visible = "/storage/emulated/0/nfs",
        lowerDir = "/data/media/0/nfs",
    )

    private val plain = RootMount.Target("/mnt/nas", "/mnt/nas", null)

    /** A real teardown would spawn su from a JVM test, so every verdict here injects
     *  one: [forbidden] also asserts that no teardown was even attempted. */
    private val forbidden: (String) -> Boolean = { path -> throw AssertionError("unexpected teardown of $path") }

    private val tornDown = mutableListOf<String>()

    private fun teardown(succeeds: Boolean): (String) -> Boolean = { path ->
        tornDown += path
        succeeds
    }

    /** A mkdir at the master path would go through the MediaProvider daemon instead of
     *  creating the mountpoint the mount needs; the lower directory is what makes the
     *  name appear in the view. */
    @Test fun `a routed mount script creates the lower directory and never the mountpoint`() {
        val scripts = listOf(
            RootMount.kernelMountScript("192.0.2.1", "/export", 2049, target.mountAt, "4.2", target.lowerDir),
            RootMount.fuseMountScript(
                "192.0.2.1", "/export", 2049, target.mountAt,
                RootMount.FuseLaunch("/data/app/base.apk", "/data/local/tmp/fuse.log", AuthIdentity.DEFAULT),
                target.lowerDir,
            ),
        )

        for (script in scripts) {
            assertTrue("lower directory is not created:\n$script", "mkdir -p '/data/media/0/nfs'" in script)
            assertFalse(
                "the master mountpoint must not be mkdir'd:\n$script",
                "mkdir -p '/mnt/user/0/emulated/0/nfs'" in script,
            )
            assertTrue("the mount does not target the master path:\n$script", "'/mnt/user/0/emulated/0/nfs'" in script)
        }
    }

    @Test fun `an unrouted mount script still creates its own mountpoint`() {
        val script = RootMount.kernelMountScript("192.0.2.1", "/export", 2049, "/mnt/nas", "4.2")

        assertTrue(script, "mkdir -p '/mnt/nas'" in script)
    }

    /** mount() is reachable without the UI, so the refusal has to live in the routing
     *  layer: this path used to be mkdir'd and mounted below the FUSE daemon. The exact
     *  message also proves the verdict was reached before any su spawned. */
    @Test fun `a storage-surface path is refused by mount itself, not only by the field`() {
        val launch = RootMount.FuseLaunch("/data/app/base.apk", "/data/local/tmp/fuse.log", AuthIdentity.DEFAULT)

        for (path in listOf("/data/media/0/nfs", "/storage/0123-4567/nfs")) {
            val result = RootMount.mount("192.0.2.1", "/export", 2049, path, launch)

            assertFalse(result.message, result.ok)
            assertEquals("$path is not a path inside shared storage", result.message)
            assertNull(result.appVisible)
        }
    }

    @Test fun `a mount visible at the app-visible path succeeds and reports that path`() {
        val mounts = """
            /dev/fuse /mnt/user/0/emulated/0/nfs fuse rw,user_id=0 0 0
            /dev/fuse /storage/emulated/0/nfs fuse rw,user_id=0 0 0
        """.trimIndent()

        val result = RootMount.mounted(target, mounts, forbidden)

        assertTrue(result.message, result.ok)
        assertEquals(RootMount.State.MOUNTED, result.stateAfter)
        assertEquals("fuse", result.fsType)
        assertEquals("/storage/emulated/0/nfs", result.appVisible)
        assertTrue(result.message, "/storage/emulated/0/nfs" in result.message)
    }

    /** Visible only at the master path means the kernel never replicated it, so the
     *  promise made to the user is unmet — and the mount is withdrawn again, since no
     *  screen names the master path and nothing else would ever remove it. */
    @Test fun `a mount that never propagated is torn down and reported as removed`() {
        val masterOnly = "/dev/fuse /mnt/user/0/emulated/0/nfs fuse rw,user_id=0 0 0"

        val result = RootMount.mounted(target, masterOnly, teardown(succeeds = true))

        assertFalse(result.message, result.ok)
        assertEquals(RootMount.MountDiagnosis.EMULATED_NOT_PROPAGATED, result.diagnosis)
        assertEquals("/storage/emulated/0/nfs", result.appVisible)
        assertEquals(listOf("/mnt/user/0/emulated/0/nfs"), tornDown)
        assertTrue("the master path must be named:\n${result.message}", "/mnt/user/0/emulated/0/nfs" in result.message)
        assertTrue("removal must be claimed only once it happened:\n${result.message}",
            "so it was removed again" in result.message)
    }

    /** A teardown that failed leaves a mount no screen names still standing, so it gets
     *  its own verdict rather than the removal claim it did not earn. */
    @Test fun `a teardown that fails is not reported as a removal`() {
        val masterOnly = "/dev/fuse /mnt/user/0/emulated/0/nfs fuse rw,user_id=0 0 0"

        val result = RootMount.mounted(target, masterOnly, teardown(succeeds = false))

        assertFalse(result.message, result.ok)
        assertEquals(RootMount.MountDiagnosis.EMULATED_NOT_PROPAGATED_STUCK, result.diagnosis)
        assertEquals(listOf("/mnt/user/0/emulated/0/nfs"), tornDown)
        assertFalse("a failed removal must not read as done:\n${result.message}", "removed it again" in result.message)
    }

    /** An unreadable /proc/1/mounts is no evidence either way, exactly as for
     *  /proc/filesystems; it must not harden into a propagation claim or a teardown. */
    @Test fun `an unreadable mount table leaves a routed mount unknown, never not-propagated`() {
        val result = RootMount.mounted(target, "", forbidden)

        assertTrue(result.message, result.ok)
        assertEquals(RootMount.State.UNKNOWN, result.stateAfter)
        assertNull(result.diagnosis)
    }

    /** An in-place route has nowhere else to look — mountAt IS the app-visible path — so
     *  a missing mount is the generic failure and there is nothing to tear down. */
    @Test fun `an in-place route never reports a propagation failure`() {
        val inPlace = RootMount.Target(
            mountAt = "/storage/emulated/0/nfs",
            visible = "/storage/emulated/0/nfs",
            lowerDir = "/data/media/0/nfs",
        )

        val present = RootMount.mounted(inPlace, "/dev/fuse /storage/emulated/0/nfs fuse rw 0 0", forbidden)
        assertTrue(present.message, present.ok)
        assertEquals("/storage/emulated/0/nfs", present.appVisible)

        val absent = RootMount.mounted(inPlace, "/dev/root / ext4 ro 0 0", forbidden)
        assertFalse(absent.message, absent.ok)
        assertEquals(RootMount.MountDiagnosis.GENERIC, absent.diagnosis)
    }

    /** Both path questions are answered from ONE parse of the capture; these are the
     *  verdicts that vary by capture shape, so a parse-once refactor that moved any of
     *  them — above all the blank-is-not-evidence guard — fails here. */
    @Test fun `every capture shape keeps its verdict when the table is parsed once`() {
        fun verdict(text: String?) = RootMount.mounted(target, text, forbidden).let { it.ok to it.stateAfter }

        assertEquals(true to RootMount.State.UNKNOWN, verdict(null))
        assertEquals(true to RootMount.State.UNKNOWN, verdict(""))
        assertEquals(true to RootMount.State.UNKNOWN, verdict("  \n \n"))
        assertEquals(false to RootMount.State.UNKNOWN, verdict("/dev/root / ext4 ro 0 0"))
        assertEquals(true to RootMount.State.MOUNTED, verdict("/dev/fuse /storage/emulated/0/nfs fuse rw 0 0"))
        assertEquals(
            RootMount.State.UNKNOWN,
            RootMount.classifyUnmountResult(target, RootMount.SuResult(1, null, "umount: bad")).stateAfter,
        )
    }

    @Test fun `an unrouted mount carries no app-visible path and keeps its own verdict`() {
        val result = RootMount.mounted(plain, "192.0.2.1:/export /mnt/nas nfs rw,vers=4.2 0 0", forbidden)

        assertTrue(result.message, result.ok)
        assertEquals(RootMount.State.MOUNTED, result.stateAfter)
        assertNull(result.appVisible)
    }

    /** A slave does not propagate to its master, so the umount targets the master path;
     *  a mount still standing there is not gone whatever the view says. */
    @Test fun `a routed unmount stays a failure while the master path still holds the mount`() {
        val stillThere = "/dev/fuse /mnt/user/0/emulated/0/nfs fuse rw,user_id=0 0 0"

        val result = RootMount.classifyUnmountResult(
            target,
            RootMount.SuResult(1, stillThere, "umount: /mnt/user/0/emulated/0/nfs: Device or resource busy"),
        )

        assertFalse(result.message, result.ok)
        assertEquals("target is busy — close apps using it first", result.message)
        assertEquals(RootMount.State.MOUNTED, result.stateAfter)
    }

    /** The kernel withdraws the propagated copy with the master mount; a copy left
     *  behind must not read as unmounted. */
    @Test fun `a routed unmount notices a copy left behind in the view`() {
        val leftover = "/dev/fuse /storage/emulated/0/nfs fuse rw,user_id=0 0 0"

        val result = RootMount.classifyUnmountResult(
            target,
            RootMount.SuResult(1, leftover, "umount: /mnt/user/0/emulated/0/nfs: Invalid argument"),
        )

        assertFalse(result.message, result.ok)
        assertEquals(RootMount.State.MOUNTED, result.stateAfter)
    }

    @Test fun `a routed unmount that cleared both paths reports the app-visible one`() {
        val result = RootMount.classifyUnmountResult(
            target,
            RootMount.SuResult(0, "/dev/root / ext4 ro 0 0", ""),
        )

        assertTrue(result.message, result.ok)
        assertEquals("unmounted /storage/emulated/0/nfs", result.message)
        assertEquals("/storage/emulated/0/nfs", result.appVisible)
        assertEquals(RootMount.State.NOT_MOUNTED, result.stateAfter)
    }
}
