package vn.tutu.musemobile.data

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Quét Wi‑Fi nhà.
 *
 * Android yêu cầu quyền vị trí để nhận kết quả scan Wi‑Fi.
 * Giai đoạn đầu, màn hình vẫn cho phép nhập SSID thủ công.
 */
class WifiScanner(private val context: Context) {

    fun scan(): List<String> {
        val wifiManager = context.getSystemService(WifiManager::class.java)
        return wifiManager.scanResults
            .mapNotNull { it.SSID }
            .filter { it.isNotBlank() }
            .distinct()
    }
}
