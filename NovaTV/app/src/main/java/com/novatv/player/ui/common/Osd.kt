package com.novatv.player.ui.common

import android.widget.TextView

/** Short on-screen message shown at the top of the screen (TV friendly replacement for Toast). */
class Osd(private val view: TextView) {
    private val hide = Runnable {
        view.animate().alpha(0f).setDuration(200).withEndAction { view.visibility = TextView.GONE }.start()
    }

    fun show(message: CharSequence, durationMs: Long = 2_600L) {
        view.removeCallbacks(hide)
        view.animate().cancel()
        view.text = message
        if (view.visibility != TextView.VISIBLE) {
            view.alpha = 0f
            view.visibility = TextView.VISIBLE
        }
        view.animate().alpha(1f).setDuration(150).start()
        view.postDelayed(hide, durationMs)
    }

    fun dispose() {
        view.removeCallbacks(hide)
    }
}
