package com.novatv.player.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/**
 * Vertical navigation rail. When focus enters from outside (e.g. LEFT from the content area)
 * only the selected item is offered as a candidate, so the user always lands on the
 * current section instead of whichever item happens to be geometrically closest.
 */
class SidebarLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var selectedItem: View? = null

    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        val selected = selectedItem
        if (!hasFocus() && selected != null && selected.isShown && selected.isFocusable) {
            views.add(selected)
            return
        }
        super.addFocusables(views, direction, focusableMode)
    }
}
