package app.mammon

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var saveButton: Button
    private lateinit var prefs: Prefs
    private lateinit var status: TextView

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

    private lateinit var scanButton: MaterialButton
    private lateinit var scanResults: LinearLayout

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
        mountStatus = findViewById(R.id.mount_status)
        scanButton = findViewById(R.id.scan_button)
        scanResults = findViewById(R.id.scan_results)
        mountBtn = findViewById(R.id.mount_button)
        unmountBtn = findViewById(R.id.unmount_button)

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
        scanButton.setOnClickListener { onScan() }
        saveButton.isEnabled = !PROBE_IN_FLIGHT.get()
        openButton.isEnabled = false
        mountBtn.isEnabled = !MOUNT_IN_FLIGHT.get()
        unmountBtn.isEnabled = !MOUNT_IN_FLIGHT.get()
        scanButton.isEnabled = !SCAN_IN_FLIGHT.get()

        if (prefs.spec() != null && !PROBE_IN_FLIGHT.get()) {
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
        val canonical = "$host:$port:$export"
        val spec = ExportSpec.parse(canonical)
        if (spec == null) {
            exportLayout.error = getString(R.string.err_field_export)
            status.setText(R.string.err_bad_config)
            return
        }
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
        val gen = PROBE_GENERATION.incrementAndGet()
        status.setText(R.string.probing)
        PROBE_IN_FLIGHT.set(true)
        saveButton.isEnabled = false
        thread(name = "nfs-probe") {
            val ok = try {
                runBlocking {
                    withTimeout(PROBE_TIMEOUT_MS) {
                        NfsSessions.open(spec).use { it.probeRoot() != null }
                    }
                }
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (gen != PROBE_GENERATION.get()) return@runOnUiThread
                PROBE_IN_FLIGHT.set(false)
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
        // Trailing slashes are stripped once here so /proc/1/mounts exact-match sees
        // the same string the mount script and the parser use.
        val mp = (mountpointEdit.text?.toString() ?: "").trim().trimEnd('/').ifEmpty { "/" }
        if (!mp.startsWith("/") || mp == "/") {
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
        MOUNT_IN_FLIGHT.set(true)
        thread(name = "root-mount") {
            val result = if (doMount) {
                RootMount.mount(spec!!.host, spec.export, spec.port, mp)
            } else {
                RootMount.unmount(mp)
            }
            val state = result.stateAfter
            runOnUiThread {
                MOUNT_IN_FLIGHT.set(false)
                mountBtn.isEnabled = true
                unmountBtn.isEnabled = true
                mountStatus.text = when {
                    !result.ok -> mountFailureText(result)
                    state == RootMount.State.MOUNTED_NFS ->
                        getString(R.string.mounted_state, result.fsType ?: "nfs", mp)
                    state == RootMount.State.UNKNOWN -> getString(R.string.unknown_state)
                    doMount -> getString(R.string.not_mounted_after_mount)
                    else -> getString(R.string.not_mounted_state)
                }
            }
        }
    }

    /** A failed unmount carries no diagnosis, so its own message stays the fallback. */
    private fun mountFailureText(result: RootMount.Result): String = when (result.diagnosis) {
        RootMount.MountDiagnosis.NO_ROOT -> getString(R.string.err_mount_no_root)
        RootMount.MountDiagnosis.KERNEL_LACKS_NFS -> getString(R.string.err_mount_kernel_no_nfs)
        RootMount.MountDiagnosis.VERSION_MODULE_MISSING ->
            getString(R.string.err_mount_version_module)
        RootMount.MountDiagnosis.GENERIC -> getString(R.string.err_mount_failed, result.message)
        null -> result.message
    }

    private fun onScan() {
        scanButton.isEnabled = false
        scanResults.removeAllViews()
        scanResults.visibility = View.GONE
        SCAN_IN_FLIGHT.set(true)
        thread(name = "nfs-scan") {
            val subnet = NfsScanner.currentSubnet(getSystemService(ConnectivityManager::class.java))
            val found = if (subnet == null) null
            else NfsScanner.scan(NfsScanner.addresses(subnet.first, subnet.second))
            runOnUiThread { renderScan(found ?: emptyList(), subnet != null) }
        }
    }

    /** Runs on the UI thread; [hasNetwork] false means no scannable local network. */
    private fun renderScan(found: List<String>, hasNetwork: Boolean) {
        SCAN_IN_FLIGHT.set(false)
        scanButton.isEnabled = true
        if (!hasNetwork) {
            status.setText(R.string.scan_no_network)
            return
        }
        if (found.isEmpty()) {
            status.setText(R.string.scan_empty)
            return
        }
        for (host in found) {
            val chip = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            chip.text = host
            chip.isAllCaps = false
            chip.setOnClickListener {
                hostEdit.setText(host)
                scanResults.removeAllViews()
                scanResults.visibility = View.GONE
            }
            scanResults.addView(chip)
        }
        scanResults.visibility = View.VISIBLE
        status.setText(R.string.scan_done)
    }

    companion object {
        const val AUTHORITY = "app.mammon.nfs"
        const val PROBE_TIMEOUT_MS = 15_000L

        /** Process-scoped so a recreated activity inherits the real mount/probe
         *  state instead of re-enabling buttons while work is still running. */
        private val MOUNT_IN_FLIGHT = AtomicBoolean(false)
        private val PROBE_IN_FLIGHT = AtomicBoolean(false)
        private val SCAN_IN_FLIGHT = AtomicBoolean(false)
        val PROBE_GENERATION = AtomicInteger(0)
    }
}
