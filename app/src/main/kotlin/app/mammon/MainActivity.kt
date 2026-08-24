package app.mammon

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView

    private lateinit var hostEdit: TextInputEditText
    private lateinit var exportEdit: TextInputEditText
    private lateinit var portEdit: TextInputEditText
    private lateinit var mountpointEdit: TextInputEditText

    private lateinit var openButton: Button
    private lateinit var mountBtn: Button
    private lateinit var unmountBtn: Button
    private lateinit var mountStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        status = findViewById(R.id.status)
        hostEdit = findViewById(R.id.host)
        exportEdit = findViewById(R.id.export)
        portEdit = findViewById(R.id.port)
        mountpointEdit = findViewById(R.id.mountpoint)
        openButton = findViewById(R.id.open_files)
        mountBtn = findViewById(R.id.mount_button)
        unmountBtn = findViewById(R.id.unmount_button)
        mountStatus = findViewById(R.id.mount_status)

        hostEdit.setText(prefs.host)
        exportEdit.setText(prefs.export)
        if (prefs.port != ExportSpec.DEFAULT_PORT) portEdit.setText(prefs.port.toString())
        mountpointEdit.setText(prefs.lastMountpoint)

        findViewById<Button>(R.id.save).setOnClickListener { onSave() }
        openButton.setOnClickListener { onOpenInFiles() }
        mountBtn.setOnClickListener { onMount(true) }
        unmountBtn.setOnClickListener { onMount(false) }
        openButton.isEnabled = false
    }

    private fun onSave() {
        val host = hostEdit.text?.toString()?.trim().orEmpty()
        val export = exportEdit.text?.toString()?.trim().orEmpty()
        val portStr = portEdit.text?.toString()?.trim()
        val spec = ExportSpec.parse("$host:${portStr ?: ""}:$export")
        if (spec == null) {
            status.setText(R.string.err_bad_config)
            return
        }
        prefs.host = spec.host
        prefs.export = spec.export
        prefs.port = spec.port
        status.text = getString(R.string.saved_ok, "${spec.host}:${spec.port}:${spec.export}")
        probeSavedConfig()
    }

    /** Verifies the share answers before offering "Open in Files". */
    private fun probeSavedConfig() {
        val spec = prefs.spec() ?: return
        status.setText(R.string.probing)
        thread(name = "nfs-probe") {
            val ok = try {
                NfsAccess(spec).use { it.probeRoot() != null }
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (ok) {
                    status.setText(R.string.probe_ok)
                    openButton.isEnabled = true
                } else {
                    status.setText(R.string.probe_fail)
                    openButton.isEnabled = false
                }
            }
        }
    }

    private fun onOpenInFiles() {
        val uri = DocumentsContract.buildRootsUri(AUTHORITY)
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR))
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_file_manager, Toast.LENGTH_LONG).show()
        }
    }

    private fun onMount(doMount: Boolean) {
        val mp = mountpointEdit.text?.toString()?.trim().orEmpty()
        if (mp.isEmpty()) {
            mountStatus.setText(R.string.err_bad_mountpoint)
            return
        }
        prefs.lastMountpoint = mp
        val spec = prefs.spec()
        if (doMount && spec == null) {
            mountStatus.setText(R.string.err_no_config)
            return
        }
        mountBtn.isEnabled = false
        unmountBtn.isEnabled = false
        mountStatus.setText(R.string.working)
        thread(name = "root-mount") {
            val result = if (doMount) {
                RootMount.mount(spec!!.host, spec.export, spec.port, mp)
            } else {
                RootMount.unmount(mp)
            }
            val state = runCatching { RootMount.mountedState(mp) }.getOrDefault(RootMount.State.UNKNOWN)
            runOnUiThread {
                mountBtn.isEnabled = true
                unmountBtn.isEnabled = true
                mountStatus.text = when {
                    !result.ok -> result.message
                    state == RootMount.State.MOUNTED_NFS ->
                        getString(R.string.mounted_state, "nfs", mp)
                    state == RootMount.State.UNKNOWN -> getString(R.string.unknown_state)
                    doMount -> getString(R.string.not_mounted_after_mount)
                    else -> getString(R.string.not_mounted_state)
                }
            }
        }
    }

    companion object {
        const val AUTHORITY = "app.mammon.nfs"
        const val ROOT_ID = "nfs"
    }
}
