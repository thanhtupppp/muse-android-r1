package vn.tutu.muser1

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings

object WifiProvisioner {

    fun provision(
        context: Context,
        wifiManager: WifiManager,
        ssid: String,
        password: String
    ): String {
        val cleanSsid = ssid.trim()

        if (cleanSsid.isEmpty()) {
            return "SSID không được để trống."
        }

        if (password.length < 8) {
            return "Mật khẩu WPA2 phải có ít nhất 8 ký tự."
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            suggestNetwork(wifiManager, cleanSsid, password)
        } else {
            addLegacyNetwork(wifiManager, cleanSsid, password)
        }
    }

    private fun suggestNetwork(
        wifiManager: WifiManager,
        ssid: String,
        password: String
    ): String {
        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .setIsAppInteractionRequired(true)
            .build()

        wifiManager.removeNetworkSuggestions(emptyList())
        val result = wifiManager.addNetworkSuggestions(listOf(suggestion))

        return when (result) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ->
                "Đã gửi cấu hình Wi‑Fi. Hãy chờ R1 kết nối hoặc mở Cài đặt Wi‑Fi nếu cần xác nhận."
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED ->
                "Hệ thống không cho phép ứng dụng đề xuất Wi‑Fi. Hãy mở Cài đặt Wi‑Fi."
            else -> "Không gửi được cấu hình Wi‑Fi (code: $result). Hãy mở Cài đặt Wi‑Fi."
        }
    }

    @Suppress("DEPRECATION")
    private fun addLegacyNetwork(
        wifiManager: WifiManager,
        ssid: String,
        password: String
    ): String {
        return try {
            val configuration = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                preSharedKey = "\"$password\""
            }

            val networkId = wifiManager.addNetwork(configuration)
            if (networkId == -1) {
                "Không thêm được mạng Wi‑Fi. Hãy mở Cài đặt Wi‑Fi."
            } else {
                wifiManager.disconnect()
                wifiManager.enableNetwork(networkId, true)
                wifiManager.reconnect()
                "Đã gửi lệnh kết nối Wi‑Fi $ssid."
            }
        } catch (error: Exception) {
            "Lỗi kết nối Wi‑Fi: ${error.message}. Hãy mở Cài đặt Wi‑Fi."
        }
    }

    fun openWifiSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
    }
}
