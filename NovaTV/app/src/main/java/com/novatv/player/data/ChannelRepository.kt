package com.novatv.player.data

import android.content.Context
import com.novatv.player.AppGraph
import com.novatv.player.R
import com.novatv.player.core.AtomicFiles
import com.novatv.player.core.HttpClients
import com.novatv.player.core.Settings
import com.novatv.player.core.TextNorm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Single source of truth for sources, channels and the user's lists.
 * Heavy work (download, parse, list building) runs on Dispatchers.IO; results are published
 * as immutable [ChannelSnapshot]s through a StateFlow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelRepository(
    private val context: Context,
    private val sourceStore: SourceStore,
    private val userLists: UserListsStore,
    private val settings: Settings,
    http: HttpClients,
) {
    private val fetcher = PlaylistFetcher(context, http)
    private val mutex = Mutex()
    private val writer = Dispatchers.IO.limitedParallelism(1)

    // Guarded by [mutex].
    @Volatile
    private var sources: List<PlaylistSource> = emptyList()
    private val baseChannels = HashMap<Long, List<Channel>>()
    private var order: List<String> = emptyList()

    // Channel edits, guarded by [mutex]; read-only copies are exposed for export.
    @Volatile
    private var hidden: Set<String> = emptySet()
    @Volatile
    private var renamed: Map<String, String> = emptyMap()

    private val _snapshot = MutableStateFlow(ChannelSnapshot.EMPTY)
    val snapshot: StateFlow<ChannelSnapshot> = _snapshot

    private val _favorites = MutableStateFlow<List<String>>(emptyList())
    val favorites: StateFlow<List<String>> = _favorites

    private val _recents = MutableStateFlow<List<String>>(emptyList())
    val recents: StateFlow<List<String>> = _recents

    private val _broken = MutableStateFlow<Set<String>>(emptySet())
    val broken: StateFlow<Set<String>> = _broken

    /** Ids of sources currently being refreshed. */
    private val _refreshing = MutableStateFlow<Set<Long>>(emptySet())
    val refreshing: StateFlow<Set<Long>> = _refreshing

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events

    private class Parsed(val channels: List<Channel>, val epgUrl: String?)

    suspend fun initialLoad() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val lists = userLists.loadLists()
            _favorites.value = lists.favorites
            _recents.value = lists.recents
            _broken.value = lists.broken
            order = userLists.loadOrder()
            userLists.loadEdits().let {
                hidden = it.hidden
                renamed = it.renamed
            }
            sources = sourceStore.load()
            if (sources.isEmpty() && !settings.defaultSourceSeeded) {
                settings.defaultSourceSeeded = true
                sources = listOf(builtInDraft().copy(id = newId()))
            }
            for (s in sources) {
                val f = sourceStore.playlistFile(s.id)
                // The bundled list is re-read from the APK so app updates bring fresh stream URLs.
                if (s.isBuiltIn) runCatching { fetcher.fetch(s, f) }
                if (f.exists()) {
                    runCatching { parseFile(s, f) }.onSuccess { parsed ->
                        baseChannels[s.id] = parsed.channels
                        if (s.isBuiltIn && s.channelCount != parsed.channels.size) {
                            sources = upsert(sources, s.copy(channelCount = parsed.channels.size, lastUpdated = System.currentTimeMillis(), lastError = ""))
                        }
                    }
                }
            }
            if (sources.isNotEmpty()) sourceStore.save(sources)
            publishLocked()
        }
    }

    fun sources(): List<PlaylistSource> = _snapshot.value.sources

    fun hasBuiltInSource(): Boolean = _snapshot.value.sources.any { it.isBuiltIn }

    /** Draft for the bundled official Turkey list (used on first start and from Settings). */
    fun builtInDraft() = PlaylistSource(
        id = 0L,
        name = context.getString(R.string.source_builtin_name),
        type = PlaylistSource.TYPE_FILE,
        location = PlaylistSource.BUILT_IN_LOCATION,
    )

    // ---------------------------------------------------------------- sources

    /**
     * Downloads and parses a new or edited source. Only persists it when at least one playable
     * channel was found. Returns the channel count.
     */
    suspend fun importSource(draft: PlaylistSource): Result<Int> {
        val id = if (draft.id == 0L) newId() else draft.id
        val source = draft.copy(id = id)
        _refreshing.update { it + id }
        try {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    val staging = File(context.cacheDir, "import_$id.m3u")
                    try {
                        fetcher.fetch(source, staging)
                        val p = parseFile(source, staging)
                        if (p.channels.isEmpty()) throw IOException("Listede oynatılabilir kanal bulunamadı")
                        staging.copyTo(sourceStore.playlistFile(id), overwrite = true)
                        p
                    } finally {
                        staging.delete()
                    }
                }
            }
            parsed.onSuccess { p ->
                mutex.withLock {
                    withContext(Dispatchers.IO) {
                        baseChannels[id] = p.channels
                        val stored = source.copy(
                            lastUpdated = System.currentTimeMillis(),
                            channelCount = p.channels.size,
                            lastError = "",
                            headerEpgUrl = p.epgUrl ?: "",
                        )
                        sources = upsert(sources, stored)
                        sourceStore.save(sources)
                        publishLocked()
                    }
                }
            }
            return parsed.map { it.channels.size }.recoverCatching { throw IOException(describeError(it)) }
        } finally {
            _refreshing.update { it - id }
        }
    }

    /** Saves a source without loading it (used for "save anyway"). */
    suspend fun saveSourceOnly(draft: PlaylistSource, error: String) {
        val id = if (draft.id == 0L) newId() else draft.id
        mutex.withLock {
            withContext(Dispatchers.IO) {
                sources = upsert(sources, draft.copy(id = id, lastError = error))
                sourceStore.save(sources)
                publishLocked()
            }
        }
    }

    suspend fun refreshSource(id: Long): Result<Int> {
        val source = mutex.withLock { sources.firstOrNull { it.id == id } }
            ?: return Result.failure(IOException("Kaynak bulunamadı"))
        _refreshing.update { it + id }
        try {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val f = sourceStore.playlistFile(id)
                    fetcher.fetch(source, f)
                    parseFile(source, f)
                }
            }
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    val current = sources.firstOrNull { it.id == id }
                    if (current != null) {
                        val updated = result.fold(
                            onSuccess = { p ->
                                baseChannels[id] = p.channels
                                current.copy(
                                    lastUpdated = System.currentTimeMillis(),
                                    channelCount = p.channels.size,
                                    lastError = "",
                                    headerEpgUrl = p.epgUrl ?: "",
                                )
                            },
                            onFailure = { e -> current.copy(lastError = describeError(e)) },
                        )
                        sources = upsert(sources, updated)
                        sourceStore.save(sources)
                        publishLocked()
                    }
                }
            }
            return result.map { it.channels.size }.recoverCatching { throw IOException(describeError(it)) }
        } finally {
            _refreshing.update { it - id }
        }
    }

    /** Refreshes all enabled sources sequentially (gentle on weak devices). */
    suspend fun refreshAll(): List<Pair<PlaylistSource, Result<Int>>> {
        val list = mutex.withLock { sources.filter { it.enabled } }
        return list.map { it to refreshSource(it.id) }
    }

    /** Called on app start: refresh sources that are older than the configured interval or not cached. */
    suspend fun refreshStaleSources() {
        val hours = settings.autoRefreshHours
        val now = System.currentTimeMillis()
        val stale = mutex.withLock {
            sources.filter { s ->
                s.enabled && (
                    baseChannels[s.id] == null ||
                        (hours > 0 && s.isUrl && now - s.lastUpdated > hours * 3_600_000L)
                    )
            }
        }
        for (s in stale) {
            refreshSource(s.id).onFailure { _events.tryEmit("${s.name}: ${it.message}") }
        }
    }

    suspend fun deleteSource(id: Long) = mutex.withLock {
        withContext(Dispatchers.IO) {
            sources.firstOrNull { it.id == id }?.let { removed ->
                val uploads = File(context.filesDir, "uploads").absolutePath
                if (!removed.isUrl && removed.location.startsWith(uploads)) File(removed.location).delete()
            }
            sources = sources.filterNot { it.id == id }
            baseChannels.remove(id)
            sourceStore.save(sources)
            sourceStore.deletePlaylistFile(id)
            val prefix = "$id|"
            _favorites.update { l -> l.filterNot { it.startsWith(prefix) } }
            _recents.update { l -> l.filterNot { it.startsWith(prefix) } }
            _broken.update { s -> s.filterNot { it.startsWith(prefix) }.toSet() }
            persistLists()
            hidden = hidden.filterNot { it.startsWith(prefix) }.toSet()
            renamed = renamed.filterKeys { !it.startsWith(prefix) }
            userLists.saveEdits(hidden, renamed)
            publishLocked()
        }
    }

    suspend fun setSourceEnabled(id: Long, enabled: Boolean) = mutex.withLock {
        withContext(Dispatchers.IO) {
            sources = sources.map { if (it.id == id) it.copy(enabled = enabled) else it }
            sourceStore.save(sources)
            publishLocked()
        }
    }

    /** Moves a source up/down; source order defines channel numbering when no custom order exists. */
    suspend fun moveSource(id: Long, delta: Int) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val list = sources.toMutableList()
            val i = list.indexOfFirst { it.id == id }
            val j = i + delta
            if (i >= 0 && j in list.indices) {
                list.add(j, list.removeAt(i))
                sources = list
                sourceStore.save(sources)
                publishLocked()
            }
        }
    }

    // ---------------------------------------------------------------- user lists

    fun isFavorite(key: String): Boolean = key in _favorites.value

    /** Returns the new favorite state. */
    fun toggleFavorite(key: String): Boolean {
        var nowFavorite = false
        _favorites.update { list ->
            if (key in list) {
                list - key
            } else {
                nowFavorite = true
                list + key
            }
        }
        persistLists()
        return nowFavorite
    }

    fun setFavorite(key: String, on: Boolean) {
        if (isFavorite(key) != on) toggleFavorite(key)
    }

    fun moveFavorite(key: String, delta: Int) {
        _favorites.update { list ->
            val m = list.toMutableList()
            val i = m.indexOf(key)
            val j = i + delta
            if (i >= 0 && j in m.indices) m.add(j, m.removeAt(i))
            m
        }
        persistLists()
    }

    fun addRecent(key: String) {
        _recents.update { list -> (listOf(key) + list.filter { it != key }).take(MAX_RECENTS) }
        persistLists()
    }

    fun removeRecent(key: String) {
        _recents.update { it - key }
        persistLists()
    }

    fun clearRecents() {
        _recents.value = emptyList()
        persistLists()
    }

    fun markBroken(key: String) {
        if (key in _broken.value) return
        _broken.update { it + key }
        persistLists()
    }

    fun clearBroken(key: String) {
        if (key !in _broken.value) return
        _broken.update { it - key }
        persistLists()
    }

    fun clearAllBroken() {
        _broken.value = emptySet()
        persistLists()
    }

    private fun persistLists() {
        val f = _favorites.value
        val r = _recents.value
        val b = _broken.value
        AppGraph.appScope.launch(writer) { runCatching { userLists.saveLists(f, r, b) } }
    }

    // ---------------------------------------------------------------- channel edits

    fun hiddenKeys(): Set<String> = hidden

    fun renamedChannels(): Map<String, String> = renamed

    fun orderKeys(): List<String> = order

    /**
     * Removes a channel from the list: a channel from "Kendi kanallarım" is deleted, a playlist
     * channel is hidden (deleting it from a remote list would be undone by the next refresh).
     */
    suspend fun deleteChannel(key: String): Result<Boolean> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val src = sourceOfKey(key) ?: return@withContext Result.failure(IOException("Kanal bulunamadı"))
            if (src.isCustom) {
                val list = customEntriesLocked(src).toMutableList()
                val i = baseChannels[src.id]?.indexOfFirst { it.key == key } ?: -1
                if (i < 0) return@withContext Result.failure(IOException("Kanal bulunamadı"))
                list.removeAt(i)
                forgetKeyLocked(key)
                writeCustomLocked(src, list)
                Result.success(true)
            } else {
                hidden = hidden + key
                userLists.saveEdits(hidden, renamed)
                publishLocked()
                Result.success(false)
            }
        }
    }

    suspend fun hideChannels(keys: Collection<String>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            hidden = hidden + keys
            userLists.saveEdits(hidden, renamed)
            publishLocked()
        }
    }

    suspend fun restoreChannel(key: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            hidden = hidden - key
            userLists.saveEdits(hidden, renamed)
            publishLocked()
        }
    }

    suspend fun restoreAllHidden() = mutex.withLock {
        withContext(Dispatchers.IO) {
            hidden = emptySet()
            userLists.saveEdits(hidden, renamed)
            publishLocked()
        }
    }

    /** Shows the channel under another name; a blank name restores the playlist's name. */
    suspend fun renameChannels(names: Map<String, String>, overwrite: Boolean = true) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val m = renamed.toMutableMap()
            for ((key, raw) in names) {
                val name = raw.replace(Regex("\\s+"), " ").trim().take(120)
                if (!overwrite && key in m) continue
                if (name.isEmpty()) m.remove(key) else m[key] = name
            }
            renamed = m
            userLists.saveEdits(hidden, renamed)
            publishLocked()
        }
    }

    /** Entries of "Kendi kanallarım" with their original names (for export / sync). */
    suspend fun customEntries(): List<CustomPlaylist.Entry> = mutex.withLock {
        sources.firstOrNull { it.isCustom }?.let { customEntriesLocked(it) } ?: emptyList()
    }

    /** Adds a channel to "Kendi kanallarım" (created on first use). Returns the new channel key. */
    suspend fun addCustomChannel(entry: CustomPlaylist.Entry): Result<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val clean = CustomPlaylist.clean(entry) ?: return@withContext Result.failure(IOException(INVALID_CHANNEL))
            val src = sources.firstOrNull { it.isCustom }
                ?: PlaylistSource(newId(), context.getString(R.string.source_custom_name), PlaylistSource.TYPE_FILE, PlaylistSource.CUSTOM_LOCATION)
            writeCustomLocked(src, customEntriesLocked(src) + clean)
            Result.success(baseChannels[src.id]?.lastOrNull()?.key ?: "")
        }
    }

    /** Edits a channel of "Kendi kanallarım"; favorites/order follow it if its key changes. */
    suspend fun updateCustomChannel(key: String, entry: CustomPlaylist.Entry): Result<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val src = sourceOfKey(key)?.takeIf { it.isCustom } ?: return@withContext Result.failure(IOException("Kanal bulunamadı"))
            val clean = CustomPlaylist.clean(entry) ?: return@withContext Result.failure(IOException(INVALID_CHANNEL))
            val i = baseChannels[src.id]?.indexOfFirst { it.key == key } ?: -1
            if (i < 0) return@withContext Result.failure(IOException("Kanal bulunamadı"))
            val list = customEntriesLocked(src).toMutableList()
            list[i] = clean
            writeCustomLocked(src, list)
            val newKey = baseChannels[src.id]?.getOrNull(i)?.key ?: key
            if (newKey != key) rekeyLocked(key, newKey)
            Result.success(newKey)
        }
    }

    // ---------------------------------------------------------------- ordering

    suspend fun saveOrder(keys: List<String>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            order = keys
            userLists.saveOrder(keys)
            publishLocked()
        }
    }

    /** Moves [key] so that it gets channel number [number] (1-based). */
    suspend fun moveToNumber(key: String, number: Int) {
        val keys = _snapshot.value.channels.map { it.key }.toMutableList()
        if (!keys.remove(key)) return
        keys.add((number - 1).coerceIn(0, keys.size), key)
        saveOrder(keys)
    }

    suspend fun resetOrder() = saveOrder(emptyList())

    // ---------------------------------------------------------------- internals

    private fun sourceOfKey(key: String): PlaylistSource? {
        val id = key.substringBefore('|').toLongOrNull() ?: return null
        return sources.firstOrNull { it.id == id }
    }

    /** Must hold [mutex]. The entries of "Kendi kanallarım" as stored (original names). */
    private fun customEntriesLocked(src: PlaylistSource): List<CustomPlaylist.Entry> =
        baseChannels[src.id]?.map { CustomPlaylist.Entry(it.name, it.url, it.group, it.logo ?: "") } ?: emptyList()

    /** Must hold [mutex]. Rewrites the custom M3U, re-parses it and publishes. */
    private fun writeCustomLocked(src: PlaylistSource, entries: List<CustomPlaylist.Entry>) {
        val file = sourceStore.playlistFile(src.id)
        AtomicFiles.writeText(file, CustomPlaylist.build(entries))
        val parsed = parseFile(src, file)
        baseChannels[src.id] = parsed.channels
        sources = upsert(sources, src.copy(lastUpdated = System.currentTimeMillis(), channelCount = parsed.channels.size, lastError = ""))
        sourceStore.save(sources)
        publishLocked()
    }

    /** Must hold [mutex]. Drops a deleted channel from every list. */
    private fun forgetKeyLocked(key: String) {
        _favorites.update { it - key }
        _recents.update { it - key }
        _broken.update { it - key }
        persistLists()
        if (key in hidden || key in renamed) {
            hidden = hidden - key
            renamed = renamed - key
            userLists.saveEdits(hidden, renamed)
        }
        if (key in order) {
            order = order - key
            userLists.saveOrder(order)
        }
    }

    /** Must hold [mutex]. A channel got a new key: move it in every list. */
    private fun rekeyLocked(old: String, new: String) {
        fun List<String>.swap() = map { if (it == old) new else it }
        _favorites.update { it.swap() }
        _recents.update { it.swap() }
        _broken.update { s -> if (old in s) s - old + new else s }
        persistLists()
        renamed[old]?.let { renamed = renamed - old + (new to it) }
        if (old in hidden) hidden = hidden - old + new
        userLists.saveEdits(hidden, renamed)
        if (old in order) {
            order = order.swap()
            userLists.saveOrder(order)
        }
        publishLocked()
    }

    private fun newId(): Long {
        val max = sources.maxOfOrNull { it.id } ?: 0L
        return maxOf(System.currentTimeMillis(), max + 1)
    }

    private fun upsert(list: List<PlaylistSource>, s: PlaylistSource): List<PlaylistSource> =
        if (list.any { it.id == s.id }) list.map { if (it.id == s.id) s else it } else list + s

    private fun parseFile(source: PlaylistSource, file: File): Parsed {
        val result = PlaylistFetcher.openMaybeGzip(file).use { M3uParser.parse(it) }
        val entries = if (result.isHlsStream && source.isUrl) {
            // The "playlist" is itself a single HLS stream: expose it as one channel.
            listOf(M3uEntry(source.name, source.location, null, "", null, null, null, null))
        } else {
            result.entries
        }
        val sourceUa = source.userAgent.ifBlank { null }
        val counts = HashMap<String, Int>(entries.size * 2)
        val channels = ArrayList<Channel>(entries.size)
        for (e in entries) {
            val base = "${source.id}|${TextNorm.identity(e.name)}|${TextNorm.identity(e.group)}"
            val n = counts[base] ?: 0
            counts[base] = n + 1
            channels.add(
                Channel(
                    key = if (n == 0) base else "$base#$n",
                    sourceId = source.id,
                    name = e.name,
                    url = e.url,
                    logo = e.logo,
                    group = e.group,
                    tvgId = e.tvgId,
                    tvgName = e.tvgName,
                    userAgent = e.userAgent ?: sourceUa,
                    referrer = e.referrer,
                    sourceName = source.name,
                )
            )
        }
        return Parsed(channels, result.epgUrl)
    }

    /** Must hold [mutex]. Builds the combined, ordered and numbered list. */
    private fun publishLocked() {
        val enabled = sources.filter { it.enabled }
        val names = HashMap<Long, String>()
        enabled.forEach { names[it.id] = it.name }

        val combined = ArrayList<Channel>()
        val hiddenChannels = ArrayList<Channel>()
        for (s in enabled) {
            val list = baseChannels[s.id] ?: continue
            for (c in list) {
                // A renamed channel keeps its playlist name as tvg-name so the guide still matches.
                val shown = renamed[c.key]?.let { n -> c.copy(name = n, searchKey = TextNorm.search(n), tvgName = c.tvgName ?: c.name) } ?: c
                if (c.key in hidden) hiddenChannels.add(shown.copy(sourceName = s.name)) else combined.add(shown)
            }
        }

        val sorted: List<Channel> = if (order.isEmpty()) {
            combined
        } else {
            val idx = HashMap<String, Int>(order.size * 2)
            order.forEachIndexed { i, k -> idx[k] = i }
            combined.sortedWith(compareBy { idx[it.key] ?: Int.MAX_VALUE })
        }

        val multi = HashSet<String>()
        if (enabled.size > 1) {
            val firstSource = HashMap<String, Long>(sorted.size * 2)
            for (c in sorted) {
                val prev = firstSource.put(c.searchKey, c.sourceId)
                if (prev != null && prev != c.sourceId) multi.add(c.searchKey)
            }
        }

        val numbered = ArrayList<Channel>(sorted.size)
        sorted.forEachIndexed { i, c ->
            numbered.add(
                c.copy(
                    number = i + 1,
                    sourceName = names[c.sourceId] ?: c.sourceName,
                    inMultipleSources = c.searchKey in multi,
                )
            )
        }
        _snapshot.value = ChannelSnapshot(numbered, sources, loaded = true, hidden = hiddenChannels)
    }

    companion object {
        const val MAX_RECENTS = 30
        private const val INVALID_CHANNEL = "Kanal adı ve geçerli bir yayın adresi (http, https, rtsp veya udp) gerekli."

        fun describeError(e: Throwable): String = when (e) {
            is UnknownHostException -> "Sunucu bulunamadı (DNS). Adresi ve internet bağlantısını kontrol edin."
            is SocketTimeoutException -> "Sunucu zamanında yanıt vermedi."
            is ConnectException -> "Sunucuya bağlanılamadı."
            is SSLException -> "Güvenli bağlantı (SSL) kurulamadı."
            is SecurityException -> "Dosya erişim izni yok."
            else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        }
    }
}
