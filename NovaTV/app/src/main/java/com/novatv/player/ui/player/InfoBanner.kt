package com.novatv.player.ui.player

import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.novatv.player.R
import com.novatv.player.core.TimeFmt
import com.novatv.player.data.Channel
import com.novatv.player.epg.EpgRepository
import com.novatv.player.ui.common.LogoLoader

/** Bottom channel banner shown on zapping / OK: number, logo, name, now & next programme. */
class InfoBanner(private val root: View, private val epg: EpgRepository) {
    private val number: TextView = root.findViewById(R.id.ib_number)
    private val logo: ImageView = root.findViewById(R.id.ib_logo)
    private val name: TextView = root.findViewById(R.id.ib_name)
    private val fav: ImageView = root.findViewById(R.id.ib_fav)
    private val source: TextView = root.findViewById(R.id.ib_source)
    private val quality: TextView = root.findViewById(R.id.ib_quality)
    private val group: TextView = root.findViewById(R.id.ib_group)
    private val now: TextView = root.findViewById(R.id.ib_now)
    private val progress: ProgressBar = root.findViewById(R.id.ib_progress)
    private val next: TextView = root.findViewById(R.id.ib_next)
    private val clock: TextView = root.findViewById(R.id.ib_clock)

    private val hideRunnable = Runnable { hide() }
    private val interpolator = DecelerateInterpolator()
    private val slide = root.resources.displayMetrics.density * 36

    var channel: Channel? = null
        private set

    val isVisible: Boolean get() = root.visibility == View.VISIBLE

    fun show(ch: Channel, seconds: Int, isFavorite: Boolean, qualityLabel: String?) {
        channel = ch
        bind(ch, isFavorite, qualityLabel)
        root.animate().cancel()
        if (!isVisible) {
            root.alpha = 0f
            root.translationY = slide
            root.visibility = View.VISIBLE
        }
        root.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(interpolator).start()
        root.removeCallbacks(hideRunnable)
        root.postDelayed(hideRunnable, seconds.coerceIn(2, 30) * 1000L)
    }

    fun hide() {
        root.removeCallbacks(hideRunnable)
        if (!isVisible) return
        root.animate().cancel()
        root.animate().alpha(0f).translationY(slide).setDuration(160)
            .withEndAction { root.visibility = View.GONE }.start()
    }

    /** Re-binds the visible banner (EPG loaded, favorite toggled, resolution known). */
    fun refresh(isFavorite: Boolean, qualityLabel: String?) {
        val ch = channel ?: return
        if (isVisible) bind(ch, isFavorite, qualityLabel)
    }

    fun dispose() {
        root.removeCallbacks(hideRunnable)
        LogoLoader.cancel(logo)
    }

    private fun bind(ch: Channel, isFavorite: Boolean, qualityLabel: String?) {
        val ctx = root.context
        number.text = if (ch.number > 0) ch.number.toString() else ""
        LogoLoader.load(logo, ch.logo, ch.name)
        name.text = ch.name
        fav.visibility = if (isFavorite) View.VISIBLE else View.GONE
        if (ch.inMultipleSources && ch.sourceName.isNotEmpty()) {
            source.visibility = View.VISIBLE
            source.text = ch.sourceName
        } else {
            source.visibility = View.GONE
        }
        if (qualityLabel != null) {
            quality.visibility = View.VISIBLE
            quality.text = qualityLabel
        } else {
            quality.visibility = View.GONE
        }
        group.text = ch.group.ifBlank { ctx.getString(R.string.group_uncategorized) }
        clock.text = TimeFmt.now()

        val nowMs = System.currentTimeMillis()
        val nn = epg.nowNext(ch, nowMs)
        val current = nn.now
        if (current != null) {
            now.text = ctx.getString(R.string.time_range, TimeFmt.hm(current.start), TimeFmt.hm(current.stop)) + "   " + current.title
            progress.visibility = View.VISIBLE
            progress.progress = (current.progress(nowMs) * 1000).toInt()
        } else {
            now.text = ctx.getString(R.string.no_epg)
            progress.visibility = View.GONE
        }
        val upcoming = nn.next
        if (upcoming != null) {
            next.visibility = View.VISIBLE
            next.text = ctx.getString(R.string.epg_next) + "  " + TimeFmt.hm(upcoming.start) + "   " + upcoming.title
        } else {
            next.visibility = View.GONE
        }
    }
}
