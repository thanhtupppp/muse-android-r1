package vn.tutu.muser1

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkStateObserver {

    fun currentSummary(context: Context): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val rawSsid = wifiManager?.connectionInfo?.ssid
        val ssid = if (rawSsid.isNullOrBlank() || rawSsid == "<unknown ssid>") {
            "unknown"
        } else {
            rawSsid.removeSurrounding("\"")
        }
        val ip = currentIpv4()

        return buildString {
            append("SSID: ")
            appendLine(ssid)
            append("IP: ")
            append(ip ?: "not available")
        }
    }

    private fun currentIpv4(): String? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

        // Ưu tiên interface Wi-Fi (wlan), nếu không có thì fallback các interface mạng khác
        val wlanIp = interfaces.asSequence()
            .filter { it.isUp && !it.isLoopback && it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress

        if (wlanIp != null) return wlanIp

        return interfaces.asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }
}
