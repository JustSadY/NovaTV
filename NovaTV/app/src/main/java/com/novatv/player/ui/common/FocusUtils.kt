package com.novatv.player.ui.common

import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

object FocusUtils {
    private val interpolator = DecelerateInterpolator()

    /** Subtle zoom on focus — cheap (hardware layer free) and readable from the couch. */
    fun applyFocusZoom(view: View, scale: Float = 1.03f) {
        view.setOnFocusChangeListener { v, hasFocus -> animateFocus(v, hasFocus, scale) }
    }

    fun animateFocus(v: View, hasFocus: Boolean, scale: Float = 1.03f) {
        val s = if (hasFocus) scale else 1f
        v.animate().scaleX(s).scaleY(s).setDuration(120).setInterpolator(interpolator).start()
    }

    /** Scrolls to [position] and focuses that row once it is laid out. */
    fun focusPosition(rv: RecyclerView, position: Int, attempts: Int = 3) {
        if (position < 0 || position >= (rv.adapter?.itemCount ?: 0)) {
            rv.requestFocus()
            return
        }
        val lm = rv.layoutManager as? LinearLayoutManager
        val holder = rv.findViewHolderForAdapterPosition(position)
        if (holder != null) {
            holder.itemView.requestFocus()
            return
        }
        val offset = (rv.height / 3).coerceAtLeast(0)
        if (lm != null) lm.scrollToPositionWithOffset(position, offset) else rv.scrollToPosition(position)
        if (attempts > 0) rv.post { focusPosition(rv, position, attempts - 1) }
    }

    fun setupList(rv: RecyclerView, horizontal: Boolean = false) {
        rv.layoutManager = TvLayoutManager(rv.context, if (horizontal) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL)
        rv.itemAnimator = null
        rv.setHasFixedSize(true)
        rv.setItemViewCacheSize(6)
    }
}
