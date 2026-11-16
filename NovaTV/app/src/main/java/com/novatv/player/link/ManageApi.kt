package com.novatv.player.link

import android.content.Context
import com.novatv.player.BuildConfig
import com.novatv.player.data.ChannelRepository
import com.novatv.player.data.ConfigSync
import com.novatv.player.data.CustomPlaylist
import com.novatv.player.data.PlaylistSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Remote management operations used by the phone panel (assets/link/manage.html).
 * The LG webOS app implements the same operations and JSON shapes (js/data/manage.js), so the
 * one panel manages both kinds of TV. Every operation answers {ok, message | error, ...}.
 */
class ManageApi(private val context: Context, private val repo: ChannelRepository, private val deviceName: () -> String) {

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Human-readable results ("Telefondan: … eklendi"), shown on the TV. */
    val events: SharedFlow<String> = _events

    fun sources(): List<PlaylistSource> = repo.sources()

    fun state(): JSONObject {
        val snap = repo.snapshot.value
        val favorites = repo.favorites.value.toHashSet()
        val refreshing = repo.refreshing.value
        val renamed = repo.renamedChannels()
        val sources = JSONArray()
        for (s in snap.sources) {
            sources.put(
                JSONObject()
                    .put("id", s.id.toString())
                    .put("name", s.name)
                    .put("kind", kind(s))
                    .put("url", if (s.isUrl) s.location else "")
                    .put("epgUrl", s.epgUrl)
                    .put("enabled", s.enabled)
                    .put("channelCount", s.channelCount)
                    .put("lastUpdated", s.lastUpdated)
                    .put("error", s.lastError)
                    .put("refreshing", s.id in refreshing)
            )
        }
        val channels = JSONArray()
        for (c in snap.channels) {
            val o = JSONObject()
                .put("key", c.key)
                .put("number", c.number)
                .put("name", c.name)
                .put("group", c.group)
                .put("sourceId", c.sourceId.toString())
                .put("sourceName", c.sourceName)
                .put("favorite", c.key in favorites)
            if (c.key in renamed) o.put("renamed", true)
            if (snap.source(c.sourceId)?.isCustom == true) o.put("custom", true).put("url", c.url).put("logo", c.logo ?: "")
            channels.put(o)
        }
        val hidden = JSONArray()
        for (c in snap.hidden) {
            hidden.put(JSONObject().put("key", c.key).put("name", c.name).put("group", c.group).put("sourceName", c.sourceName))
        }
        return JSONObject()
            .put("device", JSONObject().put("name", deviceName()).put("platform", "android-tv").put("version", BuildConfig.VERSION_NAME))
            .put("sources", sources)
            .put("channels", channels)
            .put("hidden", hidden)
    }

