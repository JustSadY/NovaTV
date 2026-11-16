package com.novatv.player.ui.home

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.novatv.player.AppGraph
import com.novatv.player.BuildConfig
import com.novatv.player.R
import com.novatv.player.core.TimeFmt
import com.novatv.player.data.Channel
import com.novatv.player.ui.common.Osd
import com.novatv.player.ui.common.SidebarLayout
import com.novatv.player.ui.home.sections.CategoriesSection
import com.novatv.player.ui.home.sections.ChannelsSection
import com.novatv.player.ui.home.sections.LiveSection
import com.novatv.player.ui.home.sections.Section
import com.novatv.player.ui.home.sections.SettingsSection
import com.novatv.player.ui.home.sections.SourcesSection
import com.novatv.player.ui.player.ChannelPanel
import com.novatv.player.ui.player.PlayerActivity
import com.novatv.player.ui.sources.SourceEditActivity
import kotlinx.coroutines.launch

/** Home screen: navigation rail + lazily created content sections. */
class MainActivity : ComponentActivity() {

    lateinit var contentContainer: FrameLayout
        private set
    lateinit var osd: Osd
        private set
    private lateinit var sidebar: SidebarLayout
    private lateinit var clock: TextView
    private lateinit var footer: TextView

    private val navIds = intArrayOf(
        R.id.nav_live, R.id.nav_all, R.id.nav_categories, R.id.nav_favorites,
        R.id.nav_recent, R.id.nav_sources, R.id.nav_settings,
    )
    private val sections = HashMap<Int, Section>()
    private var currentNav = R.id.nav_live
    private val handler = Handler(Looper.getMainLooper())
    private var pendingSwitch = Runnable {}
    private val clockTick = object : Runnable {
        override fun run() {
            clock.text = TimeFmt.now()
            handler.postDelayed(this, 20_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AppGraph.ensureStarted()

        contentContainer = findViewById(R.id.content)
        sidebar = findViewById(R.id.sidebar)
        clock = findViewById(R.id.clock)
        footer = findViewById(R.id.sidebar_footer)
        osd = Osd(findViewById(R.id.osd))

        val brand = SpannableString("NovaTV")
        brand.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, R.color.accent_light)), 4, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        findViewById<TextView>(R.id.brand).text = brand

        for (id in navIds) {
            val item = findViewById<View>(id)
            item.setOnFocusChangeListener { v, hasFocus ->
                handler.removeCallbacks(pendingSwitch)
                if (hasFocus) {
                    // Small delay: ignores transient focus blips and avoids building every
                    // section while the user scrolls through the menu.
                    pendingSwitch = Runnable { if (v.hasFocus()) showSection(id) }
                    handler.postDelayed(pendingSwitch, NAV_SWITCH_DELAY_MS)
                }
            }
            item.setOnClickListener {
                handler.removeCallbacks(pendingSwitch)
                showSection(id)
                onNavClick(id)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBackKey()
        })

        val start = savedInstanceState?.getInt(STATE_NAV, R.id.nav_live) ?: R.id.nav_live
        showSection(start)
        findViewById<View>(start).requestFocus()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AppGraph.channels.snapshot.collect { snap ->
                        footer.text = "NovaTV ${BuildConfig.VERSION_NAME}\n" +
                            getString(R.string.channel_count, snap.channels.size)
                    }
                }
                launch { AppGraph.channels.events.collect { osd.show(it, 4_000) } }
                launch { AppGraph.link.manage.events.collect { osd.show(it, 4_000) } }
            }
        }

        if (savedInstanceState == null) maybeAutoplay(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeAutoplay(intent)
    }

    override fun onStart() {
        super.onStart()
        handler.post(clockTick)
        maybeCheckUpdate()
    }

    /** Daily update check (Settings → Güncellemeler); silent unless a new version exists. */
    private fun maybeCheckUpdate() {
        val s = AppGraph.settings
        if (!s.updateAuto || s.updateRepo.isBlank()) return
        if (System.currentTimeMillis() - s.updateLastCheck < UPDATE_INTERVAL_MS) return
        UpdateFlow.check(this, silent = true)
    }

    override fun onStop() {
        handler.removeCallbacks(clockTick)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        osd.dispose()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_NAV, currentNav)
    }

    /** Launcher/boot start: jump straight into the last channel like a TV receiver. */
    private fun maybeAutoplay(intent: Intent) {
        val fromBoot = intent.getBooleanExtra(EXTRA_FROM_BOOT, false)
        if (fromBoot) AppGraph.settings.bootLaunchedAt = System.currentTimeMillis()
        val isLaunch = intent.action == Intent.ACTION_MAIN || fromBoot
        val last = AppGraph.settings.lastChannel
        if (isLaunch && AppGraph.settings.autoplayLastChannel && last != null) {
            startActivity(PlayerActivity.intent(this, last.key))
        }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val section = sections[currentNav]
        if (section != null && contentHasFocus() && section.onKey(event)) return true
        // RIGHT from the navigation rail always enters the section at its remembered item.
        if (section != null && sidebar.hasFocus() && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.action == KeyEvent.ACTION_DOWN && section.focusContent()) return true
            if (event.action == KeyEvent.ACTION_UP && contentHasFocus()) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBackKey() {
        val section = sections[currentNav]
        when {
            contentHasFocus() && section?.onBack() == true -> Unit
            contentHasFocus() -> findViewById<View>(currentNav).requestFocus()
            currentNav != R.id.nav_live -> findViewById<View>(R.id.nav_live).requestFocus()
            else -> finish()
        }
    }

    private fun contentHasFocus(): Boolean = contentContainer.hasFocus()

    fun focusNavItem() {
        findViewById<View>(currentNav).requestFocus()
    }

    private fun onNavClick(id: Int) {
        if (id == R.id.nav_live) {
            val snap = AppGraph.channels.snapshot.value
            if (snap.loaded && snap.sources.isEmpty()) {
                openSourceEditor(null)
            } else {
                val key = AppGraph.settings.lastChannel?.key ?: snap.channels.firstOrNull()?.key
                startActivity(PlayerActivity.intent(this, key))
            }
            return
        }
        sections[id]?.focusContent()
    }

    fun showSection(id: Int) {
        if (id == currentNav && sections[id]?.view?.parent != null) return
        currentNav = id
        for (navId in navIds) findViewById<View>(navId).isSelected = navId == id
        sidebar.selectedItem = findViewById(id)
        val section = sections.getOrPut(id) { createSection(id) }
        contentContainer.removeAllViews()
        contentContainer.addView(section.view)
        section.view.alpha = 0f
        section.view.animate().alpha(1f).setDuration(140).start()
        section.onShow()
    }

    /** Switches section and moves focus into it (used by cross-section shortcuts). */
    fun navigateTo(id: Int, focusContent: Boolean = true): Section {
        showSection(id)
        val section = sections.getValue(id)
        if (focusContent) section.view.post { if (!section.focusContent()) findViewById<View>(id).requestFocus() }
        return section
    }

    private fun createSection(id: Int): Section = when (id) {
        R.id.nav_all -> ChannelsSection(this, ChannelsSection.Mode.ALL)
        R.id.nav_categories -> CategoriesSection(this)
        R.id.nav_favorites -> ChannelsSection(this, ChannelsSection.Mode.FAVORITES)
        R.id.nav_recent -> ChannelsSection(this, ChannelsSection.Mode.RECENTS)
        R.id.nav_sources -> SourcesSection(this)
        R.id.nav_settings -> SettingsSection(this)
        else -> LiveSection(this)
    }

    fun play(channel: Channel, group: String = ChannelPanel.GROUP_ALL) {
        startActivity(PlayerActivity.intent(this, channel.key, group))
    }

    fun openPlayerWithList() {
        val key = AppGraph.settings.lastChannel?.key ?: AppGraph.channels.snapshot.value.channels.firstOrNull()?.key
        startActivity(PlayerActivity.intent(this, key, openPanel = true))
    }

    fun openLink() {
        startActivity(Intent(this, com.novatv.player.ui.link.LinkActivity::class.java))
    }

    fun openSourceEditor(sourceId: Long?) {
        val i = Intent(this, SourceEditActivity::class.java)
        if (sourceId != null) i.putExtra(SourceEditActivity.EXTRA_SOURCE_ID, sourceId)
        startActivity(i)
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 20L * 3_600_000L
        const val EXTRA_FROM_BOOT = "from_boot"
        private const val STATE_NAV = "nav"
        private const val NAV_SWITCH_DELAY_MS = 90L
    }
}
