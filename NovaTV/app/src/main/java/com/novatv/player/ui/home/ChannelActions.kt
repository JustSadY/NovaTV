package com.novatv.player.ui.home

import androidx.lifecycle.lifecycleScope
import com.novatv.player.AppGraph
import com.novatv.player.R
import com.novatv.player.data.Channel
import com.novatv.player.ui.common.TvDialog
import kotlinx.coroutines.launch

/** Context menu for a channel row (MENU key or long-press OK). */
object ChannelActions {

    class Extra(val label: String, val action: () -> Unit)

    fun show(host: MainActivity, ch: Channel, group: String, extras: List<Extra> = emptyList()) {
        val repo = AppGraph.channels
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        labels.add(host.getString(R.string.opt_play)); actions.add { host.play(ch, group) }
        val fav = repo.isFavorite(ch.key)
        labels.add(host.getString(if (fav) R.string.opt_fav_remove else R.string.opt_fav_add))
        actions.add {
            val now = repo.toggleFavorite(ch.key)
            host.osd.show(host.getString(if (now) R.string.fav_added else R.string.fav_removed, ch.name))
        }
        for (e in extras) {
            labels.add(e.label); actions.add(e.action)
        }
        labels.add(host.getString(R.string.opt_move_to_number)); actions.add { askMoveToNumber(host, ch) }
        val custom = repo.snapshot.value.source(ch.sourceId)?.isCustom == true
        labels.add(host.getString(if (custom) R.string.opt_delete_channel else R.string.opt_hide_channel))
        actions.add { confirmRemove(host, ch, custom) }
        if (ch.key in repo.broken.value) {
            labels.add(host.getString(R.string.opt_clear_broken)); actions.add { repo.clearBroken(ch.key) }
        }
        labels.add(host.getString(R.string.opt_info)); actions.add { showInfo(host, ch) }

        TvDialog.options(host, if (ch.number > 0) "${ch.number}  ${ch.name}" else ch.name, labels) { i -> actions[i]() }
    }

    /** Own channels are deleted; playlist channels are hidden (Settings → Gizlenen kanallar brings them back). */
    private fun confirmRemove(host: MainActivity, ch: Channel, custom: Boolean) {
        TvDialog.confirm(
            host,
            host.getString(if (custom) R.string.opt_delete_channel else R.string.opt_hide_channel),
            host.getString(if (custom) R.string.delete_channel_confirm else R.string.hide_confirm, ch.name),
            host.getString(if (custom) R.string.delete else R.string.yes),
            host.getString(R.string.cancel),
            onPositive = {
                host.lifecycleScope.launch {
                    AppGraph.channels.deleteChannel(ch.key).fold(
                        onSuccess = { deleted -> host.osd.show(host.getString(if (deleted) R.string.channel_deleted else R.string.channel_hidden, ch.name)) },
                        onFailure = { host.osd.show(it.message ?: "") },
                    )
                }
            },
        )
    }

    private fun askMoveToNumber(host: MainActivity, ch: Channel) {
        val total = AppGraph.channels.snapshot.value.channels.size
        TvDialog.input(host, host.getString(R.string.move_to_number_title, total), ch.number.toString(), numeric = true) { text ->
            val n = text.toIntOrNull() ?: return@input
            host.lifecycleScope.launch {
                AppGraph.channels.moveToNumber(ch.key, n.coerceIn(1, total))
                host.osd.show(host.getString(R.string.order_saved))
            }
        }
    }

    fun showInfo(host: MainActivity, ch: Channel) {
        val broken = ch.key in AppGraph.channels.broken.value
        TvDialog.message(
            host,
            ch.name,
            host.getString(
                R.string.channel_info_body,
                ch.number,
                ch.sourceName.ifBlank { host.getString(R.string.unknown) },
                ch.group.ifBlank { host.getString(R.string.group_uncategorized) },
                ch.tvgId ?: "—",
                host.getString(if (broken) R.string.status_broken else R.string.status_ok),
            )
        )
    }
}
