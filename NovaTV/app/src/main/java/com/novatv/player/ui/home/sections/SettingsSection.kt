package com.novatv.player.ui.home.sections

import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import com.novatv.player.AppGraph
import com.novatv.player.BuildConfig
import com.novatv.player.R
import com.novatv.player.core.HttpClients
import com.novatv.player.core.RemoteKeys
import com.novatv.player.core.TimeFmt
import com.novatv.player.epg.EpgRepository
import com.novatv.player.ui.common.FocusUtils
import com.novatv.player.ui.common.LogoLoader
import com.novatv.player.ui.common.TvDialog
import com.novatv.player.ui.home.MainActivity
import com.novatv.player.ui.home.UpdateFlow
import com.novatv.player.update.Versions
import kotlinx.coroutines.launch

/** "Ayarlar": every option is a single row; OK toggles or opens a choice dialog. */
@OptIn(UnstableApi::class)
class SettingsSection(host: MainActivity) : Section(host, R.layout.section_settings) {
    private val settings = AppGraph.settings
    private val repo = AppGraph.channels
    private val epg = AppGraph.epg

    private val list: RecyclerView = view.findViewById(R.id.set_list)
    private val adapter = Adapter()

    private sealed class Item {
        class Header(val title: String) : Item()
        class Row(val title: String, val value: String = "", val summary: String = "", val onClick: () -> Unit) : Item()
    }

    init {
        FocusUtils.setupList(list)
        list.setHasFixedSize(false)
        list.adapter = adapter
        rightEdgeViews.add(list)
        whileStarted {
            launch { epg.status.collect { rebuild() } }
            launch { epg.version.collect { rebuild() } }
            launch { AppGraph.link.address.collect { rebuild() } }
        }
    }

    override fun onShow() = rebuild()

    override fun focusContent(): Boolean {
        val pos = adapter.firstRow()
        if (pos < 0) return false
        FocusUtils.focusPosition(list, pos)
        return true
    }

    private fun onOff(v: Boolean) = string(if (v) R.string.on else R.string.off)

