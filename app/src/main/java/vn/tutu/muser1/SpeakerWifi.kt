package vn.tutu.muser1

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Tiện ích Wi-Fi phía loa dùng chung cho BLE (app Muse), HTTP :8080
 * (Muse Mobile) và các đường cấu hình khác.
 *
 * Trước đây logic quét nằm riêng trong MuseBleServer phục vụ app Muse,
 * còn Mobile phải tự quét bằng điện thoại — kết quả quét của điện thoại có
 * thể cũ (stale) và không phản ánh loa thực sự thấy mạng nào. Nay loa là
 * nguồn quét chính: [scan] trả danh sách theo góc nhìn của loa.
 *
 * Bảo mật theo chuẩn của [parseSecurity]: mạng WPA3 thuần (chỉ SAE) và
 * EAP được gọi đúng tên thay vì lặng lẽ map về WPA2, để phía app cảnh báo
 * trước khi provision chắc chắn thất bại trên supplicant Android 5.1.
 */
object SpeakerWifi {
    private const val TAG = "MuseR1"
    private const val SCAN_WAIT_MS = 2_500L
    private const val MAX_SCAN_RESULTS = 20

    data class NetworkEntry(
        val ssid: String,
        val rssi: Int,
        /** OPEN | WEP | WPA2 | WPA3 | EAP */
        val security: String,
        val has24Ghz: Boolean,
        val has5Ghz: Boolean,
        val saved: Boolean,
        val connected: Boolean
    )

    data class SavedEntry(
        val ssid: String,
        val networkId: Int,
        val connected: Boolean
    )

    /** Kết quả quên một mạng đã lưu. */
    sealed class ForgetResult {
        data class Success(val wasConnected: Boolean) : ForgetResult()
        data class Failure(val code: String) : ForgetResult()
    }

    fun wifiManager(context: Context): WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun unquoteSsid(raw: String?): String =
        raw?.trim()?.removeSurrounding("\"")?.trimEnd('\r', '\n').orEmpty()

    fun currentSsid(context: Context): String {
        val manager = wifiManager(context) ?: return ""
        val ssid = unquoteSsid(runCatching { manager.connectionInfo?.ssid }.getOrNull())
        return ssid.takeIf { it.isNotEmpty() && it != "<unknown ssid>" } ?: ""
    }

    fun currentIp(context: Context): String {
        val manager = wifiManager(context) ?: return ""
        val ipInt = runCatching { manager.dhcpInfo?.ipAddress }.getOrNull() ?: 0
        if (ipInt == 0) return ""
        return "${ipInt and 0xff}.${(ipInt shr 8) and 0xff}.${(ipInt shr 16) and 0xff}.${(ipInt shr 24) and 0xff}"
    }

    /**
     * Phân loại bảo mật từ chuỗi capabilities của một BSSID. Thuần dữ
     * liệu (không chạm Android API) để kiểm thử được bằng unit test JVM.
     * Quy tắc khớp với WifiScanner phía Mobile.
     */
    fun parseSecurity(capabilities: String?): String {
        if (capabilities.isNullOrEmpty()) return "OPEN"
        val caps = capabilities.uppercase()
        val hasSae = caps.contains("SAE")
        val hasPsk = caps.contains("PSK")
        return when {
            caps.contains("WEP") -> "WEP"
            hasSae && !hasPsk -> "WPA3"
            caps.contains("EAP") && !hasPsk -> "EAP"
            caps.contains("WPA") || caps.contains("RSN") || hasSae -> "WPA2"
            else -> "OPEN"
        }
    }

    fun is24Ghz(frequencyMhz: Int): Boolean = frequencyMhz in 2400..2500

    fun is5GhzOrAbove(frequencyMhz: Int): Boolean = frequencyMhz >= 4900

