package com.novatv.player.core

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteKeysTest {

    private fun step(code: Int, scan: Int = 0, up: RemoteKeys.Taught? = null, down: RemoteKeys.Taught? = null) =
        RemoteKeys.channelStep(code, scan, up, down)

    @Test
    fun `channel, page and media next-previous keys all change channel`() {
        assertEquals(1, step(KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals(-1, step(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals(1, step(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(-1, step(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(1, step(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(-1, step(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertNull(step(KeyEvent.KEYCODE_DPAD_UP))
        assertNull(step(KeyEvent.KEYCODE_5))
    }

    @Test
    fun `taught key wins over the defaults, also for a reversed remote`() {
        val up = RemoteKeys.Taught(KeyEvent.KEYCODE_PAGE_DOWN, 0)
        val down = RemoteKeys.Taught(KeyEvent.KEYCODE_PAGE_UP, 0)
        assertEquals(1, step(KeyEvent.KEYCODE_PAGE_DOWN, up = up, down = down))
        assertEquals(-1, step(KeyEvent.KEYCODE_PAGE_UP, up = up, down = down))
        assertEquals(1, step(KeyEvent.KEYCODE_PROG_RED, up = RemoteKeys.Taught(KeyEvent.KEYCODE_PROG_RED, 0)))
    }

    @Test
    fun `button without an Android key code is matched by scan code`() {
        val up = RemoteKeys.Taught(KeyEvent.KEYCODE_UNKNOWN, 402)
        assertEquals(1, step(KeyEvent.KEYCODE_UNKNOWN, 402, up = up))
        assertNull(step(KeyEvent.KEYCODE_UNKNOWN, 403, up = up))
        assertNull(step(KeyEvent.KEYCODE_UNKNOWN, 0, up = RemoteKeys.Taught(KeyEvent.KEYCODE_UNKNOWN, 0)))
    }

    @Test
    fun `navigation, OK, back, volume and digits cannot be taught`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_9,
        ).forEach { assertFalse("key $it", RemoteKeys.canTeach(it, 0)) }
        assertFalse(RemoteKeys.canTeach(KeyEvent.KEYCODE_UNKNOWN, 0))
        assertTrue(RemoteKeys.canTeach(KeyEvent.KEYCODE_UNKNOWN, 402))
        assertTrue(RemoteKeys.canTeach(KeyEvent.KEYCODE_PROG_BLUE, 0))
        assertTrue(RemoteKeys.canTeach(KeyEvent.KEYCODE_CHANNEL_UP, 0))
    }
}
