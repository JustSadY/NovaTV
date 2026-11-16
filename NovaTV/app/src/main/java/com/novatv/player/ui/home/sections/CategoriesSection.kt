package com.novatv.player.ui.home.sections

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.novatv.player.AppGraph
import com.novatv.player.R
import com.novatv.player.data.ChannelSnapshot
import com.novatv.player.data.GroupInfo
import com.novatv.player.ui.common.ChannelAdapter
import com.novatv.player.ui.common.FocusUtils
import com.novatv.player.ui.common.StableIds
import com.novatv.player.ui.home.ChannelActions
import com.novatv.player.ui.home.MainActivity
import com.novatv.player.ui.player.ChannelPanel
import kotlinx.coroutines.launch

/** "Kategoriler": group-title list on the left, channels of the focused group on the right. */
class CategoriesSection(host: MainActivity) : Section(host, R.layout.section_categories) {
    private val repo = AppGraph.channels
    private val epg = AppGraph.epg

    private val countView: TextView = view.findViewById(R.id.cat_count)
    private val groupList: RecyclerView = view.findViewById(R.id.cat_groups)
    private val channelList: RecyclerView = view.findViewById(R.id.cat_channels)
    private val empty: TextView = view.findViewById(R.id.cat_empty)

    private var snapshot = ChannelSnapshot.EMPTY
    private var selectedGroup: String? = null
    private val showGroupRunnable = Runnable { showChannels() }

    private val groupAdapter = GroupAdapter(
        onFocus = { g ->
            if (g.name != selectedGroup) {
                selectedGroup = g.name
                groupList.removeCallbacks(showGroupRunnable)
                groupList.postDelayed(showGroupRunnable, 140)
            }
        },
        onClick = { g ->
            selectedGroup = g.name
            showChannels()
            if (channelAdapter.itemCount > 0) FocusUtils.focusPosition(channelList, 0)
        },
    )

    private val channelAdapter = ChannelAdapter(
        epg,
        onClick = { ch, _ -> host.play(ch, ChannelPanel.GROUP_PREFIX + ch.group) },
        onLongClick = { ch, _ -> ChannelActions.show(host, ch, ChannelPanel.GROUP_PREFIX + ch.group) },
    )

    init {
        FocusUtils.setupList(groupList)
        FocusUtils.setupList(channelList)
        groupList.adapter = groupAdapter
        channelList.adapter = channelAdapter
        rightEdgeViews.add(channelList)
        whileStarted {
            launch {
                repo.snapshot.collect {
                    snapshot = it
                    bindGroups()
                }
            }
            launch { repo.favorites.collect { channelAdapter.updateMarks(it.toSet(), repo.broken.value) } }
            launch { repo.broken.collect { channelAdapter.updateMarks(repo.favorites.value.toSet(), it) } }
            launch { epg.version.collect { channelAdapter.notifyItemRangeChanged(0, channelAdapter.itemCount) } }
        }
    }

    override fun focusContent(): Boolean {
        if (groupAdapter.itemCount == 0) return false
        val pos = groupAdapter.indexOf(selectedGroup).coerceAtLeast(0)
        FocusUtils.focusPosition(groupList, pos)
        return true
    }

    private fun bindGroups() {
        val groups = snapshot.groups
        countView.text = host.resources.getQuantityString(R.plurals.category_count, groups.size, groups.size)
        groupAdapter.submit(groups)
        if (selectedGroup == null || groups.none { it.name == selectedGroup }) selectedGroup = groups.firstOrNull()?.name
        groupAdapter.selected = selectedGroup
        showChannels()
    }

    private fun showChannels() {
        val name = selectedGroup
        groupAdapter.selected = name
        groupAdapter.notifyItemRangeChanged(0, groupAdapter.itemCount, "sel")
        val items = if (name == null) emptyList() else snapshot.inGroup(name)
        channelAdapter.favorites = repo.favorites.value.toSet()
        channelAdapter.broken = repo.broken.value
        channelAdapter.submit(items)
        empty.text = if (snapshot.loaded && snapshot.sources.isEmpty()) string(R.string.empty_no_sources) else string(R.string.categories_hint)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private class GroupAdapter(
        private val onFocus: (GroupInfo) -> Unit,
        private val onClick: (GroupInfo) -> Unit,
    ) : RecyclerView.Adapter<GroupAdapter.Holder>() {
        private var items: List<GroupInfo> = emptyList()
        var selected: String? = null

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = StableIds.of("group|" + items[position].name)

        fun submit(list: List<GroupInfo>) {
            items = list
            notifyDataSetChanged()
        }

        fun indexOf(name: String?) = items.indexOfFirst { it.name == name }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val g = items[position]
            holder.name.text = g.name.ifBlank { holder.itemView.context.getString(R.string.group_uncategorized) }
            holder.count.text = g.count.toString()
            holder.itemView.isActivated = g.name == selected
        }

        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty()) {
                holder.itemView.isActivated = items[position].name == selected
            } else {
                onBindViewHolder(holder, position)
            }
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.grp_name)
            val count: TextView = v.findViewById(R.id.grp_count)

            init {
                v.setOnClickListener {
                    val p = bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) onClick(items[p])
                }
                v.setOnFocusChangeListener { view, has ->
                    FocusUtils.animateFocus(view, has, 1.02f)
                    val p = bindingAdapterPosition
                    if (has && p != RecyclerView.NO_POSITION) onFocus(items[p])
                }
            }
        }
    }
}
