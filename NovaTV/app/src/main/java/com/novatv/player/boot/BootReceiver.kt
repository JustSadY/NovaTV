package com.novatv.player.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.novatv.player.AppGraph
import com.novatv.player.ui.home.MainActivity

/**
 * Starts NovaTV after the device boots, if the user enabled it. Uses only the standard
 * BOOT_COMPLETED mechanism (plus the common QUICKBOOT alias); if the OS or manufacturer
 * blocks the launch nothing else is attempted — the Settings screen explains the situation.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        // Newer Android versions also deliver BOOT_COMPLETED when a force-stopped app is first
        // started again, long after the real boot. Only react to a genuine, recent boot.
        if (SystemClock.elapsedRealtime() > MAX_BOOT_AGE_MS) return

        val settings = AppGraph.settings
        settings.bootReceivedAt = System.currentTimeMillis()
        if (!settings.autoStartOnBoot) return

        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_FROM_BOOT, true)
        try {
            context.startActivity(launch)
        } catch (_: Exception) {
            // Blocked by the system; MainActivity shows diagnostics in Settings.
        }
    }

    private companion object {
        /** Slow TVs may deliver BOOT_COMPLETED several minutes after power-on. */
        const val MAX_BOOT_AGE_MS = 10 * 60_000L
    }
}
