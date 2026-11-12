package vn.tutu.musemobile.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) fine else (fine || coarse)
    }

    suspend fun scan(): List<WifiNetwork> = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) {
            return@withContext emptyList()
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return@withContext emptyList()

        if (!wifiManager.isWifiEnabled) {
            return@withContext emptyList()
        }

        // Kích hoạt quét ngầm để làm mới bộ đệm nếu hệ thống cho phép
        @Suppress("DEPRECATION")
        val scanStarted = runCatching { wifiManager.startScan() }.getOrDefault(false)
        if (!scanStarted) {
            Log.d(TAG, "startScan bị throttle hoặc không khả dụng; sử dụng kết quả quét có sẵn trong hệ thống")
        }

        @Suppress("DEPRECATION")
        val scanResults = try {
            wifiManager.scanResults.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }

        scanResults
            .mapNotNull { result ->
                val rawSsid = result.SSID ?: return@mapNotNull null
                val ssid = rawSsid.removeSurrounding("\"").trim()

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
        // Nhận diện chuẩn WEP riêng biệt (chuẩn bảo mật cũ lỗi thời)
        // Các chuẩn WPA/WPA2/WPA3 (Transition Mode) được map về WPA2 để tương thích với phần cứng loa R1
        return when {
            caps.contains("WEP") -> "WEP"
            caps.contains("WPA") || caps.contains("RSN") || caps.contains("SAE") -> "WPA2"
            else -> "OPEN"
        }
    }

    companion object {
        private const val TAG = "WifiScanner"
    }
}
