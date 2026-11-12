package vn.tutu.muser1

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.util.Log

object WifiProvisioner {

    private const val TAG = "MuseR1"
    private const val CONNECT_TIMEOUT_MS = 20_000L
    private const val CONNECT_POLL_INTERVAL_MS = 1_000L

    fun connect(
        context: Context,
        ssid: String,
        password: String
    ): Boolean {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false

        return connectAndWait(
            wifiManager = wifiManager,
            ssid = ssid,
            password = password
        )
    }

    fun connect(
        wifiManager: WifiManager,
        ssid: String,
        password: String
    ): Boolean {
        return connectAndWait(
            wifiManager = wifiManager,
            ssid = ssid,
            password = password
        )
    }

    @Suppress("DEPRECATION")
    fun connectAndWait(
        wifiManager: WifiManager,
        ssid: String,
        password: String
    ): Boolean {
        val cleanSsid = ssid.trim()

        if (cleanSsid.isEmpty()) {
            Log.w(TAG, "SSID is empty")
            return false
        }

        if (password.length < 8) {
            Log.w(TAG, "WPA2 password must contain at least 8 characters")
            return false
        }

        return try {
            val configuration = WifiConfiguration().apply {
                SSID = "\"$cleanSsid\""
                preSharedKey = "\"$password\""
            }

            var networkId = wifiManager.addNetwork(configuration)

            if (networkId == -1) {
                val existingId = findExistingNetworkId(
                    wifiManager,
                    cleanSsid
                )

                if (existingId == -1) {
                    Log.w(TAG, "Unable to add Wi‑Fi network")
                    return false
                }

                networkId = existingId

                val updated = wifiManager.updateNetwork(configuration)

                if (updated == -1) {
                    Log.w(TAG, "Unable to update existing Wi‑Fi network")
                    return false
                }
            }

            wifiManager.disconnect()
            wifiManager.enableNetwork(networkId, true)
            wifiManager.saveConfiguration()
            wifiManager.reconnect()

            waitForConnection(
                wifiManager = wifiManager,
                ssid = cleanSsid
            )
        } catch (error: Exception) {
            Log.e(TAG, "Wi‑Fi provisioning failed", error)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun waitForConnection(
        wifiManager: WifiManager,
        ssid: String
    ): Boolean {
        val expectedSsid = "\"$ssid\""
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            val info = wifiManager.connectionInfo
            val currentSsid = info?.ssid

            if (currentSsid == expectedSsid) {
                return true
            }

            Thread.sleep(CONNECT_POLL_INTERVAL_MS)
        }

        Log.w(TAG, "Wi‑Fi connection timed out")
        return false
    }

    @Suppress("DEPRECATION")
    private fun findExistingNetworkId(
        wifiManager: WifiManager,
        ssid: String
    ): Int {
        val configuredNetworks = wifiManager.configuredNetworks
            ?: return -1

        return configuredNetworks
            .firstOrNull {
                it.SSID == "\"$ssid\""
            }
            ?.networkId
            ?: -1
    }
}
