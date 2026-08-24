package app.mammon

/** One parsed line of /proc/mounts. */
data class MountEntry(
    val device: String,
    val mountPoint: String,
    val fsType: String,
)

/** Parses /proc/mounts content; the kernel octal-escapes whitespace in these fields. */
object MountsParser {

    private val OCTAL_ESCAPES = Regex("""\\[0-7]{3}""")

    fun unescape(raw: String): String =
        OCTAL_ESCAPES.replace(raw) { m ->
            m.value.drop(1).toInt(8).toChar().toString()
        }

    /** Malformed lines are skipped, not fatal — /proc/mounts changes while we read it. */
    fun parse(content: String): List<MountEntry> =
        content.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val f = line.split(' ')
                if (f.size < 4) return@mapNotNull null
                MountEntry(unescape(f[0]), unescape(f[1]), unescape(f[2]))
            }
            .toList()
}