    private fun rebuild() {
        val items = ArrayList<Item>()

        items.add(Item.Header(string(R.string.set_section_startup)))
        items.add(Item.Row(string(R.string.set_autoplay), onOff(settings.autoplayLastChannel)) {
            settings.autoplayLastChannel = !settings.autoplayLastChannel
            rebuild()
        })
        items.add(Item.Row(string(R.string.set_autostart), onOff(settings.autoStartOnBoot), bootStatus()) {
            settings.autoStartOnBoot = !settings.autoStartOnBoot
            rebuild()
        })
        items.add(Item.Row(string(R.string.set_autostart_help)) {
            TvDialog.message(host, string(R.string.autostart_help_title), string(R.string.autostart_help_body, bootStatus()))
        })

        items.add(Item.Header(string(R.string.set_section_playback)))
        items.add(Item.Row(string(R.string.set_updown), string(if (settings.invertUpDown) R.string.set_updown_inverted else R.string.set_updown_normal)) {
            choose(R.string.set_updown, listOf(string(R.string.set_updown_normal), string(R.string.set_updown_inverted)), if (settings.invertUpDown) 1 else 0) {
                settings.invertUpDown = it == 1
            }
        })
        items.add(Item.Row(string(R.string.set_ch_up_key), taughtLabel(settings.taughtChannelUp), string(R.string.set_ch_key_hint)) {
            teachChannelKey(up = true)
        })
        items.add(Item.Row(string(R.string.set_ch_down_key), taughtLabel(settings.taughtChannelDown)) {
            teachChannelKey(up = false)
        })
        val bannerValues = listOf(3, 5, 8, 10, 15)
        items.add(Item.Row(string(R.string.set_banner), string(R.string.set_banner_value, settings.bannerSeconds)) {
            choose(R.string.set_banner, bannerValues.map { string(R.string.set_banner_value, it) }, bannerValues.indexOf(settings.bannerSeconds)) {
                settings.bannerSeconds = bannerValues[it]
            }
        })
        val aspects = listOf(string(R.string.aspect_fit), string(R.string.aspect_fill), string(R.string.aspect_zoom))
        items.add(Item.Row(string(R.string.set_aspect), aspects[settings.aspectMode.coerceIn(0, 2)]) {
            choose(R.string.set_aspect, aspects, settings.aspectMode) { settings.aspectMode = it }
        })
        val buffers = listOf(string(R.string.buffer_fast), string(R.string.buffer_balanced), string(R.string.buffer_stable))
        items.add(Item.Row(string(R.string.set_buffer), buffers[settings.bufferProfile.coerceIn(0, 2)]) {
            choose(R.string.set_buffer, buffers, settings.bufferProfile) { settings.bufferProfile = it }
        })
        items.add(Item.Row(string(R.string.set_ua), uaLabel()) { chooseUserAgent() })

        items.add(Item.Header(string(R.string.set_section_data)))
        val refreshValues = listOf(0, 6, 12, 24, 48)
        val refreshLabels = refreshValues.map { if (it == 0) string(R.string.off) else string(R.string.auto_refresh_value, it) }
        items.add(Item.Row(string(R.string.set_auto_refresh), refreshLabels[refreshValues.indexOf(settings.autoRefreshHours).coerceAtLeast(0)]) {
            choose(R.string.set_auto_refresh, refreshLabels, refreshValues.indexOf(settings.autoRefreshHours)) {
                settings.autoRefreshHours = refreshValues[it]
            }
        })
        val hasBuiltIn = repo.hasBuiltInSource()
        items.add(Item.Row(string(R.string.set_add_builtin), if (hasBuiltIn) string(R.string.set_add_builtin_present) else "", string(R.string.source_builtin_desc)) {
            if (repo.hasBuiltInSource()) {
                host.osd.show(string(R.string.set_add_builtin_present))
            } else {
                host.lifecycleScope.launch {
                    repo.importSource(repo.builtInDraft()).fold(
                        onSuccess = { host.osd.show(string(R.string.set_add_builtin_done, it)) },
                        onFailure = { host.osd.show(it.message ?: "", 5_000) },
                    )
                    rebuild()
                }
            }
        })
        items.add(Item.Row(string(R.string.set_epg_url), settings.epgUrl.ifBlank { "—" }, string(R.string.set_epg_url_hint)) {
            TvDialog.input(host, string(R.string.set_epg_url), settings.epgUrl, hint = "http://…/epg.xml.gz") { value ->
                settings.epgUrl = value
                rebuild()
                host.lifecycleScope.launch { epg.refresh() }
            }
        })
        items.add(Item.Row(string(R.string.set_epg_refresh), "", epgStatus()) {
            host.osd.show(string(R.string.set_epg_loading))
            host.lifecycleScope.launch {
                epg.refresh().fold(
                    onSuccess = { host.osd.show(string(R.string.set_epg_status, it, string(R.string.done))) },
                    onFailure = { host.osd.show(string(R.string.set_epg_error, it.message ?: ""), 5_000) },
                )
            }
        })

        items.add(Item.Header(string(R.string.set_section_remote)))
        items.add(Item.Row(string(R.string.set_remote_open), "", string(R.string.set_remote_open_summary)) { host.openLink() })
        items.add(Item.Row(string(R.string.set_remote_always), onOff(settings.remoteManage), remoteSummary()) {
            settings.remoteManage = !settings.remoteManage
            val running = AppGraph.link.update()
            if (settings.remoteManage && !running) host.osd.show(string(R.string.link_no_port), 5_000)
            rebuild()
        })
        val paired = AppGraph.link.tokens.all().size
        items.add(Item.Row(string(R.string.set_remote_devices), paired.toString()) {
            if (paired == 0) {
                host.osd.show(string(R.string.remote_devices_none))
            } else {
                TvDialog.confirm(host, string(R.string.set_remote_devices), string(R.string.remote_devices_reset_confirm), string(R.string.delete), string(R.string.cancel), {
                    AppGraph.link.tokens.clear()
                    host.osd.show(string(R.string.remote_devices_cleared))
                    rebuild()
                })
            }
        })

        items.add(Item.Header(string(R.string.set_section_updates)))
        items.add(Item.Row(string(R.string.set_update_repo), settings.updateRepo.ifBlank { "—" }, string(R.string.set_update_repo_hint)) {
            TvDialog.input(host, string(R.string.set_update_repo), settings.updateRepo, hint = "kullanici/novatv") { value ->
                if (value.isBlank()) {
                    settings.updateRepo = ""
                } else {
                    val repoName = Versions.parseRepo(value)
                    if (repoName == null) host.osd.show(string(R.string.set_update_repo_invalid), 5_000) else settings.updateRepo = repoName
                }
                rebuild()
            }
        })
        items.add(Item.Row(string(R.string.set_update_auto), onOff(settings.updateAuto)) {
            settings.updateAuto = !settings.updateAuto
            rebuild()
        })
        val lastCheck = if (settings.updateLastCheck > 0) TimeFmt.dateTime(settings.updateLastCheck) else string(R.string.never)
        items.add(Item.Row(string(R.string.set_update_now), "", string(R.string.set_update_now_summary, BuildConfig.VERSION_NAME, lastCheck)) {
            UpdateFlow.check(host, silent = false) { rebuild() }
        })

        items.add(Item.Header(string(R.string.set_section_maintenance)))
        val hiddenCount = repo.snapshot.value.hidden.size
        items.add(Item.Row(string(R.string.set_hidden), hiddenCount.toString()) {
            if (hiddenCount == 0) {
                host.osd.show(string(R.string.set_hidden_none))
            } else {
                TvDialog.confirm(host, string(R.string.set_hidden), string(R.string.set_hidden_confirm, hiddenCount), string(R.string.yes), string(R.string.no), {
                    host.lifecycleScope.launch {
                        repo.restoreAllHidden()
                        host.osd.show(string(R.string.hidden_restored))
                        rebuild()
                    }
                })
            }
        })
        items.add(Item.Row(string(R.string.set_clear_broken), repo.broken.value.size.toString()) {
            repo.clearAllBroken()
            host.osd.show(string(R.string.broken_cleared))
            rebuild()
        })
        items.add(Item.Row(string(R.string.set_reset_order)) {
            TvDialog.confirm(host, string(R.string.set_reset_order), string(R.string.set_reset_order_confirm), string(R.string.yes), string(R.string.no), {
                host.lifecycleScope.launch {
                    repo.resetOrder()
                    host.osd.show(string(R.string.order_reset))
                }
            })
        })
        items.add(Item.Row(string(R.string.set_clear_recents)) {
            repo.clearRecents()
            host.osd.show(string(R.string.recents_cleared))
        })
        items.add(Item.Row(string(R.string.set_clear_cache)) {
            LogoLoader.clearMemory()
            AppGraph.http.clearImageCache()
            host.osd.show(string(R.string.cache_cleared))
        })
        items.add(Item.Row(string(R.string.set_about), "", string(R.string.set_about_value, BuildConfig.VERSION_NAME, MediaLibraryInfo.VERSION)) {})

        adapter.submit(items)
    }

