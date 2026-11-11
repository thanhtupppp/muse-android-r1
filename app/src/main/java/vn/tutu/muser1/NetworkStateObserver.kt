package vn.tutu.muser1

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkStateObserver {

    fun currentSummary(context: Context): String {
        val wifiManager = context.getSystemService(WifiManager::class.java)
        val connectivityManager =
            context.getSystemService(ConnectivityManager::class.java)

        val ssid = wifiManager.connectionInfo?.ssid?.removeSurrounding("\"")
        val ip = currentIpv4()
        val online = connectivityManager
            ?.getNetworkCapabilities(connectivityManager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        return buildString {
            append("SSID: ")
            appendLine(ssid ?: "Không rõ")
            append("IP: ")
            appendLine(ip ?: "Chưa có")
            append("Internet: ")
            append(if (online) "Đã kết nối" else "Chưa xác nhận")
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
