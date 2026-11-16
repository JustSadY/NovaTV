package com.novatv.player.core

import java.text.Normalizer
import java.util.Locale

object TextNorm {
    private val combining = Regex("\\p{Mn}+")
    private val nonAlnum = Regex("[^a-z0-9]+")
    private val qualitySuffix = Regex("(fhd|uhd|hd|sd|4k|8k|hevc|h265|1080p|720p|backup|yedek)$")
    private val countryPrefix = Regex("^(tr|us|uk|de|fr|nl|it|es)\\s*[:|\\-]+")

    /** Lowercase, accent- and Turkish-dotless-i-insensitive form for searching. */
    fun search(s: String): String {
        val lower = s.lowercase(Locale.ROOT).replace('ı', 'i')
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        return combining.replace(decomposed, "")
    }

    /** Compact key used to match playlist channel names against XMLTV display names. */
    fun epgKey(s: String): String {
        var k = search(s).trim()
        k = countryPrefix.replace(k, "")
        k = nonAlnum.replace(k, "")
        var prev: String
        do {
            prev = k
            k = qualitySuffix.replace(k, "")
        } while (k != prev && k.isNotEmpty())
        return if (k.isEmpty()) prev else k
    }

    /** Stable key fragment for channel identity. */
    fun identity(s: String): String = search(s).trim().replace(Regex("\\s+"), " ")
}
