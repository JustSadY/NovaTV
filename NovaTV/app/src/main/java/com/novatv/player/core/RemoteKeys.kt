package com.novatv.player.core

import android.view.KeyEvent

/**
 * Channel up / down buttons of TV remotes.
 *
 * Android defines KEYCODE_CHANNEL_UP/DOWN, but many Android TV sets and boxes deliver their
 * CH+/CH− (P+/P−) buttons as PAGE_UP/PAGE_DOWN or as MEDIA_NEXT/MEDIA_PREVIOUS. All of these
 * count as channel keys. A button taught in Settings is checked first: by its key code, or by its
 * hardware scan code when the remote's button has no Android key code (KEYCODE_UNKNOWN).
 */
object RemoteKeys {

    /** A button taught in Settings. When keyCode is KEYCODE_UNKNOWN, scanCode identifies it. */
    data class Taught(val keyCode: Int, val scanCode: Int) {
        fun matches(keyCode: Int, scanCode: Int): Boolean =
            if (this.keyCode != KeyEvent.KEYCODE_UNKNOWN) keyCode == this.keyCode
            else this.scanCode != 0 && scanCode == this.scanCode
    }

    val DEFAULT_UP = setOf(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_NEXT)
    val DEFAULT_DOWN = setOf(KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    /** Keys that cannot be taught: needed for navigation, number entry, volume and leaving. */
    val RESERVED: Set<Int> = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
    ) + (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) + (KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9)

    /** True if this button can be taught (it is not reserved and can be recognised again). */
    fun canTeach(keyCode: Int, scanCode: Int): Boolean =
        keyCode !in RESERVED && (keyCode != KeyEvent.KEYCODE_UNKNOWN || scanCode != 0)

    /** +1 = channel up (next channel), -1 = channel down (previous), null = not a channel key. */
    fun channelStep(keyCode: Int, scanCode: Int, taughtUp: Taught?, taughtDown: Taught?): Int? = when {
        taughtUp?.matches(keyCode, scanCode) == true -> 1
        taughtDown?.matches(keyCode, scanCode) == true -> -1
        keyCode in DEFAULT_UP -> 1
        keyCode in DEFAULT_DOWN -> -1
        else -> null
    }
}
