package com.novatv.player.link

import android.content.Context
import android.graphics.Bitmap
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import io.nayuki.qrcodegen.QrCode
import java.net.Inet4Address
import java.net.NetworkInterface

object LinkNetwork {

    /** First IPv4 LAN address of an active interface (Wi-Fi or Ethernet), or null. */
    fun localIpv4(): String? {
        val candidates = ArrayList<Pair<String, String>>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (nif in ifaces) {
                if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        candidates.add(nif.name to (addr.hostAddress ?: continue))
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }
        // Prefer Ethernet / Wi-Fi over VPN or other tunnels.
        return (candidates.firstOrNull { it.first.startsWith("eth") || it.first.startsWith("wlan") } ?: candidates.firstOrNull())?.second
    }

    /** Human-readable TV name (user-set device name on Android TV, else the model). */
    fun deviceName(context: Context): String {
        val name = try {
            Settings.Global.getString(context.contentResolver, "device_name")
        } catch (_: Exception) {
            null
        }
        return name?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }

    /** Renders a QR code with hard pixel edges (no filtering) at roughly [targetPx] size. */
    fun qrBitmap(text: String, targetPx: Int): Bitmap {
        val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        val border = 2
        val modules = qr.size + border * 2
        val scale = maxOf(1, targetPx / modules)
        val size = modules * scale
        val pixels = IntArray(size * size) { WHITE }
        for (y in 0 until qr.size) {
            for (x in 0 until qr.size) {
                if (!qr.getModule(x, y)) continue
                val px = (x + border) * scale
                val py = (y + border) * scale
                for (dy in 0 until scale) {
                    val row = (py + dy) * size + px
                    for (dx in 0 until scale) pixels[row + dx] = BLACK
                }
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }

    private const val WHITE = -0x1
    private const val BLACK = -0x1000000

    /** Announces the Link server via mDNS/DNS-SD (_novatv._tcp) while the screen is open. */
    class Advertiser(context: Context) {
        private val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        private var listener: NsdManager.RegistrationListener? = null

        fun register(name: String, port: Int) {
            val manager = nsd ?: return
            val info = NsdServiceInfo().apply {
                serviceName = "NovaTV ($name)"
                serviceType = LinkServer.SERVICE_TYPE
                setPort(port)
                if (Build.VERSION.SDK_INT >= 21) setAttribute("path", "/api/info")
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            }
            listener = l
            runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
        }

        fun unregister() {
            val l = listener ?: return
            listener = null
            runCatching { nsd?.unregisterService(l) }
        }
    }
}
