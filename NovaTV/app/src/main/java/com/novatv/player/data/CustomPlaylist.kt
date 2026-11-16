package com.novatv.player.data

/**
 * The "Kendi kanallarım" list: channels added one by one from the phone panel. It is kept as an
 * ordinary M3U file so it goes through the same parser as every other playlist.
 */
object CustomPlaylist {
    const val EMPTY = "#EXTM3U\n"

    private val URL_RE = Regex("^(https?|rtsp|udp)://\\S+$", RegexOption.IGNORE_CASE)
    private val UNSAFE = Regex("[\\r\\n\"]+")
    private val SPACES = Regex("\\s+")

    data class Entry(val name: String, val url: String, val group: String = "", val logo: String = "")

    fun isValidUrl(url: String): Boolean = URL_RE.matches(url.trim())

    /** Trims user input; null when the name is empty or the stream address is not usable. */
    fun clean(e: Entry): Entry? {
        val name = attr(e.name).take(MAX_NAME)
        val url = e.url.trim()
        if (name.isEmpty() || !isValidUrl(url)) return null
        val logo = e.logo.trim().takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) } ?: ""
        return Entry(name, url, attr(e.group).take(MAX_GROUP), attr(logo))
    }

    fun build(entries: List<Entry>): String = buildString {
        append(EMPTY)
        for (e in entries) {
            append("#EXTINF:-1")
            if (e.logo.isNotEmpty()) append(" tvg-logo=\"").append(e.logo).append('"')
            if (e.group.isNotEmpty()) append(" group-title=\"").append(e.group).append('"')
            append(',').append(e.name).append('\n')
            append(e.url.trim()).append('\n')
        }
    }

    private fun attr(s: String) = s.replace(UNSAFE, " ").replace(SPACES, " ").trim()

    private const val MAX_NAME = 120
    private const val MAX_GROUP = 80
}

/**
 * Portable channel references for "Diğer NovaTV'den eşitle" (same format on Android TV and LG
 * webOS). A key is "<sourceId>|<name>|<group>[#n]"; source ids differ between devices, so a key
 * travels as (source reference, rest of the key). The source reference is "u:<playlist URL>",
 * "b" (bundled official list) or "c" (Kendi kanallarım).
 */
object ConfigSync {
    const val FORMAT = "novatv-config"
    const val VERSION = 1
    const val BUILTIN_REF = "b"
    const val CUSTOM_REF = "c"

    data class Ref(val source: String, val rest: String)

    /** Null for sources that cannot exist on another device (files uploaded to this TV). */
    fun sourceRef(s: PlaylistSource): String? = when {
        s.isBuiltIn -> BUILTIN_REF
        s.isCustom -> CUSTOM_REF
        s.isUrl -> "u:" + s.location
        else -> null
    }

    fun toRef(key: String, refById: Map<Long, String>): Ref? {
        val bar = key.indexOf('|')
        if (bar <= 0) return null
        val ref = key.substring(0, bar).toLongOrNull()?.let { refById[it] } ?: return null
        return Ref(ref, key.substring(bar))
    }

    fun toKey(ref: Ref, idByRef: Map<String, Long>): String? = idByRef[ref.source]?.let { "$it${ref.rest}" }
}
