package com.novatv.player.link

import android.content.Context
import androidx.annotation.MainThread
import com.novatv.player.core.Settings
import com.novatv.player.data.ChannelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom
import java.util.Locale

/**
 * Owns the one NovaTV Link server of the app.
 *
 * It runs while the "Telefondan yönet" screen is open (that screen shows the pairing PIN) and,
 * when Settings → "Uzaktan yönetim" is on, for as long as the NovaTV process is alive — so a
 * paired phone can manage the TV while it is playing. Nothing runs after Android ends the process.
 */
class LinkHost(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: Settings,
    repo: ChannelRepository,
) {
    val tokens = ManageTokens(context)
    val manage = ManageApi(context, repo) { LinkNetwork.deviceName(context) }

    private var server: LinkServer? = null
    private var advertiser: LinkNetwork.Advertiser? = null
    private var screenOpen = false

    @Volatile
    var pairingPin: String? = null
        private set

    private val _address = MutableStateFlow<String?>(null)

    /** "http://192.168.1.20:8686" while the server runs and the TV has a network address. */
    val address: StateFlow<String?> = _address

    /** Starts or stops the server to match the screen state and the setting. */
    @MainThread
    fun update(): Boolean {
        val wanted = screenOpen || settings.remoteManage
        if (wanted && server == null) return start()
        if (!wanted && server != null) stop()
        return server != null
    }

    /** Called by the pairing screen; returns false if the server could not start. */
    @MainThread
    fun openPairing(): Boolean {
        pairingPin = String.format(Locale.ROOT, "%04d", SecureRandom().nextInt(10_000))
        screenOpen = true
        return update()
    }

    @MainThread
    fun closePairing() {
        pairingPin = null
        screenOpen = false
        update()
    }

    @MainThread
    private fun start(): Boolean {
        val s = LinkServer(
            context = context,
            deviceName = { LinkNetwork.deviceName(context) },
            manage = manage,
            tokens = tokens,
            pinProvider = { pairingPin },
            exportSources = { manage.sources() },
        )
        if (!s.start(scope)) return false
        server = s
        _address.value = LinkNetwork.localIpv4()?.let { "http://$it:${s.port}" }
        advertiser = LinkNetwork.Advertiser(context).also { it.register(LinkNetwork.deviceName(context), s.port) }
        return true
    }

    @MainThread
    private fun stop() {
        advertiser?.unregister()
        advertiser = null
        server?.stop()
        server = null
        _address.value = null
    }

    /** Re-reads the network address (e.g. after Wi-Fi reconnects). */
    fun refreshAddress() {
        val s = server ?: return
        _address.value = LinkNetwork.localIpv4()?.let { "http://$it:${s.port}" }
    }
}
