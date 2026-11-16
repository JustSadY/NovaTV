package com.novatv.player.ui.common

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Keeps one neighbouring row visible around the focused row, so the focus never sticks to
 * the very edge of the list while browsing with the remote.
 */
class TvLayoutManager(context: Context, orientation: Int) : LinearLayoutManager(context, orientation, false) {

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean,
    ): Boolean {
        val expanded = Rect(rect)
        if (orientation == VERTICAL) {
            val extra = child.height
            expanded.top -= extra
            expanded.bottom += extra
        } else {
            val extra = child.width / 2
            expanded.left -= extra
            expanded.right += extra
        }
        return super.requestChildRectangleOnScreen(parent, child, expanded, immediate, focusedChildVisible)
    }
}
