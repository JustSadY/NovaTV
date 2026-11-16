package com.novatv.player.ui.home.sections

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.novatv.player.AppGraph
import com.novatv.player.R
import com.novatv.player.core.TextNorm
import com.novatv.player.data.Channel
import com.novatv.player.data.ChannelSnapshot
import com.novatv.player.ui.common.ChannelAdapter
import com.novatv.player.ui.common.FocusUtils
import com.novatv.player.ui.common.TvDialog
import com.novatv.player.ui.home.ChannelActions
import com.novatv.player.ui.home.MainActivity
import com.novatv.player.ui.player.ChannelPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Tüm Kanallar" (with search, source filter and re-ordering), "Favoriler" and "Son İzlenenler". */
class ChannelsSection(host: MainActivity, private val mode: Mode) : Section(host, R.layout.section_channels) {
    enum class Mode { ALL, FAVORITES, RECENTS }

    private val repo = AppGraph.channels
    private val epg = AppGraph.epg

    private val title: TextView = view.findViewById(R.id.list_title)
    private val count: TextView = view.findViewById(R.id.list_count)
    private val action: TextView = view.findViewById(R.id.list_action)
    private val filters: View = view.findViewById(R.id.list_filters)
    private val search: EditText = view.findViewById(R.id.list_search)
    private val sourceFilter: TextView = view.findViewById(R.id.list_source_filter)
    private val list: RecyclerView = view.findViewById(R.id.list_recycler)
    private val empty: TextView = view.findViewById(R.id.list_empty)
    private val hint: TextView = view.findViewById(R.id.list_hint)

    private var snapshot = ChannelSnapshot.EMPTY
    private var query = ""
    private var sourceId: Long? = null
    private var filterJob: Job? = null
    private var lastFocusedKey: String? = null

    // Move (re-order) mode.
    private var movingKey: String? = null
    private var orderBeforeMove: List<Channel>? = null

    private val adapter = ChannelAdapter(
        epg,
        onClick = { ch, _ -> if (movingKey == null) host.play(ch, playGroup()) },
        onLongClick = { ch, pos -> if (movingKey == null) showOptions(ch, pos) },
        onFocused = { ch, _ -> lastFocusedKey = ch.key },
    )

    init {
        FocusUtils.setupList(list)
        list.adapter = adapter
        rightEdgeViews.add(list)
        title.text = string(
            when (mode) {
                Mode.ALL -> R.string.nav_all
                Mode.FAVORITES -> R.string.nav_favorites
                Mode.RECENTS -> R.string.nav_recent
            }
        )
        hint.text = string(R.string.list_hint_all)
        if (mode == Mode.ALL) {
            filters.visibility = View.VISIBLE
            setupSearch()
            sourceFilter.setOnClickListener { pickSource() }
            updateSourceFilterLabel()
        }
        if (mode == Mode.RECENTS) {
            action.visibility = View.VISIBLE
            action.text = string(R.string.clear_recents)
            action.setOnClickListener {
                repo.clearRecents()
                host.osd.show(string(R.string.recents_cleared))
            }
        }
        whileStarted {
            launch {
                repo.snapshot.collect {
                    snapshot = it
                    if (movingKey == null) refresh()
                }
            }
            launch { repo.favorites.collect { if (mode == Mode.FAVORITES && movingKey == null) refresh() else updateMarks() } }
            launch { repo.recents.collect { if (mode == Mode.RECENTS) refresh() } }
            launch { repo.broken.collect { updateMarks() } }
            launch { epg.version.collect { adapter.notifyItemRangeChanged(0, adapter.itemCount) } }
        }
    }

    override fun onShow() {
        if (movingKey == null) refresh()
    }

    override fun focusContent(): Boolean {
        if (adapter.itemCount > 0) {
            val pos = adapter.indexOf(lastFocusedKey).coerceAtLeast(0)
            FocusUtils.focusPosition(list, pos)
            return true
        }
        return if (mode == Mode.ALL) search.requestFocus() else action.takeIf { it.visibility == View.VISIBLE }?.requestFocus() ?: false
    }

