package com.novatv.player.data

import com.novatv.player.core.TextNorm
import org.json.JSONObject

/** A user-defined IPTV playlist (remote URL or local file). */
data class PlaylistSource(
    val id: Long,
    val name: String,
    val type: Int,
    /** http(s) URL, absolute file path or content:// URI. */
    val location: String,
    val epgUrl: String = "",
    val userAgent: String = "",
    val enabled: Boolean = true,
    val lastUpdated: Long = 0L,
    val channelCount: Int = 0,
    val lastError: String = "",
    /** EPG url found in the playlist header (url-tvg / x-tvg-url). */
    val headerEpgUrl: String = "",
) {
    val isUrl: Boolean get() = type == TYPE_URL

    /** Playlist shipped inside the APK (assets). */
    val isBuiltIn: Boolean get() = location.startsWith(ASSET_PREFIX)

    /** "Kendi kanallarım": channels added one by one (from the phone panel); stored as a local M3U. */
    val isCustom: Boolean get() = location == CUSTOM_LOCATION

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("type", type)
        .put("location", location)
        .put("epgUrl", epgUrl)
        .put("userAgent", userAgent)
        .put("enabled", enabled)
        .put("lastUpdated", lastUpdated)
        .put("channelCount", channelCount)
        .put("lastError", lastError)
        .put("headerEpgUrl", headerEpgUrl)

    companion object {
        const val TYPE_URL = 0
        const val TYPE_FILE = 1
        const val ASSET_PREFIX = "asset://"

        /** Official, free public-broadcaster streams (TRT, TBMM TV) bundled with the app. */
        const val BUILT_IN_LOCATION = ASSET_PREFIX + "playlists/turkiye_resmi.m3u"

        const val CUSTOM_LOCATION = "custom://channels"

        fun fromJson(o: JSONObject) = PlaylistSource(
            id = o.getLong("id"),
            name = o.optString("name"),
            type = o.optInt("type", TYPE_URL),
            location = o.optString("location"),
            epgUrl = o.optString("epgUrl"),
            userAgent = o.optString("userAgent"),
            enabled = o.optBoolean("enabled", true),
            lastUpdated = o.optLong("lastUpdated"),
            channelCount = o.optInt("channelCount"),
            lastError = o.optString("lastError"),
            headerEpgUrl = o.optString("headerEpgUrl"),
        )
    }
}

/**
 * One playable channel. [key] is stable across playlist refreshes (it does not include the
 * stream URL, which often contains expiring tokens) so favorites/order/last channel survive.
 */
data class Channel(
    val key: String,
    val sourceId: Long,
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String = "",
    val tvgId: String? = null,
    val tvgName: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    /** 1-based global channel number (assigned when the combined list is built). */
    val number: Int = 0,
    val sourceName: String = "",
    /** True when a channel with the same name exists in another source. */
    val inMultipleSources: Boolean = false,
    val searchKey: String = TextNorm.search(name),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("sourceId", sourceId)
        .put("name", name)
        .put("url", url)
        .put("logo", logo ?: "")
        .put("group", group)
        .put("tvgId", tvgId ?: "")
        .put("tvgName", tvgName ?: "")
        .put("userAgent", userAgent ?: "")
        .put("referrer", referrer ?: "")
        .put("number", number)
        .put("sourceName", sourceName)

    companion object {
        fun fromJson(o: JSONObject) = Channel(
            key = o.getString("key"),
            sourceId = o.optLong("sourceId"),
            name = o.optString("name"),
            url = o.getString("url"),
            logo = o.optString("logo").ifBlank { null },
            group = o.optString("group"),
            tvgId = o.optString("tvgId").ifBlank { null },
            tvgName = o.optString("tvgName").ifBlank { null },
            userAgent = o.optString("userAgent").ifBlank { null },
            referrer = o.optString("referrer").ifBlank { null },
            number = o.optInt("number"),
            sourceName = o.optString("sourceName"),
        )
    }
}

class GroupInfo(val name: String, val count: Int)

/** Immutable view of the combined, ordered and numbered channel list. */
class ChannelSnapshot(
    val channels: List<Channel>,
    val sources: List<PlaylistSource>,
    val loaded: Boolean,
    /** Channels the user hid (removed from the list without deleting their playlist). */
    val hidden: List<Channel> = emptyList(),
) {
    private val indexByKey: HashMap<String, Int> = HashMap<String, Int>(channels.size * 2).also { map ->
        channels.forEachIndexed { i, c -> map[c.key] = i }
    }

    /** Groups in order of first appearance. */
    val groups: List<GroupInfo> by lazy {
        val counts = LinkedHashMap<String, Int>()
        for (c in channels) counts[c.group] = (counts[c.group] ?: 0) + 1
        counts.map { (name, count) -> GroupInfo(name, count) }
    }

    private val byGroup: Map<String, List<Channel>> by lazy { channels.groupBy { it.group } }

    val isEmpty: Boolean get() = channels.isEmpty()

    fun indexOf(key: String?): Int = if (key == null) -1 else indexByKey[key] ?: -1

    fun byKey(key: String?): Channel? = indexOf(key).let { if (it >= 0) channels[it] else null }

    fun byNumber(number: Int): Channel? = channels.getOrNull(number - 1)

    fun inGroup(group: String): List<Channel> = byGroup[group] ?: emptyList()

    fun ofSource(sourceId: Long): List<Channel> = channels.filter { it.sourceId == sourceId }

    fun source(id: Long): PlaylistSource? = sources.firstOrNull { it.id == id }

    companion object {
        val EMPTY = ChannelSnapshot(emptyList(), emptyList(), loaded = false)
    }
}
