package com.novatv.player.ui.player

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.novatv.player.AppGraph
import com.novatv.player.BuildConfig
import com.novatv.player.R
import com.novatv.player.core.RemoteKeys
import com.novatv.player.core.TimeFmt
import com.novatv.player.data.Channel
import com.novatv.player.data.ChannelSnapshot
import com.novatv.player.player.LivePlayer
import com.novatv.player.player.TrackHelper
import com.novatv.player.ui.common.Osd
import com.novatv.player.ui.common.TvDialog
import com.novatv.player.ui.home.MainActivity
import kotlinx.coroutines.launch

/**
 * Fullscreen live TV. Behaves like a satellite receiver:
 *  CH+/UP next channel, CH-/DOWN previous, LEFT channel list, RIGHT quick menu,
 *  OK info banner, BACK previous menu, 0-9 direct channel number, LAST recall.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity(), LivePlayer.Listener {

    private val repo = AppGraph.channels
    private val epg = AppGraph.epg
    private val settings = AppGraph.settings

    private lateinit var playerView: PlayerView
    private lateinit var loading: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorChannel: TextView
    private lateinit var errorMessage: TextView
    private lateinit var errorRetry: TextView
    private lateinit var numberView: TextView
    private lateinit var osd: Osd
    private lateinit var banner: InfoBanner
    private lateinit var panel: ChannelPanel
    private lateinit var menu: QuickMenu
    private lateinit var live: LivePlayer

    private val handler = Handler(Looper.getMainLooper())
    private var snapshot = ChannelSnapshot.EMPTY
    private var current: Channel? = null
    private var zapGroup = ChannelPanel.GROUP_ALL
    private var isStartedState = false
    private var noChannelsShown = false
    /** Key of the channel whose stream actually reached READY (for the HD/SD badge). */
    private var readyKey: String? = null

    // Zapping debounce: the first press tunes immediately, rapid presses only move the banner.
    private var pendingTune: Channel? = null
    private var lastTuneAt = 0L
    private val tuneRunnable = Runnable { pendingTune?.let { tuneNow(it, showBanner = false) } }

    // Direct number entry.
    private val digits = StringBuilder()
    private val numberCommit = Runnable { commitNumber() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> live.stop()
                Intent.ACTION_SCREEN_ON -> if (isStartedState && current != null) live.retryNow()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        hideSystemUi()
        AppGraph.ensureStarted()

        playerView = findViewById(R.id.player_view)
        loading = findViewById(R.id.player_loading)
        errorView = findViewById(R.id.player_error)
        errorChannel = errorView.findViewById(R.id.err_channel)
        errorMessage = errorView.findViewById(R.id.err_message)
        errorRetry = errorView.findViewById(R.id.err_retry)
        numberView = findViewById(R.id.number_entry)
        osd = Osd(findViewById(R.id.osd))
        banner = InfoBanner(findViewById(R.id.info_banner), epg)
        menu = QuickMenu(findViewById(R.id.quick_menu))
        panel = ChannelPanel(findViewById(R.id.channel_panel), epg, object : ChannelPanel.Callbacks {
            override fun onChannelChosen(channel: Channel, groupId: String) {
                zapGroup = groupId
                if (channel.key == current?.key) panel.close() else tuneNow(channel)
            }

            override fun onToggleFavorite(channel: Channel) = toggleFavorite(channel)
            override fun playingKey(): String? = current?.key
            override fun favoriteKeys(): List<String> = repo.favorites.value
        })
        live = LivePlayer(this, AppGraph.http, settings, this)
        applyAspect()

        snapshot = repo.snapshot.value
        panel.onSnapshot(snapshot)
        handleIntent(intent, initial = true)

        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { repo.snapshot.collect { onSnapshot(it) } }
                launch { epg.version.collect { onEpgChanged() } }
                launch {
                    repo.favorites.collect { favs ->
                        panel.updateMarks(favs.toSet(), repo.broken.value)
                        refreshBanner()
                    }
                }
                launch { repo.broken.collect { panel.updateMarks(repo.favorites.value.toSet(), it) } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, initial = false)
    }

    private fun handleIntent(intent: Intent, initial: Boolean) {
        intent.getStringExtra(EXTRA_GROUP)?.let { zapGroup = it }
        val key = intent.getStringExtra(EXTRA_CHANNEL_KEY)
        val requested = snapshot.byKey(key)
        val target = requested
            ?: if (initial) {
                settings.lastChannel?.let { last -> snapshot.byKey(last.key) ?: last } ?: snapshot.channels.firstOrNull()
            } else {
                null
            }
        if (target != null) {
            if (initial) current = target else tuneNow(target)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_PANEL, false)) {
            handler.post { if (!snapshot.isEmpty) panel.open(zapGroup) }
        }
    }

    override fun onStart() {
        super.onStart()
        isStartedState = true
        playerView.player = live.ensurePlayer()
        val ch = current
        if (ch != null) {
            tuneNow(ch, showBanner = true)
        } else if (snapshot.loaded && snapshot.isEmpty) {
            showNoChannels()
        }
    }

    override fun onStop() {
        // Free the hardware decoder whenever we are not visible (standby, HOME, other app).
        isStartedState = false
        handler.removeCallbacks(tuneRunnable)
        pendingTune = null
        playerView.player = null
        live.release()
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        handler.removeCallbacksAndMessages(null)
        banner.dispose()
        panel.dispose()
        menu.dispose()
        osd.dispose()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // ---------------------------------------------------------------- data updates

    private fun onSnapshot(snap: ChannelSnapshot) {
        snapshot = snap
        panel.onSnapshot(snap)
        val cur = current
        if (cur != null) {
            val fresh = snap.byKey(cur.key)
            if (fresh != null) {
                val urlChanged = fresh.url != cur.url
                current = fresh
                // A refreshed playlist may carry a new tokenised URL for a failing channel.
                if (urlChanged && isStartedState && errorView.visibility == View.VISIBLE) tuneNow(fresh, showBanner = false)
            }
        } else if (snap.loaded) {
            val first = snap.byKey(settings.lastChannel?.key) ?: snap.channels.firstOrNull()
            if (first != null) {
                current = first
                hideNoChannels()
                if (isStartedState) tuneNow(first, showBanner = true)
            } else if (isStartedState) {
                showNoChannels()
            }
        }
    }

    private fun onEpgChanged() {
        panel.refreshEpg()
        refreshBanner()
    }

    private fun refreshBanner() {
        val ch = banner.channel ?: return
        banner.refresh(repo.isFavorite(ch.key), qualityLabel())
    }

    // ---------------------------------------------------------------- tuning

    private fun tuneNow(ch: Channel, showBanner: Boolean = true) {
        handler.removeCallbacks(tuneRunnable)
        pendingTune = null
        val previous = current
        if (previous != null && previous.key != ch.key) settings.previousChannelKey = previous.key
        current = ch
        readyKey = null
        lastTuneAt = SystemClock.uptimeMillis()
        hideError()
        hideNoChannels()
        panel.setPlaying(ch.key)
        if (showBanner) showBanner(ch)
        if (!isStartedState) return
        playerView.player = live.ensurePlayer()
        live.play(ch)
        settings.lastChannel = ch
        repo.addRecent(ch.key)
    }

    private fun zap(delta: Int) {
        val base = pendingTune ?: current
        val all = snapshot.channels
        val groupList = groupChannels(zapGroup)
        val list = if (base != null && groupList.any { it.key == base.key }) groupList else all
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.key == base?.key }
        val nextIdx = if (idx < 0) {
            if (delta > 0) 0 else list.size - 1
        } else {
            ((idx + delta) % list.size + list.size) % list.size
        }
        val target = list[nextIdx]
        showBanner(target)
        val now = SystemClock.uptimeMillis()
        if (pendingTune == null && now - lastTuneAt > ZAP_IMMEDIATE_GAP_MS) {
            tuneNow(target, showBanner = false)
        } else {
            pendingTune = target
            handler.removeCallbacks(tuneRunnable)
            handler.postDelayed(tuneRunnable, ZAP_DEBOUNCE_MS)
        }
    }

    private fun groupChannels(groupId: String): List<Channel> = when {
        groupId == ChannelPanel.GROUP_FAV -> repo.favorites.value.mapNotNull { snapshot.byKey(it) }
        groupId.startsWith(ChannelPanel.GROUP_PREFIX) -> snapshot.inGroup(groupId.removePrefix(ChannelPanel.GROUP_PREFIX))
        else -> snapshot.channels
    }

    private fun recall() {
        val prev = snapshot.byKey(settings.previousChannelKey) ?: return
        tuneNow(prev)
    }

    private fun showBanner(ch: Channel) {
        banner.show(ch, settings.bannerSeconds, repo.isFavorite(ch.key), if (ch.key == current?.key) qualityLabel() else null)
    }

    // ---------------------------------------------------------------- keys

    @SuppressLint("RestrictedApi") // calling super is the documented pattern; the override is only hidden in androidx.core
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (!menu.isOpen) {
            // CH+/CH− arrive as CHANNEL_*, PAGE_* or MEDIA_NEXT/PREVIOUS depending on the remote,
            // or as a button taught in Settings (RemoteKeys).
            RemoteKeys.channelStep(code, event.scanCode, settings.taughtChannelUp, settings.taughtChannelDown)?.let { step ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (panel.isOpen) panel.pageForChannelKey(step) else zap(step)
                }
                return true
            }
        }
        digitOf(code)?.let { d ->
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onDigit(d)
            return true
        }
        if (menu.isOpen) {
            return menu.handleKey(event) || super.dispatchKeyEvent(event)
        }
        if (panel.isOpen) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                // Let the focused row handle OK (click / long-press).
                return super.dispatchKeyEvent(event)
            }
            return panel.handleKey(event) || super.dispatchKeyEvent(event)
        }
        return handleFullscreenKey(event) || super.dispatchKeyEvent(event)
    }

    private fun handleFullscreenKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code !in FULLSCREEN_KEYS) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val first = event.repeatCount == 0
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> zap(if (settings.invertUpDown) -1 else +1)
            KeyEvent.KEYCODE_DPAD_DOWN -> zap(if (settings.invertUpDown) +1 else -1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_GUIDE -> if (first) openPanel()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU -> if (first) openMenu()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (first) onOk()
            KeyEvent.KEYCODE_INFO -> if (first) toggleBanner()
            KeyEvent.KEYCODE_BACK -> if (first) onBack()
            KeyEvent.KEYCODE_LAST_CHANNEL -> if (first) recall()
            KeyEvent.KEYCODE_CAPTIONS -> if (first) showTrackDialog(C.TRACK_TYPE_TEXT)
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> if (first) showTrackDialog(C.TRACK_TYPE_AUDIO)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> if (first) togglePause()
            KeyEvent.KEYCODE_BOOKMARK, KeyEvent.KEYCODE_PROG_YELLOW -> if (first) current?.let { toggleFavorite(it) }
        }
        return true
    }

    private fun onOk() {
        when {
            digits.isNotEmpty() -> commitNumber()
            errorView.visibility == View.VISIBLE -> {
                hideError()
                live.retryNow()
            }
            else -> toggleBanner()
        }
    }

    private fun onBack() {
        when {
            digits.isNotEmpty() -> cancelNumber()
            banner.isVisible -> banner.hide()
            else -> {
                if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }

    private fun toggleBanner() {
        val ch = current ?: return
        if (banner.isVisible) banner.hide() else showBanner(ch)
    }

    private fun togglePause() {
        live.togglePause()
        if (live.isPaused) osd.show(getString(R.string.player_paused))
    }

    private fun openPanel() {
        if (snapshot.isEmpty) return
        banner.hide()
        panel.open(zapGroup)
    }

    private fun openMenu() {
        val ch = current ?: return
        banner.hide()
        val player = live.exoPlayer
        val isFav = repo.isFavorite(ch.key)
        val items = ArrayList<QuickMenu.Item>()
        items.add(QuickMenu.Item(R.drawable.ic_star, getString(if (isFav) R.string.opt_fav_remove else R.string.opt_fav_add)) { toggleFavorite(ch) })
        if (player != null) {
            items.add(QuickMenu.Item(R.drawable.ic_audio, getString(R.string.menu_audio, TrackHelper.currentLabel(this, player, C.TRACK_TYPE_AUDIO))) {
                showTrackDialog(C.TRACK_TYPE_AUDIO)
            })
            items.add(QuickMenu.Item(R.drawable.ic_subtitles, getString(R.string.menu_subtitles, TrackHelper.currentLabel(this, player, C.TRACK_TYPE_TEXT))) {
                showTrackDialog(C.TRACK_TYPE_TEXT)
            })
        }
        items.add(QuickMenu.Item(R.drawable.ic_aspect, getString(R.string.menu_aspect, aspectLabel(settings.aspectMode))) { showAspectDialog() })
        items.add(QuickMenu.Item(R.drawable.ic_list, getString(R.string.menu_channel_list)) { openPanel() })
        items.add(QuickMenu.Item(R.drawable.ic_guide, getString(R.string.menu_guide)) { showGuide(ch) })
        items.add(QuickMenu.Item(R.drawable.ic_refresh, getString(R.string.menu_reload)) {
            hideError()
            live.retryNow()
        })
        items.add(QuickMenu.Item(R.drawable.ic_info, getString(R.string.menu_stream_info)) { showStreamInfo(ch) })
        items.add(QuickMenu.Item(R.drawable.ic_home, getString(R.string.menu_home)) {
            if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java))
            finish()
        })
        menu.open("${ch.number.takeIf { it > 0 }?.let { "$it  " } ?: ""}${ch.name}", items)
    }

    // ---------------------------------------------------------------- number entry

    private fun digitOf(code: Int): Int? = when (code) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
        else -> null
    }

    private fun onDigit(d: Int) {
        if (digits.length >= 4) digits.setLength(0)
        digits.append(d)
        numberView.text = digits
        numberView.visibility = View.VISIBLE
        handler.removeCallbacks(numberCommit)
        val maxDigits = snapshot.channels.size.toString().length
        handler.postDelayed(numberCommit, if (digits.length >= maxDigits) 500L else 2_000L)
    }

    private fun commitNumber() {
        handler.removeCallbacks(numberCommit)
        val n = digits.toString().toIntOrNull()
        cancelNumber()
        if (n == null || n <= 0) return
        val ch = snapshot.byNumber(n)
        if (ch == null) {
            osd.show(getString(R.string.player_channel_not_found, n))
            return
        }
        if (panel.isOpen) panel.close()
        if (menu.isOpen) menu.close()
        zapGroup = ChannelPanel.GROUP_ALL
        tuneNow(ch)
    }

    private fun cancelNumber() {
        handler.removeCallbacks(numberCommit)
        digits.setLength(0)
        numberView.visibility = View.GONE
    }

    // ---------------------------------------------------------------- dialogs

    private fun toggleFavorite(ch: Channel) {
        val nowFav = repo.toggleFavorite(ch.key)
        osd.show(getString(if (nowFav) R.string.fav_added else R.string.fav_removed, ch.name))
    }

    private fun showTrackDialog(type: Int) {
        val player = live.exoPlayer ?: return
        val options = if (type == C.TRACK_TYPE_TEXT) TrackHelper.textOptions(this, player) else TrackHelper.audioOptions(this, player)
        val title = getString(if (type == C.TRACK_TYPE_TEXT) R.string.subs_title else R.string.audio_title)
        val realOptions = if (type == C.TRACK_TYPE_TEXT) options.size - 1 else options.size
        if (realOptions <= 0) {
            osd.show(getString(if (type == C.TRACK_TYPE_TEXT) R.string.subs_none else R.string.audio_none))
            return
        }
        TvDialog.options(this, title, options.map { it.label }, options.indexOfFirst { it.selected }) { i ->
            TrackHelper.select(player, type, options[i])
        }
    }

    private fun showAspectDialog() {
        val labels = listOf(aspectLabel(0), aspectLabel(1), aspectLabel(2))
        TvDialog.options(this, getString(R.string.set_aspect), labels, settings.aspectMode) { i ->
            settings.aspectMode = i
            applyAspect()
        }
    }

    private fun showGuide(ch: Channel) {
        val programmes = epg.upcoming(ch)
        val body = if (programmes.isEmpty()) {
            getString(R.string.guide_empty)
        } else {
            val now = System.currentTimeMillis()
            programmes.joinToString("\n") { p ->
                val marker = if (p.isLive(now)) "▶ " else "    "
                marker + TimeFmt.weekdayTime(p.start) + "–" + TimeFmt.hm(p.stop) + "   " + p.title
            }
        }
        TvDialog.message(this, getString(R.string.guide_title, ch.name), body)
    }

    private fun showStreamInfo(ch: Channel) {
        val player = live.exoPlayer
        val video = player?.videoFormat
        val audio = player?.audioFormat
        val unknown = getString(R.string.unknown)
        val resolution = if (video != null && video.width > 0) {
            "${video.width}×${video.height}" + (if (video.frameRate > 0) " @ ${"%.0f".format(video.frameRate)} fps" else "")
        } else {
            unknown
        }
        val videoCodec = TrackHelper.codecName(video?.sampleMimeType) ?: unknown
        val audioCodec = audio?.let { TrackHelper.label(this, it, 1) } ?: unknown
        val bitrate = listOfNotNull(video?.bitrate?.takeIf { it > 0 }, audio?.bitrate?.takeIf { it > 0 }).sum()
        val bitrateText = if (bitrate > 0) "${bitrate / 1000} kbps" else unknown
        val host = ch.url.substringAfter("://").substringBefore('/').substringBefore('@')
        TvDialog.message(
            this,
            getString(R.string.menu_stream_info),
            getString(R.string.stream_info_body, ch.name, ch.sourceName.ifBlank { unknown }, resolution, videoCodec, audioCodec, bitrateText, host) +
                "\n\nNovaTV ${BuildConfig.VERSION_NAME}",
        )
    }

    private fun aspectLabel(mode: Int) = getString(
        when (mode) {
            1 -> R.string.aspect_fill
            2 -> R.string.aspect_zoom
            else -> R.string.aspect_fit
        }
    )

    private fun applyAspect() {
        playerView.resizeMode = when (settings.aspectMode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private fun qualityLabel(): String? {
        if (readyKey == null || readyKey != current?.key) return null
        val h = live.exoPlayer?.videoFormat?.height ?: return null
        return when {
            h <= 0 -> null
            h >= 2000 -> "4K"
            h >= 1000 -> "FHD"
            h >= 700 -> "HD"
            else -> "SD"
        }
    }

    // ---------------------------------------------------------------- error / empty states

    private fun showError(ch: Channel, message: String, attempt: Int, max: Int, fatal: Boolean) {
        errorChannel.text = if (ch.number > 0) "${ch.number}  ${ch.name}" else ch.name
        errorMessage.text = message
        errorRetry.text = if (fatal) getString(R.string.player_retry_background) else getString(R.string.player_retrying, attempt, max)
        errorView.visibility = View.VISIBLE
    }

    private fun hideError() {
        errorView.visibility = View.GONE
    }

    private fun showNoChannels() {
        noChannelsShown = true
        errorChannel.text = ""
        errorMessage.text = getString(R.string.player_no_channels)
        errorRetry.text = ""
        errorView.visibility = View.VISIBLE
        loading.visibility = View.GONE
    }

    private fun hideNoChannels() {
        if (noChannelsShown) {
            noChannelsShown = false
            hideError()
        }
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------------------------------------------------------------- LivePlayer.Listener

    override fun onLoadingChanged(loading: Boolean) {
        this.loading.visibility = if (loading) View.VISIBLE else View.GONE
    }

    override fun onPlaybackStarted(channel: Channel) {
        readyKey = channel.key
        hideError()
        repo.clearBroken(channel.key)
        if (banner.isVisible && banner.channel?.key == channel.key) refreshBanner()
    }

    override fun onPlaybackProblem(channel: Channel, message: String, attempt: Int, maxAttempts: Int, fatal: Boolean) {
        if (channel.key != current?.key) return
        if (fatal) repo.markBroken(channel.key)
        // A single hiccup is retried silently (spinner only); show the card from the 2nd attempt.
        if (fatal || attempt >= 2) showError(channel, message, attempt, maxAttempts, fatal)
    }

    override fun onTracksChanged(tracks: Tracks) {
        refreshBanner()
    }

    override fun onVideoUnsupported(channel: Channel) {
        osd.show(getString(R.string.player_video_unsupported), 5_000)
    }

    override fun onAudioUnsupported(channel: Channel) {
        osd.show(getString(R.string.player_audio_unsupported), 5_000)
    }

    companion object {
        const val EXTRA_CHANNEL_KEY = "channel_key"
        const val EXTRA_GROUP = "group"
        const val EXTRA_OPEN_PANEL = "open_panel"

        private const val ZAP_IMMEDIATE_GAP_MS = 600L
        private const val ZAP_DEBOUNCE_MS = 380L

        private val FULLSCREEN_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_LAST_CHANNEL, KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_BOOKMARK, KeyEvent.KEYCODE_PROG_YELLOW,
        )

        fun intent(context: Context, channelKey: String?, group: String? = null, openPanel: Boolean = false): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_CHANNEL_KEY, channelKey)
                .putExtra(EXTRA_GROUP, group ?: ChannelPanel.GROUP_ALL)
                .putExtra(EXTRA_OPEN_PANEL, openPanel)
    }
}
