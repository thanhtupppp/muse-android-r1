package vn.tutu.muser1

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.util.Log

object WifiProvisioner {
    private const val TAG = "MuseR1"
    private const val CONNECT_TIMEOUT_MS = 30_000L
    private const val CONNECT_POLL_INTERVAL_MS = 1_000L
    private const val RADIO_SWITCH_TIMEOUT_MS = 20_000L
    private const val WIFI_AP_STATE_ENABLED = 13
    private const val WIFI_AP_STATE_ENABLING = 12

    @Volatile
    var lastFailureReason: String = "Chưa có thông tin lỗi"
        private set

    private fun fail(reason: String): Boolean {
        lastFailureReason = reason
        Log.e(TAG, reason)
        return false
    }

    fun connect(context: Context, ssid: String, password: String): Boolean {
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (manager == null) {
            return fail("Không lấy được WifiManager")
        }
        return connectAndWait(manager, ssid, password)
    }

    fun connect(wifiManager: WifiManager, ssid: String, password: String): Boolean =
        connectAndWait(wifiManager, ssid, password)

    @Suppress("DEPRECATION")
    fun connectAndWait(wifiManager: WifiManager, ssid: String, password: String): Boolean {
        lastFailureReason = ""
        val cleanSsid = ssid.trim()
        Log.i(TAG, "Wi-Fi provisioning requested for SSID='$cleanSsid' (password omitted)")
        if (cleanSsid.isEmpty()) {
            return fail("SSID bị trống")
        }
        if (password.length < 8) {
            return fail("Mật khẩu WPA2 phải có ít nhất 8 ký tự")
        }
        return try {
            // On single-radio R1 hardware, SoftAP and STA cannot run together.
            // Stop the hotspot first, then wait for the client Wi-Fi radio to become enabled.
            if (!wifiManager.isWifiEnabled) {
                val apState = getWifiApState(wifiManager)
                Log.i(TAG, "Wi-Fi client disabled; current SoftAP state=$apState")
                if (apState == WIFI_AP_STATE_ENABLED || apState == WIFI_AP_STATE_ENABLING) {
                    if (!setWifiApEnabled(wifiManager, false)) {
                        return fail("Wi-Fi client đang tắt và không thể tắt SoftAP qua WifiManager; firmware có thể yêu cầu quyền hệ thống")
                    }
                    Log.i(TAG, "Đã yêu cầu tắt SoftAP; chờ chuyển radio sang Wi-Fi client")
                }

                val radioDeadline = System.currentTimeMillis() + RADIO_SWITCH_TIMEOUT_MS
                while (System.currentTimeMillis() < radioDeadline && !wifiManager.isWifiEnabled) {
                    Thread.sleep(CONNECT_POLL_INTERVAL_MS)
                }

                if (!wifiManager.isWifiEnabled) {
                    Log.i(TAG, "SoftAP state sau khi yêu cầu tắt=${getWifiApState(wifiManager)}; yêu cầu bật Wi-Fi client")
                    if (!wifiManager.setWifiEnabled(true)) {
                        return fail("Không bật được Wi-Fi client sau khi tắt SoftAP (setWifiEnabled=false); wifiState=${wifiManager.wifiState}")
                    }
                    val enableDeadline = System.currentTimeMillis() + RADIO_SWITCH_TIMEOUT_MS
                    while (System.currentTimeMillis() < enableDeadline && !wifiManager.isWifiEnabled) {
                        Thread.sleep(CONNECT_POLL_INTERVAL_MS)
                    }
                }

                if (!wifiManager.isWifiEnabled) {
                    return fail("Wi-Fi client vẫn tắt sau khi chuyển chế độ; wifiState=${wifiManager.wifiState}, SoftAP state=${getWifiApState(wifiManager)}")
                }
            }

            val configuration = WifiConfiguration().apply {
                SSID = "\"$cleanSsid\""
                preSharedKey = "\"$password\""

                // Explicit WPA/WPA2-PSK configuration for legacy Android 5.1.
                allowedKeyManagement.clear()
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                allowedAuthAlgorithms.clear()
                allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                allowedPairwiseCiphers.clear()
                allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
                allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
                allowedGroupCiphers.clear()
                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP)
                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
                allowedProtocols.clear()
                allowedProtocols.set(WifiConfiguration.Protocol.WPA)
                allowedProtocols.set(WifiConfiguration.Protocol.RSN)
                hiddenSSID = false
                status = WifiConfiguration.Status.ENABLED
            }
            var networkId = wifiManager.addNetwork(configuration)
            if (networkId == -1) {
                Log.w(TAG, "addNetwork returned -1; searching configured networks")
                val existingId = findExistingNetworkId(wifiManager, cleanSsid)
                if (existingId == -1) {
                    val configured = try { wifiManager.configuredNetworks } catch (error: Exception) {
                        Log.e(TAG, "Reading configuredNetworks failed", error)
                        null
                    }
                    val configuredSummary = when {
                        configured == null -> "null/không được firmware cung cấp"
                        configured.isEmpty() -> "rỗng"
                        else -> configured.joinToString(limit = 8) { it.SSID ?: "<SSID-null>" }
                    }
                    val wifiState = try { wifiManager.wifiState.toString() } catch (_: Exception) { "unknown" }
                    return fail("addNetwork=-1; configuredNetworks=$configuredSummary; wifiState=$wifiState; kiểm tra SoftAP/firmware Wi-Fi")
                }
                configuration.networkId = existingId
                networkId = wifiManager.updateNetwork(configuration)
                if (networkId == -1) {
                    return fail("Android từ chối cập nhật mạng đã lưu (updateNetwork=-1)")
                }
                Log.i(TAG, "Updated existing Wi-Fi networkId=$networkId")
            } else {
                Log.i(TAG, "Added Wi-Fi networkId=$networkId")
            }

            if (!wifiManager.enableNetwork(networkId, true)) {
                return fail("Android từ chối bật mạng Wi-Fi (enableNetwork=false)")
            }
            Log.i(TAG, "enableNetwork accepted for networkId=$networkId")

            val saved = wifiManager.saveConfiguration()
            Log.i(TAG, "saveConfiguration result=$saved")
            val reconnect = wifiManager.reconnect()
            Log.i(TAG, "reconnect result=$reconnect")

            waitForConnection(wifiManager, cleanSsid)
        } catch (error: Exception) {
            Log.e(TAG, "Wi-Fi provisioning exception", error)
            fail("Ngoại lệ ${error.javaClass.simpleName}: ${error.message ?: "không có chi tiết"}")
        }
    }

    @Suppress("DEPRECATION")
    private fun getWifiApState(wifiManager: WifiManager): Int? {
        return try {
            val method = wifiManager.javaClass.getMethod("getWifiApState")
            (method.invoke(wifiManager) as? Int)
        } catch (error: Exception) {
            Log.w(TAG, "Không đọc được SoftAP state qua API firmware: ${error.message}")
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun setWifiApEnabled(wifiManager: WifiManager, enabled: Boolean): Boolean {
        return try {
            val method = wifiManager.javaClass.getMethod(
                "setWifiApEnabled",
                WifiConfiguration::class.java,
                java.lang.Boolean.TYPE
            )
            (method.invoke(wifiManager, null, enabled) as? Boolean) ?: false
        } catch (error: Exception) {
            Log.e(TAG, "Không thể gọi WifiManager.setWifiApEnabled($enabled)", error)
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
        return fail("Hết 30 giây chờ Wi-Fi. Mong đợi=$expected, hiện tại=${info?.ssid}, trạng thái=${info?.supplicantState}, networkId=${info?.networkId}")
    }

    @Suppress("DEPRECATION")
    private fun findExistingNetworkId(wifiManager: WifiManager, ssid: String): Int {
        val networks = wifiManager.configuredNetworks ?: return -1
        return networks.firstOrNull { it.SSID == "\"$ssid\"" }?.networkId ?: -1
    }
}
