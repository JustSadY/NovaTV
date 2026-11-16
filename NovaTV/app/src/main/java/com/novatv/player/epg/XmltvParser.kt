package com.novatv.player.epg

import android.util.Xml
import com.novatv.player.core.TextNorm
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.Locale

/**
 * Streaming XMLTV parser that keeps memory low on weak devices: only channels requested via
 * [wantedIds]/[wantedNames] and programmes inside [windowStart, windowEnd] are retained.
 */
class XmltvParser(
    private val wantedIds: Set<String>,
    private val wantedNames: Set<String>,
    private val windowStart: Long,
    private val windowEnd: Long,
) {
    /** XMLTV channel id (lower-case) -> programmes. */
    val programmes = HashMap<String, ArrayList<Programme>>()
    /** Normalized display name -> XMLTV channel id (lower-case). */
    val nameIndex = HashMap<String, String>()

    private val keepIds = HashSet<String>()
    private var sawChannels = false
    private val preferredLang = Locale.getDefault().language

    fun parse(input: InputStream) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> readChannel(parser)
                    "programme" -> readProgramme(parser)
                }
            }
            event = parser.next()
        }
    }

    private fun readChannel(p: XmlPullParser) {
        sawChannels = true
        val id = p.getAttributeValue(null, "id")?.trim()?.lowercase(Locale.ROOT)
        val names = ArrayList<String>(2)
        val depth = p.depth
        while (true) {
            val e = p.next()
            if (e == XmlPullParser.END_DOCUMENT) return
            if (e == XmlPullParser.END_TAG && p.depth == depth) break
            if (e == XmlPullParser.START_TAG && p.name == "display-name") {
                readText(p)?.let { names.add(it) }
            }
        }
        if (id.isNullOrEmpty()) return
        val keys = names.map { TextNorm.epgKey(it) }.filter { it.isNotEmpty() }
        val wanted = id in wantedIds || TextNorm.epgKey(id) in wantedNames || keys.any { it in wantedNames }
        if (!wanted) return
        keepIds.add(id)
        for (k in keys) if (!nameIndex.containsKey(k)) nameIndex[k] = id
        val idKey = TextNorm.epgKey(id)
        if (!nameIndex.containsKey(idKey)) nameIndex[idKey] = id
    }

    private fun readProgramme(p: XmlPullParser) {
        val channel = p.getAttributeValue(null, "channel")?.trim()?.lowercase(Locale.ROOT)
        val keep = channel != null && (channel in keepIds || (!sawChannels && channel in wantedIds))
        if (!keep) {
            skip(p)
            return
        }
        val start = parseTime(p.getAttributeValue(null, "start"))
        val stop = parseTime(p.getAttributeValue(null, "stop")).let { if (it <= 0) start + 3_600_000L else it }
        if (start <= 0 || stop < windowStart || start > windowEnd) {
            skip(p)
            return
        }
        var title: String? = null
        var titleLangPreferred = false
        var desc: String? = null
        val depth = p.depth
        while (true) {
            val e = p.next()
            if (e == XmlPullParser.END_DOCUMENT) return
            if (e == XmlPullParser.END_TAG && p.depth == depth) break
            if (e != XmlPullParser.START_TAG) continue
            when (p.name) {
                "title" -> {
                    val lang = p.getAttributeValue(null, "lang")
                    val text = readText(p)
                    if (text != null && (title == null || (!titleLangPreferred && lang == preferredLang))) {
                        title = text
                        titleLangPreferred = lang == preferredLang
                    }
                }
                "desc" -> {
                    val text = readText(p)
                    if (desc == null && text != null) desc = if (text.length > 400) text.substring(0, 400) + "…" else text
                }
                else -> skip(p)
            }
        }
        val list = programmes.getOrPut(channel!!) { ArrayList(24) }
        list.add(Programme(start, stop, title ?: "", desc))
    }

    /** Reads the text content of the current element and positions on its END_TAG. */
    private fun readText(p: XmlPullParser): String? {
        val sb = StringBuilder()
        val depth = p.depth
        while (true) {
            val e = p.next()
            when {
                e == XmlPullParser.END_DOCUMENT -> break
                e == XmlPullParser.TEXT -> sb.append(p.text)
                e == XmlPullParser.END_TAG && p.depth == depth -> break
            }
        }
        return sb.toString().trim().ifEmpty { null }
    }

    /** Skips the current element including all children. */
    private fun skip(p: XmlPullParser) {
        if (p.eventType != XmlPullParser.START_TAG) return
        var level = 1
        while (level > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> level++
                XmlPullParser.END_TAG -> level--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    companion object {
        /** Parses XMLTV time "yyyyMMddHHmmss +hhmm" (seconds and offset optional) to epoch millis. */
        fun parseTime(s: String?): Long {
            if (s == null) return -1
            val t = s.trim()
            if (t.length < 12) return -1
            fun num(from: Int, len: Int): Int {
                var v = 0
                for (i in from until from + len) {
                    val c = t[i]
                    if (c !in '0'..'9') return -1
                    v = v * 10 + (c - '0')
                }
                return v
            }
            val y = num(0, 4)
            val mo = num(4, 2)
            val d = num(6, 2)
            val h = num(8, 2)
            val mi = num(10, 2)
            if (y < 0 || mo !in 1..12 || d !in 1..31 || h < 0 || mi < 0) return -1
            val sec = if (t.length >= 14) num(12, 2).coerceAtLeast(0) else 0
            var offsetMinutes = 0
            var i = 12
            while (i < t.length && t[i] != '+' && t[i] != '-') i++
            if (i < t.length && i + 5 <= t.length) {
                val sign = if (t[i] == '-') -1 else 1
                val oh = num(i + 1, 2)
                val om = num(i + 3, 2)
                if (oh >= 0 && om >= 0) offsetMinutes = sign * (oh * 60 + om)
            }
            val days = daysFromCivil(y, mo, d)
            val seconds = days * 86_400L + h * 3_600L + mi * 60L + sec - offsetMinutes * 60L
            return seconds * 1000L
        }

        /** Days since 1970-01-01 for a proleptic Gregorian date (H. Hinnant's algorithm). */
        fun daysFromCivil(year: Int, month: Int, day: Int): Long {
            val y = if (month <= 2) year - 1 else year
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val mp = (month + 9) % 12
            val doy = (153 * mp + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097L + doe - 719_468L
        }
    }
}