    suspend fun exec(op: String, a: JSONObject): JSONObject = try {
        when (op) {
            "source.add" -> addSource(a)
            "source.update" -> updateSource(a)
            "source.delete" -> {
                val s = source(a)
                repo.deleteSource(s.id) // also removes a file uploaded from the phone
                done("Kaynak silindi: ${s.name}")
            }
            "source.refresh" -> {
                val s = source(a)
                val n = repo.refreshSource(s.id).getOrThrow()
                done("${s.name} yenilendi ($n kanal)")
            }
            "source.enable" -> {
                val s = source(a)
                val on = a.optBoolean("enabled", true)
                repo.setSourceEnabled(s.id, on)
                done("${s.name} " + if (on) "açıldı" else "kapatıldı")
            }
            "channel.add" -> {
                val key = repo.addCustomChannel(entry(a)).getOrThrow()
                done("Kanal eklendi: ${a.optString("name").trim()}").put("key", key)
            }
            "channel.update" -> updateChannel(a)
            "channel.delete" -> {
                val key = a.getString("key")
                val name = channelName(key)
                val deleted = repo.deleteChannel(key).getOrThrow()
                done(if (deleted) "Kanal silindi: $name" else "Kanal gizlendi: $name")
            }
            "channel.restore" -> {
                val key = a.getString("key")
                repo.restoreChannel(key)
                done("Kanal geri getirildi: ${channelName(key)}")
            }
            "channel.favorite" -> {
                val key = a.getString("key")
                val on = a.optBoolean("on", true)
                repo.setFavorite(key, on)
                done((if (on) "Favorilere eklendi: " else "Favorilerden çıkarıldı: ") + channelName(key))
            }
            "channel.number" -> {
                val key = a.getString("key")
                val n = a.getInt("number")
                if (n < 1) throw IOException("Geçerli bir kanal numarası girin.")
                repo.moveToNumber(key, n)
                done("${channelName(key)} → $n. kanal")
            }
            "order.reset" -> {
                repo.resetOrder()
                done("Kanal sırası sıfırlandı")
            }
            "config.export" -> JSONObject().put("ok", true).put("config", exportConfig())
            "config.import" -> importConfig(a.getJSONObject("config"))
            else -> fail("Bilinmeyen işlem: $op")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fail(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
    }

    // ---------------------------------------------------------------- sources

    private suspend fun addSource(a: JSONObject): JSONObject {
        val url = a.optString("url").trim()
        val m3u = a.optString("m3u").takeIf { it.isNotBlank() }
        val epg = a.optString("epgUrl").trim().takeIf { isHttp(it) } ?: ""
        val name = a.optString("name").trim().ifBlank {
            url.substringAfter("://").substringBefore('/').ifBlank { "Telefondan gelen liste" }
        }.take(120)
        val draft = if (m3u != null) {
            if (!(m3u.contains("#EXT") || m3u.contains("://"))) throw IOException("Dosya bir M3U listesine benzemiyor.")
            val file = File(uploadDir(), UUID.randomUUID().toString().substring(0, 8) + ".m3u")
            file.writeText(m3u, Charsets.UTF_8)
            PlaylistSource(0L, name, PlaylistSource.TYPE_FILE, file.absolutePath, epgUrl = epg)
        } else {
            if (!isHttp(url)) throw IOException("Geçerli bir http(s) adresi veya M3U dosyası gönderin.")
            if (repo.sources().any { it.isUrl && it.location == url }) throw IOException("Bu liste zaten ekli.")
            PlaylistSource(0L, name, PlaylistSource.TYPE_URL, url, epgUrl = epg)
        }
        val result = repo.importSource(draft)
        if (result.isFailure && m3u != null) File(draft.location).delete()
        val n = result.getOrThrow()
        return done("Kaynak eklendi: $name ($n kanal)").put("count", n)
    }

    private suspend fun updateSource(a: JSONObject): JSONObject {
        val s = source(a)
        if (s.isBuiltIn || s.isCustom) throw IOException("Bu kaynağın yalnızca adı değiştirilemez; açıp kapatabilir veya silebilirsiniz.")
        val name = a.optString("name", s.name).trim().ifBlank { s.name }.take(120)
        val epg = a.optString("epgUrl", s.epgUrl).trim().let { if (it.isEmpty() || isHttp(it)) it else throw IOException("EPG adresi http(s) ile başlamalı.") }
        val url = if (s.isUrl) a.optString("url", s.location).trim() else s.location
        if (s.isUrl && !isHttp(url)) throw IOException("Geçerli bir http(s) adresi girin.")
        val draft = s.copy(name = name, location = url, epgUrl = epg)
        return if (url != s.location) {
            val n = repo.importSource(draft).getOrThrow()
            done("Kaynak güncellendi: $name ($n kanal)")
        } else {
            repo.saveSourceOnly(draft, s.lastError)
            done("Kaynak güncellendi: $name")
        }
    }

    private fun source(a: JSONObject): PlaylistSource {
        val id = a.optString("id").toLongOrNull()
        return repo.sources().firstOrNull { it.id == id } ?: throw IOException("Kaynak bulunamadı.")
    }

    // ---------------------------------------------------------------- channels

    private suspend fun updateChannel(a: JSONObject): JSONObject {
        val key = a.getString("key")
        val src = key.substringBefore('|').toLongOrNull()?.let { id -> repo.sources().firstOrNull { it.id == id } }
            ?: throw IOException("Kanal bulunamadı.")
        return if (src.isCustom && a.has("url")) {
            val newKey = repo.updateCustomChannel(key, entry(a)).getOrThrow()
            done("Kanal güncellendi: ${a.optString("name").trim()}").put("key", newKey)
        } else {
            val name = a.optString("name").trim()
            repo.renameChannels(mapOf(key to name))
            done(if (name.isEmpty()) "Kanal adı sıfırlandı" else "Kanal adı değişti: $name")
        }
    }

    private fun entry(a: JSONObject) = CustomPlaylist.Entry(
        name = a.optString("name"),
        url = a.optString("url"),
        group = a.optString("group"),
        logo = a.optString("logo"),
    )

    private fun channelName(key: String): String {
        val snap = repo.snapshot.value
        return snap.byKey(key)?.name ?: snap.hidden.firstOrNull { it.key == key }?.name ?: key.split('|').getOrElse(1) { key }
    }

    // ---------------------------------------------------------------- sync

    /** Lists of this TV in the device-independent format (see [ConfigSync]). */
    suspend fun exportConfig(): JSONObject {
        val snap = repo.snapshot.value
        val refById = HashMap<Long, String>()
        val sources = JSONArray()
        for (s in snap.sources) {
            val ref = ConfigSync.sourceRef(s) ?: continue
            refById[s.id] = ref
            if (ref == ConfigSync.CUSTOM_REF) continue
            sources.put(JSONObject().put("ref", ref).put("name", s.name).put("url", if (s.isUrl) s.location else "").put("epgUrl", s.epgUrl).put("enabled", s.enabled))
        }
        val custom = JSONArray()
        for (e in repo.customEntries()) custom.put(JSONObject().put("name", e.name).put("url", e.url).put("group", e.group).put("logo", e.logo))
        fun refs(keys: Collection<String>): JSONArray {
            val arr = JSONArray()
            for (k in keys) ConfigSync.toRef(k, refById)?.let { arr.put(JSONArray().put(it.source).put(it.rest)) }
            return arr
        }
        val renamed = JSONArray()
        for ((k, name) in repo.renamedChannels()) {
            ConfigSync.toRef(k, refById)?.let { renamed.put(JSONArray().put(it.source).put(it.rest).put(name)) }
        }
        return JSONObject()
            .put("format", ConfigSync.FORMAT)
            .put("version", ConfigSync.VERSION)
            .put("device", deviceName())
            .put("sources", sources)
            .put("custom", custom)
            .put("favorites", refs(repo.favorites.value))
            .put("hidden", refs(repo.hiddenKeys()))
            .put("renamed", renamed)
            .put("order", refs(repo.orderKeys()))
    }

    /**
     * Adds another TV's lists to this one. Nothing on this TV is deleted: missing sources and own
     * channels are added, favorites / hidden channels / names are merged, the channel order is
     * taken over only when this TV has no custom order yet.
     */
    private suspend fun importConfig(c: JSONObject): JSONObject {
        if (c.optString("format") != ConfigSync.FORMAT) throw IOException("Bu bir NovaTV listesi değil.")
        var sourcesAdded = 0
        val failed = ArrayList<String>()
        val srcs = c.optJSONArray("sources") ?: JSONArray()
        for (i in 0 until srcs.length()) {
            val o = srcs.getJSONObject(i)
            val ref = o.optString("ref")
            if (ref == ConfigSync.BUILTIN_REF) {
                if (!repo.hasBuiltInSource()) {
                    repo.importSource(repo.builtInDraft()).onSuccess { sourcesAdded++ }.onFailure { failed.add(o.optString("name")) }
                }
            } else if (ref.startsWith("u:")) {
                val url = ref.substring(2)
                if (!isHttp(url) || repo.sources().any { it.isUrl && it.location == url }) continue
                val draft = PlaylistSource(0L, o.optString("name").ifBlank { url }, PlaylistSource.TYPE_URL, url, epgUrl = o.optString("epgUrl"))
                repo.importSource(draft).onSuccess { sourcesAdded++ }.onFailure { failed.add("${draft.name}: ${it.message}") }
            }
        }
        var customAdded = 0
        val custom = c.optJSONArray("custom") ?: JSONArray()
        val existing = repo.customEntries().map { it.url to it.name }.toHashSet()
        for (i in 0 until custom.length()) {
            val o = custom.getJSONObject(i)
            val e = entry(o)
            if ((e.url.trim() to e.name.trim()) in existing) continue
            if (repo.addCustomChannel(e).isSuccess) customAdded++
        }
        val idByRef = HashMap<String, Long>()
        for (s in repo.sources()) ConfigSync.sourceRef(s)?.let { if (it !in idByRef) idByRef[it] = s.id }
        fun keys(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val r = arr.optJSONArray(i) ?: return@mapNotNull null
                ConfigSync.toKey(ConfigSync.Ref(r.optString(0), r.optString(1)), idByRef)
            }
        }
        val favorites = keys(c.optJSONArray("favorites")).filterNot { repo.isFavorite(it) }
        favorites.forEach { repo.setFavorite(it, true) }
        val hidden = keys(c.optJSONArray("hidden")).filterNot { it in repo.hiddenKeys() }
        if (hidden.isNotEmpty()) repo.hideChannels(hidden)
        val names = HashMap<String, String>()
        c.optJSONArray("renamed")?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.optJSONArray(i) ?: continue
                ConfigSync.toKey(ConfigSync.Ref(r.optString(0), r.optString(1)), idByRef)
                    ?.takeIf { it !in repo.renamedChannels() }
                    ?.let { names[it] = r.optString(2) }
            }
        }
        if (names.isNotEmpty()) repo.renameChannels(names, overwrite = false)
        val order = keys(c.optJSONArray("order"))
        val orderTaken = order.isNotEmpty() && repo.orderKeys().isEmpty()
        if (orderTaken) repo.saveOrder(order)

