package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The saved mountpoint the app hands to root UNTOUCHED, and what the module does with
 * the same string.
 *
 * A non-emulated mountpoint is mounted and unmounted verbatim by design — normalize
 * decides, it never rewrites — so a spelling that is not absolute as saved becomes a
 * root `mkdir -p` of a RELATIVE path, resolved in whatever directory the su shell
 * happens to be in. A routed one cannot: its target is derived from the normalized
 * path, which is why the refusal here is branch-local and why the module re-asks
 * absoluteness only in its own non-emulated branch (fslib.sh).
 *
 * Only a hand edit of shared_prefs produces these spellings, since MainActivity trims
 * before it saves — and that file is exactly what the boot automount reads.
 *
 * The module column is EXECUTED against the real fslib.sh, never read off its comments.
 * The harness is a smaller sibling of FslibAutomountFuseFdTest's: no fd probe and no
 * propagation stub, because a refusal is proven by a log line and an empty record.
 */
class MountpointVerbatimTargetTest {

    /** Every spelling in the class, with the verdict both entrances get for it. */
    private val refusedAsSaved = listOf(
        " /mnt/nas" to EmulatedMount.Refusal.NOT_ABSOLUTE,
        "\t/mnt/nas" to EmulatedMount.Refusal.NOT_ABSOLUTE,
        " " to EmulatedMount.Refusal.NOT_ABSOLUTE,
        "" to EmulatedMount.Refusal.NOT_ABSOLUTE,
        "mnt/nas" to EmulatedMount.Refusal.NOT_ABSOLUTE,
        "./nfs" to EmulatedMount.Refusal.NOT_ABSOLUTE,
        "/" to EmulatedMount.Refusal.IS_FILESYSTEM_ROOT,
    )

    /**
     * The reproduction: before the refusal existed every one of these reached
     * `kernelMountScript` and put its own spelling inside `mkdir -p '...'`.
     */
    @Test fun `a mountpoint that is not absolute as saved never becomes a root mkdir`() {
        for ((mp, _) in refusedAsSaved) {
            val spawned = mutableListOf<String>()

            val result = RootMount.mount(HOST, EXPORT, PORT, mp, launch) { script ->
                spawned += script
                RootMount.SuResult(0, "", "")
            }

            val relative = mkdirTargets(spawned).filterNot { it.startsWith("/") }
            assertTrue(
                "root would mkdir -p a relative path $relative for '$mp':\n${spawned.joinToString("\n--\n")}",
                relative.isEmpty(),
            )
            assertEquals("'$mp' must be refused before any su spawn", emptyList<String>(), spawned)
            assertFalse("'$mp' must not report success", result.ok)
        }
    }

    /** The message has to name the path fault; a diagnosis-less exit code would send the
     *  user to the mount ladder for something only the pref can fix. */
    @Test fun `the refusal names the path rather than an exit code`() {
        val notAbsolute = refuse(" /mnt/nas")
        assertTrue(notAbsolute, notAbsolute.contains("not an absolute path"))
        // Quoted, or a leading space is invisible in the status line.
        assertTrue(notAbsolute, notAbsolute.contains("' /mnt/nas'"))
        assertTrue(refuse("/"), refuse("/").contains("filesystem root"))
    }

    /** Both entrances read the same function, so pinning it pins the mountpoint field
     *  too: MainActivity has no absoluteness rule of its own any more. */
    @Test fun `the shared seam is what refuses, so the field answers the same`() {
        for ((mp, refusal) in refusedAsSaved) {
            assertEquals("'$mp'", refusal, MountpointPolicy.refusalFor(mp))
        }
    }

    /**
     * The controls that keep this a refusal of the unmountable rather than a narrowing
     * of the product: trailing whitespace stays absolute and is mounted VERBATIM on both
     * sides, and a routed path is derived from the normalized spelling, so a leading
     * space cannot reach root through it.
     */
    @Test fun `absolute spellings keep mounting, whitespace-edged routed ones included`() {
        assertNull(MountpointPolicy.refusalFor("/mnt/nas"))
        assertNull(MountpointPolicy.refusalFor("/mnt/nas "))
        assertNull(MountpointPolicy.refusalFor("/storage/emulated/0/nfs"))
        assertNull(MountpointPolicy.refusalFor(" /storage/emulated/0/nfs"))

        for (mp in listOf("/mnt/nas", "/mnt/nas ")) {
            val spawned = mutableListOf<String>()
            RootMount.mount(HOST, EXPORT, PORT, mp, launch) { script ->
                spawned += script
                RootMount.SuResult(0, "", "")
            }
            assertEquals(
                "'$mp' must reach root unchanged",
                listOf(mp),
                mkdirTargets(spawned.take(1)),
            )
        }

        val routed = mutableListOf<String>()
        RootMount.mount(HOST, EXPORT, PORT, " /storage/emulated/0/nfs", launch) { script ->
            routed += script
            RootMount.SuResult(0, if (routed.size == 1) STOCK_MOUNTINFO else "", "")
        }
        assertEquals(
            "a routed target is derived, so the typed spelling never reaches root",
            listOf("/data/media/0/nfs"),
            mkdirTargets(routed.drop(1).take(1)),
        )
    }

