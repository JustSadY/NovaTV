package com.novatv.player.ui.home

import androidx.lifecycle.lifecycleScope
import com.novatv.player.AppGraph
import com.novatv.player.R
import com.novatv.player.ui.common.TvDialog
import com.novatv.player.update.Updater
import com.novatv.player.update.Versions
import kotlinx.coroutines.launch

/** Dialog flow for app updates from GitHub Releases (Settings → Güncellemeler, daily check on start). */
object UpdateFlow {

    private var running = false

    /**
     * [silent]: the automatic daily check — says nothing when up to date or offline and does not
     * offer a version the user chose to skip.
     */
    fun check(host: MainActivity, silent: Boolean, onDone: () -> Unit = {}) {
        if (running) return
        running = true
        val updater = AppGraph.updater
        if (!silent) host.osd.show(host.getString(R.string.update_checking))
        host.lifecycleScope.launch {
            val result = updater.check()
            running = false
            onDone()
            when (result) {
                is Updater.Check.Available -> {
                    val r = result.release
                    if (silent && AppGraph.settings.updateSkipped == r.version) return@launch
                    offer(host, r, silent)
                }
                is Updater.Check.UpToDate -> if (!silent) host.osd.show(host.getString(R.string.update_up_to_date, updater.currentVersion))
                is Updater.Check.Failed -> if (!silent) host.osd.show(result.message, 6_000)
            }
        }
    }

    private fun offer(host: MainActivity, r: Updater.Release, silent: Boolean) {
        val size = if (r.size > 0) " (%.1f MB)".format(r.size / 1_048_576.0) else ""
        val body = host.getString(R.string.update_body, AppGraph.updater.currentVersion, Versions.display(r.version), size) +
            if (r.notes.isNotBlank()) "\n\n" + r.notes.trim() else ""
        TvDialog.confirm(
            host,
            host.getString(R.string.update_title, Versions.display(r.version)),
            body,
            host.getString(R.string.update_install),
            host.getString(if (silent) R.string.update_skip else R.string.cancel),
            onPositive = { download(host, r) },
            onNegative = { if (silent) AppGraph.settings.updateSkipped = r.version },
        )
    }

    private fun download(host: MainActivity, r: Updater.Release) {
        val updater = AppGraph.updater
        if (updater.needsInstallPermission()) {
            TvDialog.confirm(
                host,
                host.getString(R.string.update_permission_title),
                host.getString(R.string.update_permission_body),
                host.getString(R.string.update_open_settings),
                host.getString(R.string.cancel),
                onPositive = {
                    if (!updater.openInstallPermissionSettings(host)) host.osd.show(host.getString(R.string.update_no_settings), 6_000)
                },
            )
            return
        }
        host.osd.show(host.getString(R.string.update_downloading, 0), 60_000)
        host.lifecycleScope.launch {
            updater.download(r) { p -> host.osd.show(host.getString(R.string.update_downloading, p), 60_000) }.fold(
                onSuccess = { file ->
                    val problem = updater.verify(file)
                    if (problem != null) {
                        file.delete()
                        TvDialog.message(host, host.getString(R.string.update_failed_title), problem)
                        return@fold
                    }
                    host.osd.show(host.getString(R.string.update_ready))
                    updater.install(host, file)?.let { TvDialog.message(host, host.getString(R.string.update_failed_title), it) }
                },
                onFailure = { host.osd.show(it.message ?: "", 6_000) },
            )
        }
    }
}
