package com.novatv.player.ui.player

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.novatv.player.R
import com.novatv.player.ui.common.FocusUtils

/** Right-hand quick menu (RIGHT / MENU key in fullscreen). */
class QuickMenu(private val root: View) {
    class Item(val icon: Int, val label: String, val action: () -> Unit)

    private val title: TextView = root.findViewById(R.id.menu_channel)
    private val container: LinearLayout = root.findViewById(R.id.menu_items)
    private val interpolator = DecelerateInterpolator()
    private val autoHide = Runnable { close() }

    val isOpen: Boolean get() = root.visibility == View.VISIBLE

    fun open(header: String, items: List<Item>) {
        title.text = header
        container.removeAllViews()
        val inflater = LayoutInflater.from(root.context)
        var first: View? = null
        for (item in items) {
            val row = inflater.inflate(R.layout.item_menu, container, false) as TextView
            row.text = item.label
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(item.icon, 0, 0, 0)
            row.setOnClickListener {
                close()
                item.action()
            }
            FocusUtils.applyFocusZoom(row, 1.02f)
            container.addView(row)
            if (first == null) first = row
        }
        root.animate().cancel()
        if (!isOpen) {
            root.translationX = root.width.takeIf { it > 0 }?.toFloat() ?: (root.resources.displayMetrics.density * 340)
            root.visibility = View.VISIBLE
        }
        root.animate().translationX(0f).setDuration(180).setInterpolator(interpolator).start()
        first?.requestFocus()
        bump()
    }

    fun close() {
        root.removeCallbacks(autoHide)
        if (!isOpen) return
        root.animate().cancel()
        root.animate().translationX(root.width.toFloat()).setDuration(150)
            .withEndAction { root.visibility = View.GONE }.start()
    }

    /** Handles keys while open. Returns true if consumed. */
    fun handleKey(event: KeyEvent): Boolean {
        bump()
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MENU -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) close()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> true
            else -> false
        }
    }

    private fun bump() {
        root.removeCallbacks(autoHide)
        root.postDelayed(autoHide, 20_000)
    }

    fun dispose() {
        root.removeCallbacks(autoHide)
    }
}
