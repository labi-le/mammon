package app.mammon

/**
 * The AUTH_SYS identity mammon claims: effective uid, effective gid, and the
 * supplementary groups the server checks alongside them.
 *
 * Deliberately not a field of [ExportSpec]: where an export lives and which account we
 * claim to be are separate settings, and only this one has a wire form to share between
 * the prefs and the FUSE daemon's argv.
 *
 * [DEFAULT] is a starting point, not a fact: root_squash is the default on Linux
 * exports, so uid 0 is mapped to nobody and cannot write, while the owning account
 * differs per server. That is why every field is settable.
 */
data class AuthIdentity(val uid: Long, val gid: Long, val auxGids: List<Long>) {

    init {
        require(auxGids.size <= MAX_AUX_GIDS) { "at most $MAX_AUX_GIDS supplementary gids" }
    }

    /** The form [parse] reads back, and the one argv field the FUSE daemon receives. */
    override fun toString(): String = "$uid:$gid:${auxGids.joinToString(",")}"

    companion object {
        /** RFC 5531 appendix A declares the supplementary list as `gids<16>`. */
        const val MAX_AUX_GIDS = 16

        /** uid and gid are `unsigned int` on the wire, so the range outgrows [Int]. */
        const val MAX_ID = 4_294_967_295L

        val DEFAULT = AuthIdentity(1000, 237, listOf(237, 100))

        /** Null when [raw] is not exactly "uid:gid:aux[,aux...]" with every field valid. */
        fun parse(raw: String): AuthIdentity? {
            val parts = raw.split(':')
            if (parts.size != 3) return null
            return AuthIdentity(
                idOrNull(parts[0]) ?: return null,
                idOrNull(parts[1]) ?: return null,
                auxOrNull(parts[2]) ?: return null,
            )
        }

        /** Null unless [raw] is a bare decimal inside the wire's 32-bit unsigned range. */
        fun idOrNull(raw: String): Long? {
            val text = raw.trim()
            if (text.isEmpty() || text.length > MAX_ID_DIGITS) return null
            if (!text.all { it in '0'..'9' }) return null
            return text.toLongOrNull()?.takeIf { it <= MAX_ID }
        }

        /** Empty means no supplementary groups, which is a valid identity, not an error. */
        fun auxOrNull(raw: String): List<Long>? {
            val text = raw.trim()
            if (text.isEmpty()) return emptyList()
            val parts = text.split(',')
            if (parts.size > MAX_AUX_GIDS) return null
            return parts.map { idOrNull(it) ?: return null }
        }

        private const val MAX_ID_DIGITS = 10
    }
}
