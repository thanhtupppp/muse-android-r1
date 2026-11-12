package vn.tutu.muser1

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Wi‑Fi provisioning cho Phicomm R1 chạy Android 5.1.1 (API 22).
 *
 * Không dùng WifiNetworkSuggestion vì API này chỉ có từ Android 10.
 */
object WifiProvisioner {

    private const val TAG = "MuseR1"

    fun isSuccessResult(result: String): Boolean {
        return !result.contains("failed", ignoreCase = true) &&
                !result.contains("unable", ignoreCase = true) &&
                !result.contains("empty", ignoreCase = true) &&
                !result.contains("must contain", ignoreCase = true)
    }

    fun connect(context: Context, ssid: String, password: String): Boolean {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false
        val result = connect(wifiManager, ssid, password)
        return isSuccessResult(result)
    }

    fun connect(wifiManager: WifiManager, ssid: String, password: String): String {
        val cleanSsid = ssid.trim()

        if (cleanSsid.isEmpty()) {
            return "SSID is empty"
        }

        if (password.length < 8) {
            return "WPA2 password must contain at least 8 characters"
        }

        return try {
            val configuration = WifiConfiguration().apply {
                SSID = "\"$cleanSsid\""
                preSharedKey = "\"$password\""
            }

            val networkId = wifiManager.addNetwork(configuration)

            if (networkId == -1) {
                val existingId = findExistingNetworkId(wifiManager, cleanSsid)

                if (existingId == -1) {
                    return "Unable to add Wi‑Fi network"
                }

                enableAndReconnect(wifiManager, existingId)
                return "Updated existing Wi‑Fi network: $cleanSsid"
            }

            enableAndReconnect(wifiManager, networkId)
            "Connected command sent for $cleanSsid"
        } catch (error: Exception) {
            Log.e(TAG, "Wi‑Fi provisioning failed", error)
            "Wi‑Fi provisioning failed: ${error.message}"
        }
    }

    @Suppress("DEPRECATION")
    private fun findExistingNetworkId(
        wifiManager: WifiManager,
        ssid: String
    ): Int {
        val configuredNetworks = wifiManager.configuredNetworks ?: return -1

        return configuredNetworks
            .firstOrNull { it.SSID == "\"$ssid\"" }
            ?.networkId
            ?: -1
    }

    @Suppress("DEPRECATION")
    private fun enableAndReconnect(wifiManager: WifiManager, networkId: Int) {
        wifiManager.disconnect()
        wifiManager.enableNetwork(networkId, true)
        wifiManager.reconnect()
    }
}
