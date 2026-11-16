package com.novatv.player.ui.link

import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.novatv.player.AppGraph
import com.novatv.player.R
import com.novatv.player.link.LinkNetwork
import com.novatv.player.ui.common.Osd
import kotlinx.coroutines.launch

/**
 * "Telefondan yönet": shows a QR code, the address and a pairing PIN. A phone that scans the
 * code opens the management panel, pairs once and is remembered. While this screen is open the
 * LG webOS NovaTV can also copy this TV's sources with the PIN. Whether the server keeps running
 * after the screen closes depends on Settings → "Uzaktan yönetim" (see LinkHost).
 */
class LinkActivity : ComponentActivity() {

    private lateinit var qr: ImageView
    private lateinit var address: TextView
    private lateinit var pinView: TextView
    private lateinit var log: TextView
    private lateinit var errorView: TextView
    private lateinit var hint: TextView
    private lateinit var osd: Osd

    private val lines = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_link)
        AppGraph.ensureStarted()
        qr = findViewById(R.id.link_qr)
        address = findViewById(R.id.link_address)
        pinView = findViewById(R.id.link_pin)
        log = findViewById(R.id.link_log)
        errorView = findViewById(R.id.link_error)
        hint = findViewById(R.id.link_hint)
        osd = Osd(findViewById(R.id.osd))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppGraph.link.manage.events.collect { addLine(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val link = AppGraph.link
        if (LinkNetwork.localIpv4() == null) {
            showError(getString(R.string.link_no_network))
            return
        }
        if (!link.openPairing()) {
            showError(getString(R.string.link_no_port))
            return
        }
        link.refreshAddress()
        val base = link.address.value
        val pin = link.pairingPin
        if (base == null || pin == null) {
            showError(getString(R.string.link_no_network))
            return
        }
        errorView.visibility = View.GONE
        address.text = base
        pinView.text = pin
        val sizePx = (260 * resources.displayMetrics.density).toInt()
        // The PIN in the QR code pairs the phone without typing; it is only valid while this screen is open.
        qr.setImageDrawable(BitmapDrawable(resources, LinkNetwork.qrBitmap("$base/#p=$pin", sizePx)).apply { isFilterBitmap = false })
        hint.setText(if (AppGraph.settings.remoteManage) R.string.link_hint_always else R.string.link_hint_screen)
    }

    override fun onStop() {
        AppGraph.link.closePairing()
        super.onStop()
    }

    override fun onDestroy() {
        osd.dispose()
        super.onDestroy()
    }

    private fun showError(message: String) {
        errorView.text = message
        errorView.visibility = View.VISIBLE
    }

    private fun addLine(text: String) {
        lines.add(text.removePrefix("Telefondan: "))
        // Newest first: the area shows only a few lines.
        log.text = lines.takeLast(8).asReversed().joinToString("\n")
        osd.show(text)
    }
}
