package com.novatv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private fun parse(text: String) = M3uParser.parse(text.byteInputStream())

    @Test
    fun parsesExtendedAttributesAndHeader() {
        val r = parse(
            "﻿#EXTM3U url-tvg=\"http://epg.example/guide.xml.gz\" x-tvg-url=\"ignored\"\r\n" +
                "#EXTINF:-1 tvg-id=\"trt1.tr\" tvg-name=\"TRT 1\" tvg-logo=\"http://logo/trt1.png\" group-title=\"Ulusal, HD\",TRT 1 HD\r\n" +
                "http://stream.example/live/trt1.m3u8\r\n"
        )
        assertEquals("http://epg.example/guide.xml.gz", r.epgUrl)
        assertEquals(1, r.entries.size)
        val e = r.entries[0]
        assertEquals("TRT 1 HD", e.name)
        assertEquals("trt1.tr", e.tvgId)
        assertEquals("TRT 1", e.tvgName)
        assertEquals("http://logo/trt1.png", e.logo)
        assertEquals("Ulusal, HD", e.group)
        assertEquals("http://stream.example/live/trt1.m3u8", e.url)
        assertFalse(r.isHlsStream)
    }

    @Test
    fun nameMayContainCommas() {
        val r = parse("#EXTM3U\n#EXTINF:-1 group-title=\"News\",Channel, Extra\nhttp://a/b.ts\n")
        assertEquals("Channel, Extra", r.entries[0].name)
        assertEquals("News", r.entries[0].group)
    }

    @Test
    fun extGrpVlcOptAndPipeHeaders() {
        val r = parse(
            "#EXTM3U\n" +
                "#EXTINF:0,First\n#EXTGRP:Sports\n#EXTVLCOPT:http-user-agent=MyAgent/1.0\n#EXTVLCOPT:http-referrer=http://ref/\nhttp://a/1.ts\n" +
                "#EXTINF:-1,Second\nhttp://a/2.m3u8|User-Agent=Kodi%2F20&Referer=http://r2/\n"
        )
        assertEquals(2, r.entries.size)
        assertEquals("Sports", r.entries[0].group)
        assertEquals("MyAgent/1.0", r.entries[0].userAgent)
        assertEquals("http://ref/", r.entries[0].referrer)
        assertEquals("http://a/2.m3u8", r.entries[1].url)
        assertEquals("Kodi/20", r.entries[1].userAgent)
        assertEquals("http://r2/", r.entries[1].referrer)
        // Options must not leak into the next entry.
        assertEquals("", r.entries[1].group)
    }

    @Test
    fun bareUrlsAndUnsupportedSchemes() {
        val r = parse("http://host/path/channel_one.ts\nrtmp://host/live\n#EXTINF:-1,Udp\nudp://@239.0.0.1:1234\n")
        assertEquals(2, r.entries.size)
        assertEquals("channel_one", r.entries[0].name)
        assertEquals("Udp", r.entries[1].name)
        assertEquals(1, r.skipped)
    }

    @Test
    fun unquotedAndSingleQuotedAttributes() {
        val r = parse("#EXTM3U\n#EXTINF:-1 tvg-id=abc tvg-logo='http://l/x.png',Name\nhttps://s/x\n")
        assertEquals("abc", r.entries[0].tvgId)
        assertEquals("http://l/x.png", r.entries[0].logo)
        assertNull(r.entries[0].tvgName)
    }

    @Test
    fun detectsHlsMediaPlaylist() {
        val r = parse("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:6.0,\nhttp://s/seg1.ts\n")
        assertTrue(r.isHlsStream)
    }
}
