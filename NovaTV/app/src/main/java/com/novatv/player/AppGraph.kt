package com.novatv.player

import android.content.Context
import com.novatv.player.core.HttpClients
import com.novatv.player.core.Settings
import com.novatv.player.data.ChannelRepository
import com.novatv.player.data.SourceStore
import com.novatv.player.data.UserListsStore
import com.novatv.player.epg.EpgRepository
import com.novatv.player.link.LinkHost
import com.novatv.player.update.Updater
import com.novatv.player.ui.common.LogoLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tiny manual dependency container. Everything is created lazily so a process started
 * only by the boot receiver does not pay for unused components.
 */
object AppGraph {
    lateinit var context: Context
        private set

    /** Application-wide scope for work that must survive activity changes (refresh, EPG). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings by lazy { Settings(context) }
    val http by lazy { HttpClients(context, settings) }
    val sourceStore by lazy { SourceStore(context) }
    val userLists by lazy { UserListsStore(context) }
    val channels by lazy { ChannelRepository(context, sourceStore, userLists, settings, http) }
    val epg by lazy { EpgRepository(context, http, settings, channels) }
    val link by lazy { LinkHost(context, appScope, settings, channels) }
    val updater by lazy { Updater(context, http, settings) }

    private val started = AtomicBoolean(false)

    fun init(context: Context) {
        this.context = context.applicationContext
        LogoLoader.init(this.context) { http.images }
    }

    /** Loads cached channels/EPG and schedules stale refreshes. Safe to call repeatedly. */
    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        appScope.launch {
            channels.initialLoad()
            // "Uzaktan yönetim": phones can manage the TV whenever NovaTV runs.
            if (settings.remoteManage) link.update()
            epg.loadCache()
            channels.refreshStaleSources()
            epg.refreshIfNeeded()
        }
    }
}
