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

        val scanned = scanResults.mapNotNull { result ->
            val rawSsid = result.SSID ?: return@mapNotNull null
            val ssid = rawSsid.removeSurrounding("\"").trim()

            if (ssid.isBlank() || ssid == "<unknown ssid>" || ssid.contains("\u0000")) {
                return@mapNotNull null
            }

            WifiNetwork(
                ssid = ssid,
                bssid = result.BSSID ?: "",
                frequencyMhz = result.frequency,
                signalLevel = result.level,
                security = parseSecurity(result.capabilities)
            )
        }

        // Gộp theo SSID: AP đại diện là AP sóng mạnh nhất, nhưng giữ lại
        // thông tin "SSID có AP 2.4 GHz hay không" từ TOÀN BỘ các AP —
        // distinctBy như trước ném mất thông tin này, khiến mạng hai băng
        // bị gán nhãn theo AP 5 GHz mạnh hơn và mạng thuần 5 GHz trông
        // không khác gì mạng loa dùng được.
        return@withContext scanned
            .groupBy { it.ssid }
            .values
            .map { group ->
                val strongest = group.maxByOrNull { it.signalLevel } ?: group.first()
                strongest.copy(has24Ghz = group.any { it.is24Ghz })
            }
            .sortedByDescending { it.signalLevel }
    }

    private fun parseSecurity(capabilities: String?): String {
        if (capabilities.isNullOrEmpty()) return "OPEN"
        val caps = capabilities.uppercase()
        val hasSae = caps.contains("SAE")
        val hasPsk = caps.contains("PSK")
        return when {
            caps.contains("WEP") -> "WEP"
            // WPA3 thuần (chỉ SAE, không PSK): supplicant Android 5.1 của
            // loa không bắt tay được — phải hiện rõ thay vì lặng lẽ map về
            // WPA2 như trước (khi đó provision chắc chắn CONNECT_TIMEOUT
            // mà không ai hiểu vì sao). Chế độ hỗn hợp WPA2/WPA3 có cả
            // PSK nên vẫn đi đường WPA2 bình thường.
            hasSae && !hasPsk -> "WPA3"
            caps.contains("EAP") && !hasPsk -> "EAP"
            caps.contains("WPA") || caps.contains("RSN") || hasSae -> "WPA2"
            else -> "OPEN"
        }
    }

    companion object {
        private const val TAG = "WifiScanner"
    }
}
