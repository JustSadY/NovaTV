package com.novatv.player.data

import com.novatv.player.update.Versions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageLogicTest {

    @Test
    fun `own channels survive a write and parse round trip`() {
        val entries = listOf(
            CustomPlaylist.Entry("Kanal \"A\", HD", "https://ornek.tv/a/index.m3u8", "Haber", "https://ornek.tv/a.png"),
            CustomPlaylist.Entry("Yerel Yayın", "udp://239.0.0.1:1234"),
        ).map { CustomPlaylist.clean(it)!! }
        val parsed = M3uParser.parse(CustomPlaylist.build(entries).byteInputStream()).entries
        assertEquals(2, parsed.size)
        assertEquals("Kanal A , HD", parsed[0].name)
        assertEquals("https://ornek.tv/a/index.m3u8", parsed[0].url)
        assertEquals("Haber", parsed[0].group)
        assertEquals("https://ornek.tv/a.png", parsed[0].logo)
        assertEquals("Yerel Yayın", parsed[1].name)
        assertEquals("udp://239.0.0.1:1234", parsed[1].url)
    }

    @Test
    fun `invalid own channels are rejected`() {
        assertNull(CustomPlaylist.clean(CustomPlaylist.Entry("", "https://x.tv/a.m3u8")))
        assertNull(CustomPlaylist.clean(CustomPlaylist.Entry("A", "ftp://x.tv/a")))
        assertNull(CustomPlaylist.clean(CustomPlaylist.Entry("A", "javascript:alert(1)")))
        // a non-http logo is dropped, the channel is kept
        assertEquals("", CustomPlaylist.clean(CustomPlaylist.Entry("A", "http://x.tv/a", logo = "file:///etc/x"))!!.logo)
    }

    @Test
    fun `channel keys travel between devices by playlist address`() {
        val here = mapOf(11L to "u:http://liste.tv/a.m3u", 12L to ConfigSync.BUILTIN_REF, 13L to ConfigSync.CUSTOM_REF)
        val there = mapOf("u:http://liste.tv/a.m3u" to 501L, ConfigSync.BUILTIN_REF to 502L, ConfigSync.CUSTOM_REF to 503L)
        val ref = ConfigSync.toRef("11|trt 1|ulusal", here)!!
        assertEquals("u:http://liste.tv/a.m3u", ref.source)
        assertEquals("501|trt 1|ulusal", ConfigSync.toKey(ref, there))
        assertEquals("502|trt haber|haber#1", ConfigSync.toKey(ConfigSync.toRef("12|trt haber|haber#1", here)!!, there))
        assertEquals("503|benim|", ConfigSync.toKey(ConfigSync.toRef("13|benim|", here)!!, there))
        assertNull(ConfigSync.toRef("99|x|", here))        // unknown (e.g. uploaded file) source
        assertNull(ConfigSync.toKey(ConfigSync.Ref("u:http://baska", "|x|"), there))
    }

    @Test
    fun `versions and repository setting`() {
        assertTrue(Versions.compare("v1.0.2", "1.0.1") > 0)
        assertTrue(Versions.compare("1.0.10", "1.0.9") > 0)
        assertEquals(0, Versions.compare("v1.0", "1.0.0"))
        assertTrue(Versions.compare("1.0.1", "1.1") < 0)
        assertTrue(Versions.compare("NovaTV 2.0.0-beta", "1.9.9") > 0)
        assertEquals("kullanici/novatv", Versions.parseRepo("kullanici/novatv"))
        assertEquals("kullanici/novatv", Versions.parseRepo("https://github.com/kullanici/novatv/releases"))
        assertEquals("kullanici/nova.tv", Versions.parseRepo("github.com/kullanici/nova.tv.git"))
        assertNull(Versions.parseRepo("sadece-isim"))
        assertNull(Versions.parseRepo("a b/c"))
    }
}
