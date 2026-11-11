package vn.tutu.muser1

import android.content.Context
import android.net.wifi.WifiManager
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkStateObserver {

    fun currentSummary(context: Context): String {
        val wifiManager = context.getSystemService(WifiManager::class.java)
        val ssid = wifiManager.connectionInfo?.ssid?.removeSurrounding("\"")
        val ip = currentIpv4()

        return buildString {
            append("SSID: ")
            appendLine(ssid ?: "unknown")
            append("IP: ")
            append(ip ?: "not available")
        }
    }

    private fun currentIpv4(): String? {
        return NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<InetAddress>()
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(":") == false }
            ?.hostAddress
    }
}