    private fun choose(titleRes: Int, labels: List<String>, selected: Int, apply: (Int) -> Unit) {
        TvDialog.options(host, string(titleRes), labels, selected) {
            apply(it)
            rebuild()
        }
    }

    private fun taughtLabel(t: RemoteKeys.Taught?): String = if (t == null) string(R.string.set_ch_key_default) else keyName(t)

    private fun keyName(t: RemoteKeys.Taught): String =
        if (t.keyCode != KeyEvent.KEYCODE_UNKNOWN) "${KeyEvent.keyCodeToString(t.keyCode).removePrefix("KEYCODE_")} (${t.keyCode})"
        else string(R.string.key_scan_code, t.scanCode)

    /** Lets the user press the remote's CH+ / CH− button so it works even with an unusual key code. */
    private fun teachChannelKey(up: Boolean) {
        val name = string(if (up) R.string.key_ch_up else R.string.key_ch_down)
        TvDialog.captureKey(
            host,
            string(R.string.teach_title, name),
            string(R.string.teach_body, name),
            string(R.string.teach_reset),
            accept = RemoteKeys::canTeach,
            onReset = {
                if (up) settings.taughtChannelUp = null else settings.taughtChannelDown = null
                host.osd.show(string(R.string.teach_reset_done, name))
                rebuild()
            },
        ) { keyCode, scanCode ->
            val taught = RemoteKeys.Taught(keyCode, scanCode)
            if (up) {
                settings.taughtChannelUp = taught
                if (settings.taughtChannelDown == taught) settings.taughtChannelDown = null
            } else {
                settings.taughtChannelDown = taught
                if (settings.taughtChannelUp == taught) settings.taughtChannelUp = null
            }
            host.osd.show(string(R.string.teach_saved, name, keyName(taught)), 4_000)
            rebuild()
        }
    }

