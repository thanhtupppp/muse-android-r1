package vn.tutu.muser1

import android.content.Context
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.util.Log

object WifiProvisioner {
    private const val TAG = "MuseR1"
    private const val CONNECT_TIMEOUT_MS = 35_000L
    private const val SWITCH_TIMEOUT_MS = 20_000L
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

    /**
     * Bọc giá trị cho WifiConfiguration đúng định dạng: đặt trong dấu ngoặc
     * kép và escape `\` + `"` bên trong. Trước đây chỉ nối chuỗi thô nên
     * SSID/mật khẩu chứa hai ký tự này (hoàn toàn hợp lệ trong WPA) làm hỏng
     * cấu hình và kết nối thất bại khó hiểu.
     */
    private fun quoteWifiValue(raw: String): String {
        val escaped = raw.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    fun connect(context: Context, ssid: String, password: String): ProvisionResult =
        synchronized(lock) {
            val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return ProvisionResult.Failure("NO_WIFI_MANAGER")
            val result = connectAndWait(manager, ssid, password)
            if (result is ProvisionResult.Failure && result.apTouched) {
                restoreAp(manager)
            }
            recordOutcome(context, ssid, result, action = null)
            result
        }

    /**
     * Chuyển sang một mạng ĐÃ LƯU (không cần mật khẩu) — đường "Dùng" của
     * mục quản lý mạng đã lưu trong Mobile. Thất bại thì cố quay về mạng
     * trước đó để loa không mất kết nối oan.
     */
    fun switchToSaved(context: Context, ssid: String): ProvisionResult =
        synchronized(lock) {
            val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return ProvisionResult.Failure("NO_WIFI_MANAGER")
            val result = switchAndWait(manager, ssid.trimEnd('\r', '\n').trim())
            recordOutcome(context, ssid, result, action = "wifi_use")
            result
        }

    // Ghi lại kết quả để chẩn đoán từ xa: đường HTTP trả 200 ngay khi
    // mới LÊN LỊCH kết nối nên app không bao giờ thấy mã lỗi thật
    // (CONNECT_TIMEOUT, SWITCH_TIMEOUT...) — trước đây chúng chỉ tồn tại
    // trong logcat của loa. /status mục wifi_provision.
    private fun recordOutcome(
        context: Context,
        ssid: String,
        result: ProvisionResult,
        action: String?
    ) {
        runCatching {
            val record = org.json.JSONObject()
                .put("ssid", ssid.trimEnd('\r', '\n'))
                .put("at_ms", System.currentTimeMillis())
            if (action != null) record.put("action", action)
            when (result) {
                is ProvisionResult.Success -> record.put("ok", true).put("ip", result.ip)
                is ProvisionResult.Failure -> record.put("ok", false).put("code", result.code)
            }
            MusePreferences.setLastWifiProvision(context, record.toString())
        }
    }

    @Suppress("DEPRECATION")
    private fun switchAndWait(wifiManager: WifiManager, cleanSsid: String): ProvisionResult {
        if (cleanSsid.isEmpty()) {
            return ProvisionResult.Failure("EMPTY_SSID")
        }
        return try {
            if (!wifiManager.isWifiEnabled) {
                return ProvisionResult.Failure("WIFI_DISABLED")
            }
            val targetId = findExistingNetworkId(wifiManager, cleanSsid)
            if (targetId == -1) {
                return ProvisionResult.Failure("NOT_SAVED")
            }
            val previousId = runCatching { wifiManager.connectionInfo?.networkId }.getOrNull() ?: -1
            if (previousId == targetId) {
                val ipInt = runCatching { wifiManager.dhcpInfo?.ipAddress }.getOrNull() ?: 0
                if (ipInt != 0) {
                    return ProvisionResult.Success(ipToString(ipInt))
                }
            }

            Log.i(TAG, "Chuyển sang mạng Wi-Fi đã lưu '$cleanSsid' (networkId=$targetId)")
            wifiManager.disconnect()
            if (!wifiManager.enableNetwork(targetId, true)) {
                return ProvisionResult.Failure("ENABLE_NETWORK_FAILED")
            }
            wifiManager.saveConfiguration()
            wifiManager.reconnect()

            val ip = waitForConnection(wifiManager, cleanSsid, SWITCH_TIMEOUT_MS)
            if (ip != null) {
                ProvisionResult.Success(ip)
            } else {
                if (previousId != -1 && previousId != targetId) {
                    Log.w(TAG, "Chuyển mạng thất bại; cố quay về mạng trước (networkId=$previousId)")
                    runCatching {
                        wifiManager.enableNetwork(previousId, true)
                        wifiManager.reconnect()
                    }
                }
                ProvisionResult.Failure("SWITCH_TIMEOUT")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ProvisionResult.Failure("INTERRUPTED")
        } catch (error: Exception) {
            Log.e(TAG, "Chuyển mạng Wi-Fi exception", error)
            ProvisionResult.Failure("EXCEPTION")
        }
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
                    Log.i(TAG, "Đã gửi yêu cầu tắt SoftAP; chờ radio hoàn tất tắt hotspot")

                    val apDeadline = System.currentTimeMillis() + 8_000L
                    while (System.currentTimeMillis() < apDeadline) {
                        val state = getWifiApState(wifiManager)
                        if (state == 11 || state == null) break // WIFI_AP_STATE_DISABLED = 11
                        Thread.sleep(300)
                    }
                }

                if (!wifiManager.isWifiEnabled) {
                    Log.i(TAG, "SoftAP đã tắt; đang bật Wi-Fi client (STA)")
                    if (!wifiManager.setWifiEnabled(true)) {
                        return ProvisionResult.Failure("WIFI_ENABLE_FAILED", apWasDisabledByUs)
                    }
                    val enableDeadline = System.currentTimeMillis() + 10_000L
                    while (System.currentTimeMillis() < enableDeadline && !wifiManager.isWifiEnabled) {
                        Thread.sleep(500)
                    }
                }

                if (!wifiManager.isWifiEnabled) {
                    return ProvisionResult.Failure("WIFI_RADIO_STILL_DISABLED", apWasDisabledByUs)
                }
            }

            val configuration = WifiConfiguration().apply {
                SSID = quoteWifiValue(cleanSsid)

                if (password.isEmpty()) {
                    allowedKeyManagement.clear()
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                } else {
                    val isHexPsk = password.length == 64 && password.all {
                        it.isDigit() || it in 'a'..'f' || it in 'A'..'F'
                    }
                    preSharedKey = if (isHexPsk) password else quoteWifiValue(password)

                    allowedKeyManagement.clear()
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                    allowedProtocols.clear()
                    allowedProtocols.set(WifiConfiguration.Protocol.WPA)
                    allowedProtocols.set(WifiConfiguration.Protocol.RSN)
                }

                hiddenSSID = false
                status = WifiConfiguration.Status.ENABLED
            }

            // Giữ cấu hình cũ của SSID này làm phương án dự phòng và CHỈ xoá
            // sau khi cấu hình mới kết nối thành công. Trước đây xoá trước nên
            // provision sai một lần (nhầm mật khẩu, AP chậm...) là mất luôn
            // cấu hình đang chạy, loa có thể rớt mạng hẳn.
            val existingId = findExistingNetworkId(wifiManager, cleanSsid)

            val networkId = wifiManager.addNetwork(configuration)
            if (networkId == -1) {
                return ProvisionResult.Failure("ADD_NETWORK_FAILED", apWasDisabledByUs)
            }

            Log.i(TAG, "Wi-Fi networkId=$networkId đã thêm vào hệ thống")

            // addNetwork có thể gộp vào cấu hình cũ khi SSID trùng (trả về
            // chính existingId): khi đó cấu hình cũ đã bị ghi đè tại chỗ,
            // không còn bản sao để khôi phục và tuyệt đối không được
            // removeNetwork(id này) ở các nhánh lỗi bên dưới.
            val mergedWithExisting = existingId != -1 && networkId == existingId

            if (!wifiManager.enableNetwork(networkId, true)) {
                if (!mergedWithExisting) {
                    wifiManager.removeNetwork(networkId)
                }
                return ProvisionResult.Failure("ENABLE_NETWORK_FAILED", apWasDisabledByUs)
            }

            wifiManager.saveConfiguration()
            wifiManager.reconnect()

            val ip = waitForConnection(wifiManager, cleanSsid)
            if (ip != null) {
                if (existingId != -1 && !mergedWithExisting) {
                    Log.i(TAG, "Kết nối mới ổn định; xoá cấu hình cũ existingId=$existingId")
                    wifiManager.removeNetwork(existingId)
                    wifiManager.saveConfiguration()
                }
                ProvisionResult.Success(ip)
            } else {
                Log.w(TAG, "Kết nối thất bại, dọn dẹp networkId=$networkId và giữ lại cấu hình cũ")
                if (!mergedWithExisting) {
                    wifiManager.removeNetwork(networkId)
                    wifiManager.saveConfiguration()
                }
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
    private fun waitForConnection(
        wifiManager: WifiManager,
        ssid: String,
        timeoutMs: Long = CONNECT_TIMEOUT_MS
    ): String? {
        val expected = quoteWifiValue(ssid)
        val deadline = System.currentTimeMillis() + timeoutMs

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
        val quoted = quoteWifiValue(ssid)
        return networks.firstOrNull { it.SSID == quoted || it.SSID == "\"$ssid\"" }?.networkId ?: -1
    }
}
