package com.novatv.player.ui.home.sections

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.novatv.player.ui.home.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A content page of the home screen. Sections are created lazily and kept while the activity lives. */
abstract class Section(protected val host: MainActivity, layoutRes: Int) {
    val view: View = LayoutInflater.from(host).inflate(layoutRes, host.contentContainer, false)

    /** Moves focus into the section's content. Returns false if nothing is focusable. */
    abstract fun focusContent(): Boolean

    open fun onShow() {}

    /** Lists at the right edge of the screen: RIGHT must not jump to unrelated views above them. */
    protected val rightEdgeViews = ArrayList<View>(2)

    /** Keys while focus is inside this section (e.g. move mode). Return true to consume. */
    open fun onKey(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && rightEdgeViews.any { it.hasFocus() }

    /** Back while focus is inside this section. Return true to consume. */
    open fun onBack(): Boolean = false

    /** Runs [block] while the activity is at least STARTED (restarts on every start). */
    protected fun whileStarted(block: suspend CoroutineScope.() -> Unit) {
        host.lifecycleScope.launch { host.repeatOnLifecycle(Lifecycle.State.STARTED, block) }
    }

    protected fun string(id: Int, vararg args: Any): String = host.getString(id, *args)
}