    /** Applies an external source filter (from the sources section). */
    fun showSource(id: Long?) {
        sourceId = id
        query = ""
        search.setText("")
        updateSourceFilterLabel()
        refresh()
    }

    private fun playGroup() = if (mode == Mode.FAVORITES) ChannelPanel.GROUP_FAV else ChannelPanel.GROUP_ALL

    private fun setupSearch() {
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                if (q == query) return
                query = q
                refresh(debounceMs = 250)
            }
        })
        search.setOnEditorActionListener { v, actionId, event ->
            val isEnter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || isEnter) {
                if (event == null || event.action == KeyEvent.ACTION_UP) {
                    val imm = host.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                    // Apply the query right away, then move to the results.
                    query = search.text.toString().trim()
                    refresh()
                    list.postDelayed({ if (adapter.itemCount > 0) FocusUtils.focusPosition(list, 0) }, 150)
                }
                true
            } else {
                false
            }
        }
    }

    private fun refresh(debounceMs: Long = 0) {
        filterJob?.cancel()
        val snap = snapshot
        val q = query
        val src = sourceId
        filterJob = host.lifecycleScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val result = withContext(Dispatchers.Default) { compute(snap, q, src) }
            show(result)
        }
    }

    private fun compute(snap: ChannelSnapshot, q: String, src: Long?): List<Channel> = when (mode) {
        Mode.FAVORITES -> repo.favorites.value.mapNotNull { snap.byKey(it) }
        Mode.RECENTS -> repo.recents.value.mapNotNull { snap.byKey(it) }
        Mode.ALL -> {
            var base = if (src == null) snap.channels else snap.channels.filter { it.sourceId == src }
            if (q.isNotEmpty()) {
                val nq = TextNorm.search(q)
                val number = q.toIntOrNull()
                val matches = base.filter { it.searchKey.contains(nq) || (it.tvgName?.let { n -> TextNorm.search(n).contains(nq) } == true) }
                val exact = number?.let { snap.byNumber(it) }?.takeIf { src == null || it.sourceId == src }
                base = if (exact != null) listOf(exact) + matches.filter { it.key != exact.key } else matches
            }
            base
        }
    }

    private fun show(items: List<Channel>) {
        val hadFocus = list.hasFocus()
        adapter.favorites = repo.favorites.value.toSet()
        adapter.broken = repo.broken.value
        adapter.submit(items)
        count.text = string(R.string.channel_count, items.size)
        val emptyText = when {
            snapshot.loaded && snapshot.sources.isEmpty() -> string(R.string.empty_no_sources)
            mode == Mode.FAVORITES -> string(R.string.empty_favorites)
            mode == Mode.RECENTS -> string(R.string.empty_recent)
            else -> string(R.string.empty_channels)
        }
        empty.text = emptyText
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (hadFocus && items.isNotEmpty()) {
            list.post { if (!list.hasFocus()) FocusUtils.focusPosition(list, adapter.indexOf(lastFocusedKey).coerceAtLeast(0)) }
        } else if (hadFocus) {
            host.focusNavItem()
        }
    }

    private fun updateMarks() {
        adapter.updateMarks(repo.favorites.value.toSet(), repo.broken.value)
    }

    private fun pickSource() {
        val sources = snapshot.sources.filter { it.enabled }
        val labels = listOf(string(R.string.all_sources)) + sources.map { "${it.name}  (${it.channelCount})" }
        val selected = if (sourceId == null) 0 else sources.indexOfFirst { it.id == sourceId } + 1
        TvDialog.options(host, string(R.string.nav_sources), labels, selected) { i ->
            sourceId = if (i == 0) null else sources[i - 1].id
            updateSourceFilterLabel()
            refresh()
        }
    }

    private fun updateSourceFilterLabel() {
        val name = sourceId?.let { id -> snapshot.source(id)?.name }
        sourceFilter.text = if (name == null) string(R.string.filter_source_all) else string(R.string.filter_source, name)
    }

    // ---------------------------------------------------------------- options / move mode

    private fun showOptions(ch: Channel, pos: Int) {
        val extras = ArrayList<ChannelActions.Extra>()
        when (mode) {
            Mode.ALL -> extras.add(ChannelActions.Extra(string(R.string.opt_move)) { startMove(ch, pos) })
            Mode.FAVORITES -> {
                extras.add(ChannelActions.Extra(string(R.string.opt_move_up)) { repo.moveFavorite(ch.key, -1) })
                extras.add(ChannelActions.Extra(string(R.string.opt_move_down)) { repo.moveFavorite(ch.key, +1) })
            }
            Mode.RECENTS -> extras.add(ChannelActions.Extra(string(R.string.opt_remove_recent)) { repo.removeRecent(ch.key) })
        }
        ChannelActions.show(host, ch, playGroup(), extras)
    }

    private fun startMove(ch: Channel, pos: Int) {
        if (query.isNotEmpty() || sourceId != null) {
            host.osd.show(string(R.string.move_needs_no_filter))
            return
        }
        orderBeforeMove = adapter.items
        movingKey = ch.key
        adapter.movingKey = ch.key
        adapter.numberByPosition = true
        adapter.notifyItemChanged(pos, ChannelAdapter.PAYLOAD_MARKS)
        hint.text = string(R.string.list_hint_move)
        hint.setTextColor(ContextCompat.getColor(host, R.color.accent_light))
        FocusUtils.focusPosition(list, pos)
    }

    override fun onKey(event: KeyEvent): Boolean {
        val key = movingKey
        if (key == null) {
            // UP from the first row goes to the search box (not the source button beside it).
            if (mode == Mode.ALL && event.keyCode == KeyEvent.KEYCODE_DPAD_UP && list.hasFocus()) {
                val focused = list.focusedChild
                if (focused != null && list.getChildAdapterPosition(focused) == 0) {
                    if (event.action == KeyEvent.ACTION_DOWN) search.requestFocus()
                    return true
                }
            }
            return super.onKey(event)
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (down) {
                    val from = adapter.indexOf(key)
                    val step = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1
                    else if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1
                    else if (event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP) -10 else 10
                    val to = (from + step).coerceIn(0, adapter.itemCount - 1)
                    if (from >= 0 && to != from) {
                        if (kotlin.math.abs(step) == 1) {
                            adapter.move(from, to)
                        } else {
                            var cur = from
                            while (cur != to) {
                                val nxt = cur + if (to > cur) 1 else -1
                                adapter.move(cur, nxt)
                                cur = nxt
                            }
                        }
                        FocusUtils.focusPosition(list, to)
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.action == KeyEvent.ACTION_UP) commitMove()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
        }
        return false
    }

    override fun onBack(): Boolean {
        if (movingKey == null) return false
        // Cancel: restore the previous order.
        orderBeforeMove?.let { adapter.submit(it) }
        endMove()
        return true
    }

    private fun commitMove() {
        val keys = adapter.items.map { it.key }
        val moved = movingKey
        endMove()
        host.lifecycleScope.launch {
            repo.saveOrder(keys)
            host.osd.show(string(R.string.order_saved))
            lastFocusedKey = moved
        }
    }

    private fun endMove() {
        val key = movingKey
        movingKey = null
        orderBeforeMove = null
        adapter.movingKey = null
        adapter.numberByPosition = false
        adapter.notifyItemRangeChanged(0, adapter.itemCount, ChannelAdapter.PAYLOAD_MARKS)
        hint.text = string(R.string.list_hint_all)
        hint.setTextColor(ContextCompat.getColor(host, R.color.text_tertiary))
        FocusUtils.focusPosition(list, adapter.indexOf(key).coerceAtLeast(0))
    }
}
