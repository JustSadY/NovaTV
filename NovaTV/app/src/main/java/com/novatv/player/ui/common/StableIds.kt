package com.novatv.player.ui.common

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Maps string keys to unique long ids for RecyclerView stable ids. Stable ids let RecyclerView
 * keep the focused row attached across notifyDataSetChanged(), so remote focus never "falls"
 * to another part of the screen when a list refreshes.
 */
object StableIds {
    private val ids = ConcurrentHashMap<String, Long>()
    private val next = AtomicLong(1)

    fun of(key: String): Long = ids.getOrPut(key) { next.getAndIncrement() }
}
