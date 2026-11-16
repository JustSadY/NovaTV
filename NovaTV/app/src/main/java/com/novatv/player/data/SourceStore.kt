package com.novatv.player.data

import android.content.Context
import com.novatv.player.core.AtomicFiles
import org.json.JSONArray
import java.io.File

/** Persists the list of playlist sources and their cached playlist files. */
class SourceStore(context: Context) {
    private val file = File(context.filesDir, "sources.json")
    private val playlistDir = File(context.filesDir, "playlists")

    @Synchronized
    fun load(): List<PlaylistSource> {
        val text = AtomicFiles.readText(file) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).map { PlaylistSource.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(sources: List<PlaylistSource>) {
        val arr = JSONArray()
        sources.forEach { arr.put(it.toJson()) }
        AtomicFiles.writeText(file, arr.toString())
    }

    fun playlistFile(id: Long): File {
        playlistDir.mkdirs()
        return File(playlistDir, "$id.m3u")
    }

    fun deletePlaylistFile(id: Long) {
        playlistFile(id).delete()
    }
}
