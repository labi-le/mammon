package app.mammon

import android.content.Context
import androidx.core.content.edit

/** Persisted NFS settings, SharedPreferences-backed. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("mammon", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString(KEY_HOST, "") ?: ""
        set(v) = sp.edit { putString(KEY_HOST, v) }

    /** Export path including leading slash, e.g. "/export/data". */
    var export: String
        get() = sp.getString(KEY_EXPORT, "") ?: ""
        set(v) = sp.edit { putString(KEY_EXPORT, v) }

    var port: Int
        get() = sp.getInt(KEY_PORT, ExportSpec.DEFAULT_PORT)
        set(v) = sp.edit { putInt(KEY_PORT, v) }

    /**
     * The AUTH_SYS identity, stored in [AuthIdentity]'s own wire form so the prefs, the
     * FUSE daemon's argv and the boot module all read one string through one parser.
     * An unparseable value degrades to the default rather than blocking every mount.
     */
    var identity: AuthIdentity
        get() = AuthIdentity.parse(sp.getString(KEY_IDENTITY, "") ?: "") ?: AuthIdentity.DEFAULT
        set(v) = sp.edit { putString(KEY_IDENTITY, v.toString()) }

    var lastMountpoint: String
        get() = sp.getString(KEY_MOUNTPOINT, DEFAULT_MOUNTPOINT) ?: DEFAULT_MOUNTPOINT
        set(v) = sp.edit { putString(KEY_MOUNTPOINT, v) }

    fun spec(): ExportSpec? = ExportSpec.parse("$host:$port:$export")

    fun target(): NfsTarget? = spec()?.let { NfsTarget(it, identity) }

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_EXPORT = "export"
        const val KEY_PORT = "port"
        const val KEY_IDENTITY = "identity"
        const val KEY_MOUNTPOINT = "mountpoint"
        const val DEFAULT_MOUNTPOINT = "/mnt/nas"
    }
}
