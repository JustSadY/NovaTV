package com.novatv.player.ui.player

import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.novatv.player.R
import com.novatv.player.core.TimeFmt
import com.novatv.player.data.Channel
import com.novatv.player.data.ChannelSnapshot
import com.novatv.player.epg.EpgRepository
import com.novatv.player.ui.common.ChannelAdapter
import com.novatv.player.ui.common.FocusUtils

/**
 * Left-hand channel list over the video (LEFT key). LEFT/RIGHT switch group (All, Favorites,
 * categories), UP/DOWN browse, CH+/CH- page, OK tunes (OK on the playing channel closes),
 * MENU toggles favorite, BACK closes.
 */
class ChannelPanel(
    private val root: View,
    private val epg: EpgRepository,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onChannelChosen(channel: Channel, groupId: String)
        fun onToggleFavorite(channel: Channel)
        fun playingKey(): String?
        fun favoriteKeys(): List<String>
    }

    class Group(val id: String, val title: String)

    private val groupTitle: TextView = root.findViewById(R.id.panel_group)
    private val count: TextView = root.findViewById(R.id.panel_count)
    private val list: RecyclerView = root.findViewById(R.id.panel_list)
    private val empty: TextView = root.findViewById(R.id.panel_empty)
    private val epgNow: TextView = root.findViewById(R.id.panel_epg_now)
    private val epgProgress: ProgressBar = root.findViewById(R.id.panel_epg_progress)
    private val epgNext: TextView = root.findViewById(R.id.panel_epg_next)
    private val interpolator = DecelerateInterpolator()
    private val autoHide = Runnable { close() }

    private var snapshot = ChannelSnapshot.EMPTY
    private var groups: List<Group> = emptyList()
    private var groupIndex = 0
    private var focusedChannel: Channel? = null

    private val adapter = ChannelAdapter(
        epg,
        onClick = { ch, _ -> callbacks.onChannelChosen(ch, currentGroupId()) },
        onLongClick = { ch, _ -> callbacks.onToggleFavorite(ch) },
        onFocused = { ch, _ ->
            focusedChannel = ch
            showEpg(ch)
        },
    )

    init {
        FocusUtils.setupList(list)
        list.adapter = adapter
    }

    val isOpen: Boolean get() = root.visibility == View.VISIBLE

    fun currentGroupId(): String = groups.getOrNull(groupIndex)?.id ?: GROUP_ALL

    fun onSnapshot(snap: ChannelSnapshot) {
        snapshot = snap
        val ctx = root.context
        val g = ArrayList<Group>()
        g.add(Group(GROUP_ALL, ctx.getString(R.string.group_all)))
        g.add(Group(GROUP_FAV, ctx.getString(R.string.group_favorites)))
        for (info in snap.groups) {
            g.add(Group(GROUP_PREFIX + info.name, info.name.ifBlank { ctx.getString(R.string.group_uncategorized) }))
        }
        val previous = currentGroupId()
        groups = g
        groupIndex = groups.indexOfFirst { it.id == previous }.coerceAtLeast(0)
        if (isOpen) showGroup(keepFocusKey = focusedChannel?.key)
    }

    fun open(groupId: String) {
        groupIndex = groups.indexOfFirst { it.id == groupId }.coerceAtLeast(0)
        // Fall back to "All" when the playing channel is not in the requested group.
        val playing = callbacks.playingKey()
        if (playing != null && channelsFor(currentGroupId()).none { it.key == playing }) groupIndex = 0
        root.animate().cancel()
        if (!isOpen) {
            root.translationX = -(root.width.takeIf { it > 0 }?.toFloat() ?: (root.resources.displayMetrics.density * 430))
            root.visibility = View.VISIBLE
        }
        root.animate().translationX(0f).setDuration(180).setInterpolator(interpolator).start()
        adapter.playingKey = playing
        showGroup(keepFocusKey = playing)
        bump()
    }

    fun close() {
        root.removeCallbacks(autoHide)
        if (!isOpen) return
        root.animate().cancel()
        root.animate().translationX(-root.width.toFloat()).setDuration(150)
            .withEndAction { root.visibility = View.GONE }.start()
    }

    fun handleKey(event: KeyEvent): Boolean {
        bump()
        val down = event.action == KeyEvent.ACTION_DOWN
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (down) switchGroup(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) switchGroup(+1)
                true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_GUIDE -> {
                if (down && event.repeatCount == 0) close()
                true
            }
            else -> false
        }
    }

    fun updateMarks(favorites: Set<String>, broken: Set<String>) {
        adapter.updateMarks(favorites, broken)
        if (isOpen && currentGroupId() == GROUP_FAV) showGroup(keepFocusKey = focusedChannel?.key)
    }

    fun setPlaying(key: String?) {
        adapter.setPlaying(key)
    }

    fun refreshEpg() {
        if (!isOpen) return
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        focusedChannel?.let { showEpg(it) }
    }

    fun dispose() {
        root.removeCallbacks(autoHide)
    }

    private fun switchGroup(delta: Int) {
        if (groups.isEmpty()) return
        groupIndex = ((groupIndex + delta) % groups.size + groups.size) % groups.size
        showGroup(keepFocusKey = callbacks.playingKey())
    }

    /** CH+ pages up the list, CH− pages down (like a receiver's channel list). */
    fun pageForChannelKey(step: Int) {
        bump()
        page(if (step > 0) -PAGE else PAGE)
    }

    private fun page(delta: Int) {
        val focused = list.focusedChild ?: return
        val pos = list.getChildAdapterPosition(focused)
        if (pos == RecyclerView.NO_POSITION) return
        FocusUtils.focusPosition(list, (pos + delta).coerceIn(0, adapter.itemCount - 1))
    }

    private fun channelsFor(groupId: String): List<Channel> = when {
        groupId == GROUP_ALL -> snapshot.channels
        groupId == GROUP_FAV -> callbacks.favoriteKeys().mapNotNull { snapshot.byKey(it) }
        groupId.startsWith(GROUP_PREFIX) -> snapshot.inGroup(groupId.removePrefix(GROUP_PREFIX))
        else -> snapshot.channels
    }

    private fun showGroup(keepFocusKey: String?) {
        val group = groups.getOrNull(groupIndex) ?: return
        val items = channelsFor(group.id)
        groupTitle.text = group.title
        count.text = root.context.getString(R.string.channel_count, items.size)
        adapter.submit(items)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (items.isEmpty()) View.INVISIBLE else View.VISIBLE
        if (items.isEmpty()) {
            focusedChannel = null
            epgNow.text = ""
            epgNext.text = ""
            epgProgress.visibility = View.GONE
            // Keep keys flowing to the activity while the list is empty.
            root.requestFocus()
            return
        }
        val pos = items.indexOfFirst { it.key == keepFocusKey }.coerceAtLeast(0)
        list.post { FocusUtils.focusPosition(list, pos) }
    }

    private fun showEpg(ch: Channel) {
        val ctx = root.context
        val nowMs = System.currentTimeMillis()
        val nn = epg.nowNext(ch, nowMs)
        val current = nn.now
        if (current != null) {
            epgNow.text = ctx.getString(R.string.time_range, TimeFmt.hm(current.start), TimeFmt.hm(current.stop)) + "  " + current.title
            epgProgress.visibility = View.VISIBLE
            epgProgress.progress = (current.progress(nowMs) * 1000).toInt()
        } else {
            epgNow.text = ch.name
            epgProgress.visibility = View.GONE
        }
        epgNext.text = nn.next?.let { ctx.getString(R.string.epg_next) + "  " + TimeFmt.hm(it.start) + "  " + it.title }
            ?: ctx.getString(R.string.no_epg).takeIf { current == null } ?: ""
    }

    private fun bump() {
        root.removeCallbacks(autoHide)
        root.postDelayed(autoHide, 30_000)
    }

    companion object {
        const val GROUP_ALL = "all"
        const val GROUP_FAV = "fav"
        const val GROUP_PREFIX = "g:"
        private const val PAGE = 8
    }
}
