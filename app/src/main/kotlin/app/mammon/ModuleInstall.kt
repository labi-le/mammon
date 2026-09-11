package app.mammon

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Decision core for the Install-module button: probe script, verdict mapping, staging
 * policy. Android-free so the mapping stays testable on the JVM; the zip it stages is
 * packed at build time by packModuleZip from magisk-module/.
 */
object ModuleInstall {
    enum class Probe { PRESENT, ABSENT, OUTDATED, UNAVAILABLE }

    /** The versionCodes are filled in only where a comparison actually happened. */
    data class Verdict(
        val probe: Probe,
        val installedVersionCode: Int = UNKNOWN_VERSION,
        val bundledVersionCode: Int = UNKNOWN_VERSION,
    )

    const val UNKNOWN_VERSION = -1

    const val ASSET_NAME = "mammon-module.zip"

    const val MODULE_ID = "mammon_fsloader"

    const val FILE_PROVIDER_AUTHORITY = "app.mammon.fileprovider"

    private const val PROP_NAME = "module.prop"
    private const val VERSION_KEY = "versionCode"
    private const val ABSENT_TOKEN = "absent"
    private const val UNREADABLE_TOKEN = "unknown"

    /** Parameterised only so [probeScript] can be run against a fabricated tree on the JVM. */
    internal const val ADB_ROOT = "/data/adb"

    /**
     * Precedence order, not merely a list: KernelSU and Magisk unpack an update into
     * modules_update and swap it in at the next reboot, so whatever a staged copy carries
     * is what the device will run, and it decides the verdict outright — the copy under
     * modules/ is about to be deleted and never gets a vote.
     */
    internal fun sources(root: String = ADB_ROOT): List<String> =
        listOf("$root/modules_update/$MODULE_ID", "$root/modules/$MODULE_ID")

    private val SOURCE_COUNT = sources().size

    /**
     * One line per source in [sources] order. A source with no directory reads
     * [ABSENT_TOKEN]; a directory whose module.prop is missing or carries no versionCode
     * reads [UNREADABLE_TOKEN], because an installed module of unknown version is not an
     * absent one. The second sed collapses a duplicated key so one source cannot spend
     * two lines.
     */
    internal fun probeScript(root: String = ADB_ROOT): String =
        "for d in ${sources(root).joinToString(" ")}; do " +
            "if [ ! -d \"\$d\" ]; then echo $ABSENT_TOKEN; " +
            "elif [ ! -f \"\$d/$PROP_NAME\" ]; then echo $UNREADABLE_TOKEN; " +
            "else v=\$(sed -n 's/^$VERSION_KEY=//p' \"\$d/$PROP_NAME\" | sed -n 1p); " +
            "echo \"\${v:-$UNREADABLE_TOKEN}\"; fi; done"

    /** Runs through RootMount's su chain, so no second su pathway exists. */
    val PROBE_SCRIPT: String = probeScript()

    /**
     * Anything but one recognised token per source means we learned nothing about the
     * device: a denied, timed-out or unparseable probe reads UNAVAILABLE, never silently
     * absent and never silently up to date.
     */
    fun classify(exitCode: Int, stdout: String?, bundledVersionCode: Int): Verdict {
        if (exitCode != 0 || stdout == null) {
            return Verdict(Probe.UNAVAILABLE)
        }
        val tokens = stdout.trimEnd().lines().map { it.trim() }
        if (tokens.size != SOURCE_COUNT) return Verdict(Probe.UNAVAILABLE)
        for (token in tokens) {
            if (token == ABSENT_TOKEN) continue
            val installed = parseVersionCode(token) ?: return Verdict(Probe.UNAVAILABLE)
            val probe = if (installed >= bundledVersionCode) Probe.PRESENT else Probe.OUTDATED
            return Verdict(probe, installed, bundledVersionCode)
        }
        return Verdict(Probe.ABSENT)
    }

    /**
     * module.prop inside the packaged zip is the only home of the bundled versionCode; a
     * Gradle, BuildConfig or Kotlin copy would drift from the module it describes. Null
     * when the asset carries no module.prop or no usable versionCode.
     */
    fun bundledVersionCode(asset: InputStream): Int? = ZipInputStream(asset).use { zip ->
        generateSequence { zip.nextEntry }
            .firstOrNull { it.name == PROP_NAME }
            ?.let { propVersionCode(zip.readBytes().toString(Charsets.UTF_8)) }
    }

    fun stagedFile(cacheDir: File): File = File(cacheDir, ASSET_NAME)

    /** Truncate first: re-staged on every press, and a stale longer copy would leave trailing garbage. */
    fun stageCopy(input: InputStream, target: File) {
        FileOutputStream(target, false).use { out -> input.copyTo(out) }
    }

    private fun propVersionCode(text: String): Int? = text.lineSequence()
        .firstOrNull { it.startsWith("$VERSION_KEY=") }
        ?.let { parseVersionCode(it.removePrefix("$VERSION_KEY=")) }

    /** A versionCode orders releases, so only a non-negative integer is one. */
    private fun parseVersionCode(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it >= 0 }
}
