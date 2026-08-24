package app.mammon

import android.content.Context

/** Persisted NFS settings, SharedPreferences-backed. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("mammon", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString(KEY_HOST, "") ?: ""
        set(v) = sp.edit().putString(KEY_HOST, v).apply()

    /** Export path including leading slash, e.g. "/export/data". */
    var export: String
        get() = sp.getString(KEY_EXPORT, "") ?: ""
        set(v) = sp.edit().putString(KEY_EXPORT, v).apply()

    var port: Int
        get() = sp.getInt(KEY_PORT, ExportSpec.DEFAULT_PORT)
        set(v) = sp.edit().putInt(KEY_PORT, v).apply()

    var lastMountpoint: String
        get() = sp.getString(KEY_MOUNTPOINT, DEFAULT_MOUNTPOINT) ?: DEFAULT_MOUNTPOINT
        set(v) = sp.edit().putString(KEY_MOUNTPOINT, v).apply()

    fun spec(): ExportSpec? = ExportSpec.parse("$host:$port:$export")

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_EXPORT = "export"
        const val KEY_PORT = "port"
        const val KEY_MOUNTPOINT = "mountpoint"
        const val DEFAULT_MOUNTPOINT = "/mnt/nas"
    }
}
