package com.novatv.player.data

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URLDecoder

/** One raw playlist entry before identity/numbering is applied. */
data class M3uEntry(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val tvgId: String?,
    val tvgName: String?,
    val userAgent: String?,
    val referrer: String?,
)

class M3uResult(
    val entries: List<M3uEntry>,
    /** EPG URL(s) announced in the #EXTM3U header, comma separated if several. */
    val epgUrl: String?,
    /** True if the file is actually an HLS media/master playlist (a single stream), not a channel list. */
    val isHlsStream: Boolean,
    /** Entries ignored because of unsupported URL schemes. */
    val skipped: Int,
)

/**
 * Streaming M3U / M3U8 (extended M3U) parser.
 *
 * Supports: #EXTM3U header attributes (url-tvg, x-tvg-url, tvg-url), #EXTINF attributes
 * (tvg-id, tvg-name, tvg-logo, logo, group-title, user-agent) with quoted values containing commas,
 * #EXTGRP, #EXTVLCOPT http-user-agent / http-referrer, Kodi style "url|User-Agent=..&Referer=..",
 * bare URL lines without #EXTINF, BOM and CRLF line endings.
 */
object M3uParser {

    private const val BOM = '\uFEFF'
    private val SUPPORTED_SCHEMES = setOf("http", "https", "rtsp", "udp")

    fun parse(input: InputStream): M3uResult {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024)
        val entries = ArrayList<M3uEntry>(1024)
        var epgUrl: String? = null
        var hlsTags = 0
        var skipped = 0

        var pendingAttrs: Map<String, String>? = null
        var pendingName: String? = null
        var extGroup: String? = null
        var vlcUserAgent: String? = null
        var vlcReferrer: String? = null
        var first = true

        while (true) {
            var line = reader.readLine() ?: break
            if (first) {
                if (line.isNotEmpty() && line[0] == BOM) line = line.substring(1)
                first = false
            }
            line = line.trim()
            if (line.isEmpty()) continue

            if (line[0] == '#') {
                when {
                    line.startsWith("#EXTM3U", ignoreCase = true) -> {
                        val attrs = HashMap<String, String>()
                        parseAttributes(line, 7, attrs, stopAtComma = false)
                        val header = attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"]
                        if (!header.isNullOrBlank()) epgUrl = header.trim()
                    }
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        val attrs = HashMap<String, String>()
                        val colon = line.indexOf(':')
                        val nameStart = if (colon < 0) line.length else parseExtInf(line, colon + 1, attrs)
                        pendingAttrs = attrs
                        pendingName = line.substring(nameStart).trim()
                    }
                    line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                        extGroup = line.substring(8).trim()
                    }
                    line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                        val opt = line.substring(11).trim()
                        val eq = opt.indexOf('=')
                        if (eq > 0) {
                            val key = opt.substring(0, eq).trim().lowercase()
                            val value = opt.substring(eq + 1).trim()
                            when (key) {
                                "http-user-agent" -> vlcUserAgent = value
                                "http-referrer", "http-referer" -> vlcReferrer = value
                            }
                        }
                    }
                    line.startsWith("#EXT-X-TARGETDURATION", ignoreCase = true) ||
                        line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) ||
                        line.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true) -> hlsTags++
                }
                continue
            }

            // A URL line.
            val pipe = line.indexOf('|')
            val url = (if (pipe >= 0) line.substring(0, pipe) else line).trim()
            var pipeUa: String? = null
            var pipeRef: String? = null
            if (pipe >= 0) {
                for (part in line.substring(pipe + 1).split('&')) {
                    val eq = part.indexOf('=')
                    if (eq <= 0) continue
                    val k = part.substring(0, eq).trim().lowercase()
                    val v = decode(part.substring(eq + 1).trim())
                    when (k) {
                        "user-agent" -> pipeUa = v
                        "referer", "referrer" -> pipeRef = v
                    }
                }
            }

            val attrs = pendingAttrs ?: emptyMap()
            if (isSupported(url)) {
                val tvgName = attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                val name = pendingName?.takeIf { it.isNotBlank() } ?: tvgName ?: nameFromUrl(url)
                entries.add(
                    M3uEntry(
                        name = name,
                        url = url,
                        logo = (attrs["tvg-logo"] ?: attrs["logo"])?.takeIf { it.isNotBlank() },
                        group = (attrs["group-title"]?.takeIf { it.isNotBlank() } ?: extGroup ?: "").trim(),
                        tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                        tvgName = tvgName,
                        userAgent = pipeUa ?: vlcUserAgent ?: attrs["user-agent"]?.takeIf { it.isNotBlank() },
                        referrer = pipeRef ?: vlcReferrer ?: attrs["http-referrer"]?.takeIf { it.isNotBlank() },
                    )
                )
            } else {
                skipped++
            }
            pendingAttrs = null
            pendingName = null
            extGroup = null
            vlcUserAgent = null
            vlcReferrer = null
        }
        return M3uResult(entries, epgUrl, isHlsStream = hlsTags > 0 && entries.none { it.tvgId != null || it.logo != null }, skipped = skipped)
    }

    /**
     * Parses `-1 key="value" key2=value2,Display Name` starting at [start].
     * Returns the index where the display name begins.
     */
    internal fun parseExtInf(line: String, start: Int, out: MutableMap<String, String>): Int {
        var i = start
        val n = line.length
        // Skip the duration token.
        while (i < n && line[i] != ' ' && line[i] != '\t' && line[i] != ',') i++
        i = parseAttributes(line, i, out, stopAtComma = true)
        return if (i < n && line[i] == ',') i + 1 else i
    }

    /** Parses key=value pairs; returns the index of the terminating comma (or end of line). */
    private fun parseAttributes(line: String, start: Int, out: MutableMap<String, String>, stopAtComma: Boolean): Int {
        var i = start
        val n = line.length
        while (i < n) {
            val c = line[i]
            if (c == ',' && stopAtComma) return i
            if (c == ' ' || c == '\t' || c == ',') {
                i++
                continue
            }
            val keyStart = i
            while (i < n && line[i] != '=' && line[i] != ' ' && line[i] != '\t' && !(stopAtComma && line[i] == ',')) i++
            val key = line.substring(keyStart, i).trim().lowercase()
            if (i < n && line[i] == '=') {
                i++
                if (i < n && (line[i] == '"' || line[i] == '\'')) {
                    val quote = line[i]
                    val valueStart = i + 1
                    val end = line.indexOf(quote, valueStart).let { if (it < 0) n else it }
                    if (key.isNotEmpty()) out[key] = line.substring(valueStart, end).trim()
                    i = if (end < n) end + 1 else n
                } else {
                    val valueStart = i
                    while (i < n && line[i] != ' ' && line[i] != '\t' && !(stopAtComma && line[i] == ',')) i++
                    if (key.isNotEmpty()) out[key] = line.substring(valueStart, i).trim()
                }
            }
        }
        return n
    }

    fun isSupported(url: String): Boolean {
        val colon = url.indexOf("://")
        if (colon <= 0) return false
        return url.substring(0, colon).lowercase() in SUPPORTED_SCHEMES
    }

    private fun nameFromUrl(url: String): String {
        val path = url.substringBefore('?').trimEnd('/')
        val last = path.substringAfterLast('/')
        val base = last.substringBeforeLast('.').ifBlank { last }
        return base.ifBlank { url.substringAfter("://").substringBefore('/') }
    }

    private fun decode(v: String): String = try {
        URLDecoder.decode(v, "UTF-8")
    } catch (_: Exception) {
        v
    }
}
