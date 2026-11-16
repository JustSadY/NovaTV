package com.novatv.player.ui.common

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.novatv.player.R
import com.novatv.player.data.Channel
import com.novatv.player.epg.EpgRepository

/**
 * Channel rows for every list (home sections and the in-player panel). Binding is cheap:
 * the EPG lookup is a binary search and logos come from the shared [LogoLoader].
 */
class ChannelAdapter(
    private val epg: EpgRepository,
    private val onClick: (Channel, Int) -> Unit,
    private val onLongClick: ((Channel, Int) -> Unit)? = null,
    private val onFocused: ((Channel, Int) -> Unit)? = null,
) : RecyclerView.Adapter<ChannelAdapter.Holder>() {

    var items: List<Channel> = emptyList()
        private set
    var favorites: Set<String> = emptySet()
    var broken: Set<String> = emptySet()
    var playingKey: String? = null
    var movingKey: String? = null
    /** In move mode numbers reflect the position being edited. */
    var numberByPosition = false

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = StableIds.of(items[position].key)

    fun submit(list: List<Channel>) {
        items = list
        notifyDataSetChanged()
    }

    fun updateMarks(favorites: Set<String>, broken: Set<String>) {
        this.favorites = favorites
        this.broken = broken
        notifyItemRangeChanged(0, items.size, PAYLOAD_MARKS)
    }

    fun setPlaying(key: String?) {
        val old = playingKey
        playingKey = key
        items.indexOfFirst { it.key == old }.takeIf { it >= 0 }?.let { notifyItemChanged(it, PAYLOAD_MARKS) }
        items.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.let { notifyItemChanged(it, PAYLOAD_MARKS) }
    }

    /** Swaps two rows (move mode). */
    fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        val m = items.toMutableList()
        m.add(to, m.removeAt(from))
        items = m
        notifyItemMoved(from, to)
        notifyItemChanged(from, PAYLOAD_MARKS)
        notifyItemChanged(to, PAYLOAD_MARKS)
    }

    fun indexOf(key: String?): Int = if (key == null) -1 else items.indexOfFirst { it.key == key }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val ch = items[position]
        holder.name.text = ch.name
        LogoLoader.load(holder.logo, ch.logo, ch.name)
        val now = epg.nowNext(ch).now
        holder.sub.text = now?.title?.takeIf { it.isNotBlank() }?.let { "▶ $it" }
            ?: ch.group.ifBlank { holder.itemView.context.getString(R.string.group_uncategorized) }
        if (ch.inMultipleSources) {
            holder.source.visibility = View.VISIBLE
            holder.source.text = ch.sourceName
        } else {
            holder.source.visibility = View.GONE
        }
        bindMarks(holder, ch, position)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_MARKS }) {
            bindMarks(holder, items[position], position)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    private fun bindMarks(holder: Holder, ch: Channel, position: Int) {
        holder.number.text = (if (numberByPosition) position + 1 else ch.number).toString()
        holder.fav.visibility = if (ch.key in favorites) View.VISIBLE else View.GONE
        holder.broken.visibility = if (ch.key in broken) View.VISIBLE else View.GONE
        holder.itemView.isActivated = ch.key == playingKey || ch.key == movingKey
    }

    override fun onViewRecycled(holder: Holder) {
        LogoLoader.cancel(holder.logo)
        holder.itemView.animate().cancel()
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.ch_number)
        val logo: ImageView = view.findViewById(R.id.ch_logo)
        val name: TextView = view.findViewById(R.id.ch_name)
        val sub: TextView = view.findViewById(R.id.ch_sub)
        val source: TextView = view.findViewById(R.id.ch_source)
        val fav: ImageView = view.findViewById(R.id.ch_fav)
        val broken: ImageView = view.findViewById(R.id.ch_broken)

        init {
            view.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos], pos)
            }
            view.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && onLongClick != null) {
                    onLongClick.invoke(items[pos], pos)
                    true
                } else {
                    false
                }
            }
            view.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP && onLongClick != null) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onLongClick.invoke(items[pos], pos)
                    true
                } else {
                    keyCode == KeyEvent.KEYCODE_MENU && onLongClick != null
                }
            }
            view.setOnFocusChangeListener { v, hasFocus ->
                FocusUtils.animateFocus(v, hasFocus, 1.02f)
                name.isSelected = hasFocus
                if (hasFocus) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onFocused?.invoke(items[pos], pos)
                }
            }
        }
    }

    companion object {
        const val PAYLOAD_MARKS = "marks"
    }
}
