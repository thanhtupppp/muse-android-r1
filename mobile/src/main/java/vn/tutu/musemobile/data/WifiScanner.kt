package vn.tutu.musemobile.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.tutu.musemobile.domain.WifiNetwork

class WifiScanner(private val context: Context) {

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    suspend fun scan(): List<WifiNetwork> = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) {
            return@withContext emptyList()
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return@withContext emptyList()

        @Suppress("DEPRECATION")
        val scanResults = try {
            wifiManager.scanResults.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }

        scanResults
            .mapNotNull { result ->
                val ssid = result.SSID ?: return@mapNotNull null

                if (ssid.isBlank() || ssid == "<unknown ssid>" || ssid.contains("\u0000")) {
                    return@mapNotNull null
                }

                val security = parseSecurity(result.capabilities)

                WifiNetwork(
                    ssid = ssid,
                    bssid = result.BSSID ?: "",
                    frequencyMhz = result.frequency,
                    signalLevel = result.level,
                    security = security
                )
            }
            // Sắp xếp theo cường độ sóng giảm dần trước khi distinctBy để chọn AP mạnh nhất cho cùng một SSID (Mesh)
            .sortedByDescending { it.signalLevel }
            .distinctBy { it.ssid }
    }

    private fun parseSecurity(capabilities: String?): String {
        if (capabilities.isNullOrEmpty()) return "OPEN"
        val caps = capabilities.uppercase()
        return when {
            caps.contains("WPA3") || caps.contains("SAE") -> "WPA3"
            caps.contains("WPA2") || caps.contains("RSN") -> "WPA2"
            caps.contains("WPA") -> "WPA"
            caps.contains("WEP") -> "WEP"
            else -> "OPEN"
        }
    }
}
