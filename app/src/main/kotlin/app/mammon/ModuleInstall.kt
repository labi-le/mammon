package app.mammon

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Decision core for the Install-module button: probe script, verdict mapping, staging
 * policy. Android-free so the mapping stays testable on the JVM; the zip it stages is
 * packed at build time by ModuleZipTask from magisk-module/.
 */
object ModuleInstall {
    enum class Probe { PRESENT, ABSENT, UNAVAILABLE }

    const val ASSET_NAME = "mammon-module.zip"

    /** The marker directory customize.sh creates when flashed. */
    const val MODULE_DIR = "/data/adb/modules/mammon_fsloader"

    const val FILE_PROVIDER_AUTHORITY = "app.mammon.fileprovider"

    /** One line whose stdout names the verdict; runs through RootMount's su chain, so no second su pathway. */
    const val PROBE_SCRIPT = "[ -d $MODULE_DIR ] && echo present || echo absent"

    /**
     * Anything beyond a clean present/absent echo means we learned nothing about the
     * device: a denied or timed-out probe must read UNAVAILABLE, never silently absent.
     */
    fun classify(exitCode: Int, stdout: String?): Probe = when {
        exitCode == 0 && stdout?.trim() == "present" -> Probe.PRESENT
        exitCode == 0 && stdout?.trim() == "absent" -> Probe.ABSENT
        else -> Probe.UNAVAILABLE
    }

    fun stagedFile(cacheDir: File): File = File(cacheDir, ASSET_NAME)

    /** Truncate first: re-staged on every press, and a stale longer copy would leave trailing garbage. */
    fun stageCopy(input: InputStream, target: File) {
        FileOutputStream(target, false).use { out -> input.copyTo(out) }
    }
}