        val parts = ArrayList<String>()
        parts.add("$sourcesAdded kaynak")
        if (customAdded > 0) parts.add("$customAdded kanal")
        if (favorites.isNotEmpty()) parts.add("${favorites.size} favori")
        if (hidden.isNotEmpty()) parts.add("${hidden.size} gizleme")
        if (names.isNotEmpty()) parts.add("${names.size} kanal adı")
        if (orderTaken) parts.add("kanal sırası")
        val msg = "Eşitlendi: " + parts.joinToString(", ") + " alındı" + if (failed.isEmpty()) "" else ". Eklenemeyen: " + failed.joinToString("; ")
        return done(msg).put("failed", JSONArray(failed))
    }

    // ---------------------------------------------------------------- helpers

    private fun kind(s: PlaylistSource) = when {
        s.isBuiltIn -> "builtin"
        s.isCustom -> "custom"
        s.isUrl -> "url"
        else -> "file"
    }

    private fun isHttp(s: String) = s.startsWith("http://", true) || s.startsWith("https://", true)

    private fun uploadDir() = File(context.filesDir, UPLOAD_DIR).apply { mkdirs() }

    private fun done(message: String): JSONObject {
        _events.tryEmit("Telefondan: $message")
        return JSONObject().put("ok", true).put("message", message)
    }

    private fun fail(message: String) = JSONObject().put("ok", false).put("error", message)

    companion object {
        const val UPLOAD_DIR = "uploads"
    }
}
