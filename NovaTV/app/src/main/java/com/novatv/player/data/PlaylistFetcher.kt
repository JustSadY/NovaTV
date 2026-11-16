package com.novatv.player.data

import android.content.Context
import android.net.Uri
import com.novatv.player.core.HttpClients
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** Downloads / copies a playlist into the local cache file. Blocking: call from a background thread. */
class PlaylistFetcher(private val context: Context, private val http: HttpClients) {

    fun fetch(source: PlaylistSource, dest: File) {
        if (source.isCustom) {
            // The local file is the list itself; it is rewritten by ChannelRepository.
            if (!dest.exists()) dest.writeText(CustomPlaylist.EMPTY, Charsets.UTF_8)
            return
        }
        val tmp = File(dest.path + ".tmp")
        try {
            openSource(source).use { input -> copyLimited(input, tmp) }
            if (tmp.length() == 0L) throw IOException("Liste boş")
            if (dest.exists() && !dest.delete()) throw IOException("Önbellek dosyası güncellenemedi")
            if (!tmp.renameTo(dest)) throw IOException("Önbellek dosyası yazılamadı")
        } finally {
            tmp.delete()
        }
    }

    private fun openSource(source: PlaylistSource): InputStream {
        if (source.isUrl) {
            val request = Request.Builder()
                .url(source.location)
                .header("User-Agent", http.userAgent(source.userAgent))
                .header("Accept", "*/*")
                .build()
            val response = http.download.newCall(request).execute()
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                throw IOException("HTTP $code")
            }
            return response.body?.byteStream() ?: run {
                response.close()
                throw IOException("Boş yanıt")
            }
        }
        val location = source.location
        if (location.startsWith(PlaylistSource.ASSET_PREFIX)) {
            return context.assets.open(location.removePrefix(PlaylistSource.ASSET_PREFIX))
        }
        return if (location.startsWith("content://") || location.startsWith("file://")) {
            context.contentResolver.openInputStream(Uri.parse(location))
                ?: throw IOException("Dosya açılamadı")
        } else {
            val f = File(location)
            if (!f.canRead()) throw IOException("Dosyaya erişilemiyor: ${f.name}")
            FileInputStream(f)
        }
    }

    private fun copyLimited(input: InputStream, out: File) {
        out.outputStream().buffered(64 * 1024).use { os ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                total += r
                if (total > MAX_PLAYLIST_BYTES) throw IOException("Liste çok büyük (>${MAX_PLAYLIST_BYTES / 1024 / 1024} MB)")
                os.write(buf, 0, r)
            }
        }
    }

    companion object {
        const val MAX_PLAYLIST_BYTES = 80L * 1024 * 1024

        /** Opens a cached playlist, transparently handling gzip-compressed files. */
        fun openMaybeGzip(file: File): InputStream = maybeGunzip(FileInputStream(file))

        fun maybeGunzip(raw: InputStream): InputStream {
            val input = BufferedInputStream(raw, 64 * 1024)
            input.mark(4)
            val b1 = input.read()
            val b2 = input.read()
            input.reset()
            return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(input, 64 * 1024) else input
        }
    }
}
