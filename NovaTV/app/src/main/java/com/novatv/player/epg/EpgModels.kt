package com.novatv.player.epg

class Programme(
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
) {
    fun isLive(now: Long) = now in start until stop

    fun progress(now: Long): Float =
        if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}

/**
 * In-memory guide. [programmes] is keyed by lower-cased XMLTV channel id, each array sorted by
 * start time. [nameIndex] maps normalized display names to channel ids for name-based matching.
 */
class EpgData(
    val programmes: Map<String, Array<Programme>>,
    val nameIndex: Map<String, String>,
    val updatedAt: Long,
) {
    val channelCount: Int get() = programmes.size

    companion object {
        val EMPTY = EpgData(emptyMap(), emptyMap(), 0L)
    }
}

class NowNext(val now: Programme?, val next: Programme?)
