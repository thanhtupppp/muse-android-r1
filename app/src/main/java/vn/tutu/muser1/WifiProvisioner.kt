package vn.tutu.muser1

import android.content.Context
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.util.Log

object WifiProvisioner {
    private const val TAG = "MuseR1"
    private const val CONNECT_TIMEOUT_MS = 35_000L
    private const val CONNECT_POLL_INTERVAL_MS = 1_000L
    private const val RADIO_SWITCH_TIMEOUT_MS = 20_000L
    private const val WIFI_AP_STATE_ENABLED = 13
    private const val WIFI_AP_STATE_ENABLING = 12

    private val lock = Any()

    sealed class ProvisionResult {
        data class Success(val ip: String) : ProvisionResult()
        data class Failure(val code: String, val apTouched: Boolean = false) : ProvisionResult()
    }

    private fun isValidPassword(p: String): Boolean =
        p.isEmpty() || p.length in 8..63 ||
            (p.length == 64 && p.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' })

    fun connect(context: Context, ssid: String, password: String): ProvisionResult =
        synchronized(lock) {
            val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return ProvisionResult.Failure("NO_WIFI_MANAGER")
            val result = connectAndWait(manager, ssid, password)
            if (result is ProvisionResult.Failure && result.apTouched) {
                restoreAp(manager)
            }
            result
        }

    fun connect(wifiManager: WifiManager, ssid: String, password: String): ProvisionResult =
        synchronized(lock) {
            val result = connectAndWait(wifiManager, ssid, password)
            if (result is ProvisionResult.Failure && result.apTouched) {
                restoreAp(wifiManager)
            }
            result
        }

    @Suppress("DEPRECATION")
    private fun connectAndWait(
        wifiManager: WifiManager,
        ssid: String,
        password: String
    ): ProvisionResult {
        val cleanSsid = ssid.trimEnd('\r', '\n')
        Log.i(TAG, "Wi-Fi provisioning requested for SSID='$cleanSsid' (password length=${password.length})")

        if (cleanSsid.isEmpty()) {
            return ProvisionResult.Failure("EMPTY_SSID")
        }

        if (!isValidPassword(password)) {
            Log.e(TAG, "Mật khẩu không hợp lệ (cần rỗng, 8-63 ký tự hoặc 64 hex): độ dài=${password.length}")
            return ProvisionResult.Failure("INVALID_PASSWORD")
        }

        var apWasDisabledByUs = false

        return try {
            // Trên phần cứng Phicomm R1 radio đơn, SoftAP và STA Wi-Fi Client không thể chạy cùng lúc.
            // Phải tắt SoftAP trước, sau đó chờ radio chuyển sang chế độ client.
            if (!wifiManager.isWifiEnabled) {
                val apState = getWifiApState(wifiManager)
                Log.i(TAG, "Wi-Fi client đang tắt; SoftAP state=$apState")
                if (apState == WIFI_AP_STATE_ENABLED || apState == WIFI_AP_STATE_ENABLING) {
                    if (!setWifiApEnabled(wifiManager, false)) {
                        return ProvisionResult.Failure("SOFTAP_DISABLE_FAILED")
                    }
                    apWasDisabledByUs = true
                    Log.i(TAG, "Đã gửi yêu cầu tắt SoftAP; chờ chuyển radio sang Wi-Fi client")
                }

                val radioDeadline = System.currentTimeMillis() + RADIO_SWITCH_TIMEOUT_MS
                while (System.currentTimeMillis() < radioDeadline && !wifiManager.isWifiEnabled) {
                    Thread.sleep(CONNECT_POLL_INTERVAL_MS)
                }

                if (!wifiManager.isWifiEnabled) {
                    Log.i(TAG, "SoftAP state sau khi yêu cầu tắt=${getWifiApState(wifiManager)}; bật Wi-Fi client")
                    if (!wifiManager.setWifiEnabled(true)) {
                        return ProvisionResult.Failure("WIFI_ENABLE_FAILED", apWasDisabledByUs)
                    }
                    val enableDeadline = System.currentTimeMillis() + RADIO_SWITCH_TIMEOUT_MS
                    while (System.currentTimeMillis() < enableDeadline && !wifiManager.isWifiEnabled) {
                        Thread.sleep(CONNECT_POLL_INTERVAL_MS)
                    }
                }

                if (!wifiManager.isWifiEnabled) {
                    return ProvisionResult.Failure("WIFI_RADIO_STILL_DISABLED", apWasDisabledByUs)
                }
            }

            val configuration = WifiConfiguration().apply {
                SSID = "\"$cleanSsid\""

                if (password.isEmpty()) {
                    allowedKeyManagement.clear()
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                } else {
                    val isHexPsk = password.length == 64 && password.all {
                        it.isDigit() || it in 'a'..'f' || it in 'A'..'F'
                    }
                    preSharedKey = if (isHexPsk) password else "\"$password\""

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
                }

                hiddenSSID = false
                status = WifiConfiguration.Status.ENABLED
            }

            // Xóa cấu hình cũ của SSID này trước để tránh xung đột quyền/UID
            val existingId = findExistingNetworkId(wifiManager, cleanSsid)
            if (existingId != -1) {
                Log.i(TAG, "Xóa mạng cũ existingId=$existingId trước khi thêm cấu hình mới")
                wifiManager.removeNetwork(existingId)
            }

            val networkId = wifiManager.addNetwork(configuration)
            if (networkId == -1) {
                return ProvisionResult.Failure("ADD_NETWORK_FAILED", apWasDisabledByUs)
            }

            Log.i(TAG, "Wi-Fi networkId=$networkId đã thêm vào hệ thống")

            if (!wifiManager.enableNetwork(networkId, true)) {
                wifiManager.removeNetwork(networkId)
                return ProvisionResult.Failure("ENABLE_NETWORK_FAILED", apWasDisabledByUs)
            }

            wifiManager.saveConfiguration()

            val ip = waitForConnection(wifiManager, cleanSsid)
            if (ip != null) {
                ProvisionResult.Success(ip)
            } else {
                Log.w(TAG, "Kết nối thất bại, dọn dẹp networkId=$networkId khỏi danh sách lưu")
                wifiManager.removeNetwork(networkId)
                wifiManager.saveConfiguration()
                ProvisionResult.Failure("CONNECT_TIMEOUT", apWasDisabledByUs)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Wi-Fi provisioning bị ngắt", e)
            ProvisionResult.Failure("INTERRUPTED", apWasDisabledByUs)
        } catch (error: Exception) {
            Log.e(TAG, "Wi-Fi provisioning exception", error)
            ProvisionResult.Failure("EXCEPTION", apWasDisabledByUs)
        }
    }

    @Suppress("DEPRECATION")
    private fun waitForConnection(wifiManager: WifiManager, ssid: String): String? {
        val expected = "\"$ssid\""
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            val info = wifiManager.connectionInfo
            val actual = info?.ssid
            val ssidOk = actual == expected || actual == ssid
            val completed = info?.supplicantState == SupplicantState.COMPLETED
            val ipInt = wifiManager.dhcpInfo?.ipAddress ?: 0
            val hasIp = ipInt != 0

            Log.d(TAG, "Wi-Fi poll: actual='$actual', completed=$completed, hasIp=$hasIp ($ipInt)")

            if (ssidOk && completed && hasIp) {
                val ipString = ipToString(ipInt)
                Log.i(TAG, "Wi-Fi đã kết nối và nhận DHCP IP: $ipString (SSID='$ssid')")
                return ipString
            }

            Thread.sleep(CONNECT_POLL_INTERVAL_MS)
        }

        return null
    }

    private fun ipToString(ip: Int): String =
        "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}"

    private fun restoreAp(wifiManager: WifiManager) {
        Log.w(TAG, "Kết nối Wi-Fi thất bại, khôi phục lại SoftAP để người dùng có thể thử lại")
        runCatching {
            wifiManager.disconnect()
            wifiManager.setWifiEnabled(false)
            Thread.sleep(2_000)
            setWifiApEnabled(wifiManager, true)
        }.onFailure {
            Log.w(TAG, "Khôi phục SoftAP thất bại", it)
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
    private fun findExistingNetworkId(wifiManager: WifiManager, ssid: String): Int {
        val networks = wifiManager.configuredNetworks ?: return -1
        return networks.firstOrNull { it.SSID == "\"$ssid\"" }?.networkId ?: -1
    }
}