    private fun remoteSummary(): String = when {
        !settings.remoteManage -> string(R.string.set_remote_off_summary)
        else -> AppGraph.link.address.value?.let { string(R.string.set_remote_on_summary, it) } ?: string(R.string.set_remote_on_no_net)
    }

    private fun uaLabel(): String = when (settings.userAgent) {
        "" -> string(R.string.ua_default)
        HttpClients.VLC_USER_AGENT -> string(R.string.ua_vlc)
        HttpClients.BROWSER_USER_AGENT -> string(R.string.ua_browser)
        else -> settings.userAgent
    }

    private fun chooseUserAgent() {
        val labels = listOf(string(R.string.ua_default), string(R.string.ua_vlc), string(R.string.ua_browser), string(R.string.ua_custom))
        val selected = when (settings.userAgent) {
            "" -> 0
            HttpClients.VLC_USER_AGENT -> 1
            HttpClients.BROWSER_USER_AGENT -> 2
            else -> 3
        }
        TvDialog.options(host, string(R.string.set_ua), labels, selected) { i ->
            when (i) {
                0 -> settings.userAgent = ""
                1 -> settings.userAgent = HttpClients.VLC_USER_AGENT
                2 -> settings.userAgent = HttpClients.BROWSER_USER_AGENT
                else -> TvDialog.input(host, string(R.string.ua_custom_title), settings.userAgent) { value ->
                    settings.userAgent = value
                    rebuild()
                }
            }
            rebuild()
        }
    }

    private fun epgStatus(): String = when (val status = epg.status.value) {
        is EpgRepository.Status.Loading -> string(R.string.set_epg_loading)
        is EpgRepository.Status.Error -> string(R.string.set_epg_error, status.message)
        else -> if (epg.urls().isEmpty()) {
            string(R.string.set_epg_status_none)
        } else {
            val updated = if (epg.updatedAt > 0) TimeFmt.dateTime(epg.updatedAt) else string(R.string.never)
            string(R.string.set_epg_status, epg.channelCount, updated)
        }
    }

    private fun bootStatus(): String {
        val bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val received = settings.bootReceivedAt
        val launched = settings.bootLaunchedAt
        return when {
            received == 0L -> string(R.string.boot_status_never)
            received < bootTime - 120_000L -> string(R.string.boot_status_missed)
            launched >= received -> string(R.string.boot_status_ok, TimeFmt.dateTime(launched))
            else -> string(R.string.boot_status_receiver_only, TimeFmt.dateTime(received))
        }
    }

    private class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<Item> = emptyList()

        fun submit(list: List<Item>) {
            val sameShape = list.size == items.size && list.indices.all { list[it]::class == items[it]::class }
            items = list
            if (sameShape) notifyItemRangeChanged(0, list.size) else notifyDataSetChanged()
        }

        fun firstRow(): Int = items.indexOfFirst { it is Item.Row }

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = if (items[position] is Item.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                object : RecyclerView.ViewHolder(inflater.inflate(R.layout.item_setting_header, parent, false)) {}
            } else {
                RowHolder(inflater.inflate(R.layout.item_setting, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.Header -> (holder.itemView as TextView).text = item.title
                is Item.Row -> (holder as RowHolder).bind(item)
            }
        }

        inner class RowHolder(v: View) : RecyclerView.ViewHolder(v) {
            private val title: TextView = v.findViewById(R.id.set_title)
            private val summary: TextView = v.findViewById(R.id.set_summary)
            private val value: TextView = v.findViewById(R.id.set_value)

            init {
                v.setOnClickListener {
                    val p = bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) (items[p] as? Item.Row)?.onClick?.invoke()
                }
                FocusUtils.applyFocusZoom(v, 1.015f)
            }

            fun bind(item: Item.Row) {
                title.text = item.title
                value.text = item.value
                value.visibility = if (item.value.isEmpty()) View.GONE else View.VISIBLE
                summary.text = item.summary
                summary.visibility = if (item.summary.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }
}
