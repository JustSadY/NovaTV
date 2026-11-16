package com.novatv.player.epg

import android.content.Context
import com.novatv.player.AppGraph
import com.novatv.player.core.HttpClients
import com.novatv.player.core.Settings
import com.novatv.player.core.TextNorm
import com.novatv.player.data.Channel
import com.novatv.player.data.ChannelRepository
import com.novatv.player.data.PlaylistFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * XMLTV guide: downloads from the global EPG URL plus every source's EPG URL (manual or
 * url-tvg header), keeps only matching channels inside a ~1.5 day window and caches to disk.
 * The app works normally when no EPG is configured.
 */
@OptIn(FlowPreview::class)
class EpgRepository(
    context: Context,
    private val http: HttpClients,
    private val settings: Settings,
    private val channels: ChannelRepository,
) {
    sealed class Status {
        data object Idle : Status()
        data object Loading : Status()
        data class Error(val message: String) : Status()
    }

    private val cache = EpgCache(File(context.filesDir, "epg/guide.bin"))
    private val refreshMutex = Mutex()
    private val resolved = ConcurrentHashMap<String, String>()

    @Volatile
    private var data: EpgData = EpgData.EMPTY

    /** Increments whenever new guide data is available, so UIs can rebind. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    val channelCount: Int get() = data.channelCount
    val updatedAt: Long get() = data.updatedAt

    suspend fun loadCache() {
        val loaded = withContext(Dispatchers.IO) { cache.read(System.currentTimeMillis() - KEEP_PAST_MS) }
        if (loaded != null) publish(loaded)
        // Re-check whenever the channel list changes (new source, refresh, enable/disable).
        AppGraph.appScope.launch {
            channels.snapshot
                .map { signature(it.channels) }
                .distinctUntilChanged()
                .drop(1)
                .debounce(4_000)
                .collect { refreshIfNeeded() }
        }
    }

    fun urls(): List<String> {
        val out = LinkedHashSet<String>()
        splitUrls(settings.epgUrl).forEach { out.add(it) }
        for (s in channels.snapshot.value.sources) {
            if (!s.enabled) continue
            splitUrls(s.epgUrl.ifBlank { s.headerEpgUrl }).forEach { out.add(it) }
        }
        return out.toList()
    }

    /** Refreshes when the URL/channel signature changed or the cache is older than [MAX_AGE_MS]. */
    suspend fun refreshIfNeeded() {
        refresh(force = false)
    }

    suspend fun refresh(force: Boolean = true): Result<Int> = refreshMutex.withLock {
        val urls = urls()
        val list = channels.snapshot.value.channels
        if (urls.isEmpty()) {
            _status.value = Status.Idle
            return Result.success(0)
        }
        if (!force) {
            if (list.isEmpty()) return Result.success(data.channelCount)
            val age = System.currentTimeMillis() - settings.epgUpdatedAt
            val neverLoaded = data.updatedAt == 0L && settings.epgLastError.isEmpty()
            if (signature(list) == settings.epgSignature && age in 0..MAX_AGE_MS && !neverLoaded) {
                return Result.success(data.channelCount)
            }
        }
        _status.value = Status.Loading
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val wantedIds = HashSet<String>()
                val wantedNames = HashSet<String>()
                for (c in list) {
                    c.tvgId?.let { wantedIds.add(it.trim().lowercase(Locale.ROOT)); wantedNames.add(TextNorm.epgKey(it)) }
                    c.tvgName?.let { wantedNames.add(TextNorm.epgKey(it)) }
                    wantedNames.add(TextNorm.epgKey(c.name))
                }
                val now = System.currentTimeMillis()
                val parser = XmltvParser(wantedIds, wantedNames, now - KEEP_PAST_MS, now + KEEP_FUTURE_MS)
                val errors = ArrayList<String>()
                var okCount = 0
                for (url in urls) {
                    try {
                        download(url, parser)
                        okCount++
                    } catch (e: Exception) {
                        errors.add("${shortUrl(url)}: ${ChannelRepository.describeError(e)}")
                    }
                }
                if (okCount == 0) throw IOException(errors.joinToString("\n"))
                val programmes = HashMap<String, Array<Programme>>(parser.programmes.size * 2)
                for ((id, progs) in parser.programmes) {
                    progs.sortBy { it.start }
                    // Drop exact duplicates that appear when several guides cover the same channel.
                    val dedup = ArrayList<Programme>(progs.size)
                    for (p in progs) if (dedup.isEmpty() || dedup.last().start != p.start) dedup.add(p)
                    programmes[id] = dedup.toTypedArray()
                }
                val epg = EpgData(programmes, HashMap(parser.nameIndex), System.currentTimeMillis())
                cache.write(epg)
                epg to errors
            }
        }
        result.fold(
            onSuccess = { (epg, errors) ->
                publish(epg)
                settings.epgUpdatedAt = epg.updatedAt
                settings.epgSignature = signature(list)
                settings.epgLastError = errors.joinToString("\n")
                _status.value = if (errors.isEmpty()) Status.Idle else Status.Error(errors.first())
                Result.success(epg.channelCount)
            },
            onFailure = { e ->
                val msg = ChannelRepository.describeError(e)
                settings.epgLastError = msg
                // Avoid hammering a broken URL on every channel change: remember the attempt.
                settings.epgUpdatedAt = System.currentTimeMillis()
                settings.epgSignature = signature(list)
                _status.value = Status.Error(msg)
                Result.failure(IOException(msg))
            },
        )
    }

    private fun download(url: String, parser: XmltvParser) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", http.userAgent())
            .build()
        http.download.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Boş yanıt")
            PlaylistFetcher.maybeGunzip(body.byteStream()).use { parser.parse(it) }
        }
    }

    fun clear() {
        cache.delete()
        settings.epgUpdatedAt = 0L
        settings.epgSignature = ""
        publish(EpgData.EMPTY)
    }

    // ---------------------------------------------------------------- lookups (any thread)

    fun programmes(channel: Channel): Array<Programme>? {
        val d = data
        if (d.programmes.isEmpty()) return null
        val id = resolveId(channel, d) ?: return null
        return d.programmes[id]
    }

    fun nowNext(channel: Channel, now: Long = System.currentTimeMillis()): NowNext {
        val list = programmes(channel) ?: return EMPTY_NOW_NEXT
        // Binary search for the last programme starting at or before now.
        var lo = 0
        var hi = list.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].start <= now) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        val current = if (idx >= 0 && list[idx].stop > now) list[idx] else null
        val next = list.getOrNull(idx + 1)
        return NowNext(current, next)
    }

    /** Programmes from now on (current one included), for the guide dialog. */
    fun upcoming(channel: Channel, now: Long = System.currentTimeMillis(), max: Int = 30): List<Programme> {
        val list = programmes(channel) ?: return emptyList()
        return list.asSequence().filter { it.stop > now }.take(max).toList()
    }

    private fun resolveId(channel: Channel, d: EpgData): String? {
        resolved[channel.key]?.let { return it.ifEmpty { null } }
        var id: String? = null
        channel.tvgId?.trim()?.lowercase(Locale.ROOT)?.let { if (d.programmes.containsKey(it)) id = it }
        if (id == null) {
            val candidates = listOfNotNull(channel.tvgId, channel.tvgName, channel.name)
            for (c in candidates) {
                val hit = d.nameIndex[TextNorm.epgKey(c)]
                if (hit != null && d.programmes.containsKey(hit)) {
                    id = hit
                    break
                }
            }
        }
        resolved[channel.key] = id ?: ""
        return id
    }

    private fun publish(newData: EpgData) {
        data = newData
        resolved.clear()
        _version.value = _version.value + 1
    }

    private fun signature(list: List<Channel>): String {
        var h = 17
        for (c in list) h = 31 * h + (c.tvgId ?: c.name).hashCode()
        return urls().joinToString(",") + "#" + list.size + "#" + h
    }

    private fun splitUrls(s: String): List<String> =
        s.split(',', '\n', ' ').map { it.trim() }.filter { it.startsWith("http://") || it.startsWith("https://") }

    private fun shortUrl(url: String) = url.substringAfter("://").substringBefore('/')

    companion object {
        private const val KEEP_PAST_MS = 2 * 3_600_000L
        private const val KEEP_FUTURE_MS = 36 * 3_600_000L
        private const val MAX_AGE_MS = 12 * 3_600_000L
        private val EMPTY_NOW_NEXT = NowNext(null, null)
    }
}
