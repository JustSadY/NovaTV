package com.novatv.player.epg

import com.novatv.player.core.TextNorm
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class XmltvTimeTest {

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(y, mo - 1, d, h, mi, s)
        }.timeInMillis

    @Test
    fun parsesWithOffset() {
        assertEquals(utc(2024, 11, 15, 3, 0), XmltvParser.parseTime("20241115060000 +0300"))
        assertEquals(utc(2024, 3, 1, 1, 30), XmltvParser.parseTime("20240229203000 -0500"))
    }

    @Test
    fun parsesWithoutSecondsOrOffset() {
        assertEquals(utc(2025, 1, 1, 0, 0), XmltvParser.parseTime("202501010000"))
        assertEquals(utc(1970, 1, 1, 0, 0), XmltvParser.parseTime("19700101000000 +0000"))
    }

    @Test
    fun rejectsGarbage() {
        assertEquals(-1L, XmltvParser.parseTime("abc"))
        assertEquals(-1L, XmltvParser.parseTime(null))
        assertEquals(-1L, XmltvParser.parseTime("20241345000000"))
    }

    @Test
    fun epgKeyNormalisation() {
        assertEquals("trt1", TextNorm.epgKey("TRT 1 HD"))
        assertEquals("trt1", TextNorm.epgKey("TR: TRT 1"))
        assertEquals("trt1", TextNorm.epgKey("TR | TRT-1 FHD"))
        assertEquals("showtv", TextNorm.epgKey("Show TV"))
        assertEquals("ntv", TextNorm.epgKey("NTV"))
        assertEquals("cnnturk", TextNorm.epgKey("CNN Türk"))
        assertEquals("istanbul", TextNorm.search("İstanbul").let { TextNorm.epgKey(it) })
    }
}
