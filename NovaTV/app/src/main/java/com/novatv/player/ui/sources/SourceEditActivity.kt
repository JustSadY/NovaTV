package com.novatv.player.ui.sources

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.novatv.player.AppGraph
import com.novatv.player.R
import com.novatv.player.data.PlaylistSource
import com.novatv.player.ui.common.FocusUtils
import com.novatv.player.ui.common.TvDialog
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Add / edit an IPTV source (M3U URL or local M3U file). Also receives VIEW/SEND intents. */
class SourceEditActivity : ComponentActivity() {

    private val repo = AppGraph.channels

    private lateinit var nameInput: EditText
    private lateinit var urlInput: EditText
    private lateinit var epgInput: EditText
    private lateinit var uaInput: EditText
    private lateinit var typeUrl: TextView
    private lateinit var typeFile: TextView
    private lateinit var urlGroup: View
    private lateinit var fileGroup: View
    private lateinit var filePathView: TextView
    private lateinit var saveButton: TextView
    private lateinit var cancelButton: TextView
    private lateinit var deleteButton: TextView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    private var editing: PlaylistSource? = null
    private var type = PlaylistSource.TYPE_URL
    private var fileLocation: String? = null
    private var busy = false

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            setFile(uri.toString())
        }
    }

    private val browseFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(FileBrowserActivity.EXTRA_PATH)?.let { setFile(it) }
    }

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchBrowser() else Toast.makeText(this, R.string.source_permission_denied, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_source_edit)
        AppGraph.ensureStarted()

        nameInput = findViewById(R.id.se_name)
        urlInput = findViewById(R.id.se_url)
        epgInput = findViewById(R.id.se_epg)
        uaInput = findViewById(R.id.se_ua)
        typeUrl = findViewById(R.id.se_type_url)
        typeFile = findViewById(R.id.se_type_file)
        urlGroup = findViewById(R.id.se_url_group)
        fileGroup = findViewById(R.id.se_file_group)
        filePathView = findViewById(R.id.se_file_path)
        saveButton = findViewById(R.id.se_save)
        cancelButton = findViewById(R.id.se_cancel)
        deleteButton = findViewById(R.id.se_delete)
        progress = findViewById(R.id.se_progress)
        status = findViewById(R.id.se_status)

        listOf(typeUrl, typeFile, saveButton, cancelButton, deleteButton, findViewById(R.id.se_pick)).forEach {
            FocusUtils.applyFocusZoom(it)
        }
        typeUrl.setOnClickListener { setType(PlaylistSource.TYPE_URL) }
        typeFile.setOnClickListener { setType(PlaylistSource.TYPE_FILE) }
        findViewById<View>(R.id.se_type_phone).apply {
            FocusUtils.applyFocusZoom(this)
            setOnClickListener {
                startActivity(Intent(this@SourceEditActivity, com.novatv.player.ui.link.LinkActivity::class.java))
                finish()
            }
        }
        findViewById<View>(R.id.se_pick).setOnClickListener { pickFile() }
        saveButton.setOnClickListener { save() }
        cancelButton.setOnClickListener { finish() }
        deleteButton.setOnClickListener { confirmDelete() }

        val id = intent.getLongExtra(EXTRA_SOURCE_ID, 0L)
        editing = if (id != 0L) AppGraph.sourceStore.load().firstOrNull { it.id == id } else null
        val e = editing
        if (e != null) {
            findViewById<TextView>(R.id.se_title).setText(R.string.source_edit_title)
            nameInput.setText(e.name)
            epgInput.setText(e.epgUrl)
            uaInput.setText(e.userAgent)
            if (e.isUrl) urlInput.setText(e.location) else fileLocation = e.location
            setType(e.type)
            deleteButton.visibility = View.VISIBLE
        } else {
            findViewById<TextView>(R.id.se_title).setText(R.string.source_add_title)
            setType(PlaylistSource.TYPE_URL)
            prefillFromIntent(intent)
        }
        updateFileLabel()

        if (savedInstanceState == null) {
            // Prefilled (edit / shared link): focus the main action; otherwise start at the name field.
            val initial: View = if (e != null || urlInput.text.isNotEmpty() || fileLocation != null) saveButton else nameInput
            initial.post { initial.requestFocus() }
        }
    }

    private fun prefillFromIntent(intent: Intent) {
        intent.getStringExtra(EXTRA_NAME)?.let { nameInput.setText(it) }
        intent.getStringExtra(EXTRA_URL)?.let { urlInput.setText(it) }
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                when (uri.scheme) {
                    "http", "https" -> urlInput.setText(uri.toString())
                    "content", "file" -> {
                        setType(PlaylistSource.TYPE_FILE)
                        setFile(uri.toString())
                    }
                }
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                Regex("https?://\\S+").find(text)?.let { urlInput.setText(it.value) }
            }
        }
    }

    private fun setType(t: Int) {
        type = t
        typeUrl.isSelected = t == PlaylistSource.TYPE_URL
        typeFile.isSelected = t == PlaylistSource.TYPE_FILE
        urlGroup.visibility = if (t == PlaylistSource.TYPE_URL) View.VISIBLE else View.GONE
        fileGroup.visibility = if (t == PlaylistSource.TYPE_FILE) View.VISIBLE else View.GONE
    }

    private fun setFile(location: String) {
        fileLocation = location
        updateFileLabel()
        if (nameInput.text.isBlank()) {
            nameInput.setText(Uri.decode(location).substringAfterLast('/').substringBeforeLast('.'))
        }
        saveButton.requestFocus()
    }

    private fun updateFileLabel() {
        val loc = fileLocation
        filePathView.text = when {
            loc == null -> getString(R.string.source_file_none)
            loc.startsWith(PlaylistSource.ASSET_PREFIX) -> getString(R.string.source_builtin_desc)
            else -> Uri.decode(loc)
        }
    }

    // ---------------------------------------------------------------- file picking

    private fun pickFile() {
        val systemPickerAvailable = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .resolveActivity(packageManager) != null
        if (!systemPickerAvailable) {
            // Most Android TV builds have no document picker: use the built-in browser.
            requestBrowser()
            return
        }
        TvDialog.options(
            this,
            getString(R.string.source_picker_title),
            listOf(getString(R.string.source_picker_builtin), getString(R.string.source_picker_system)),
        ) { i ->
            if (i == 0) {
                requestBrowser()
            } else {
                try {
                    openDocument.launch(arrayOf("*/*"))
                } catch (_: ActivityNotFoundException) {
                    requestBrowser()
                }
            }
        }
    }

    private fun requestBrowser() {
        val needsPermission = Build.VERSION.SDK_INT in 23..32 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE) else launchBrowser()
    }

    private fun launchBrowser() {
        browseFile.launch(Intent(this, FileBrowserActivity::class.java))
    }

    // ---------------------------------------------------------------- save / delete

    private fun save() {
        if (busy) return
        val location: String
        if (type == PlaylistSource.TYPE_URL) {
            location = urlInput.text.toString().trim()
            if (!(location.startsWith("http://", true) || location.startsWith("https://", true)) || location.length < 12) {
                status.text = getString(R.string.source_err_url)
                urlInput.requestFocus()
                return
            }
        } else {
            location = fileLocation ?: run {
                status.text = getString(R.string.source_err_file)
                findViewById<View>(R.id.se_pick).requestFocus()
                return
            }
        }
        val name = nameInput.text.toString().trim().ifEmpty {
            if (type == PlaylistSource.TYPE_URL) {
                Uri.parse(location).host ?: getString(R.string.source_default_name)
            } else {
                getString(R.string.source_default_name)
            }
        }
        val e = editing
        val draft = PlaylistSource(
            id = e?.id ?: 0L,
            name = name,
            type = type,
            location = location,
            epgUrl = epgInput.text.toString().trim(),
            userAgent = uaInput.text.toString().trim(),
            enabled = e?.enabled ?: true,
            lastUpdated = e?.lastUpdated ?: 0L,
            channelCount = e?.channelCount ?: 0,
            headerEpgUrl = e?.headerEpgUrl ?: "",
        )
        setBusy(true)
        // Run in the app scope so an import finishes even if this screen is closed meanwhile.
        val job = AppGraph.appScope.async { repo.importSource(draft) }
        lifecycleScope.launch {
            val result = job.await()
            setBusy(false)
            result.fold(
                onSuccess = { count ->
                    Toast.makeText(this@SourceEditActivity, getString(R.string.source_loaded, count), Toast.LENGTH_SHORT).show()
                    finish()
                },
                onFailure = { err ->
                    val msg = err.message ?: err.javaClass.simpleName
                    status.text = msg
                    TvDialog.confirm(
                        this@SourceEditActivity,
                        getString(R.string.source_load_failed_title),
                        getString(R.string.source_load_failed_body, msg),
                        getString(R.string.source_save_anyway),
                        getString(R.string.cancel),
                        onPositive = {
                            AppGraph.appScope.launch { repo.saveSourceOnly(draft, msg) }
                            finish()
                        },
                    )
                },
            )
        }
    }

    private fun confirmDelete() {
        val e = editing ?: return
        TvDialog.confirm(
            this,
            getString(R.string.source_opt_delete),
            getString(R.string.source_delete_confirm, e.name),
            getString(R.string.delete),
            getString(R.string.cancel),
            onPositive = {
                AppGraph.appScope.launch { repo.deleteSource(e.id) }
                finish()
            },
        )
    }

    private fun setBusy(value: Boolean) {
        busy = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        if (value) status.text = getString(R.string.source_loading)
        saveButton.isEnabled = !value
        saveButton.alpha = if (value) 0.5f else 1f
    }

    companion object {
        const val EXTRA_SOURCE_ID = "source_id"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
    }
}
