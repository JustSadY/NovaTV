package com.novatv.player.ui.home.sections

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.novatv.player.AppGraph
import com.novatv.player.R
import com.novatv.player.core.TimeFmt
import com.novatv.player.data.Channel
import com.novatv.player.data.ChannelSnapshot
import com.novatv.player.ui.common.FocusUtils
import com.novatv.player.ui.common.LogoLoader
import com.novatv.player.ui.common.StableIds
import com.novatv.player.ui.home.MainActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** "Canlı TV": hero card for the last channel (or onboarding when no source exists) + recents row. */
class LiveSection(host: MainActivity) : Section(host, R.layout.section_live) {
    private val repo = AppGraph.channels
    private val epg = AppGraph.epg

    private val stats: TextView = view.findViewById(R.id.live_stats)
    private val logo: ImageView = view.findViewById(R.id.live_logo)
    private val caption: TextView = view.findViewById(R.id.live_caption)
    private val title: TextView = view.findViewById(R.id.live_title)
    private val body: TextView = view.findViewById(R.id.live_body)
    private val progress: ProgressBar = view.findViewById(R.id.live_progress)
    private val primary: TextView = view.findViewById(R.id.live_primary)
    private val secondary: TextView = view.findViewById(R.id.live_secondary)
    private val recentTitle: TextView = view.findViewById(R.id.live_recent_title)
    private val recentList: RecyclerView = view.findViewById(R.id.live_recent)
    private val recentAdapter = CardAdapter { host.play(it) }

    private var snapshot = ChannelSnapshot.EMPTY
    private var recents: List<String> = emptyList()

    init {
        FocusUtils.setupList(recentList, horizontal = true)
        recentList.adapter = recentAdapter
        FocusUtils.applyFocusZoom(primary)
        FocusUtils.applyFocusZoom(secondary)
        whileStarted {
            launch {
                combine(repo.snapshot, repo.recents) { s, r -> s to r }.collect { (s, r) ->
                    snapshot = s
                    recents = r
                    bind()
                }
            }
            launch { epg.version.collect { bind() } }
        }
    }

    override fun onShow() = bind()

    override fun focusContent(): Boolean = primary.requestFocus()

    private fun bind() {
        val s = snapshot
        stats.text = string(R.string.live_stats, s.channels.size, s.sources.size, s.groups.size)
        when {
            s.loaded && s.sources.isEmpty() -> bindWelcome()
            !s.loaded && AppGraph.settings.lastChannel == null -> bindLoading()
            else -> bindChannel(s.byKey(AppGraph.settings.lastChannel?.key) ?: AppGraph.settings.lastChannel ?: s.channels.firstOrNull())
        }
        val recentChannels = recents.mapNotNull { s.byKey(it) }.take(12)
        recentAdapter.submit(recentChannels)
        val showRecents = recentChannels.isNotEmpty()
        recentTitle.visibility = if (showRecents) View.VISIBLE else View.GONE
        recentList.visibility = if (showRecents) View.VISIBLE else View.INVISIBLE
    }

    private fun bindWelcome() {
        LogoLoader.showResource(logo, R.mipmap.ic_launcher)
        caption.text = string(R.string.app_name)
        title.text = string(R.string.live_welcome_title)
        body.text = string(R.string.live_welcome_body)
        progress.visibility = View.GONE
        primary.text = string(R.string.live_add_source)
        primary.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_add, 0, 0, 0)
        primary.setOnClickListener { host.openSourceEditor(null) }
        secondary.visibility = View.VISIBLE
        secondary.text = string(R.string.link_button)
        secondary.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_phone, 0, 0, 0)
        secondary.setOnClickListener { host.openLink() }
    }

    private fun bindLoading() {
        LogoLoader.showResource(logo, R.mipmap.ic_launcher)
        caption.text = string(R.string.app_name)
        title.text = string(R.string.live_loading_channels)
        body.text = ""
        progress.visibility = View.GONE
        primary.text = string(R.string.live_watch)
        primary.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0)
        primary.setOnClickListener { snapshot.channels.firstOrNull()?.let { host.play(it) } }
        secondary.visibility = View.GONE
    }

    private fun bindChannel(ch: Channel?) {
        if (ch == null) {
            bindLoading()
            return
        }
        LogoLoader.load(logo, ch.logo, ch.name)
        val isLast = AppGraph.settings.lastChannel != null
        caption.text = string(if (isLast) R.string.live_last_channel else R.string.nav_live)
        title.text = if (ch.number > 0) "${ch.number}  ${ch.name}" else ch.name
        val now = System.currentTimeMillis()
        val nn = epg.nowNext(ch, now)
        val current = nn.now
        val lines = ArrayList<String>(2)
        if (current != null) {
            lines.add(string(R.string.time_range, TimeFmt.hm(current.start), TimeFmt.hm(current.stop)) + "   " + current.title)
            progress.visibility = View.VISIBLE
            progress.progress = (current.progress(now) * 1000).toInt()
        } else {
            lines.add(ch.group.ifBlank { string(R.string.group_uncategorized) })
            progress.visibility = View.GONE
        }
        nn.next?.let { lines.add(string(R.string.epg_next) + "  " + TimeFmt.hm(it.start) + "   " + it.title) }
        body.text = lines.joinToString("\n")
        primary.text = string(if (isLast) R.string.live_continue else R.string.live_watch)
        primary.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0)
        primary.setOnClickListener { host.play(ch) }
        secondary.visibility = View.VISIBLE
        secondary.text = string(R.string.live_channel_list)
        secondary.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_list, 0, 0, 0)
        secondary.setOnClickListener { host.openPlayerWithList() }
    }

    private class CardAdapter(private val onClick: (Channel) -> Unit) : RecyclerView.Adapter<CardAdapter.Holder>() {
        private var items: List<Channel> = emptyList()

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = StableIds.of(items[position].key)

        fun submit(list: List<Channel>) {
            if (list == items) return
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_channel_card, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val ch = items[position]
            holder.name.text = ch.name
            LogoLoader.load(holder.logo, ch.logo, ch.name)
        }

        override fun onViewRecycled(holder: Holder) = LogoLoader.cancel(holder.logo)

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val logo: ImageView = v.findViewById(R.id.card_logo)
            val name: TextView = v.findViewById(R.id.card_name)

            init {
                v.setOnClickListener {
                    val p = bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) onClick(items[p])
                }
                FocusUtils.applyFocusZoom(v, 1.06f)
            }
        }
    }
}
