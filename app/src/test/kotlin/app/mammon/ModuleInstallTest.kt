package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModuleInstallTest {
    @Test
    fun anInstallAtOrPastTheBundledVersionIsPresent() {
        assertEquals(
            ModuleInstall.Verdict(ModuleInstall.Probe.PRESENT, 2, 2),
            ModuleInstall.classify(0, "absent\n2\n", 2),
        )
        assertEquals(
            ModuleInstall.Verdict(ModuleInstall.Probe.PRESENT, 7, 2),
            ModuleInstall.classify(0, "absent\n7\n", 2),
        )
    }

    @Test
    fun anOlderInstallIsOutdatedAndNamesBothVersions() {
        // The v1.0 field case: the module is there, so PRESENT would dead-end the button
        // forever and the automount feature of the bundled v1.1 could never arrive.
        val verdict = ModuleInstall.classify(0, "absent\n1\n", 2)
        assertEquals(ModuleInstall.Probe.OUTDATED, verdict.probe)
        assertEquals(1, verdict.installedVersionCode)
        assertEquals(2, verdict.bundledVersionCode)
    }

    @Test
    fun neitherDirectoryPresentIsAbsent() {
        assertEquals(
            ModuleInstall.Verdict(ModuleInstall.Probe.ABSENT),
            ModuleInstall.classify(0, "absent\nabsent\n", 2),
        )
    }

    @Test
    fun aStagedUpdateWinsOverTheInstalledCopy() {
        // KernelSU and Magisk unpack an update into modules_update and swap it in at the
        // next reboot: a pending newer copy is installed-newer, not outdated.
        assertEquals(
            ModuleInstall.Verdict(ModuleInstall.Probe.PRESENT, 2, 2),
            ModuleInstall.classify(0, "2\n1\n", 2),
        )
        // And a staged OLDER copy is what the reboot will run, so it decides too.
        assertEquals(
            ModuleInstall.Verdict(ModuleInstall.Probe.OUTDATED, 1, 2),
            ModuleInstall.classify(0, "1\n5\n", 2),
        )
    }

    @Test
    fun anythingUnparseableIsUnavailableNotAbsentAndNotUpToDate() {
        val unavailable = ModuleInstall.Verdict(ModuleInstall.Probe.UNAVAILABLE)
        // A denied or timed-out probe knows nothing about the device; reading it as
        // absent would push the user into reinstalling a module that is already there,
        // and reading it as present would hide an available update.
        assertEquals(unavailable, ModuleInstall.classify(1, null, 2))
        assertEquals(unavailable, ModuleInstall.classify(124, null, 2))
        assertEquals(unavailable, ModuleInstall.classify(0, null, 2))
        assertEquals(unavailable, ModuleInstall.classify(0, "", 2))
        assertEquals(unavailable, ModuleInstall.classify(0, "absent", 2))
        assertEquals(unavailable, ModuleInstall.classify(0, "absent\nabsent\nabsent", 2))
        assertEquals(unavailable, ModuleInstall.classify(0, "absent\n\n2", 2))
        // module.prop present but carrying no versionCode line, or a directory with no
        // module.prop at all: installed, version unknown, so nothing to compare.
        assertEquals(unavailable, ModuleInstall.classify(0, "absent\nunknown\n", 2))
        assertEquals(unavailable, ModuleInstall.classify(0, "unknown\nabsent\n", 2))
        // Non-numeric, out-of-int-range and negative versionCodes order nothing.
        assertEquals(unavailable, ModuleInstall.classify(0, "absent\nv1.1\n", 2))
        assertEquals(unavailable, ModuleInstall.classify(0, "absent\n2beta\n", 2))
        assertEquals(unavailable, ModuleInstall.classify(0, "absent\n99999999999\n", 2))
        assertEquals(unavailable, ModuleInstall.classify(0, "absent\n-3\n", 2))
    }

    @Test
    fun surroundingWhitespaceAndCrlfDoNotChangeTheVerdict() {
        assertEquals(
            ModuleInstall.Verdict(ModuleInstall.Probe.OUTDATED, 1, 2),
            ModuleInstall.classify(0, "absent\r\n 1 \r\n", 2),
        )
        assertEquals(
            ModuleInstall.Verdict(ModuleInstall.Probe.ABSENT),
            ModuleInstall.classify(0, "absent\r\nabsent\r\n", 2),
        )
    }

    @Test
    fun bundledVersionComesFromThePackagedZip() {
        val zip = zipFixture("module.prop" to "id=mammon_fsloader\nversionCode=2\nversion=v1.1\n")
        assertEquals(2, ModuleInstall.bundledVersionCode(zip.inputStream()))
    }

    @Test
    fun bundledVersionIsUnavailableRatherThanAGuess() {
        val noProp = zipFixture("service.sh" to "#!/system/bin/sh\n")
        assertNull(ModuleInstall.bundledVersionCode(noProp.inputStream()))

        assertNull(ModuleInstall.bundledVersionCode(zipFixture("module.prop" to "id=x\n").inputStream()))
        assertNull(ModuleInstall.bundledVersionCode(zipFixture("module.prop" to "versionCode=v2\n").inputStream()))
        assertNull(ModuleInstall.bundledVersionCode(zipFixture().inputStream()))
        // A nested entry of the same basename is a different file, not the manifest.
        assertNull(ModuleInstall.bundledVersionCode(zipFixture("sub/module.prop" to "versionCode=9\n").inputStream()))
    }

    @Test
    fun theRealPackagedModulePropParses() {
        // The number has one home; a reader that cannot read the shipped module.prop
        // would make every device read UNAVAILABLE.
        val prop = File("../magisk-module/module.prop")
        assertTrue("module.prop not found at ${prop.absolutePath}", prop.isFile)
        // A MODULE_ID rename must land here too, or the probe targets a directory that
        // never exists and every device reads UNAVAILABLE.
        assertTrue(
            "module.prop must declare id=${ModuleInstall.MODULE_ID}",
            prop.readText().contains("id=${ModuleInstall.MODULE_ID}"),
        )
        // Read the archive packModuleZip actually produced, not a fixture: the zip-root
        // layout is part of the contract and a re-packed source file cannot prove it.
        val archivePath = System.getProperty("mammon.moduleZip")
        requireNotNull(archivePath) { "mammon.moduleZip not set; packModuleZip output not wired into the test" }
        val archive = File(archivePath)
        assertTrue("packed module zip not found at ${archive.absolutePath}", archive.isFile)
        val version = archive.inputStream().use(ModuleInstall::bundledVersionCode)
        requireNotNull(version) { "bundled versionCode missing from ${archive.name}" }
        assertTrue("bundled versionCode must be non-negative, was $version", version >= 0)
    }

    @Test
    fun theProbeScriptTargetsModulesUpdateBeforeModules() {
        // ADB_ROOT is load-bearing: retargeting it to /data/adb/modules points the probe
        // at a directory that never exists and every device would read UNAVAILABLE.
        val script = ModuleInstall.PROBE_SCRIPT
        val staged = script.indexOf("/data/adb/modules_update/${ModuleInstall.MODULE_ID}")
        val installed = script.indexOf("/data/adb/modules/${ModuleInstall.MODULE_ID}")
        assertTrue(
            "probe must address modules_update before modules: $script",
            staged >= 0 && staged < installed,
        )
    }

    @Test
    fun theProbeScriptAnswersOneLinePerSourceUnderARealShell() {
        // The script is the other half of classify(): a token it never emits, or a
        // second line from one source, silently turns every verdict into UNAVAILABLE.
        val root = tempDir()
        assertEquals("absent\nabsent", runProbe(root))

        File(root, "modules/${ModuleInstall.MODULE_ID}").mkdirs()
        assertEquals("absent\nunknown", runProbe(root))

        writeProp(root, "modules", "id=x\nversion=v1.0\n")
        assertEquals("absent\nunknown", runProbe(root))

        writeProp(root, "modules", "id=x\nversionCode=1\nversion=v1.0\n")
        assertEquals("absent\n1", runProbe(root))

        writeProp(root, "modules", "versionCode=1\r\nversionCode=9\r\n")
        assertEquals("absent\n1\r", runProbe(root))

        writeProp(root, "modules_update", "versionCode=2\n")
        assertEquals("2\n1\r", runProbe(root))

        // A line that merely contains the key is not the key.
        writeProp(root, "modules_update", "#versionCode=7\nversionCode=3\n")
        assertEquals("3\n1\r", runProbe(root))
    }

    @Test
    fun theProbeVerdictSurvivesTheRealShellsOutput() {
        val root = tempDir()
        writeProp(root, "modules", "id=x\nversionCode=1\nversion=v1.0\n")
        assertEquals(
            ModuleInstall.Verdict(ModuleInstall.Probe.OUTDATED, 1, 2),
            ModuleInstall.classify(0, runProbe(root) + "\n", 2),
        )
    }

    @Test
    fun stagingKeepsTheFixedNameAndTruncates() {
        val dir = Files.createTempDirectory("mammon-stage").toFile()
        try {
            assertEquals(File(dir, ModuleInstall.ASSET_NAME), ModuleInstall.stagedFile(dir))

            val target = ModuleInstall.stagedFile(dir)
            target.writeBytes(ByteArray(32))
            ModuleInstall.stageCopy(ByteArrayInputStream("zip".toByteArray()), target)
            assertEquals("zip", target.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun zipFixture(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, body) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun writeProp(root: File, dir: String, text: String) {
        val moduleDir = File(root, "$dir/${ModuleInstall.MODULE_ID}")
        moduleDir.mkdirs()
        File(moduleDir, "module.prop").writeText(text)
    }

    private fun runProbe(root: File): String {
        val script = File(tempDir(), "probe.sh")
            .apply { writeText(ModuleInstall.probeScript(root.path)) }
        val proc = ProcessBuilder("/bin/sh", script.path).redirectErrorStream(false).start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        val err = proc.errorStream.bufferedReader().use { it.readText() }
        proc.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertEquals("probe script wrote to stderr: $err", "", err)
        assertEquals("probe script exit", 0, proc.exitValue())
        return out.trimEnd('\n')
    }

    private fun tempDir(): File = Files.createTempDirectory("mammon-probe").toFile()
        .apply { deleteOnExit() }

    private companion object {
        const val SHELL_TIMEOUT_SECONDS = 60L
    }
}
