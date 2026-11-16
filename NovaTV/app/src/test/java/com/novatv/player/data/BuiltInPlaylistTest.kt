package com.novatv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards the playlist bundled in assets: official public-broadcaster HTTPS streams only. */
class BuiltInPlaylistTest {

    private val file = File("src/main/assets/" + PlaylistSource.BUILT_IN_LOCATION.removePrefix(PlaylistSource.ASSET_PREFIX))

    @Test
    fun bundledPlaylistParsesCompletely() {
        val result = file.inputStream().use { M3uParser.parse(it) }
        assertEquals(13, result.entries.size)
        assertEquals(0, result.skipped)
        assertTrue(!result.isHlsStream)
        assertEquals(result.entries.size, result.entries.map { it.name }.toSet().size)
        for (e in result.entries) {
            assertTrue(e.name, e.group.isNotBlank())
            assertTrue(e.name, !e.tvgId.isNullOrBlank())
            assertTrue(e.name, e.logo?.startsWith("https://") == true)
        }
    }

    @Test
    fun onlyOfficialBroadcasterHosts() {
        val result = file.inputStream().use { M3uParser.parse(it) }
        val allowed = listOf(".medya.trt.com.tr", ".live.trt.com.tr", "meclistv-live.ercdn.net")
        for (e in result.entries) {
            val host = e.url.substringAfter("https://").substringBefore('/')
            assertTrue("${e.name}: $host", allowed.any { host.endsWith(it) || host == it })
        }
    }
}