    /** Refusing to CREATE a mount must not strand one an older build already made:
     *  the refusal is about the path, decided before any su, and knows nothing about
     *  what is mounted. */
    @Test fun `unmount still reaches a mount standing at a refused path`() {
        val spawned = mutableListOf<String>()

        val result = RootMount.unmount(" /mnt/nas") { script ->
            spawned += script
            RootMount.SuResult(0, "/dev/root / ext4 ro 0 0\n", "")
        }

        assertEquals(listOf(RootMount.unmountScript(" /mnt/nas")), spawned)
        assertTrue(result.message, result.ok)
    }

    /** fslib.sh's own absoluteness guards had no test: a /tmp deletion of either left
     *  the suite green while a hand-edited pref became a boot-time root mkdir. */
    @Test fun `the module refuses the same spellings, measured by running fslib sh`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)
        for ((mp, _) in refusedAsSaved) {
            if (mp.isEmpty()) continue
            val (log, record) = runAutomount(mksh!!, mp)
            assertTrue(
                "the module must refuse '$mp' for want of absoluteness:\n$log",
                log.contains("is not absolute"),
            )
            assertFalse("mount must never run for '$mp':\n$record", record.contains("MOUNT "))
        }
    }

    /**
     * The boot log is the only diagnostic channel there is, and the skip above tests the
     * NORMALIZED value, which ' ', '\t', '/', '//' and ' / ' all empty: naming only that
     * printed one identical line for five unrelated hand edits. What is pinned is the
     * saved spelling appearing verbatim and the lines being pairwise distinct, not the
     * prose around them, so the wording stays the module's to choose. './nfs' beside
     * './nfs/' is the pair that needs BOTH values named: the saved spellings differ while
     * the normalized ones do not, so quoting either alone collapses them again.
     */
    @Test fun `the absoluteness skip names the saved spelling, so distinct faults stay distinct`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)
        val skips = mutableMapOf<String, String>()
        for (mp in listOf(" ", "\t", "  ", "/", "//", " / ", "mnt/nas", "./nfs", "./nfs/")) {
            val (log, record) = runAutomount(mksh!!, mp)
            val skip = log.lineSequence().firstOrNull { "is not absolute" in it }
            assertTrue("no absoluteness skip logged for '$mp':\n$log", skip != null)
            assertTrue("the skip must quote the saved spelling '$mp':\n$skip", skip!!.contains("'$mp'"))
            assertFalse("mount must never run for '$mp':\n$record", record.contains("MOUNT "))
            skips[mp] = skip
        }
        assertEquals(
            "each saved spelling must earn its own line:\n" + skips.entries.joinToString("\n"),
            skips.size,
            skips.values.toSet().size,
        )
    }

    /** The other half of the parity: what the app mounts verbatim, the module mounts at
     *  the same byte, and what the app routes, the module routes to the same target. */
    @Test fun `the module mounts the same controls at the same paths`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)
        for (mp in listOf("/mnt/nas", "/mnt/nas ")) {
            val (_, record) = runAutomount(mksh!!, mp)
            assertTrue(
                "the module must mount '$mp' verbatim, as the app does:\n$record",
                record.lineSequence().any { it.startsWith("MOUNT ") && it.endsWith(mp) },
            )
        }
        val route = EmulatedMount.route(STOCK_MOUNTINFO, " /storage/emulated/0/nfs")
        val at = (route as EmulatedMount.Result.Routed).route.mountAt
        val (log, record) = runAutomount(mksh!!, " /storage/emulated/0/nfs")
        assertTrue("the module must route the same leading-space spelling:\n$log", log.contains("automount: routing"))
        assertTrue(
            "the module must route it to the app's target $at:\n$record",
            record.lineSequence().any { it.startsWith("MOUNT ") && it.endsWith(at) },
        )
    }

    /**
     * The CLASS the two surfaces answer differently on is "a value the module's reader
     * cannot read at all", not one instance of it: mammon_pref_value is a line-anchored
     * sed, so an empty `<string>` fails it and so does any value carrying a newline (the
     * sibling case below). Whatever fails it takes the documented default, exactly as if
     * the key were absent, while the app is handed the value itself by SharedPreferences
     * and answers it. Naming the empty row as the only one is the sentence a later reader
     * would trust when judging whether a NEW divergence is a regression.
     */
    @Test fun `an empty saved mountpoint reads as no pref to the module and as a refusal to the app`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)
        assertEquals(EmulatedMount.Refusal.NOT_ABSOLUTE, MountpointPolicy.refusalFor(""))
        val (_, record) = runAutomount(mksh!!, "")
        assertTrue(
            "the module must fall back to its own default rather than mount nothing:\n$record",
            record.lineSequence().any { it.startsWith("MOUNT ") && it.endsWith(MODULE_DEFAULT_MOUNTPOINT) },
        )
    }

    /**
     * The other member of that class, and the reason the empty row is not the only one:
     * a newline anywhere in the value — edge or interior — puts the closing tag on
     * another line, both of mammon_pref_value's patterns are anchored to one line, and
     * the module mounts its default at a path the user never saved. The app trims the
     * edges (LF is in Char.isWhitespace) and routes. Pre-existing and out of scope to
     * change; pinned so it cannot be mistaken for a regression later.
     */
    @Test fun `a newline in the saved mountpoint is unreadable to the module while the app answers it`() {
        val mksh = findOnPath("mksh")
        assumeTrue("mksh required (nix-shell -p mksh); skipped", mksh != null)
        for (mp in listOf(
            "\n/storage/emulated/0/nfs",
            "/storage/emulated/0/nfs\n",
            "/storage/emulated/0/n\nfs",
        )) {
            assertNull("the app must accept '$mp', or this case proves no divergence", MountpointPolicy.refusalFor(mp))
            val (log, record) = runAutomount(mksh!!, mp)
            assertTrue(
                "the module must mount its own default, not the saved value:\n$log$record",
                record.lineSequence().any { it.startsWith("MOUNT ") && it.endsWith(MODULE_DEFAULT_MOUNTPOINT) },
            )
        }
    }

    private fun refuse(mountpoint: String): String =
        RootMount.mount(HOST, EXPORT, PORT, mountpoint, launch) { RootMount.SuResult(0, "", "") }.message

    private fun mkdirTargets(scripts: List<String>): List<String> =
        scripts.flatMap { script -> MKDIR.findAll(script).map { it.groupValues[1] } }

    /** Runs mammon_automount_main under mksh with one saved mountpoint, returning the
     *  load.log text and the mount stub's record. */
    private fun runAutomount(mksh: String, mountpoint: String): Pair<String, String> {
        val dir = File.createTempFile("mammon-verbatim-", "").apply { delete(); mkdirs(); deleteOnExit() }
        File(dir, "automount").writeText("1")
        File(dir, "mammon.xml").writeText(
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" +
                "<map>\n" +
                "    <string name=\"host\">192.0.2.1</string>\n" +
                "    <string name=\"export\">/export</string>\n" +
                "    <int name=\"port\" value=\"2049\" />\n" +
                "    <string name=\"mountpoint\">$mountpoint</string>\n" +
                "</map>\n",
        )
        val mounts = File(dir, "mounts").apply { writeText("") }
        File(dir, "mountinfo").writeText(STOCK_MOUNTINFO)
        val log = File(dir, "load.log").apply { writeText("") }
        val record = File(dir, "record").apply { writeText("") }

        val path = File(dir, "path").apply { mkdirs() }
        File(path, "mount").apply {
            writeText("#!/bin/sh\necho \"MOUNT ${'$'}@\" >> '${record.path}'\nexit 0\n")
            setExecutable(true)
        }
        for (name in listOf("app_process", "modprobe", "pm", "nc", "grep", "tail", "wc", "sleep", "umount", "mkdir")) {
            val body = if (name == "pm") "echo package:/data/app/base.apk\n" else "exit 0\n"
            File(path, name).apply { writeText("#!/bin/sh\n$body"); setExecutable(true) }
        }

        val fslib = File(System.getProperty("mammon.fslib") ?: "../magisk-module/fslib.sh")
        assertTrue("fslib.sh not found at $fslib", fslib.isFile)
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

    private val launch = RootMount.FuseLaunch("/data/app/base.apk", "/data/local/tmp/fuse.log", AuthIdentity.DEFAULT)

    private companion object {
        const val HOST = "192.0.2.1"
        const val EXPORT = "/export"
        const val PORT = 2049
        const val SHELL_TIMEOUT_SECONDS = 60L

        /** fslib.sh's own `mp=/mnt/nas` fallback, not Prefs.DEFAULT_MOUNTPOINT: the
         *  module never reads the app's constant. */
        const val MODULE_DEFAULT_MOUNTPOINT = "/mnt/nas"

        /** The script quotes with [RootMount.quote], so the target is what sits between
         *  the single quotes of the one `mkdir -p` every rung emits. */
        val MKDIR = Regex("""mkdir -p '(.*)'""")

        /** The waydroid-shaped table: a fuse SLAVE view and the shared master of its
         *  group. No phone has executed this path, so it pins the rule, not a device. */
        const val STOCK_MOUNTINFO =
            "2860 918 0:122 / /mnt/user/0/emulated rw,relatime shared:805 - fuse /dev/fuse rw,user_id=0\n" +
                "2890 957 0:122 / /storage/emulated rw,relatime master:805 - fuse /dev/fuse rw,user_id=0\n"
    }
}
