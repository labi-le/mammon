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
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private val probeGeneration = AtomicInteger(0)
    private lateinit var saveButton: Button

    private lateinit var hostLayout: TextInputLayout
    private lateinit var exportLayout: TextInputLayout
    private lateinit var portLayout: TextInputLayout
    private lateinit var mountpointLayout: TextInputLayout
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
        hostLayout = findViewById(R.id.host_layout)
        exportLayout = findViewById(R.id.export_layout)
        portLayout = findViewById(R.id.port_layout)
        mountpointLayout = findViewById(R.id.mountpoint_layout)
        hostEdit = findViewById(R.id.host)
        exportEdit = findViewById(R.id.export)
        portEdit = findViewById(R.id.port)
        mountpointEdit = findViewById(R.id.mountpoint)
        saveButton = findViewById(R.id.save)
        openButton = findViewById(R.id.open_files)
        mountBtn = findViewById(R.id.mount_button)
        unmountBtn = findViewById(R.id.unmount_button)
        mountStatus = findViewById(R.id.mount_status)

        hostEdit.setText(prefs.host)
        exportEdit.setText(prefs.export)
        if (prefs.port != ExportSpec.DEFAULT_PORT) portEdit.setText(prefs.port.toString())
        mountpointEdit.setText(prefs.lastMountpoint)

        bindClearOnType(hostLayout, hostEdit)
        bindClearOnType(exportLayout, exportEdit)
        bindClearOnType(portLayout, portEdit)
        bindClearOnType(mountpointLayout, mountpointEdit)

        saveButton.setOnClickListener { onSave() }
        openButton.setOnClickListener { onOpenInFiles() }
        mountBtn.setOnClickListener { onMount(true) }
        unmountBtn.setOnClickListener { onMount(false) }
        openButton.isEnabled = false

        if (prefs.spec() != null) {
            status.setText(R.string.checking_saved)
            probeSavedConfig()
        }
    }

    /** Inline errors clear as soon as the field changes again. */
    private fun bindClearOnType(layout: TextInputLayout, edit: TextInputEditText) {
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                layout.error = null
            }
        })
    }

    private fun onSave() {
        var valid = true
        val host = hostEdit.text?.toString()?.trim().orEmpty()
        val export = exportEdit.text?.toString()?.trim().orEmpty()
        val portStr = portEdit.text?.toString()?.trim().orEmpty()
        if (host.isEmpty()) {
            hostLayout.error = getString(R.string.err_field_host)
            valid = false
        }
        if (!export.startsWith("/")) {
            exportLayout.error = getString(R.string.err_field_export)
            valid = false
        }
        val port = when {
            portStr.isEmpty() -> ExportSpec.DEFAULT_PORT
            else -> portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: run {
                portLayout.error = getString(R.string.err_field_port)
                valid = false
                ExportSpec.DEFAULT_PORT
            }
        }
        if (!valid) {
            status.setText(R.string.err_bad_config)
            return
        }
        val spec = ExportSpec(host, export.trimEnd('/').ifEmpty { "/" }, port)
        prefs.host = spec.host
        prefs.export = spec.export
        prefs.port = spec.port
        status.text = getString(R.string.saved_ok, "${spec.host}:${spec.port}:${spec.export}")
        probeSavedConfig()
    }

    /**
     * Probes the saved config off the main thread with its own timeout; the
     * generation counter discards results from superseded probes.
     */
    private fun probeSavedConfig() {
        val spec = prefs.spec() ?: return
        val gen = probeGeneration.incrementAndGet()
        status.setText(R.string.probing)
        saveButton.isEnabled = false
        thread(name = "nfs-probe") {
            val ok = try {
                runBlocking {
                    withTimeout(PROBE_TIMEOUT_MS) {
                        NfsAccess(spec).use { it.probeRoot() != null }
                    }
                }
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (gen != probeGeneration.get()) return@runOnUiThread
                saveButton.isEnabled = true
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
        if (!mp.startsWith("/")) {
            mountpointLayout.error = getString(R.string.err_bad_mountpoint_absolute)
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
            val state = result.stateAfter
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
        const val PROBE_TIMEOUT_MS = 15_000L
    }
}
