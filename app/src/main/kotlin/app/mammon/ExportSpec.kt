package app.mammon

/**
 * Parsed NFS server location: "host[:port]:/export".
 *
 * The port sits between host and export (unlike kernel mount syntax) because the
 * colon-delimited "host:/export" form leaves no room for it. IPv6 literals must be
 * bracketed, exactly because of that colon ambiguity.
 */
data class ExportSpec(
    val host: String,
    val export: String,
    val port: Int,
) {
    val exportTail: String
        get() = export.trimEnd('/').substringAfterLast('/').ifEmpty { host }

    companion object {
        const val DEFAULT_PORT = 2049
        private const val MAX_PORT = 65535

        /** Null when the string does not match host[:port]:/export. */
        fun parse(raw: String): ExportSpec? {
            val specEnd = lastUnbracketedColon(raw) ?: return null
            if (specEnd <= 0) return null
            val export = raw.substring(specEnd + 1)
            if (!export.startsWith("/") || export.contains("..")) return null
            // trailing slashes normalized so doc ids stay stable across saves
            val normalized = export.trimEnd('/').ifEmpty { "/" }

            val (host, port) = splitPort(raw.substring(0, specEnd)) ?: return null
            return ExportSpec(host, normalized, port)
        }

        private fun lastUnbracketedColon(raw: String): Int? {
            var depth = 0
            for (i in raw.indices.reversed()) {
                when (raw[i]) {
                    ']' -> depth++
                    '[' -> depth--
                    ':' -> if (depth <= 0 && raw.indexOf('/', i) > 0) return i
                }
            }
            return null
        }

        /** "[v6]" keeps its colons; otherwise a trailing :digits is the port. */
        private fun splitPort(head: String): Pair<String, Int>? {
            if (!head.endsWith("]")) {
                val c = head.lastIndexOf(':')
                if (c > 0) {
                    val digits = head.substring(c + 1)
                    if (digits.isNotEmpty() && digits.all { it.isDigit() }) {
                        val p = digits.toInt()
                        if (p !in 1..MAX_PORT) return null
                        return head.substring(0, c) to p
                    }
                }
            }
            return head to DEFAULT_PORT
        }
    }
}
