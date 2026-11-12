package vn.tutu.musemobile.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import vn.tutu.musemobile.domain.WifiNetwork

class WifiScanner(private val context: Context) {

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun scan(): List<WifiNetwork> {
        if (!hasLocationPermission()) {
            return emptyList()
        }

        val wifiManager = context.getSystemService(WifiManager::class.java)

        return wifiManager.scanResults
            .mapNotNull { result ->
                val ssid = result.SSID ?: return@mapNotNull null

                if (ssid.isBlank()) {
                    return@mapNotNull null
                }

                WifiNetwork(
                    ssid = ssid,
                    bssid = result.BSSID,
                    frequencyMhz = result.frequency,
                    signalLevel = result.level
                )
            }
            .distinctBy { it.ssid }
            .sortedByDescending { it.signalLevel }
    }
}
