package vn.tutu.muser1

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.util.Log

object WifiProvisioner {
    private const val TAG = "MuseR1"
    private const val CONNECT_TIMEOUT_MS = 30_000L
    private const val CONNECT_POLL_INTERVAL_MS = 1_000L

    fun connect(context: Context, ssid: String, password: String): Boolean {
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (manager == null) {
            Log.e(TAG, "Wi-Fi provisioning failed: WifiManager unavailable")
            return false
        }
        return connectAndWait(manager, ssid, password)
    }

    fun connect(wifiManager: WifiManager, ssid: String, password: String): Boolean =
        connectAndWait(wifiManager, ssid, password)

    @Suppress("DEPRECATION")
    fun connectAndWait(wifiManager: WifiManager, ssid: String, password: String): Boolean {
        val cleanSsid = ssid.trim()
        Log.i(TAG, "Wi-Fi provisioning requested for SSID='$cleanSsid' (password omitted)")
        if (cleanSsid.isEmpty()) {
            Log.e(TAG, "Wi-Fi provisioning rejected: SSID is empty")
            return false
        }
        if (password.length < 8) {
            Log.e(TAG, "Wi-Fi provisioning rejected: WPA2 password is shorter than 8 characters")
            return false
        }
        return try {
            if (!wifiManager.isWifiEnabled) {
                Log.i(TAG, "Wi-Fi is disabled; requesting enable")
                if (!wifiManager.setWifiEnabled(true)) {
                    Log.e(TAG, "Wi-Fi provisioning failed at setWifiEnabled")
                    return false
                }
            }

            val configuration = WifiConfiguration().apply {
                SSID = "\"$cleanSsid\""
                preSharedKey = "\"$password\""
            }
            var networkId = wifiManager.addNetwork(configuration)
            if (networkId == -1) {
                Log.w(TAG, "addNetwork returned -1; searching configured networks")
                val existingId = findExistingNetworkId(wifiManager, cleanSsid)
                if (existingId == -1) {
                    Log.e(TAG, "Wi-Fi provisioning failed at addNetwork: no existing SSID match")
                    return false
                }
                configuration.networkId = existingId
                networkId = wifiManager.updateNetwork(configuration)
                if (networkId == -1) {
                    Log.e(TAG, "Wi-Fi provisioning failed at updateNetwork for networkId=$existingId")
                    return false
                }
                Log.i(TAG, "Updated existing Wi-Fi networkId=$networkId")
            } else {
                Log.i(TAG, "Added Wi-Fi networkId=$networkId")
            }

            if (!wifiManager.enableNetwork(networkId, true)) {
                Log.e(TAG, "Wi-Fi provisioning failed at enableNetwork networkId=$networkId")
                return false
            }
            Log.i(TAG, "enableNetwork accepted for networkId=$networkId")

            val saved = wifiManager.saveConfiguration()
            Log.i(TAG, "saveConfiguration result=$saved")
            val reconnect = wifiManager.reconnect()
            Log.i(TAG, "reconnect result=$reconnect")

            waitForConnection(wifiManager, cleanSsid)
        } catch (error: Exception) {
            Log.e(TAG, "Wi-Fi provisioning threw exception: ${error.javaClass.simpleName}: ${error.message}", error)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun waitForConnection(wifiManager: WifiManager, ssid: String): Boolean {
        val expected = "\"$ssid\""
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val info = wifiManager.connectionInfo
            val actual = info?.ssid
            val state = wifiManager.dhcpInfo
            Log.d(TAG, "Wi-Fi connection check: ssid=$actual, networkId=${info?.networkId}, ipAddress=${state?.ipAddress}")
            if (actual == expected || actual == ssid) {
                Log.i(TAG, "Wi-Fi connected successfully: SSID='$ssid'")
                return true
            }
            Thread.sleep(CONNECT_POLL_INTERVAL_MS)
        }
        val info = wifiManager.connectionInfo
        Log.e(TAG, "Wi-Fi connection timed out: expected='$expected', actual='${info?.ssid}', supplicantState=${info?.supplicantState}, networkId=${info?.networkId}")
        return false
    }

    @Suppress("DEPRECATION")
    private fun findExistingNetworkId(wifiManager: WifiManager, ssid: String): Int {
        val networks = wifiManager.configuredNetworks ?: return -1
        return networks.firstOrNull { it.SSID == "\"$ssid\"" }?.networkId ?: -1
    }
}