    /**
     * Quét Wi-Fi từ chính loa: kích hoạt một lượt quét mới, chờ kết quả,
     * gộp theo SSID (AP mạnh nhất làm đại diện, thông tin băng tần lấy từ
     * toàn bộ BSSID), đánh dấu mạng đã lưu / đang kết nối.
     */
    @Suppress("DEPRECATION")
    fun scan(context: Context): List<NetworkEntry> {
        val manager = wifiManager(context) ?: return emptyList()
        if (!runCatching { manager.isWifiEnabled }.getOrDefault(false)) {
            return emptyList()
        }

        val started = runCatching { manager.startScan() }.getOrDefault(false)
        if (started) {
            try {
                Thread.sleep(SCAN_WAIT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return emptyList()
            }
        }

        val results = runCatching { manager.scanResults }.getOrNull().orEmpty()
        val savedSsids = savedNetworks(context).map { it.ssid }.toSet()
        val connectedSsid = currentSsid(context)
        val connectedRssi = runCatching { manager.connectionInfo?.rssi }.getOrNull() ?: -127

        data class Acc(
            var rssi: Int,
            var security: String,
            var has24: Boolean,
            var has5: Boolean
        )

        val bySsid = linkedMapOf<String, Acc>()
        for (scan in results) {
            val ssid = scan.SSID?.trim().orEmpty()
            if (ssid.isEmpty()) continue
            val security = parseSecurity(scan.capabilities)
            val acc = bySsid.getOrPut(ssid) {
                Acc(scan.level, security, is24Ghz(scan.frequency), is5GhzOrAbove(scan.frequency))
            }
            if (scan.level > acc.rssi) {
                acc.rssi = scan.level
                acc.security = security
            }
            acc.has24 = acc.has24 || is24Ghz(scan.frequency)
            acc.has5 = acc.has5 || is5GhzOrAbove(scan.frequency)
        }

        // Mạng đang kết nối có thể không nằm trong kết quả quét (đang nối
        // qua cấu hình đã lưu ở xa tầm quét mới) — vẫn phải có mặt để app
        // hiển thị đúng trạng thái.
        if (connectedSsid.isNotEmpty() && connectedSsid !in bySsid) {
            bySsid[connectedSsid] = Acc(connectedRssi, "WPA2", has24 = true, has5 = false)
        }

        return bySsid.entries
            .map { (ssid, acc) ->
                NetworkEntry(
                    ssid = ssid,
                    rssi = acc.rssi,
                    security = acc.security,
                    has24Ghz = acc.has24,
                    has5Ghz = acc.has5,
                    saved = ssid in savedSsids,
                    connected = ssid == connectedSsid
                )
            }
            .sortedByDescending { it.rssi }
            .take(MAX_SCAN_RESULTS)
    }

    /** Danh sách mạng đã lưu trong cấu hình Wi-Fi của loa. */
    @Suppress("DEPRECATION")
    fun savedNetworks(context: Context): List<SavedEntry> {
        val manager = wifiManager(context) ?: return emptyList()
        val connectedId = runCatching { manager.connectionInfo?.networkId }.getOrNull() ?: -1
        val configured = runCatching { manager.configuredNetworks }.getOrNull().orEmpty()
        return configured.mapNotNull { config ->
            val ssid = unquoteSsid(config.SSID)
            if (ssid.isEmpty()) return@mapNotNull null
            SavedEntry(
                ssid = ssid,
                networkId = config.networkId,
                connected = config.networkId == connectedId && connectedId != -1
            )
        }
    }

    /**
     * Quên (xoá cấu hình) một mạng đã lưu. Nếu đang kết nối chính mạng
     * này, việc xoá kèm ngắt kết nối — phía gọi nên lên lịch sau khi đã
     * trả lời client (xem endpoint /wifi-forget).
     */
    @Suppress("DEPRECATION")
    fun forget(context: Context, ssid: String): ForgetResult {
        val cleanSsid = ssid.trimEnd('\r', '\n').trim()
        if (cleanSsid.isEmpty()) return ForgetResult.Failure("EMPTY_SSID")
        val manager = wifiManager(context) ?: return ForgetResult.Failure("NO_WIFI_MANAGER")

        val targets = savedNetworks(context).filter { it.ssid == cleanSsid }
        if (targets.isEmpty()) return ForgetResult.Failure("NOT_SAVED")

        val wasConnected = targets.any { it.connected }
        if (wasConnected) {
            runCatching { manager.disconnect() }
        }
        var removed = 0
        for (target in targets) {
            if (runCatching { manager.removeNetwork(target.networkId) }.getOrDefault(false)) {
                removed++
            }
        }
        runCatching { manager.saveConfiguration() }
        Log.i(TAG, "Đã quên mạng Wi-Fi đã lưu '$cleanSsid' (removed=$removed, wasConnected=$wasConnected)")
        return if (removed > 0) {
            ForgetResult.Success(wasConnected)
        } else {
            ForgetResult.Failure("REMOVE_FAILED")
        }
    }
}
