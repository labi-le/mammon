package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * fslib.sh's boot automount opens /dev/fuse and hands the descriptor to mount(2),
 * exactly like RootMount.kt's fuseMountScript. Magisk's /system/bin/sh is mksh, which
 * sets close-on-exec on fds >= 3 opened by `exec` redirection — so the mount child must
 * inherit the fd from a GROUP redirection, not from `exec 3<>`. These pins run the real
 * fslib.sh through its host dry-run overrides (MAMMON_PROC_MOUNTS, MAMMON_PROC_MOUNTINFO,
 * MAMMON_MEDIA_ROOT, MAMMON_FUSE_DEVICE) under mksh: the fd pin, the refusals that
 * survived the emulated-routing change, the routed path that replaced the old blanket
 * refusal of shared storage, and the routing rules that exist ONLY in the shell half of
 * the pair — the Kotlin twin has its own fixtures, and a rule pinned on one side only is
 * a rule that can be deleted on the other with the suite still green.
 */
class FslibAutomountFuseFdTest {

    @Test fun `automount keeps fd 3 open across exec under mksh`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)
        val fslib = fslib()
        val (log, record) = runAutomount(fslib, mksh!!, "/mnt/nas")
        assertTrue(
            "automount mount stub saw fd 3 closed (mksh cloexec on exec-redirection fds >= 3):\n" +
                "record:\n$record\nlog:\n$log",
            record.contains("FD_OK"),
        )
    }

    /** The refusal NARROWED rather than vanished: a physical volume under /storage has
     *  no emulated view to route through, so it is still refused before any mount. */
    @Test fun `automount still refuses a non-emulated storage mountpoint before mounting`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)
        val (log, record) = runAutomount(fslib(), mksh!!, "/storage/0123-4567/nfs")
        assertTrue(
            "automount must SKIP a non-emulated storage mountpoint before any mount call:\n$log",
            log.contains(
                "SKIPPED: mountpoint '/storage/0123-4567/nfs' is Android's own storage;" +
                    " of that tree only a path inside emulated storage can be routed",
            ),
        )
        assertFalse("mount must never run for a refused mountpoint:\n$record", record.contains("MOUNT "))
    }

    /** vold bind-mounts ext4 over Android/data and Android/obb itself. */
    @Test fun `automount refuses a mountpoint vold owns before mounting`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)
        val (log, record) = runAutomount(fslib(), mksh!!, "/storage/emulated/0/Android/nfs")
        assertTrue(
            "automount must SKIP a path vold owns before any mount call:\n$log",
            log.contains("SKIPPED: mountpoint '/storage/emulated/0/Android/nfs' is under Android/, which vold owns"),
        )
        assertFalse("mount must never run for a refused mountpoint:\n$record", record.contains("MOUNT "))
    }

    /** The path the user asked for all along: it must reach the mount, and the mount
     *  must land on the master path rather than the slave view that propagates nowhere. */
    @Test fun `automount routes a subdirectory of emulated storage to the shared master`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)
        val (log, record) = runAutomount(fslib(), mksh!!, "/storage/emulated/0/nfs")
        assertTrue(
            "automount must announce the route it took:\n$log",
            log.contains(
                "automount: routing /storage/emulated/0/nfs to /mnt/user/0/emulated/0/nfs," +
                    " visible at /storage/emulated/0/nfs",
            ),
        )
        assertTrue("the mount must be attempted at the master path:\n$record", record.contains("MOUNT "))
        assertTrue(
            "the mount must target the master path, not the slave view:\n$record",
            record.lineSequence().any { it.startsWith("MOUNT ") && it.endsWith("/mnt/user/0/emulated/0/nfs") },
        )
        // The pre-mount and post-mount checks read the same static table, so on a stock
        // tree the run must reach A5's teardown rather than claim a mount nothing saw.
        assertTrue(
            "a mount that did not propagate must be torn down, not left at the master:\n$log",
            log.contains(
                "FAILED: mount at /mnt/user/0/emulated/0/nfs never propagated to" +
                    " /storage/emulated/0/nfs; unmounted /mnt/user/0/emulated/0/nfs",
            ),
        )
    }

    /**
     * The one that had no test at all: without the guard a hand-edited pref of
     * /sdcard/../../data/local/nfs became `mkdir -p` and a FUSE mount at /data/local/nfs
     * as root on every boot. The refusal has to be the traversal one, and no mount may
     * run — a guard that only changes the log line is still a root mount off the tree.
     */
    @Test fun `automount refuses a traversal out of the emulated tree before mounting`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)
        for (mp in listOf("/sdcard/../../data/local/nfs", "/storage/emulated/0/../nfs")) {
            val (log, record) = runAutomount(fslib(), mksh!!, mp)
            assertTrue(
                "automount must name the traversal rather than the generic storage refusal:\n$log",
                log.contains(
                    "SKIPPED: mountpoint '$mp' has a . or .. component;" +
                        " save the path it really names instead",
                ),
            )
            assertFalse("mount must never run for a traversal:\n$record", record.contains("MOUNT "))
        }
    }

    /** The success line no other case can reach: the propagated copy only exists once
     *  the mount ran, so the stub appends it exactly like the kernel would. */
    @Test fun `automount reports MOUNTED at the app-visible path once the mount propagated`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)
        val (log, record) = runAutomount(
            fslib(),
            mksh!!,
            "/storage/emulated/0/nfs",
            propagatedTo = "/storage/emulated/0/nfs",
        )
        assertTrue(
            "a propagated routed mount must be reported at the app-visible path:\n$log",
            log.contains("MOUNTED: 192.0.2.1:2049/export at /storage/emulated/0/nfs via fuse (pid "),
        )
        assertTrue(
            "the mount itself still lands on the master path:\n$record",
            record.lineSequence().any { it.startsWith("MOUNT ") && it.endsWith("/mnt/user/0/emulated/0/nfs") },
        )
    }

    /** A view carrying a bare shared: SENDS propagation, so it is its own master and the
     *  mount is made where it stands; refusing there denies a device the route works on. */
    @Test fun `automount mounts in place on a view that is its own master`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)
        val (log, record) = runAutomount(
            fslib(),
            mksh!!,
            "/storage/emulated/0/nfs",
            "2890 957 0:122 / /storage/emulated rw,relatime shared:805 - fuse /dev/fuse rw,user_id=0\n",
        )
        assertTrue(
            "an own-master view must route in place, not SKIP for want of a peer:\n$log",
            log.contains(
                "automount: routing /storage/emulated/0/nfs to /storage/emulated/0/nfs," +
                    " visible at /storage/emulated/0/nfs",
            ),
        )
        assertTrue(
            "the mount must be attempted at the view itself:\n$record",
            record.lineSequence().any { it.startsWith("MOUNT ") && it.endsWith("/storage/emulated/0/nfs") },
        )
    }

    /** mangle_path octal-escapes the mount-point field, so an un-decoded master path
     *  names a directory that does not exist and the mount lands nowhere. The escape sits
     *  before a digit on purpose: printf '%b' eats that digit as part of the octal. */
    @Test fun `automount decodes an octal escape in the derived master path`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)
        val (log, record) = runAutomount(
            fslib(),
            mksh!!,
            "/storage/emulated/0/nfs",
            "2860 918 0:122 / /mnt/user\\0400/emulated rw,relatime shared:805 - fuse /dev/fuse rw,user_id=0\n" +
                "2890 957 0:122 / /storage/emulated rw,relatime master:805 - fuse /dev/fuse rw,user_id=0\n",
        )
        assertTrue(
            "the escaped space must decode to one space with the digit intact:\n$log",
            log.contains(
                "automount: routing /storage/emulated/0/nfs to /mnt/user 0/emulated/0/nfs," +
                    " visible at /storage/emulated/0/nfs",
            ),
        )
        assertTrue(
            "the mount must target the decoded path:\n$record",
            record.lineSequence().any { it.startsWith("MOUNT ") && it.endsWith("/mnt/user 0/emulated/0/nfs") },
        )
    }

    /** Two candidates in one group, pinned in BOTH orders: only that separates first-wins
     *  from last-wins and from a preference for one spelling. */
    @Test fun `automount takes the first candidate in the peer group`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)
        val user = "2860 918 0:122 / /mnt/user/0/emulated rw,relatime shared:805 - fuse /dev/fuse rw,user_id=0\n"
        val runtime =
            "2861 918 0:122 / /mnt/runtime/write/emulated rw,relatime shared:805 - fuse /dev/fuse rw,user_id=0\n"
        val slave = "2890 957 0:122 / /storage/emulated rw,relatime master:805 - fuse /dev/fuse rw,user_id=0\n"
        for ((mountinfo, master) in listOf(
            user + runtime + slave to "/mnt/user/0/emulated",
            runtime + user + slave to "/mnt/runtime/write/emulated",
        )) {
            val (log, record) = runAutomount(fslib(), mksh!!, "/storage/emulated/0/nfs", mountinfo)
            assertTrue(
                "the first candidate in the group must win, here $master:\n$log",
                log.contains(
                    "automount: routing /storage/emulated/0/nfs to $master/0/nfs," +
                        " visible at /storage/emulated/0/nfs",
                ),
            )
            assertTrue(
                "the mount must target the first candidate:\n$record",
                record.lineSequence().any { it.startsWith("MOUNT ") && it.endsWith("$master/0/nfs") },
            )
        }
    }

    /** master: is the IMMEDIATE master's group, which fs/pnode.c get_dominating_id may
     *  leave with no reachable line, so the propagate_from group is tried first — and in
     *  its own pass, which is what keeps mountinfo line order from deciding instead. */
    @Test fun `automount prefers the propagate_from group over master`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)
        val dominating = "2860 918 0:122 / /mnt/user/0/emulated rw,relatime shared:795 - fuse /dev/fuse rw,user_id=0\n"
        val immediate =
            "2861 918 0:122 / /mnt/pass_through/0/emulated rw,relatime shared:1 - fuse /dev/fuse rw,user_id=0\n"
        val slave =
            "2890 957 0:122 / /storage/emulated rw,relatime master:1 propagate_from:795 - fuse /dev/fuse rw,user_id=0\n"
        for (mountinfo in listOf(dominating + immediate + slave, immediate + dominating + slave)) {
            val (log, record) = runAutomount(fslib(), mksh!!, "/storage/emulated/0/nfs", mountinfo)
            assertTrue(
                "the propagate_from group must be tried before master, whatever the line order:\n$log",
                log.contains(
                    "automount: routing /storage/emulated/0/nfs to /mnt/user/0/emulated/0/nfs," +
                        " visible at /storage/emulated/0/nfs",
                ),
            )
            assertFalse("the master group must not win:\n$log", log.contains("/mnt/pass_through/0/emulated/0/nfs"))
            assertTrue(
                "the mount must target the propagate_from group's candidate:\n$record",
                record.lineSequence().any { it.startsWith("MOUNT ") && it.endsWith("/mnt/user/0/emulated/0/nfs") },
            )
        }
    }

    /** The /mnt/user spellings the app mounts LITERALLY: the module's glob spans slashes,
     *  so without a digit gate one saved pref produced two different mounts — the button
     *  at the typed path, the boot automount at the derived master. */
    @Test fun `automount routes only the digit-shaped user id under mnt user`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)
        for (mp in listOf("/mnt/user/abc/emulated/0/nfs", "/mnt/user/x/y/emulated/0/nfs")) {
            val (log, record) = runAutomount(fslib(), mksh!!, mp)
            assertFalse("$mp must not be routed at all:\n$log", log.contains("automount: routing"))
            assertTrue(
                "$mp must be mounted literally, as the app does:\n$record",
                record.lineSequence().any { it.startsWith("MOUNT ") && it.endsWith(mp) },
            )
        }
        val (log, _) = runAutomount(fslib(), mksh!!, "/mnt/user/0/emulated/0/nfs")
        assertTrue(
            "the digit-shaped spelling must still route:\n$log",
            log.contains(
                "automount: routing /mnt/user/0/emulated/0/nfs to /mnt/user/0/emulated/0/nfs," +
                    " visible at /storage/emulated/0/nfs",
            ),
        )
    }

    /**
     * Same ART constraint RootMountScriptHygieneTest pins for fuseMountScript: the
     * flag must sit between "/" and the daemon class — app_process feeds leading
     * dash-args to ART (unknown ones exit before main) and a trailing flag leaks into
     * main()'s argv. The automount line must keep that shape, or the boot mount dies
     * exactly like the v0.6.5 app-side one did.
     */
    @Test fun `automount passes nice-name between the class dir and the daemon class`() {
        val launch = fslib().readLines().first { "app.mammon.FuseDaemonKt" in it }
        val classIdx = launch.indexOf("app.mammon.FuseDaemonKt")
        val slashIdx = launch.indexOf(" / ")
        val niceIdx = launch.indexOf("--nice-name=app.mammon:fuse")
        assertTrue("no daemon class token in fslib.sh launch line: $launch", classIdx >= 0)
        assertTrue("no classpath-dir argument in fslib.sh launch line: $launch", slashIdx >= 0)
        assertTrue("no --nice-name in fslib.sh launch line: $launch", niceIdx >= 0)
        assertTrue(
            "--nice-name must sit between the / argument and app.mammon.FuseDaemonKt (leading flags die in ART, trailing ones leak into main):\n$launch",
            niceIdx > slashIdx && classIdx > niceIdx,
        )
    }

    /**
     * The storage-surface case list matches at a component boundary, so an uncollapsed
     * `//data/media/0/nfs` slips past it and becomes a FUSE mount as root BELOW the
     * MediaProvider daemon on every boot. The refusal alone is not the pin: a guard that
     * only reaches the log line still leaves that mount standing, so the record must
     * carry no MOUNT at all.
     */
    @Test fun `automount collapses slash runs before judging the storage surface`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)
        for (mp in listOf("//data/media/0/nfs", "/data//media/0/nfs", "/data/media//0//nfs")) {
            val (log, record) = runAutomount(fslib(), mksh!!, mp)
            assertTrue(
                "the collapsed spelling must earn the storage refusal, not fall through:\n$log",
                log.contains(
                    "SKIPPED: mountpoint '/data/media/0/nfs' is Android's own storage;" +
                        " of that tree only a path inside emulated storage can be routed",
                ),
            )
            assertFalse("mount must never run for '$mp':\n$record", record.contains("MOUNT "))
        }
    }

    /** The control the refusal above needs: collapsing must NARROW the surface, so the
     *  one spelling that is genuinely routable still reaches MOUNTED. */
    @Test fun `collapsing slash runs still lets a doubled emulated path mount`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)
        val (log, record) = runAutomount(
            fslib(),
            mksh!!,
            "//storage//emulated/0/nfs/",
            propagatedTo = "/storage/emulated/0/nfs",
        )
        assertTrue(
            "a doubled but routable spelling must still reach the app-visible mount:\n$log",
            log.contains("MOUNTED: 192.0.2.1:2049/export at /storage/emulated/0/nfs via fuse (pid "),
        )
        assertTrue(
            "the mount must land on the master path:\n$record",
            record.lineSequence().any { it.startsWith("MOUNT ") && it.endsWith("/mnt/user/0/emulated/0/nfs") },
        )
    }

    /**
     * MountpointPolicy.normalize trims BEFORE it collapses and before it strips a
     * trailing slash, and the module has to do all three in that order: one hand-edited
     * trailing space otherwise walks a pref past the Android/ refusal, and routes a boot
     * mount to a path the app's Unmount — which normalizes the same string — can never
     * name. Only a hand edit can produce these spellings, since MainActivity trims before
     * it saves, and the prefs file is exactly what this script reads. The EXPECTATIONS are
     * the app's own verdict on the same string; the literal spellings are what keeps that
     * from being a tautology, since a corpus and an expectation both derived from
     * Char.isWhitespace would agree with the trim deleted on both sides.
     */
    @Test fun `automount trims a whitespace-edged mountpoint exactly as the app does`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)
        // U+00A0 counts because Kotlin reads Char.isWhitespace as
        // Character.isWhitespace OR isSpaceChar; [[:space:]] alone would miss it.
        for (mp in listOf(
            "/storage/emulated/0/Android ",
            "\t/storage/emulated/0/Android",
            "/storage/emulated/0/Android\u00A0",
            // Every saveable member of the set at once, so one missing table entry leaves
            // a residue that changes the verdict — a per-character corpus would cost 26
            // more mksh runs to localise the same drift.
            "$WHITESPACE_EDGE/storage/emulated/0/Android$WHITESPACE_EDGE",
        )) {
            assertEquals(
                "the app must refuse '$mp', or this case proves nothing",
                EmulatedMount.Refusal.RESERVED_NAME,
                MountpointPolicy.refusalFor(mp),
            )
            val (log, record) = runAutomount(fslib(), mksh!!, mp)
            assertTrue(
                "the module must earn the same Android/ refusal on the trimmed spelling:\n$log",
                log.contains(
                    "SKIPPED: mountpoint '${MountpointPolicy.normalize(mp)}'" +
                        " is under Android/, which vold owns",
                ),
            )
            assertFalse("mount must never run for '$mp':\n$record", record.contains("MOUNT "))
        }
        for (mp in listOf(
            "/storage/emulated/0/nfs ",
            " /storage/emulated/0/nfs",
            "/storage/emulated/0/nfs\t",
            "/storage/emulated/0/nfs\u00A0",
            // Trimming after the slash strip would leave this one's trailing slash on.
            "/storage/emulated/0/nfs/ ",
            // The control that keeps this a trim rather than a strip.
            "/storage/emulated/0/my share",
            // mangle_path's \040 is decoded in mountinfo paths only; a saved mountpoint
            // carrying those characters is four literal ones on both sides.
            "/storage/emulated/0/nfs\\040",
        )) {
            val result = EmulatedMount.route(STOCK_MOUNTINFO, mp)
            assertTrue("the app must route '$mp', or this case proves nothing", result is EmulatedMount.Result.Routed)
            val route = (result as EmulatedMount.Result.Routed).route
            val (log, record) = runAutomount(fslib(), mksh!!, mp)
            assertTrue(
                "the module must route '$mp' exactly where the app routes it:\n$log",
                log.contains(
                    "automount: routing ${MountpointPolicy.normalize(mp)} to ${route.mountAt}," +
                        " visible at ${route.appVisible}",
                ),
            )
            assertTrue(
                "the mount must land on the app's target:\n$record",
                record.lineSequence().any { it.startsWith("MOUNT ") && it.endsWith(route.mountAt) },
            )
        }
        // Before the trim this one missed every /sdcard case label and became a root
        // mkdir and a FUSE mount ON the sdcard symlink.
        assertEquals(
            EmulatedMount.Refusal.IS_TREE_ROOT,
            MountpointPolicy.refusalFor("/sdcard "),
        )
        val (rootLog, rootRecord) = runAutomount(fslib(), mksh!!, "/sdcard ")
        assertTrue(
            "a tree root with a trailing space must earn the tree-root refusal:\n$rootLog",
            rootLog.contains("SKIPPED: mountpoint '/sdcard' is the emulated tree root; mount a subdirectory instead"),
        )
        assertFalse("mount must never run for '/sdcard ':\n$rootRecord", rootRecord.contains("MOUNT "))
    }

    /** Routing is handed the SAVED spelling because normalize is not idempotent: this
     *  one's trailing slash hides a space from the trim, so one pass keeps the space and
     *  a second eats it. Normalizing twice mounts where the app's Unmount — one pass on
     *  the same pref — cannot name, and every log line still reads well formed. */
    @Test fun `automount routes the saved mountpoint through exactly one normalize`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)
        val mp = "/storage/emulated/0/nfs /"
        val once = MountpointPolicy.normalize(mp)
        // Both passes asserted, or a normalize that became idempotent would leave this
        // case green while retiring the contract it exists for.
        assertEquals("one normalize must keep the space", "/storage/emulated/0/nfs ", once)
        assertEquals("a second must eat it", "/storage/emulated/0/nfs", MountpointPolicy.normalize(once))
        val result = EmulatedMount.route(STOCK_MOUNTINFO, mp)
        assertTrue("the app must route '$mp', or this case proves nothing", result is EmulatedMount.Result.Routed)
        val route = (result as EmulatedMount.Result.Routed).route
        val (log, record) = runAutomount(fslib(), mksh!!, mp)
        assertTrue(
            "the module must route the once-normalized spelling, not a twice-normalized one:\n$log",
            log.contains("automount: routing $once to ${route.mountAt}, visible at ${route.appVisible}"),
        )
        assertTrue(
            "the mount must land on the app's target '${route.mountAt}':\n$record",
            record.lineSequence().any { it.startsWith("MOUNT ") && it.endsWith(route.mountAt) },
        )
    }

    /**
     * The behavioural case above cannot see an entry the module has that the JVM has
     * not: an over-wide table trims a character the app keeps, and every expectation
     * there is derived from the app, so it stays green. Round 5 measured exactly that —
     * adding U+0085 to fslib.sh drifted silently. This compares the shipped table
     * against the predicate itself, so both directions fail here, per character, and a
     * JDK whose set moves fails too.
     */
    @Test fun `the module's trim table is exactly the set the JVM calls whitespace`() {
        assertEquals(
            "fslib.sh's trim table has drifted from Char.isWhitespace",
            codePoints(JVM_WHITESPACE.map { it.toString() }),
            codePoints(moduleTrimTable(fslib())),
        )
    }

    /** The table as fslib.sh ships it: comma-separated runs of `\NNN` octal escapes,
     *  which are UTF-8 bytes rather than characters, so they decode per entry. */
    private fun moduleTrimTable(fslib: File): List<String> {
        val found = TW_SET.findAll(fslib.readText()).map { it.groupValues[1] }.toList()
        assertEquals("expected exactly one tw_set assignment in ${fslib.path}", 1, found.size)
        return found.single().split(',').map { entry ->
            val escapes = OCTAL.findAll(entry).toList()
            assertEquals("table entry '$entry' is not a pure run of octal escapes", entry.length, escapes.size * 4)
            String(escapes.map { it.groupValues[1].toInt(8).toByte() }.toByteArray(), Charsets.UTF_8)
        }
    }

    private fun codePoints(entries: Collection<String>): List<String> =
        entries.map { s -> s.map { "U+%04X".format(it.code) }.joinToString("+") }.sorted()

    /** Builds the harness once; the automount path needs the real magisk-module/fslib.sh. */
    private fun fslib(): File {
        val f = File(System.getProperty("mammon.fslib") ?: "../magisk-module/fslib.sh")
        assertTrue("fslib.sh not found at $f", f.isFile)
        return f
    }

    /** Runs mammon_automount_main under mksh with the given mountpoint saved, returning
     *  the load.log text and the mount stub's record. */
    private fun runAutomount(
        fslib: File,
        mksh: String,
        mountpoint: String,
        mountinfo: String = STOCK_MOUNTINFO,
        propagatedTo: String? = null,
    ): Pair<String, String> {
        val dir = File.createTempFile("mammon-fslib-", "").apply { delete(); mkdirs(); deleteOnExit() }
        File(dir, "automount").writeText("1")
        File(dir, "mammon.xml").writeText(
            // Android's SharedPreferences writes one entry per line, indented; the
            // harness must mirror that or mammon_pref_value's line-anchored sed misses.
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" +
                "<map>\n" +
                "    <string name=\"host\">192.0.2.1</string>\n" +
                "    <string name=\"export\">/export</string>\n" +
                "    <int name=\"port\" value=\"2049\" />\n" +
                "    <string name=\"mountpoint\">$mountpoint</string>\n" +
                "</map>\n",
        )
        val mounts = File(dir, "mounts").apply { writeText("") } // nothing mounted at $mountpoint
        // Without this the script would read the workstation's own mountinfo, which has
        // no /storage/emulated at all, and every emulated path would refuse for want of
        // a shared peer instead of taking the branch under test.
        File(dir, "mountinfo").writeText(mountinfo)
        val log = File(dir, "load.log").apply { writeText("") }
        val record = File(dir, "record").apply { writeText("") }

        val path = File(dir, "path").apply { mkdirs() }
        // A real mount's propagated copy appears in the table only after mount(2) ran,
        // which is the one shape a static fixture cannot have: the readiness check and
        // the propagation check read this file before and after the same call.
        val propagate = propagatedTo?.let { "printf 'fuse %s fuse rw 0 0\\n' '$it' >> '${mounts.path}'\n" }.orEmpty()
        File(path, "mount").apply {
            writeText(
                "#!/bin/sh\n" +
                    "if [ -e /proc/self/fd/3 ]; then echo FD_OK; else echo FD_MISSING; fi >> '${record.path}'\n" +
                    "echo \"MOUNT ${'$'}@\" >> '${record.path}'\n" +
                    propagate +
                    "exit 0\n",
            )
            setExecutable(true)
        }
        // Everything else the automount path calls resolves to a silent success, and the
        // reachability/readiness probes are stubbed so the run reaches the mount at once.
        for (name in listOf("app_process", "modprobe", "pm", "nc", "grep", "tail", "wc", "sleep", "umount", "mkdir")) {
            val body = if (name == "pm") "echo package:/data/app/base.apk\n" else "exit 0\n"
            File(path, name).apply { writeText("#!/bin/sh\n$body"); setExecutable(true) }
        }

        val builder = ProcessBuilder(mksh, "-c", ". '${fslib.path}' && mammon_automount_main '${log.path}'")
        builder.environment()["PATH"] = path.path + ":" + (System.getenv("PATH").orEmpty())
        builder.environment()["MODDIR"] = dir.path
        builder.environment()["MAMMON_PREFS_FILE"] = File(dir, "mammon.xml").path
        builder.environment()["MAMMON_PROC_MOUNTS"] = mounts.path
        builder.environment()["MAMMON_PROC_MOUNTINFO"] = File(dir, "mountinfo").path
        builder.environment()["MAMMON_MEDIA_ROOT"] = File(dir, "media").path
        builder.environment()["MAMMON_FUSE_DEVICE"] = "/dev/fuse"
        builder.environment()["MAMMON_WAIT_TRIES"] = "1"
        builder.environment()["MAMMON_WAIT_INTERVAL"] = "0"
        val proc = builder.start()
        proc.inputStream.bufferedReader().use { it.readText() }
        proc.errorStream.bufferedReader().use { it.readText() }
        proc.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return log.readText() to record.readText()
    }

    private fun findOnPath(name: String): String? =
        (System.getenv("PATH").orEmpty().split(':').firstOrNull { File(it, name).canExecute() })
            ?.let { File(it, name).absolutePath }

    private companion object {
        const val SHELL_TIMEOUT_SECONDS = 60L

        /** The waydroid-shaped table: a fuse SLAVE view and the shared master of its
         *  group. No phone has executed this path, so it pins the rule, not a device. */
        const val STOCK_MOUNTINFO =
            "2860 918 0:122 / /mnt/user/0/emulated rw,relatime shared:805 - fuse /dev/fuse rw,user_id=0\n" +
                "2890 957 0:122 / /storage/emulated rw,relatime master:805 - fuse /dev/fuse rw,user_id=0\n"

        /** Derived, never listed: MountpointPolicy.normalize trims what the JVM answers
         *  isWhitespace to, so a JDK whose set moves must move the pin with it. */
        val JVM_WHITESPACE: List<Char> = (0..0xFFFF).map { it.toChar() }.filter { it.isWhitespace() }

        /** LF is left out because mammon_pref_value's line-anchored sed cannot read a
         *  value carrying one at all, so the module never sees the spelling
         *  (MountpointVerbatimTargetTest pins that whole class). Descending order also
         *  costs a single-pass trim its fixpoint: stripping runs in table order clears
         *  one character per pass from the leading edge. */
        val WHITESPACE_EDGE: String =
            JVM_WHITESPACE.filter { it != '\n' }.sortedDescending().joinToString("")

        val TW_SET = Regex("""tw_set=\$\(printf '([^']*)'\)""")
        val OCTAL = Regex("""\\([0-7]{3})""")
    }
}
