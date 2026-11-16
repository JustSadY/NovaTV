package com.novatv.player.ui.home.sections

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.novatv.player.AppGraph
import com.novatv.player.R
import com.novatv.player.core.TimeFmt
import com.novatv.player.data.PlaylistSource
import com.novatv.player.ui.common.FocusUtils
import com.novatv.player.ui.common.TvDialog
import com.novatv.player.ui.home.MainActivity
import kotlinx.coroutines.launch

/** "IPTV Kaynakları": add / edit / refresh / enable / reorder / delete playlists. */
class SourcesSection(host: MainActivity) : Section(host, R.layout.section_sources) {
    private val repo = AppGraph.channels

    private val addButton: TextView = view.findViewById(R.id.src_add)
    private val phoneButton: TextView = view.findViewById(R.id.src_phone)
    private val refreshAll: TextView = view.findViewById(R.id.src_refresh_all)
    private val progress: ProgressBar = view.findViewById(R.id.src_progress)
    private val list: RecyclerView = view.findViewById(R.id.src_list)
    private val empty: TextView = view.findViewById(R.id.src_empty)
    private val adapter = SourceAdapter { showOptions(it) }

    init {
        FocusUtils.setupList(list)
        list.adapter = adapter
        rightEdgeViews.add(list)
        FocusUtils.applyFocusZoom(addButton)
        FocusUtils.applyFocusZoom(refreshAll)
        FocusUtils.applyFocusZoom(phoneButton)
        addButton.setOnClickListener { host.openSourceEditor(null) }
        phoneButton.setOnClickListener { host.openLink() }
        refreshAll.setOnClickListener { refreshAllSources() }
        whileStarted {
            launch {
                repo.snapshot.collect { snap ->
                    adapter.submit(snap.sources)
                    empty.visibility = if (snap.loaded && snap.sources.isEmpty()) View.VISIBLE else View.GONE
                }
            }
            launch {
                repo.refreshing.collect { ids ->
                    adapter.refreshing = ids
                    adapter.notifyItemRangeChanged(0, adapter.itemCount)
                    progress.visibility = if (ids.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    override fun focusContent(): Boolean =
        if (adapter.itemCount > 0) {
            FocusUtils.focusPosition(list, 0)
            true
        } else {
            addButton.requestFocus()
        }

    private fun refreshAllSources() {
        host.osd.show(string(R.string.sources_refreshing_all))
        host.lifecycleScope.launch {
            val results = repo.refreshAll()
            val failures = results.filter { it.second.isFailure }
            if (failures.isEmpty()) {
                host.osd.show(string(R.string.sources_refreshed_all))
            } else {
                val (src, res) = failures.first()
                host.osd.show(string(R.string.source_refresh_failed, src.name, res.exceptionOrNull()?.message ?: ""), 5_000)
            }
        }
    }

    private fun showOptions(source: PlaylistSource) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (!source.isCustom) {
            labels.add(string(R.string.source_opt_edit)); actions.add { host.openSourceEditor(source.id) }
        }
        labels.add(string(R.string.source_opt_refresh)); actions.add { refreshOne(source) }
        labels.add(string(R.string.source_opt_show)); actions.add {
            val section = host.navigateTo(R.id.nav_all, focusContent = true)
            (section as? ChannelsSection)?.showSource(source.id)
        }
        labels.add(string(if (source.enabled) R.string.source_opt_disable else R.string.source_opt_enable))
        actions.add { host.lifecycleScope.launch { repo.setSourceEnabled(source.id, !source.enabled) } }
        labels.add(string(R.string.opt_move_up)); actions.add { host.lifecycleScope.launch { repo.moveSource(source.id, -1) } }
        labels.add(string(R.string.opt_move_down)); actions.add { host.lifecycleScope.launch { repo.moveSource(source.id, +1) } }
        labels.add(string(R.string.source_opt_delete)); actions.add { confirmDelete(source) }
        TvDialog.options(host, source.name, labels) { i -> actions[i]() }
    }

    private fun refreshOne(source: PlaylistSource) {
        host.osd.show(string(R.string.source_refreshing, source.name))
        host.lifecycleScope.launch {
            repo.refreshSource(source.id).fold(
                onSuccess = { host.osd.show(string(R.string.source_refreshed, source.name, it)) },
                onFailure = { host.osd.show(string(R.string.source_refresh_failed, source.name, it.message ?: ""), 5_000) },
            )
        }
    }

    private fun confirmDelete(source: PlaylistSource) {
        TvDialog.confirm(
            host,
            string(R.string.source_opt_delete),
            string(R.string.source_delete_confirm, source.name),
            string(R.string.delete),
            string(R.string.cancel),
            onPositive = {
                host.lifecycleScope.launch {
                    repo.deleteSource(source.id)
                    view.post { focusContent() }
                }
            },
        )
    }

    private class SourceAdapter(private val onClick: (PlaylistSource) -> Unit) : RecyclerView.Adapter<SourceAdapter.Holder>() {
        private var items: List<PlaylistSource> = emptyList()
        var refreshing: Set<Long> = emptySet()

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = items[position].id

        fun submit(list: List<PlaylistSource>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_source, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val s = items[position]
            val ctx = holder.itemView.context
            holder.icon.setImageResource(
                when {
                    s.isUrl -> R.drawable.ic_link
                    s.isBuiltIn -> R.drawable.ic_live
                    s.isCustom -> R.drawable.ic_star
                    else -> R.drawable.ic_file
                }
            )
            holder.name.text = s.name
            holder.location.text = when {
                s.isBuiltIn -> ctx.getString(R.string.source_builtin_desc)
                s.isCustom -> ctx.getString(R.string.source_custom_desc)
                s.isUrl -> maskCredentials(s.location)
                else -> s.location.substringAfterLast('/')
            }
            val updated = if (s.lastUpdated > 0) TimeFmt.dateTime(s.lastUpdated) else ctx.getString(R.string.never)
            val status = StringBuilder(ctx.getString(R.string.source_status, s.channelCount, updated))
            val errorColor = s.lastError.isNotEmpty()
            if (errorColor) status.append('\n').append(ctx.getString(R.string.source_error, s.lastError))
            holder.status.text = status
            holder.status.setTextColor(ContextCompat.getColor(ctx, if (errorColor) R.color.error else R.color.text_secondary))
            holder.badge.text = when {
                s.id in refreshing -> ctx.getString(R.string.loading)
                !s.enabled -> ctx.getString(R.string.source_disabled)
                s.isBuiltIn -> ctx.getString(R.string.source_type_builtin)
                s.isCustom -> ctx.getString(R.string.source_type_custom)
                else -> ctx.getString(if (s.isUrl) R.string.source_type_url else R.string.source_type_file)
            }
            holder.itemView.alpha = if (s.enabled) 1f else 0.6f
        }

        /** Hides user:password style credentials (e.g. Xtream get.php links) in the list. */
        private fun maskCredentials(url: String): String =
            url.replace(Regex("(?i)(username|password|user|pass)=([^&]+)"), "$1=•••")
                .replace(Regex("://([^/@:]+):([^/@]+)@"), "://•••@")

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.src_icon)
            val name: TextView = v.findViewById(R.id.src_name)
            val location: TextView = v.findViewById(R.id.src_location)
            val status: TextView = v.findViewById(R.id.src_status)
            val badge: TextView = v.findViewById(R.id.src_badge)

            init {
                v.setOnClickListener {
                    val p = bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) onClick(items[p])
                }
                FocusUtils.applyFocusZoom(v, 1.015f)
            }
        }
    }
}
