package vn.tutu.musemobile.domain

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import vn.tutu.musemobile.data.BluetoothProvisioner
import vn.tutu.musemobile.data.R1ApiClient
import vn.tutu.musemobile.data.SecretRedactor
import vn.tutu.musemobile.data.R1DiscoveryClient
import vn.tutu.musemobile.data.R1Installer
import vn.tutu.musemobile.data.WifiScanner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProvisioningViewModel(application: Application) : AndroidViewModel(application) {

    private val apiClient = R1ApiClient(application)
    private val discoveryClient = R1DiscoveryClient()

    // Provision token của loa: loa sinh token này khi được cấu hình Muse lần
    // đầu và trả về trong response cấu hình; từ đó mọi lệnh gọi :8080 (status,
    // command, cấu hình lại) phải đính kèm header x-muse-token. Mobile lưu
    // token bền ở đây để các lần mở app sau vẫn điều khiển được loa.
    private val mobilePrefs =
        application.getSharedPreferences("musemobile_prefs", Context.MODE_PRIVATE)

    private var savedProvisionToken: String
        get() = mobilePrefs.getString("provision_token", "").orEmpty()
        set(value) {
            mobilePrefs.edit().putString("provision_token", value).apply()
        }

    private fun authToken(): String? = savedProvisionToken.ifEmpty { null }

    private fun captureProvisionToken(response: JSONObject) {
        val token = response.optString("provision_token", "").trim()
        if (token.isNotEmpty()) {
            savedProvisionToken = token
        }
    }
    private val wifiScanner = WifiScanner(application)
    private val installer = R1Installer(application)
    private val bluetoothProvisioner = BluetoothProvisioner(application)

    var state by mutableStateOf<ProvisioningState>(ProvisioningState.Idle)
        private set

    var status by mutableStateOf("Nhập địa chỉ R1 rồi kiểm tra kết nối.")
        private set

    var isApkDownloaded by mutableStateOf(installer.hasLocalApk())
        private set

    var isInstalling by mutableStateOf(false)
        private set

    var installProgress by mutableStateOf<Float?>(null)
        private set

    var installStage by mutableStateOf("")
        private set

    var installLogs by mutableStateOf<List<String>>(emptyList())
        private set

    var diagnosticLogs by mutableStateOf<List<String>>(emptyList())
        private set

    var isCheckingR1 by mutableStateOf(false)
        private set

    var isDownloading by mutableStateOf(false)
        private set

    var downloadProgress by mutableStateOf<Int?>(null)
        private set

    fun hasLocalApk(): Boolean = installer.hasLocalApk()

    var wifiNetworks by mutableStateOf<List<WifiNetwork>>(emptyList())
        private set

    /** Danh sách wifiNetworks hiện tại do loa quét (true) hay điện thoại quét (false). */
    var wifiListFromSpeaker by mutableStateOf(false)
        private set

    /** Các mạng Wi-Fi đã lưu trên loa (GET /wifi-saved). */
    var savedWifiNetworks by mutableStateOf<List<SavedWifiNetwork>>(emptyList())
        private set

    /** SSID loa đang kết nối, theo /wifi-saved gần nhất. */
    var savedWifiCurrentSsid by mutableStateOf("")
        private set

    var isLoadingSavedWifi by mutableStateOf(false)
        private set

    /** Đang chờ loa xác nhận chuyển mạng (sau POST /wifi-use). */
    var isSwitchingWifi by mutableStateOf(false)
        private set

    var selectedNetwork by mutableStateOf<WifiNetwork?>(null)
        private set
    var currentSpeakerIp by mutableStateOf("192.168.2.10")
        private set
    val speakerIp: String get() = currentSpeakerIp

    var currentMuseTokenInput by mutableStateOf("")
        private set
    val museTokenInput: String get() = currentMuseTokenInput

    var useDirectVmConfig by mutableStateOf(true)
        private set

    var currentNoiseHost by mutableStateOf("hatch.metaaivm.com")
        private set

    var currentVmId by mutableStateOf("")
        private set

    var currentDeviceToken by mutableStateOf("")
        private set

    var isPairingMuse by mutableStateOf(false)
        private set

    var musePairStatus by mutableStateOf("")
        private set

    var isOpeningMusePairing by mutableStateOf(false)

    var isProbingVoice by mutableStateOf(false)
        private set

    /** Phase 1: đang gửi lệnh start/stop cuộc gọi voice lên loa. */
    var isTogglingVoiceCall by mutableStateOf(false)
        private set

    /** Phase 1: cuộc gọi voice đang hoạt động (theo block voice_call của /status). */
    var voiceCallActive by mutableStateOf(false)
        private set

    /** Phase 1: một dòng trạng thái cuộc gọi voice để hiện dưới nút gọi. */
    var voiceCallStatusText by mutableStateOf("")
        private set

    var musePairingModeStatus by mutableStateOf("")
        private set

    /** Tên BLE loa đang dùng (MuseGadget-xxxxxx), đọc từ /status. */
    var museBleName by mutableStateOf("")
        private set

    /** Số giây còn lại của cửa sổ pairing phát nhanh, đọc từ /status. */
    var musePairingWindowRemainingSec by mutableStateOf(0)
        private set

    var isMusePaired by mutableStateOf(false)
        private set

    var isMuseConnected by mutableStateOf(false)
        private set

    /** VM + host mà phiên Muse trên loa THỰC SỰ đang kết nối (từ /status). */
    var activeVmId by mutableStateOf("")
        private set

    var activeVmName by mutableStateOf("")
        private set

    var activeNoiseHost by mutableStateOf("")
        private set

    var speakerVolume by mutableStateOf(70)
        private set

    /** LED vòng: chỉ khả dụng khi /status báo led_probe.available. */
    var ledAvailable by mutableStateOf(false)
        private set

    var ledColor by mutableStateOf("#FF8C00")
        private set

    var ledBrightness by mutableStateOf(80)
        private set

    var ledOn by mutableStateOf(false)
        private set

    var commandResult by mutableStateOf("")
        private set

    private var password = ""
    private var bluetoothDevice: BluetoothDevice? = null

    private fun addInstallLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        installLogs = installLogs + "[$time] ${SecretRedactor.redact(msg)}"
    }

    fun getInstallLogsText(): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val sb = StringBuilder()
        sb.append("=== MUSE R1 INSTALLATION LOG ($dateStr) ===\n")
        sb.append("Địa chỉ loa: $speakerIp:5555\n")
        sb.append("Trạng thái: $installStage\n")
        sb.append("------------------------------------------\n")
        installLogs.forEach { sb.append(it).append("\n") }
        return SecretRedactor.redact(sb.toString())
    }

    @SuppressLint("MissingPermission")
    fun selectBluetoothDevice(device: BluetoothDevice) {
        bluetoothDevice = device
        status = "Đã chọn ${device.name ?: "Muse R1"}."
    }

    fun setSpeakerIp(value: String) {
        currentSpeakerIp = value.trim()
    }

    fun setMuseTokenInput(value: String) {
        currentMuseTokenInput = value.trim()
    }

    fun setDirectMode(enabled: Boolean) {
        useDirectVmConfig = enabled
    }

    fun setNoiseHost(value: String) {
        currentNoiseHost = value.trim()
    }

    fun setVmId(value: String) {
        currentVmId = value.trim()
    }

    fun setDeviceToken(value: String) {
        currentDeviceToken = value.trim()
    }

    fun configureMuseDirect(
        ip: String? = null,
        serverHost: String? = null,
        vmId: String? = null,
        deviceToken: String? = null
    ) {
        val targetIp = (ip ?: speakerIp).trim()
        val host = (serverHost ?: currentNoiseHost).trim().ifEmpty { "hatch.metaaivm.com" }
        val cleanVm = (vmId ?: currentVmId).trim()
        val cleanTok = (deviceToken ?: currentDeviceToken).trim()

        if (cleanVm.isEmpty()) {
            musePairStatus = "Vui lòng nhập VM ID"
            return
        }
        if (cleanTok.isEmpty()) {
            musePairStatus = "Vui lòng nhập Device Token"
            return
        }

        viewModelScope.launch {
            isPairingMuse = true
            isMuseConnected = false
            musePairStatus = "Đang gửi cấu hình Direct VM tới R1 ($targetIp:8080)..."
            apiClient.configureMuseDirect(
                speakerIp = targetIp,
                serverHost = host,
                vmId = cleanVm,
                deviceToken = cleanTok,
                token = authToken()
            ).fold(
                onSuccess = { res ->
                    captureProvisionToken(res)
                    isMusePaired = true
                    val devId = res.optString("device_id", "R1")
                    val savedHost = res.optString("noise_host", host)
                    val savedVm = res.optString("vm_id", cleanVm)
                    musePairStatus = "✅ Đã lưu Direct VM ($savedHost / $savedVm) cho $devId. Đang kết nối Noise XX..."
                    kotlinx.coroutines.delay(2500)
                    checkMuseStatus(targetIp)
                },
                onFailure = { err ->
                    musePairStatus = "❌ Lưu cấu hình thất bại: ${err.message}"
                }
            )
            isPairingMuse = false
        }
    }

    fun configureMuseToken(ip: String? = null, token: String? = null) {
        val targetIp = (ip ?: speakerIp).trim()
        val rawToken = (token ?: museTokenInput).trim()

        if (rawToken.isEmpty()) {
            musePairStatus = "Vui lòng nhập Muse SDK Token"
            return
        }

        if (!rawToken.startsWith("mgst_")) {
            musePairStatus = "Token không đúng định dạng! Phải bắt đầu bằng 'mgst_'"
            return
        }

        viewModelScope.launch {
            isPairingMuse = true
            isMuseConnected = false
            musePairStatus = "Đang gửi Muse Token tới R1 ($targetIp:8080)..."
            apiClient.configureMuse(speakerIp = targetIp, museToken = rawToken, token = authToken()).fold(
                onSuccess = { res ->
                    captureProvisionToken(res)
                    isMusePaired = true
                    val devId = res.optString("device_id", "R1")
                    musePairStatus = "✅ Đã ghép đôi thành công! Thiết bị: $devId"
                    kotlinx.coroutines.delay(2500)
                    checkMuseStatus(targetIp)
                },
                onFailure = { err ->
                    musePairStatus = "❌ Ghép đôi thất bại: ${err.message}"
                }
            )
            isPairingMuse = false
        }
    }

    /** Xoá cấu hình Muse trên loa (đổi tài khoản/token hoặc gỡ ghép đôi). */
    fun unconfigureMuse(ip: String? = null) {
        val targetIp = (ip ?: speakerIp).trim()
        viewModelScope.launch {
            isPairingMuse = true
            musePairStatus = "Đang xoá cấu hình Muse trên R1 ($targetIp:8080)..."
            apiClient.unconfigureMuse(speakerIp = targetIp, token = authToken()).fold(
                onSuccess = { res ->
                    captureProvisionToken(res)
                    isMusePaired = false
                    isMuseConnected = false
                    activeVmId = ""
                    activeVmName = ""
                    activeNoiseHost = ""
                    musePairStatus = "✅ Đã xoá cấu hình Muse. Loa đã ngắt kết nối cloud, sẵn sàng cấu hình lại."
                },
                onFailure = { err ->
                    musePairStatus = "❌ Xoá cấu hình thất bại: ${err.message}"
                }
            )
            isPairingMuse = false
        }
    }

    private fun updateBlePairingStatus(server: JSONObject?) {
        if (server == null) return
        val bleName = server.optString("ble_name", "").trim()
        if (bleName.isNotEmpty()) {
            museBleName = bleName
        }
        musePairingWindowRemainingSec =
            server.optInt("pairing_window_remaining_sec", musePairingWindowRemainingSec)
    }

    /**
     * Bấm để ép loa mở sẵn chế độ ghép nối cho app Muse chính thức: loa
     * phát nhanh BLE trong 10 phút. Hàm chỉ báo thành công khi /status xác
     * nhận advertising đã bật (không dừng ở mức "đã gửi lệnh"), vì khác biệt
     * đó chính là chỗ người dùng dễ tưởng loa đã sẵn sàng trong khi BLE còn
     * chưa lên.
     */
    fun openMusePairingMode(ip: String? = null) {
        val targetIp = (ip ?: speakerIp).trim()
        if (targetIp.isEmpty()) {
            musePairingModeStatus = "❌ Chưa có địa chỉ IP của loa."
            return
        }

        viewModelScope.launch {
            isOpeningMusePairing = true
            musePairingModeStatus = "Đang yêu cầu R1 bật BLE ghép nối..."
            apiClient.openPairingWindow(speakerIp = targetIp, token = authToken()).fold(
                onSuccess = { res ->
                    captureProvisionToken(res)
                    var server = res.optJSONObject("bluetooth")?.optJSONObject("server")
                    updateBlePairingStatus(server)

                    var advertising = server?.optBoolean("advertising", false) == true
                    var startError = server?.optString("last_start_error", "").orEmpty()
                    if (!advertising && startError.isEmpty()) {
                        musePairingModeStatus = "⏳ Loa đã nhận lệnh, đang chờ BLE phát sóng..."
                        for (attempt in 1..8) {
                            delay(1000)
                            val latestStatus = apiClient.status(
                                speakerIp = targetIp,
                                token = authToken()
                            ).getOrNull() ?: continue
                            server = latestStatus.optJSONObject("bluetooth")
                                ?.optJSONObject("server")
                            updateBlePairingStatus(server)
                            advertising = server?.optBoolean("advertising", false) == true
                            startError = server?.optString("last_start_error", "").orEmpty()
                            if (advertising || startError.isNotEmpty()) break
                        }
                    }

                    musePairingModeStatus = when {
                        advertising -> {
                            val displayName = museBleName.ifEmpty { "MuseGadget-xxxxxx" }
                            val minutes = if (musePairingWindowRemainingSec > 0) {
                                (musePairingWindowRemainingSec + 59) / 60
                            } else {
                                res.optInt("pairing_window_minutes", 10)
                            }
                            "✅ Loa đang phát BLE '$displayName' và chờ ghép nối trong khoảng $minutes phút. " +
                                "Mở app Muse → Cài đặt → Thiết bị → Chế độ nhà phát triển → Thêm thiết bị."
                        }
                        startError.isNotEmpty() ->
                            "❌ Loa chưa bật được BLE: $startError"
                        else ->
                            "⏳ Loa đã nhận lệnh mở ghép nối nhưng chưa xác nhận được BLE đang phát. " +
                                "Chờ vài giây rồi thử Thêm thiết bị trong app Muse; nếu chưa thấy loa, bấm nút này lại."
                    }
                },
                onFailure = { err ->
                    musePairingModeStatus =
                        "❌ Không mở được chế độ ghép nối: ${err.message}. " +
                            "Hãy bảo đảm điện thoại đang ở hotspot của loa hoặc cùng Wi-Fi với loa, và địa chỉ IP loa đúng."
                }
            )
            isOpeningMusePairing = false
        }
    }

    fun checkMuseStatus(ip: String? = null) {
        val targetIp = (ip ?: speakerIp).trim()
        viewModelScope.launch {
            commandResult = "Đang kiểm tra trạng thái R1..."
            apiClient.status(speakerIp = targetIp, token = authToken()).fold(
                onSuccess = { res ->
                    val paired = res.optBoolean("muse_paired", false)
                    val connected = res.optBoolean("muse_connected", false)
                    val mode = res.optString("muse_mode", "none")
                    val host = res.optString("noise_host", "hatch.metaaivm.com")
                    val vmId = res.optString("vm_id", "")
                    val museErr = res.optString("muse_error", "")
                    val devId = res.optString("device_id", "")
                    val net = res.optString("network", "")
                    isMusePaired = paired
                    isMuseConnected = connected
                    updateBlePairingStatus(
                        res.optJSONObject("bluetooth")?.optJSONObject("server")
                    )
                    activeVmId = res.optString("active_vm_id", "")
                    activeVmName = res.optString("active_vm_name", "")
                    activeNoiseHost = res.optString("active_noise_host", "")
                    val sb = StringBuilder()
                    sb.append("Trạng thái R1:\n")
                    sb.append("• ID: $devId\n")
                    sb.append("• Chế độ Muse: $mode (${if (paired) "Đã cấu hình ✅" else "Chưa cấu hình ❌"})\n")
                    sb.append("• Kết nối Noise XX: ${if (connected) "ĐÃ KẾT NỐI CLOUD ✅" else "Chưa kết nối ⏳"}\n")
                    if (activeVmId.isNotEmpty()) {
                        // VM thực tế đang kết nối (ở chế độ SDK token đây là
                        // giá trị server cấp — nơi duy nhất người dùng đọc
                        // được để mang sang chế độ Direct VM).
                        sb.append("• VM đang dùng: $activeVmId")
                        if (activeVmName.isNotEmpty()) sb.append(" ($activeVmName)")
                        sb.append(" @ ${activeNoiseHost.ifEmpty { host }}\n")
                    } else {
                        sb.append("• Server: $host")
                        if (vmId.isNotEmpty()) sb.append(" | VM: $vmId")
                        sb.append("\n")
                    }
                    if (museErr.isNotEmpty()) sb.append("• Chi tiết: $museErr\n")
                    // Mã lỗi cấu hình Wi-Fi thật từ phía loa (nếu đã từng thử):
                    // CONNECT_TIMEOUT thường là sai mật khẩu, mạng 5 GHz
                    // hoặc WPA3 — app tự nó không phân biệt được các ca này.
                    val wifiProv = res.optJSONObject("wifi_provision")
                    if (wifiProv != null && wifiProv.length() > 0) {
                        val provSsid = wifiProv.optString("ssid", "")
                        val provPhase = wifiProv.optString("phase", "")
                        val provVia = wifiProv.optString("via", "")
                        if (wifiProv.optBoolean("ok", false)) {
                            sb.append("• Cấu hình Wi-Fi gần nhất: ✅ $provSsid → ${wifiProv.optString("ip", "")}\n")
                        } else {
                            val provCode = wifiProv.optString("code", "?")
                            sb.append("• Cấu hình Wi-Fi gần nhất: ❌ $provCode (SSID: $provSsid")
                            if (provPhase.isNotEmpty()) sb.append(", pha: $provPhase")
                            if (provVia.isNotEmpty()) sb.append(", qua: $provVia")
                            sb.append(")\n")
                            ProvisioningHelp.helpFor(provCode)?.let { help ->
                                sb.append("  → $help\n")
                            }
                        }
                    }
                    // Kết quả dò đường LED vòng từ phía loa: có đường
                    // điều khiển ghi được thì thẻ LED ở tab Home mới hiện.
                    val ledProbe = res.optJSONObject("led_probe")
                    ledAvailable = ledProbe?.optBoolean("available", false) ?: false
                    if (ledProbe != null && ledProbe.length() > 0) {
                        val strategy = ledProbe.optString("strategy", "none")
                        sb.append(
                            if (ledAvailable) {
                                "• LED vòng: ✅ có đường điều khiển ($strategy)\n"
                            } else {
                                "• LED vòng: chưa tìm thấy đường điều khiển ghi được ($strategy)\n"
                            }
                        )
                    }
                    // Kết quả Phase 0 voice call (probe chạy ngầm phía loa,
                    // lưu bền trong /status): đây là cổng quyết định có làm
                    // Phase 1 (gọi thoại thật) hay không — mic phải mở được
                    // và có tín hiệu (RMS > 0 khi nói gần loa), và VM phải
                    // chấp nhận cuộc gọi thử (accepted + ready + có
                    // session_id). VM từ chối stream trên phiên chung không
                    // phải fail cứng: Phase 1 khi đó dùng phiên voice riêng.
                    val voice = res.optJSONObject("voice")
                    if (voice != null && voice.length() > 0) {
                        val mic = voice.optJSONObject("mic_probe")
                        if (mic != null && mic.length() > 0) {
                            if (mic.optBoolean("supported", false)) {
                                sb.append(
                                    "• Mic (voice P0): ✅ mở được — " +
                                        "${mic.optInt("sample_rate")} Hz, nguồn ${mic.optString("source", "?")}, " +
                                        "RMS ${mic.optDouble("rms", 0.0)}, peak ${mic.optInt("peak")}\n"
                                )
                            } else {
                                sb.append("• Mic (voice P0): ❌ không mở được thiết bị thu nào\n")
                            }
                        }
                        val call = voice.optJSONObject("call_probe")
                        if (call != null && call.length() > 0) {
                            val callErr = call.optString("error", "")
                            val http = call.optInt("http_status", -1)
                            val events = call.optJSONArray("event_types")?.let { arr ->
                                (0 until arr.length()).joinToString(",") { arr.optString(it) }
                            }.orEmpty()
                            when {
                                call.optBoolean("accepted", false) && call.optBoolean("ready", false) ->
                                    sb.append("• Voice call (P0): ✅ VM chấp nhận cuộc gọi thử, đã sẵn sàng (có session_id)\n")
                                call.optBoolean("accepted", false) ->
                                    sb.append("• Voice call (P0): ⚠️ mở được stream nhưng chưa sẵn sàng (HTTP $http${if (events.isNotEmpty()) ", sự kiện: $events" else ""})\n")
                                callErr.isNotEmpty() ->
                                    sb.append("• Voice call (P0): ❌ $callErr${if (http >= 0) " (HTTP $http)" else ""}\n")
                                http >= 0 ->
                                    // Probe chạy trọn vòng nhưng VM từ chối
                                    // stream realtime trên PHIÊN CHUNG (ca
                                    // thật 2026-11-14: HTTP 400, end-call vẫn
                                    // 200). Đây là kết quả ĐÃ BIẾT TRƯỚC và
                                    // là lý do Phase 1 dùng phiên voice riêng
                                    // — không phải ca "chưa rõ" cần raw dump.
                                    sb.append("• Voice call (P0): ❌ VM từ chối stream trên phiên chung (HTTP $http) — đúng như dự kiến; Phase 1 dùng phiên voice riêng\n")
                                else ->
                                    // Không accepted, không error: in NGUYÊN VĂN
                                    // JSON — bản tóm tắt cũ nuốt mất bằng chứng
                                    // đúng ở ca này (2026-11-14, probe chết giữa
                                    // chừng chỉ để lại object dở dang).
                                    sb.append("• Voice call (P0): ❌ chưa rõ — raw: ${call.toString().take(220)}\n")
                            }
                        }
                    }
                    // Phase 1 voice call: trạng thái cuộc gọi từ block
                    // voice_call (phiên voice riêng) — cập nhật nút
                    // start/stop ở Home và một dòng tóm tắt trong bảng.
                    val voiceCall = res.optJSONObject("voice_call")
                    if (voiceCall != null && voiceCall.length() > 0) {
                        val vcState = voiceCall.optString("state", "")
                        voiceCallActive = voiceCall.optBoolean("active", false)
                        val vcReason = voiceCall.optString("reason", "")
                        val vcError = voiceCall.optString("error", "")
                        voiceCallStatusText = when (vcState) {
                            "connecting" -> "📞 Đang kết nối phiên voice…"
                            "admitting" -> "📞 Đang xin vào cuộc gọi (admission)…"
                            "in_call" -> "📞 Đang trong cuộc gọi — nói vào loa để trò chuyện với Muse"
                            "ending" -> "📞 Đang kết thúc cuộc gọi…"
                            "ended" -> "📞 Cuộc gọi đã kết thúc" +
                                (if (vcReason.isNotEmpty()) " ($vcReason)" else "") +
                                (if (vcError.isNotEmpty()) " — $vcError" else "") +
                                (if (voiceCall.optBoolean("end_call_ack", false)) " — VM đã xác nhận end-call ✅" else "")
                            "failed" -> "📞 Cuộc gọi lỗi: ${vcError.ifEmpty { vcReason }}"
                            else -> ""
                        }
                        if (vcState.isNotEmpty() && vcState != "idle") {
                            sb.append("• Voice call (P1): ${voiceCallStatusText.removePrefix("📞 ")}\n")
                        }
                    } else {
                        voiceCallActive = false
                        voiceCallStatusText = ""
                    }
                    sb.append("• Mạng: $net")
                    commandResult = sb.toString()
                },
                onFailure = { err ->
                    commandResult = "Không kết nối được R1: ${err.message}"
                }
            )
        }
    }

    /**
     * Chạy bộ probe Phase 0 của voice call trên loa rồi chờ kết quả mới:
     * POST /probe-voice-call kích AgentService chạy mic probe + call probe
     * (trước đây chỉ kích được qua ADB), sau đó poll /status cho tới khi
     * call_probe mang probed_at_ms mới hơn mốc ban đầu (tối đa ~90 giây —
     * riêng admission chờ readiness có thể mất tới ~35 giây), cuối cùng vẽ
     * lại bảng trạng thái đầy đủ qua checkMuseStatus.
     */
    fun runVoiceProbe(ip: String? = null) {
        val targetIp = (ip ?: speakerIp).trim()
        if (isProbingVoice) return
        viewModelScope.launch {
            isProbingVoice = true
            var baselineMs = 0L
            apiClient.status(speakerIp = targetIp, token = authToken()).onSuccess { res ->
                baselineMs = res.optJSONObject("voice")?.optJSONObject("call_probe")
                    ?.optLong("probed_at_ms", 0L) ?: 0L
            }
            val started = apiClient.probeVoiceCall(speakerIp = targetIp, token = authToken())
            if (started.isFailure) {
                commandResult = "❌ Không kích hoạt được voice probe: " +
                    "${started.exceptionOrNull()?.message}. Kiểm tra điện thoại có cùng Wi-Fi với loa không."
                isProbingVoice = false
                return@launch
            }
            commandResult = "🎙️ Đang chạy voice probe trên loa… Hãy nói gần loa NGAY lúc này " +
                "để mic đo được tiếng nói thật (probe mic chạy trong vài giây đầu)."
            val deadlineMs = System.currentTimeMillis() + 90_000L
            var fresh = false
            while (System.currentTimeMillis() < deadlineMs) {
                delay(3_000L)
                val res = apiClient.status(speakerIp = targetIp, token = authToken()).getOrNull()
                val probedAt = res?.optJSONObject("voice")?.optJSONObject("call_probe")
                    ?.optLong("probed_at_ms", 0L) ?: 0L
                if (probedAt > baselineMs) {
                    fresh = true
                    break
                }
            }
            isProbingVoice = false
            if (fresh) {
                checkMuseStatus(targetIp)
            } else {
                commandResult = "🎙️ Voice probe chưa trả kết quả mới sau 90 giây. " +
                    "Thử bấm 'Kiểm tra trạng thái loa' — kết quả có thể đã về muộn phía loa."
            }
        }
    }

    /**
     * Bắt đầu cuộc gọi voice Phase 1: POST /voice-call/start kích loa mở
     * phiên Noise voice riêng (app id hatch-web), admission và phát/thu
     * tiếng hai chiều. Trạng thái cuộc gọi theo block voice_call trong
     * /status; hàm này refresh trạng thái vài lần trong lúc cuộc gọi vào
     * giai đoạn in_call. Cuộc gọi tự kết thúc theo idle 45s / overall
     * 300s phía loa hoặc bằng nút dừng.
     */
    fun startVoiceCall(ip: String? = null) {
        val targetIp = (ip ?: speakerIp).trim()
        if (isTogglingVoiceCall || voiceCallActive) return
        viewModelScope.launch {
            isTogglingVoiceCall = true
            val started = apiClient.startVoiceCall(speakerIp = targetIp, token = authToken())
            isTogglingVoiceCall = false
            if (started.isFailure) {
                commandResult = "❌ Không bắt đầu được voice call: " +
                    "${started.exceptionOrNull()?.message}. Kiểm tra điện thoại có cùng Wi-Fi với loa không."
                return@launch
            }
            voiceCallStatusText = "📞 Đang kết nối phiên voice…"
            // Admission + readiness có thể mất vài giây: refresh vài nhịp
            // đầu, sau đó người dùng tự bấm Kiểm tra trạng thái nếu cần.
            repeat(4) {
                delay(3_000L)
                checkMuseStatus(targetIp)
                if (!voiceCallActive && voiceCallStatusText.contains("kết thúc")) return@launch
            }
        }
    }

    /** Kết thúc cuộc gọi voice Phase 1 đang chạy trên loa. */
    fun stopVoiceCall(ip: String? = null) {
        val targetIp = (ip ?: speakerIp).trim()
        if (isTogglingVoiceCall) return
        viewModelScope.launch {
            isTogglingVoiceCall = true
            val stopped = apiClient.stopVoiceCall(speakerIp = targetIp, token = authToken())
            isTogglingVoiceCall = false
            if (stopped.isFailure) {
                commandResult = "❌ Không dừng được voice call: " +
                    "${stopped.exceptionOrNull()?.message}"
                return@launch
            }
            delay(1_500L)
            checkMuseStatus(targetIp)
        }
    }

    fun setSpeakerVolume(volume: Int, ip: String? = null) {
        val targetIp = (ip ?: speakerIp).trim()
        speakerVolume = volume.coerceIn(0, 100)
        viewModelScope.launch {
            commandResult = "Đang đặt âm lượng $volume%..."
            apiClient.setVolume(speakerIp = targetIp, volume = volume, token = authToken()).fold(
                onSuccess = {
                    commandResult = "✅ Đã đặt âm lượng thành $volume%"
                },
                onFailure = { err ->
                    commandResult = "❌ Lỗi đổi âm lượng: ${err.message}"
                }
            )
        }
    }

    /** Đặt màu/độ sáng/tắt vòng LED qua POST /led (chỉ khi ledAvailable). */
    fun setLed(color: String? = null, brightness: Int? = null, off: Boolean = false, ip: String? = null) {
        val targetIp = (ip ?: speakerIp).trim()
        if (color != null) ledColor = color
        if (brightness != null) ledBrightness = brightness.coerceIn(0, 100)
        ledOn = !off && (brightness ?: ledBrightness) > 0
        viewModelScope.launch {
            commandResult = if (off) "Đang tắt LED vòng..." else "Đang đặt LED vòng..."
            apiClient.setLed(
                speakerIp = targetIp,
                color = color,
                brightness = brightness,
                off = off,
                token = authToken()
            ).fold(
                onSuccess = {
                    commandResult = if (off) {
                        "✅ Đã tắt LED vòng"
                    } else {
                        "✅ Đã đặt LED vòng: $ledColor, sáng $ledBrightness%"
                    }
                },
                onFailure = { err ->
                    commandResult = "❌ Lỗi đặt LED: ${err.message}"
                }
            )
        }
    }

    fun testTts(text: String, ip: String? = null) {
        val targetIp = (ip ?: speakerIp).trim()
        viewModelScope.launch {
            commandResult = "Đang gửi TTS: \"$text\"..."
            apiClient.testTts(speakerIp = targetIp, text = text, token = authToken()).fold(
                onSuccess = {
                    commandResult = "✅ R1 đã nhận lệnh TTS: \"$text\""
                },
                onFailure = { err ->
                    commandResult = "❌ Lỗi gửi TTS: ${err.message}"
                }
            )
        }
    }

    fun setPassword(value: String) {
        password = value
    }

    private var expectedApkSha256: String? = null

    fun setExpectedApkSha256(hash: String?) {
        expectedApkSha256 = hash?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun selectWifiNetwork(network: WifiNetwork) {
        selectedNetwork = network
        state = ProvisioningState.SelectingWifi
        status = if (network.isOpen) {
            "Đã chọn mạng mở " + network.ssid + ". Bấm tiếp tục để gửi tới R1."
        } else {
            "Đã chọn " + network.ssid + " (" + network.security + "). Nhập mật khẩu để tiếp tục."
        }
    }

    /** Các địa chỉ HTTP ứng viên của loa: IP đang đặt, rồi gateway hotspot. */
    private fun speakerHttpCandidates(): List<String> =
        listOf(speakerIp.trim(), "192.168.43.1").filter { it.isNotEmpty() }.distinct()

    private fun parseSpeakerNetworks(json: JSONObject): List<WifiNetwork> {
        val array = json.optJSONArray("networks") ?: return emptyList()
        val result = mutableListOf<WifiNetwork>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val ssid = item.optString("ssid").trim()
            if (ssid.isEmpty()) continue
            val has24 = item.optBoolean("has_2_4ghz", false)
            val has5 = item.optBoolean("has_5ghz", false)
            result += WifiNetwork(
                ssid = ssid,
                // BSSID tổng hợp theo loa: phân biệt với kết quả điện thoại
                // quét và giữ phép so sánh đã chọn trong WifiPicker đúng.
                bssid = "speaker:$ssid",
                frequencyMhz = if (has24) 2437 else 5180,
                signalLevel = item.optInt("rssi", -80),
                security = item.optString("security", "WPA2"),
                has24Ghz = has24
            )
        }
        return result
    }

    fun refreshWifiNetworks() {
        viewModelScope.launch {
            // Ưu tiên danh sách do CHÍNH LOA quét: loa chỉ nối được mạng nó
            // nhìn thấy, còn kết quả điện thoại quét có thể cũ hoặc thấy
            // mạng ngoài tầm loa. Không hỏi được loa mới dùng điện thoại.
            var speakerList: List<WifiNetwork> = emptyList()
            for (ip in speakerHttpCandidates()) {
                val json = apiClient.getWifiScan(ip, authToken()).getOrNull()
                if (json != null) {
                    speakerList = parseSpeakerNetworks(json)
                    if (speakerList.isNotEmpty()) break
                }
            }

            if (speakerList.isNotEmpty()) {
                wifiNetworks = speakerList
                wifiListFromSpeaker = true
                status = "Loa quét thấy " + speakerList.size +
                    " mạng Wi‑Fi (danh sách theo góc nhìn của loa)."
                return@launch
            }

            wifiNetworks = wifiScanner.scan()
            wifiListFromSpeaker = false
            status = if (wifiNetworks.isEmpty()) {
                "Chưa có kết quả quét. Hãy cấp quyền vị trí và thử lại."
            } else {
                "Đã tìm thấy " + wifiNetworks.size +
                    " mạng Wi‑Fi (điện thoại quét; chưa hỏi được loa)."
            }
        }
    }

    private fun parseSavedNetworks(json: JSONObject): List<SavedWifiNetwork> {
        val array = json.optJSONArray("saved") ?: return emptyList()
        val result = mutableListOf<SavedWifiNetwork>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val ssid = item.optString("ssid").trim()
            if (ssid.isEmpty()) continue
            result += SavedWifiNetwork(
                ssid = ssid,
                networkId = item.optInt("network_id", -1),
                connected = item.optBoolean("connected", false)
            )
        }
        return result
    }

    /** Tải danh sách mạng đã lưu trên loa (im lặng nếu chưa gọi được loa). */
    fun loadSavedWifi() {
        if (isLoadingSavedWifi) return
        isLoadingSavedWifi = true
        viewModelScope.launch {
            try {
                for (ip in speakerHttpCandidates()) {
                    val json = apiClient.getWifiSaved(ip, authToken()).getOrNull()
                    if (json != null) {
                        savedWifiNetworks = parseSavedNetworks(json)
                        savedWifiCurrentSsid = json.optString("current_ssid", "")
                        break
                    }
                }
            } finally {
                isLoadingSavedWifi = false
            }
        }
    }

    /** Quên một mạng đã lưu trên loa; quên mạng đang dùng sẽ làm loa rớt mạng. */
    fun forgetSavedWifi(ssid: String) {
        viewModelScope.launch {
            var lastError: Throwable? = null
            for (ip in speakerHttpCandidates()) {
                val result = apiClient.forgetWifi(ip, ssid, authToken())
                if (result.isSuccess) {
                    val json = result.getOrNull()
                    status = if (json?.optBoolean("was_connected") == true) {
                        "Đã lên lịch quên mạng '$ssid' — loa sẽ ngắt kết nối và rớt mạng hiện tại."
                    } else {
                        "Đã quên mạng '$ssid' trên loa."
                    }
                    loadSavedWifi()
                    return@launch
                }
                lastError = result.exceptionOrNull()
            }
            status = "Quên mạng '$ssid' thất bại: ${lastError?.message ?: "không liên lạc được loa"}"
        }
    }

    /**
     * Chuyển loa sang một mạng đã lưu. Loa lên lịch thực hiện và đổi IP
     * ngay sau đó, nên hàm này thăm dò /wifi-saved vài nhịp để xác nhận
     * current_ssid đã khớp; không khớp thì nói rõ là chưa xác nhận được
     * thay vì báo thành công giả.
     */
    fun useSavedWifi(ssid: String) {
        if (isSwitchingWifi) return
        viewModelScope.launch {
            var accepted: JSONObject? = null
            var lastError: Throwable? = null
            for (ip in speakerHttpCandidates()) {
                val result = apiClient.useWifi(ip, ssid, authToken())
                if (result.isSuccess) {
                    accepted = result.getOrNull()
                    break
                }
                lastError = result.exceptionOrNull()
            }
            val response = accepted
            if (response == null) {
                status = "Chuyển sang mạng '$ssid' thất bại: ${lastError?.message ?: "không liên lạc được loa"}"
                return@launch
            }
            if (response.optBoolean("already_connected")) {
                status = "Loa đang dùng sẵn mạng '$ssid' (${response.optString("ip")})."
                loadSavedWifi()
                return@launch
            }

            isSwitchingWifi = true
            status = "Đã gửi lệnh chuyển mạng — đang chờ loa xác nhận dùng '$ssid'..."
            try {
                var confirmed = false
                var attempts = 0
                while (!confirmed && attempts < 10) {
                    attempts++
                    delay(3_000)
                    for (ip in speakerHttpCandidates()) {
                        val json = apiClient.getWifiSaved(ip, authToken()).getOrNull()
                            ?: continue
                        savedWifiNetworks = parseSavedNetworks(json)
                        savedWifiCurrentSsid = json.optString("current_ssid", "")
                        if (savedWifiCurrentSsid == ssid) {
                            confirmed = true
                            break
                        }
                    }
                }
                status = if (confirmed) {
                    "Loa đã chuyển sang mạng '$ssid' (IP mới có thể khác — kiểm tra lại trạng thái loa nếu cần)."
                } else {
                    "Chưa xác nhận được loa đã chuyển sang '$ssid'. Xem mục wifi_provision trong trạng thái loa để biết mã lỗi thật."
                }
            } finally {
                isSwitchingWifi = false
            }
        }
    }

    fun checkR1() {
        if (isCheckingR1) return
        isCheckingR1 = true
        diagnosticLogs = emptyList()
        viewModelScope.launch {
            state = ProvisioningState.CheckingR1
            status = "Đang tìm R1 trên Wi-Fi hiện tại bằng UDP Discovery..."
            try {
                val discoveryLog = mutableListOf<String>()
                val discovered = discoveryClient.discover(timeoutMs = 4000)
                if (discovered != null) {
                    currentSpeakerIp = discovered.ipAddress
                    discoveryLog += "UDP Discovery: tìm thấy R1 tại ${discovered.ipAddress}"
                    discoveryLog += "Muse: ${if (discovered.musePaired) "đã cấu hình" else "chưa cấu hình"} (discovery không còn phát SSID/device_id của loa)"
                } else {
                    discoveryLog += "UDP Discovery: không nhận được phản hồi trong 4 giây."
                    discoveryLog += "Thử tiếp địa chỉ gần nhất: $speakerIp"
                }

                val targetIp = speakerIp
                val report = installer.diagnoseAgent(targetIp).getOrElse {
                    "Không lấy được chẩn đoán ADB: ${it.javaClass.simpleName}: ${it.message}"
                }
                diagnosticLogs = (discoveryLog + report.lineSequence().toList())
                    .map { SecretRedactor.redact(it) }
                val health = apiClient.health(targetIp)
                status = health.fold(
                    onSuccess = { isReady ->
                        if (isReady) "Đã kết nối Muse R1 Agent tại $targetIp:8080."
                        else "Muse R1 Agent tại $targetIp:8080 chưa sẵn sàng (ok=false)."
                    },
                    onFailure = { error ->
                        "Health check thất bại tại $targetIp:8080: ${error.javaClass.simpleName}: ${error.message ?: "không có chi tiết"}. Xem nhật ký chẩn đoán."
                    }
                )
            } finally {
                isCheckingR1 = false
            }
        }
    }

    fun getDiagnosticsText(): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        // Che bí mật ở bản xuất cuối cùng: người dùng hay copy nguyên
        // khối này gửi đi chẩn đoán, không được để lọt token/mật khẩu.
        return SecretRedactor.redact(buildString {
            append("=== MUSE R1 DIAGNOSTICS ($dateStr) ===\n")
            append("Speaker: $speakerIp\n")
            append("Status: $status\n")
            append("------------------------------------------\n")
            diagnosticLogs.forEach { append(it).append('\n') }
        })
    }

    @SuppressLint("MissingPermission")
    fun startProvisioning() {
        val network = selectedNetwork

        if (network == null) {
            state = ProvisioningState.Failure("Chưa chọn Wi‑Fi nhà", code = "NO_WIFI_SELECTED")
            return
        }

        if (network.ssid.startsWith("Phicomm_R1_", ignoreCase = true) || network.ssid.startsWith("PhiWiFi-", ignoreCase = true)) {
            state = ProvisioningState.Failure("Không thể chọn trạm phát của chính loa R1. Vui lòng chọn Wi‑Fi nhà của router.", code = "INVALID_NETWORK")
            return
        }

        // Loa R1 (Android 5.1) không bắt tay được WPA3 thuần (SAE) hay
        // WPA-Enterprise (EAP): chặn ngay tại app với lý do rõ ràng thay vì
        // để loa thử 35 giây rồi app chờ mù 2 phút.
        if (network.security.equals("WPA3", ignoreCase = true) ||
            network.security.equals("EAP", ignoreCase = true)
        ) {
            state = ProvisioningState.Failure(
                "Mạng ${network.ssid} dùng ${network.security.uppercase()} — loa R1 không hỗ trợ chuẩn này. Hãy đặt router sang WPA2-PSK hoặc chế độ hỗn hợp WPA2/WPA3 rồi thử lại.",
                code = "UNSUPPORTED_SECURITY"
            )
            return
        }

        val isOpen = network.isOpen
        val isHexPsk = password.length == 64 && password.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        if (!isOpen && (password.length !in 8..63 && !isHexPsk)) {
            state = ProvisioningState.Failure("Mật khẩu WPA2 cần từ 8 đến 63 ký tự hoặc khóa hex 64 ký tự", code = "INVALID_PASSWORD")
            return
        }

        viewModelScope.launch {
            state = ProvisioningState.Provisioning
            // Mốc thời gian bắt đầu lần thử này: dùng để nhận ra bản ghi
            // wifi_provision MỚI của loa khi thăm dò trạng thái (bản ghi
            // cũ của lần thử trước không được tính là kết quả lần này).
            val attemptStartedAt = System.currentTimeMillis()

            // Ép Android ưu tiên định tuyến gói tin qua Wi-Fi (bỏ qua 4G/5G khi Wi-Fi loa không có Internet)
            val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val wifiNet = cm?.allNetworks?.firstOrNull { net ->
                cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (wifiNet != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.bindProcessToNetwork(wifiNet)
            }

            // Phải gỡ bind khi đã gửi xong cấu hình: trước đây process bị ghim
            // vĩnh viễn vào Network của hotspot loa, khiến các lần gọi API/
            // chẩn đoán sau (khi điện thoại đã về Wi-Fi nhà) có thể bị định
            // tuyến vào mạng cũ đã biến mất.
            fun unbindProcessNetwork() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    runCatching { cm?.bindProcessToNetwork(null) }
                }
            }

            // 1. Thử gửi trực tiếp qua HTTP (khi điện thoại đang nối Wi-Fi Phicomm_R1_xxx)
            val targetHttpIp = if (speakerIp.isNotBlank()) speakerIp.trim() else "192.168.43.1"
            val isHttpAvailable = runCatching {
                apiClient.health(targetHttpIp).getOrDefault(false)
            }.getOrDefault(false)

            if (isHttpAvailable) {
                status = "Đang gửi cấu hình Wi‑Fi tới R1 qua HTTP ($targetHttpIp:8080)..."
                val httpResult = apiClient.provisionWifi(
                    speakerIp = targetHttpIp,
                    ssid = network.ssid,
                    password = password,
                    security = network.security,
                    token = authToken()
                )

                if (httpResult.isSuccess) {
                    status = "R1 đã nhận cấu hình Wi‑Fi! Đang đợi R1 kết nối vào mạng ${network.ssid}..."
                    unbindProcessNetwork()
                    waitForR1OnHomeNetwork(targetSsid = network.ssid, attemptStartedAt = attemptStartedAt)
                    return@launch
                } else {
                    Log.w("Provisioning", "HTTP provision thất bại: ${httpResult.exceptionOrNull()?.message}, thử Bluetooth...")
                }
            }

            // 2. Dự phòng: Thử gửi qua Bluetooth
            val device = bluetoothDevice ?: bluetoothProvisioner.findR1Device().getOrElse {
                state = ProvisioningState.Failure(
                    "Không thể gửi cấu hình tới loa: Không có kết nối HTTP tới $targetHttpIp và chưa ghép đôi Bluetooth với R1. Hãy kết nối Wi‑Fi điện thoại vào mạng loa (Phicomm_R1_xxx) rồi thử lại.",
                    code = "NO_TRANSPORT_AVAILABLE"
                )
                unbindProcessNetwork()
                return@launch
            }

            val deviceName = device.name ?: "Muse R1"
            status = "Đang gửi Wi‑Fi tới $deviceName qua Bluetooth..."

            // Thử Bluetooth tối đa 2 lần, và CHỈ thử lại khi lỗi là lỗi
            // đường truyền (socket đóng, quá hạn, RFCOMM chập chờn). Loa
            // đã trả mã nghiệp vụ (ERROR: CONNECT_TIMEOUT...) thì thử lại
            // y hệt chỉ tốn thêm một phút vô ích — dừng ngay với mã đó.
            var sppResult: Result<String?>? = null
            var sppAttempts = 0
            while (sppAttempts < 2) {
                sppAttempts++
                val attempt = bluetoothProvisioner.provisionWifi(
                    device = device,
                    ssid = network.ssid,
                    password = password,
                    // Loa đã cấu hình Muse sẽ từ chối SPP không có token (kênh SPP
                    // giờ cũng kiểm tra provision token như HTTP :8080).
                    token = authToken(),
                    onSaved = {
                        status = "Loa đã lưu cấu hình Wi‑Fi — đang chờ loa kết nối vào ${network.ssid}..."
                    }
                )
                if (attempt.isSuccess) {
                    sppResult = attempt
                    break
                }
                val agentCode = ProvisioningHelp.extractCode(attempt.exceptionOrNull()?.message)
                if (agentCode != null || sppAttempts >= 2) {
                    sppResult = attempt
                    break
                }
                status = "Bluetooth chập chờn — đang thử lại lần 2/2..."
                delay(2_000)
            }

            sppResult!!.fold(
                onSuccess = { ip ->
                    unbindProcessNetwork()
                    if (!ip.isNullOrBlank()) {
                        currentSpeakerIp = ip
                        status = "R1 đã kết nối Wi‑Fi nhà tại $ip! Đang kiểm tra kết nối..."
                        waitForR1OnHomeNetwork(knownIp = ip, targetSsid = network.ssid, attemptStartedAt = attemptStartedAt)
                    } else {
                        status = "Đã nhận xác nhận Bluetooth từ R1. Đang xác minh trên Wi‑Fi nhà..."
                        waitForR1OnHomeNetwork(targetSsid = network.ssid, attemptStartedAt = attemptStartedAt)
                    }
                },
                onFailure = { error ->
                    val agentCode = ProvisioningHelp.extractCode(error.message)
                    state = if (agentCode != null) {
                        ProvisioningState.Failure(
                            "Loa từ chối cấu hình Wi-Fi qua Bluetooth. " + ProvisioningHelp.describe(agentCode),
                            code = agentCode
                        )
                    } else {
                        ProvisioningState.Failure(
                            "Gửi cấu hình qua Bluetooth thất bại (${error.message ?: "ngắt kết nối"}). " +
                                (ProvisioningHelp.helpFor("BLUETOOTH_FAILED") ?: ""),
                            code = "BLUETOOTH_FAILED"
                        )
                    }
                }
            )
            unbindProcessNetwork()
        }
    }

    /** SSID mà điện thoại hiện đang nối (null nếu không đọc được). */
    private fun currentPhoneSsid(): String? {
        val wifiManager = getApplication<Application>().getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        @Suppress("DEPRECATION")
        val raw = wifiManager.connectionInfo?.ssid ?: return null
        val cleaned = raw.removeSurrounding("\"").trim()
        return cleaned.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
    }

    /**
     * Hỏi trạng thái loa ở các địa chỉ có thể còn tới được (hotspot của
     * loa nếu nó đã tự khôi phục sau thất bại, IP đang đặt) và trả bản
     * ghi wifi_provision nếu đó là bản ghi của LẦN THỬ NÀY: SSID khớp
     * mạng đích và mốc thời gian không cũ hơn lần thử (nới 5 phút cho
     * lệch đồng hồ giữa điện thoại và loa).
     */
    private suspend fun probeSpeakerProvisionRecord(
        targetSsid: String?,
        attemptStartedAt: Long
    ): JSONObject? {
        for (ip in listOf("192.168.43.1", speakerIp.trim()).filter { it.isNotEmpty() }.distinct()) {
            val res = apiClient.status(ip, authToken()).getOrNull() ?: continue
            val record = res.optJSONObject("wifi_provision") ?: continue
            if (record.length() == 0) continue
            val recordSsid = record.optString("ssid", "")
            if (targetSsid != null && recordSsid.isNotEmpty() && recordSsid != targetSsid) continue
            val atMs = record.optLong("at_ms", 0L)
            if (atMs in 1 until attemptStartedAt - 300_000L) continue
            return record
        }
        return null
    }

    private suspend fun waitForR1OnHomeNetwork(
        bluetoothError: String? = null,
        knownIp: String? = null,
        targetSsid: String? = null,
        attemptStartedAt: Long = 0L
    ) {
        state = ProvisioningState.WaitingForR1
        val netName = targetSsid ?: selectedNetwork?.ssid ?: "Wi‑Fi nhà"
        status = when {
            knownIp != null -> "R1 đã báo IP $knownIp. Đang kết nối thử..."
            else -> "R1 đang kết nối vào $netName. Vui lòng chuyển Wi‑Fi điện thoại sang mạng $netName để tìm loa..."
        }

        // Nếu loa đã gửi lại IP thật qua Bluetooth, probe trực tiếp trước (bỏ qua IP trạm phát hotspot 192.168.43.x)
        if (knownIp != null && !knownIp.startsWith("192.168.43.")) {
            repeat(6) {
                delay(1500)
                val health = apiClient.health(knownIp)
                if (health.getOrDefault(false)) {
                    state = ProvisioningState.Success(knownIp)
                    status = "Đã kết nối thành công tới R1 tại $knownIp:8080"
                    return
                }
            }
        }

        repeat(24) { iteration ->
            delay(5000)
            // Cho người dùng thấy ĐIỆN THOẠI đang ở mạng nào: nguyên nhân
            // kẹt ở bước chờ này rất hay gặp là điện thoại chưa về đúng
            // mạng nhà (còn ở hotspot loa đã tắt, hoặc tự nhảy sang SSID
            // khác) — trước đây màn hình chờ không nói gì trong 2 phút.
            if (targetSsid != null) {
                val phoneSsid = currentPhoneSsid()
                status = when {
                    phoneSsid == null ->
                        "Đang tìm loa trên $netName... (chưa đọc được Wi‑Fi điện thoại đang nối)"
                    phoneSsid == targetSsid ->
                        "Điện thoại đã ở mạng $targetSsid. Đang tìm loa..."
                    else ->
                        "⚠ Điện thoại đang ở mạng '$phoneSsid' — hãy chuyển Wi‑Fi điện thoại sang '$targetSsid' để tìm thấy loa."
                }
            }
            // Mỗi ~15 giây, hỏi thẳng trạng thái loa (nếu còn tới được):
            // loa nối hỏng thường tự khôi phục hotspot, và khi đó bản ghi
            // wifi_provision cho biết MÃ LỖI THẬT — fail-fast tại đây thay
            // vì chờ mù đủ 2 phút mới báo một câu chung chung.
            if (attemptStartedAt > 0 && iteration % 3 == 2) {
                val record = probeSpeakerProvisionRecord(targetSsid, attemptStartedAt)
                if (record != null) {
                    when (record.optString("phase", "")) {
                        "failed" -> {
                            val code = record.optString("code", "EXCEPTION")
                            state = ProvisioningState.Failure(
                                "Loa báo cấu hình Wi-Fi thất bại. " + ProvisioningHelp.describe(code),
                                code = code
                            )
                            status = "Loa đã dừng thử và báo mã $code."
                            return
                        }
                        "connected" -> {
                            val ip = record.optString("ip", "")
                            if (ip.isNotEmpty() && !ip.startsWith("192.168.43.")) {
                                currentSpeakerIp = ip
                                state = ProvisioningState.Success(ip)
                                status = "Đã xác minh R1 trên Wi‑Fi nhà: $ip (theo trạng thái loa báo)"
                                return
                            }
                        }
                        "saved" -> {
                            status = "Loa đã lưu cấu hình — đang chờ kết nối vào $netName..."
                        }
                    }
                }
            }
            val discovered = discoveryClient.discover()
            if (discovered != null && !discovered.ipAddress.startsWith("192.168.43.")) {
                state = ProvisioningState.Success(discovered.ipAddress)
                status = "Đã xác minh R1 trên Wi‑Fi nhà: " + discovered.ipAddress
                return
            }
        }

        state = ProvisioningState.Failure(
            message = if (bluetoothError == null) {
                "Không tìm thấy R1 trên Wi‑Fi nhà sau 2 phút. " +
                    (ProvisioningHelp.helpFor("DISCOVERY_TIMEOUT") ?: "")
            } else {
                "Loa chưa kết nối được Wi‑Fi nhà ($bluetoothError). Hãy đảm bảo mật khẩu đúng và điện thoại đang kết nối Wi‑Fi nhà."
            },
            code = if (bluetoothError == null) "DISCOVERY_TIMEOUT" else "PROVISION_FAILED"
        )
    }

    fun retry(resetNetwork: Boolean = false) {
        state = ProvisioningState.Idle
        if (resetNetwork) {
            selectedNetwork = null
            password = ""
        }
        status = "Sẵn sàng thử lại."
    }

    fun downloadAgentApk(apkUrl: String) {
        if (isDownloading) return
        isDownloading = true
        downloadProgress = 0
        viewModelScope.launch {
            try {
                status = "Bắt đầu tải APK từ: $apkUrl"
                installer.downloadAgentApk(apkUrl, expectedSha256 = expectedApkSha256) { percent ->
                    downloadProgress = percent
                    status = "Đang tải APK: $percent%"
                }.onSuccess { file ->
                    isApkDownloaded = true
                    val sizeMb = String.format("%.2f", file.length() / (1024.0 * 1024.0))
                    status = "Đã lưu APK ($sizeMb MB) vào máy! Hãy đổi Wi‑Fi sang Phicomm_R1_xxx rồi bấm Cài APK."
                }.onFailure { error ->
                    status = "Tải APK thất bại: ${error.message}. Hãy kiểm tra Internet (Wi‑Fi nhà / 4G) hoặc URL."
                }
            } finally {
                isDownloading = false
                downloadProgress = null
            }
        }
    }

    fun installR1Agent(apkUrl: String) {
        if (isInstalling) return
        isInstalling = true
        installProgress = null
        installStage = "Chuẩn bị cài đặt..."
        installLogs = emptyList()

        viewModelScope.launch {
            try {
                addInstallLog("Bắt đầu quy trình cài đặt Agent lên R1")
                val localApk = if (installer.hasLocalApk()) {
                    val file = installer.getLocalApkFile()
                    val sizeMb = String.format("%.2f", file.length() / (1024.0 * 1024.0))
                    val logMsg = "Sử dụng file APK offline có sẵn ($sizeMb MB)"
                    status = logMsg
                    addInstallLog(logMsg)
                    file
                } else {
                    val logMsg = "Chưa có file APK offline. Đang tải từ URL..."
                    status = logMsg
                    addInstallLog(logMsg)
                    isDownloading = true
                    val downloadResult = installer.downloadAgentApk(apkUrl, expectedSha256 = expectedApkSha256) { percent ->
                        downloadProgress = percent
                        installStage = "Đang tải APK về máy: $percent%"
                    }
                    isDownloading = false
                    downloadProgress = null
                    if (downloadResult.isFailure) {
                        val err = downloadResult.exceptionOrNull()?.message ?: "Lỗi tải APK"
                        val failMsg = "Không thể tải APK. Hãy kết nối Wi‑Fi nhà/4G và bấm 'Tải APK về máy' trước!"
                        status = failMsg
                        addInstallLog("❌ Lỗi: $err")
                        addInstallLog(failMsg)
                        installStage = "Thất bại: Mất kết nối internet"
                        return@launch
                    }
                    isApkDownloaded = true
                    addInstallLog("Đã tải APK thành công")
                    downloadResult.getOrThrow()
                }

                status = "Đang kết nối ADB tới R1 ($speakerIp:5555)..."
                val result = installer.installAgent(
                    speakerIp = speakerIp,
                    apkFile = localApk,
                    expectedSha256 = expectedApkSha256
                ) { message, progress ->
                    installStage = message
                    installProgress = progress
                    status = message
                    addInstallLog(message)
                }

                result.fold(
                    onSuccess = {
                        status = "Cài đặt hoàn tất! Hãy kiểm tra kết nối R1 hoặc chọn Wi-Fi để tiếp tục."
                        installStage = "Hoàn tất cài đặt Muse R1 Agent!"
                        installProgress = 1.0f
                        addInstallLog("✅ Hoàn tất thành công!")
                    },
                    onFailure = { error ->
                        val errMsg = error.message ?: "Lỗi không xác định"
                        status = "Lỗi cài Agent qua ADB: $errMsg"
                        installStage = "Lỗi cài đặt!"
                        addInstallLog("❌ Thất bại: $errMsg")
                        error.cause?.message?.let { causeMsg ->
                            addInstallLog("Nguyên nhân: $causeMsg")
                        }
                    }
                )
            } finally {
                isInstalling = false
            }
        }
    }
}
