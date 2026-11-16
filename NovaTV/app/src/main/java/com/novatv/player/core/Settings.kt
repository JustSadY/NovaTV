package com.novatv.player.core

import android.content.Context
import com.novatv.player.BuildConfig
import com.novatv.player.data.Channel
import org.json.JSONObject

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("novatv_settings", Context.MODE_PRIVATE)

    var autoplayLastChannel: Boolean
        get() = prefs.getBoolean(K_AUTOPLAY, true)
        set(v) = prefs.edit().putBoolean(K_AUTOPLAY, v).apply()

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(K_AUTOSTART, true)
        set(v) = prefs.edit().putBoolean(K_AUTOSTART, v).apply()

    /** false: UP = next channel (default, like most receivers). true: UP = previous channel. */
    var invertUpDown: Boolean
        get() = prefs.getBoolean(K_INVERT, false)
        set(v) = prefs.edit().putBoolean(K_INVERT, v).apply()

    var bannerSeconds: Int
        get() = prefs.getInt(K_BANNER, 5)
        set(v) = prefs.edit().putInt(K_BANNER, v).apply()

    /** 0 = fit, 1 = fill (stretch), 2 = zoom (crop). */
    var aspectMode: Int
        get() = prefs.getInt(K_ASPECT, 0)
        set(v) = prefs.edit().putInt(K_ASPECT, v).apply()

    /** 0 = fast zapping, 1 = balanced, 2 = stable. */
    var bufferProfile: Int
        get() = prefs.getInt(K_BUFFER, 1)
        set(v) = prefs.edit().putInt(K_BUFFER, v).apply()

    var userAgent: String
        get() = prefs.getString(K_UA, "") ?: ""
        set(v) = prefs.edit().putString(K_UA, v.trim()).apply()

    /** Hours after which sources are refreshed on app start. 0 = never. */
    var autoRefreshHours: Int
        get() = prefs.getInt(K_REFRESH, 24)
        set(v) = prefs.edit().putInt(K_REFRESH, v).apply()

    var epgUrl: String
        get() = prefs.getString(K_EPG_URL, "") ?: ""
        set(v) = prefs.edit().putString(K_EPG_URL, v.trim()).apply()

    var epgUpdatedAt: Long
        get() = prefs.getLong(K_EPG_UPDATED, 0L)
        set(v) = prefs.edit().putLong(K_EPG_UPDATED, v).apply()

    var epgSignature: String
        get() = prefs.getString(K_EPG_SIG, "") ?: ""
        set(v) = prefs.edit().putString(K_EPG_SIG, v).apply()

    var epgLastError: String
        get() = prefs.getString(K_EPG_ERR, "") ?: ""
        set(v) = prefs.edit().putString(K_EPG_ERR, v).apply()

    /** The bundled Turkey list is added once on first start; deleting it is respected. */
    var defaultSourceSeeded: Boolean
        get() = prefs.getBoolean(K_SEEDED, false)
        set(v) = prefs.edit().putBoolean(K_SEEDED, v).apply()

    var bootReceivedAt: Long
        get() = prefs.getLong(K_BOOT_RX, 0L)
        set(v) = prefs.edit().putLong(K_BOOT_RX, v).apply()

    var bootLaunchedAt: Long
        get() = prefs.getLong(K_BOOT_LAUNCH, 0L)
        set(v) = prefs.edit().putLong(K_BOOT_LAUNCH, v).apply()

    /** Full description of the last played channel so playback can start before lists load. */
    var lastChannel: Channel?
        get() = prefs.getString(K_LAST, null)?.let { runCatching { Channel.fromJson(JSONObject(it)) }.getOrNull() }
        set(v) = prefs.edit().putString(K_LAST, v?.toJson()?.toString()).apply()

    var previousChannelKey: String?
        get() = prefs.getString(K_PREV, null)
        set(v) = prefs.edit().putString(K_PREV, v).apply()

    /** Keeps the NovaTV Link server running while NovaTV runs, so paired phones can manage the TV. */
    var remoteManage: Boolean
        get() = prefs.getBoolean(K_MANAGE, false)
        set(v) = prefs.edit().putBoolean(K_MANAGE, v).apply()

    /**
     * GitHub repository ("owner/name") whose releases are checked for app updates; defaults to the
     * official one (BuildConfig.UPDATE_REPO). Empty = off.
     */
    var updateRepo: String
        get() = prefs.getString(K_UPD_REPO, BuildConfig.UPDATE_REPO) ?: ""
        set(v) = prefs.edit().putString(K_UPD_REPO, v.trim()).apply()

    /** Check for updates once a day when NovaTV starts. */
    var updateAuto: Boolean
        get() = prefs.getBoolean(K_UPD_AUTO, true)
        set(v) = prefs.edit().putBoolean(K_UPD_AUTO, v).apply()

    var updateLastCheck: Long
        get() = prefs.getLong(K_UPD_LAST, 0L)
        set(v) = prefs.edit().putLong(K_UPD_LAST, v).apply()

    /** A version the user chose to skip in the automatic check. */
    var updateSkipped: String
        get() = prefs.getString(K_UPD_SKIP, "") ?: ""
        set(v) = prefs.edit().putString(K_UPD_SKIP, v).apply()

    /** Remote buttons taught as channel up / down in Settings (null = the defaults in RemoteKeys). */
    var taughtChannelUp: RemoteKeys.Taught?
        get() = readTaught(K_CH_UP)
        set(v) = writeTaught(K_CH_UP, v)

    var taughtChannelDown: RemoteKeys.Taught?
        get() = readTaught(K_CH_DOWN)
        set(v) = writeTaught(K_CH_DOWN, v)

    private fun readTaught(key: String): RemoteKeys.Taught? {
        val code = prefs.getInt("${key}_code", -1)
        return if (code < 0) null else RemoteKeys.Taught(code, prefs.getInt("${key}_scan", 0))
    }

    private fun writeTaught(key: String, v: RemoteKeys.Taught?) {
        val e = prefs.edit()
        if (v == null) e.remove("${key}_code").remove("${key}_scan")
        else e.putInt("${key}_code", v.keyCode).putInt("${key}_scan", v.scanCode)
        e.apply()
    }

    private companion object {
        const val K_AUTOPLAY = "autoplay_last"
        const val K_AUTOSTART = "autostart_boot"
        const val K_INVERT = "invert_updown"
        const val K_BANNER = "banner_seconds"
        const val K_ASPECT = "aspect_mode"
        const val K_BUFFER = "buffer_profile"
        const val K_UA = "user_agent"
        const val K_REFRESH = "auto_refresh_hours"
        const val K_EPG_URL = "epg_url"
        const val K_EPG_UPDATED = "epg_updated"
        const val K_EPG_SIG = "epg_signature"
        const val K_EPG_ERR = "epg_error"
        const val K_SEEDED = "default_source_seeded"
        const val K_BOOT_RX = "boot_received"
        const val K_BOOT_LAUNCH = "boot_launched"
        const val K_LAST = "last_channel"
        const val K_PREV = "previous_channel"
        const val K_MANAGE = "remote_manage"
        const val K_UPD_REPO = "update_repo"
        const val K_UPD_AUTO = "update_auto"
        const val K_UPD_LAST = "update_last_check"
        const val K_UPD_SKIP = "update_skipped"
        const val K_CH_UP = "key_channel_up"
        const val K_CH_DOWN = "key_channel_down"
    }
}
