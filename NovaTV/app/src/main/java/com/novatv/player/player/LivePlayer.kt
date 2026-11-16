package com.novatv.player.player

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.novatv.player.R
import com.novatv.player.core.HttpClients
import com.novatv.player.core.Settings
import com.novatv.player.data.Channel
import java.util.Locale

/**
 * Wraps a single ExoPlayer instance tuned for live IPTV:
 *  - one reusable player (switching channels replaces the media source, the old one is released),
 *  - HLS / MPEG-TS / RTSP / UDP support with automatic HLS fallback for extension-less URLs,
 *  - automatic reconnection with back-off, then slow background retries,
 *  - buffer profiles for fast zapping vs. stability, capped memory on low-RAM devices.
 */
@OptIn(UnstableApi::class)
class LivePlayer(
    private val context: Context,
    private val http: HttpClients,
    private val settings: Settings,
    private val listener: Listener,
) : Player.Listener {

    interface Listener {
        fun onLoadingChanged(loading: Boolean)
        fun onPlaybackStarted(channel: Channel)
        /** [fatal] = fast retries exhausted or the error is permanent. */
        fun onPlaybackProblem(channel: Channel, message: String, attempt: Int, maxAttempts: Int, fatal: Boolean)
        fun onTracksChanged(tracks: Tracks)
        fun onVideoUnsupported(channel: Channel)
        fun onAudioUnsupported(channel: Channel)
    }

    var exoPlayer: ExoPlayer? = null
        private set
    var channel: Channel? = null
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable { prepareCurrent() }
    private var attempts = 0
    private var forceHls = false
    private var started = false
    private var videoWarningShown = false
    private var audioWarningShown = false

    private val loadErrorPolicy = object : DefaultLoadErrorHandlingPolicy(LOADABLE_RETRIES) {
        // 4xx answers (except timeout / rate limit) will not fix themselves within seconds:
        // fail fast so the user sees a clear message instead of a long spinner.
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
            if (PlayerErrors.isClientHttpError(loadErrorInfo.exception)) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
    }
    private val extractorsFactory = DefaultExtractorsFactory().setTsExtractorFlags(TS_FLAGS)

    fun ensurePlayer(): ExoPlayer {
        exoPlayer?.let { return it }
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage(Locale.getDefault().language)
                    .setExceedRendererCapabilitiesIfNecessary(true)
            )
        }
        val player = ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(buildLoadControl())
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(this)
        player.playWhenReady = true
        exoPlayer = player
        return player
    }

    fun play(ch: Channel) {
        channel = ch
        attempts = 0
        forceHls = false
        handler.removeCallbacks(retryRunnable)
        prepareCurrent()
    }

    /** Immediate manual retry (OK on the error screen) or resume after standby. */
    fun retryNow() {
        attempts = 0
        handler.removeCallbacks(retryRunnable)
        prepareCurrent()
    }

    fun stop() {
        handler.removeCallbacks(retryRunnable)
        exoPlayer?.stop()
    }

    fun release() {
        handler.removeCallbacks(retryRunnable)
        exoPlayer?.let {
            it.removeListener(this)
            it.release()
        }
        exoPlayer = null
    }

    fun togglePause() {
        val p = exoPlayer ?: return
        if (p.playWhenReady) {
            p.playWhenReady = false
        } else {
            // Live: jump back to the live edge when resuming.
            p.seekToDefaultPosition()
            p.playWhenReady = true
        }
    }

    val isPaused: Boolean get() = exoPlayer?.playWhenReady == false

    private fun prepareCurrent() {
        val ch = channel ?: return
        val player = ensurePlayer()
        started = false
        videoWarningShown = false
        audioWarningShown = false
        listener.onLoadingChanged(true)
        player.setMediaSource(buildSource(ch), true)
        player.prepare()
        player.playWhenReady = true
    }

    private fun buildSource(ch: Channel): MediaSource {
        val httpFactory = OkHttpDataSource.Factory(http.stream)
            .setUserAgent(http.userAgent(ch.userAgent))
        ch.referrer?.let { httpFactory.setDefaultRequestProperties(mapOf("Referer" to it)) }
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val hls = forceHls || isHlsUrl(ch.url)
        val item = MediaItem.Builder()
            .setUri(ch.url)
            .setMediaId(ch.key)
            .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        return if (hls) {
            HlsMediaSource.Factory(dataSourceFactory)
                .setExtractorFactory(DefaultHlsExtractorFactory(TS_FLAGS, true))
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(loadErrorPolicy)
                .createMediaSource(item)
        } else {
            DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
                .setLoadErrorHandlingPolicy(loadErrorPolicy)
                .createMediaSource(item)
        }
    }

    private fun buildLoadControl(): LoadControl {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val targetBytes = if (am.isLowRamDevice) 12 * 1024 * 1024 else 24 * 1024 * 1024
        val (minMs, maxMs, playMs, rebufferMs) = when (settings.bufferProfile) {
            0 -> listOf(6_000, 15_000, 700, 1_500)
            2 -> listOf(20_000, 40_000, 2_500, 5_000)
            else -> listOf(10_000, 25_000, 1_200, 2_500)
        }
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(minMs, maxMs, playMs, rebufferMs)
            .setTargetBufferBytes(targetBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    }

    // ---------------------------------------------------------------- Player.Listener

    override fun onPlaybackStateChanged(playbackState: Int) {
        val ch = channel ?: return
        when (playbackState) {
            Player.STATE_BUFFERING -> listener.onLoadingChanged(true)
            Player.STATE_READY -> {
                listener.onLoadingChanged(false)
                attempts = 0
                if (!started) {
                    started = true
                    listener.onPlaybackStarted(ch)
                }
            }
            // A live stream should never end: the server closed the connection.
            Player.STATE_ENDED -> scheduleRetry(ch, context.getString(R.string.err_network))
            Player.STATE_IDLE -> Unit
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val ch = channel ?: return
        val player = exoPlayer ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        if (!forceHls && !isHlsUrl(ch.url) && PlayerErrors.isFormatError(error)) {
            // Extension-less URL that was not a raw TS stream: try it as HLS once.
            forceHls = true
            prepareCurrent()
            return
        }
        val message = PlayerErrors.describe(context, error)
        if (PlayerErrors.isPermanent(error)) {
            listener.onLoadingChanged(false)
            listener.onPlaybackProblem(ch, message, MAX_FAST_RETRIES, MAX_FAST_RETRIES, fatal = true)
            return
        }
        // Client errors (403/404...) go straight to slow background retries.
        if (PlayerErrors.isClientHttpError(error)) attempts = MAX_FAST_RETRIES
        scheduleRetry(ch, message)
    }

    override fun onTracksChanged(tracks: Tracks) {
        listener.onTracksChanged(tracks)
        val ch = channel ?: return
        if (!videoWarningShown && tracks.containsType(C.TRACK_TYPE_VIDEO) && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)) {
            videoWarningShown = true
            listener.onVideoUnsupported(ch)
        }
        // Typical on old TVs: AC3 / E-AC3 / MP2 audio without a matching decoder.
        if (!audioWarningShown && tracks.containsType(C.TRACK_TYPE_AUDIO) && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)) {
            audioWarningShown = true
            listener.onAudioUnsupported(ch)
        }
    }

    private fun scheduleRetry(ch: Channel, message: String) {
        attempts++
        val fatal = attempts > MAX_FAST_RETRIES
        val delay = if (fatal) SLOW_RETRY_MS else minOf(1_000L shl (attempts - 1), 8_000L)
        listener.onLoadingChanged(!fatal)
        listener.onPlaybackProblem(ch, message, attempts.coerceAtMost(MAX_FAST_RETRIES), MAX_FAST_RETRIES, fatal)
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delay)
    }

    companion object {
        const val MAX_FAST_RETRIES = 4
        private const val SLOW_RETRY_MS = 15_000L
        private const val LOADABLE_RETRIES = 3
        private const val TS_FLAGS =
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS

        fun isHlsUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.ROOT)
            val path = lower.substringBefore('?')
            return path.endsWith(".m3u8") || path.endsWith(".m3u") || path.contains(".m3u8/") ||
                lower.contains("type=m3u8") || lower.contains("output=hls") || lower.contains("format=m3u8") ||
                lower.contains("extension=m3u8")
        }
    }
}
