package com.novatv.player.ui.sources

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.novatv.player.R
import com.novatv.player.ui.common.FocusUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Remote-friendly file browser for importing M3U files from internal storage or USB drives.
 * Needed because most Android TV builds ship without a system document picker.
 */
class FileBrowserActivity : ComponentActivity() {

    private class Entry(val file: File, val label: String, val isDir: Boolean, val icon: Int, val info: String = "")

    private lateinit var pathView: TextView
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private val adapter = EntryAdapter { open(it) }

    /** null = list of storage roots. */
    private var currentDir: File? = null
    private var roots: List<Entry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_browser)
        pathView = findViewById(R.id.fb_path)
        list = findViewById(R.id.fb_list)
        empty = findViewById(R.id.fb_empty)
        FocusUtils.setupList(list)
        list.adapter = adapter

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goUp()
        })

        roots = findRoots()
        if (roots.size == 1) show(roots[0].file) else show(null)
    }

    private fun findRoots(): List<Entry> {
        val result = LinkedHashMap<String, Entry>()
        @Suppress("DEPRECATION")
        val primary = Environment.getExternalStorageDirectory()
        if (primary.canRead()) result[primary.absolutePath] = Entry(primary, getString(R.string.files_internal), true, R.drawable.ic_folder)
        // Removable volumes (USB sticks / SD cards): derive their roots from app-specific dirs.
        for (dir in getExternalFilesDirs(null)) {
            val path = dir?.absolutePath ?: continue
            val idx = path.indexOf("/Android/")
            if (idx <= 0) continue
            val root = File(path.substring(0, idx))
            if (root.canRead() && root.absolutePath !in result) {
                result[root.absolutePath] = Entry(root, root.name, true, R.drawable.ic_usb)
            }
        }
        File("/storage").listFiles()?.forEach { f ->
            if (f.isDirectory && f.canRead() && f.name != "self" && f.name != "emulated" && f.absolutePath !in result) {
                result[f.absolutePath] = Entry(f, f.name, true, R.drawable.ic_usb)
            }
        }
        return result.values.toList()
    }

    private fun show(dir: File?) {
        currentDir = dir
        pathView.text = dir?.absolutePath ?: getString(R.string.files_roots)
        lifecycleScope.launch {
            val entries = if (dir == null) roots else withContext(Dispatchers.IO) { listDir(dir) }
            adapter.submit(entries)
            empty.visibility = if (entries.isEmpty() || (entries.size == 1 && entries[0].label == getString(R.string.files_up))) View.VISIBLE else View.GONE
            if (entries.isNotEmpty()) list.post { FocusUtils.focusPosition(list, 0) }
        }
    }

    private fun listDir(dir: File): List<Entry> {
        val out = ArrayList<Entry>()
        out.add(Entry(dir.parentFile ?: dir, getString(R.string.files_up), true, R.drawable.ic_chevron_left))
        val files = dir.listFiles() ?: return out
        val dirs = files.filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name.lowercase(Locale.ROOT) }
        val playlists = files.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in EXTENSIONS }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
        dirs.forEach { out.add(Entry(it, it.name, true, R.drawable.ic_folder)) }
        playlists.forEach { out.add(Entry(it, it.name, false, R.drawable.ic_file, formatSize(it.length()))) }
        return out
    }

    private fun open(entry: Entry) {
        if (entry.label == getString(R.string.files_up)) {
            goUp()
            return
        }
        if (entry.isDir) {
            show(entry.file)
        } else {
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PATH, entry.file.absolutePath))
            finish()
        }
    }

    private fun goUp() {
        val dir = currentDir
        when {
            dir == null -> finish()
            roots.any { it.file.absolutePath == dir.absolutePath } -> if (roots.size > 1) show(null) else finish()
            else -> show(dir.parentFile)
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024f / 1024f)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    private class EntryAdapter(private val onClick: (Entry) -> Unit) : RecyclerView.Adapter<EntryAdapter.Holder>() {
        private var items: List<Entry> = emptyList()

        fun submit(list: List<Entry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = items[position]
            holder.icon.setImageResource(e.icon)
            holder.name.text = e.label
            holder.info.text = e.info
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.file_icon)
            val name: TextView = v.findViewById(R.id.file_name)
            val info: TextView = v.findViewById(R.id.file_info)

            init {
                v.setOnClickListener {
                    val p = bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) onClick(items[p])
                }
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        private val EXTENSIONS = setOf("m3u", "m3u8", "txt")
    }
}
