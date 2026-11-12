package vn.tutu.musemobile.domain

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.tutu.musemobile.data.BluetoothProvisioner
import vn.tutu.musemobile.data.R1ApiClient
import vn.tutu.musemobile.data.R1DiscoveryClient
import vn.tutu.musemobile.data.R1Installer
import vn.tutu.musemobile.data.WifiScanner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProvisioningViewModel(application: Application) : AndroidViewModel(application) {

    private val apiClient = R1ApiClient(application)
    private val discoveryClient = R1DiscoveryClient()
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

    var selectedNetwork by mutableStateOf<WifiNetwork?>(null)
        private set
    var currentSpeakerIp by mutableStateOf("192.168.2.10")
        private set
    val speakerIp: String get() = currentSpeakerIp

    var currentMuseTokenInput by mutableStateOf("")
        private set
    val museTokenInput: String get() = currentMuseTokenInput

    var isPairingMuse by mutableStateOf(false)
        private set

    var musePairStatus by mutableStateOf("")
        private set

    var isMusePaired by mutableStateOf(false)
        private set

    var speakerVolume by mutableStateOf(70)
        private set

    var commandResult by mutableStateOf("")
        private set

    private var password = ""
    private var bluetoothDevice: BluetoothDevice? = null

    private fun addInstallLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        installLogs = installLogs + "[$time] $msg"
    }

    fun getInstallLogsText(): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val sb = StringBuilder()
        sb.append("=== MUSE R1 INSTALLATION LOG ($dateStr) ===\n")
        sb.append("Địa chỉ loa: $speakerIp:5555\n")
        sb.append("Trạng thái: $installStage\n")
        sb.append("------------------------------------------\n")
        installLogs.forEach { sb.append(it).append("\n") }
        return sb.toString()
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
            musePairStatus = "Đang gửi Muse Token tới R1 ($targetIp:8080)..."
            apiClient.configureMuse(speakerIp = targetIp, museToken = rawToken).fold(
                onSuccess = { res ->
                    isMusePaired = true
                    val devId = res.optString("device_id", "R1")
                    musePairStatus = "✅ Đã ghép đôi thành công! Thiết bị: $devId"
                },
                onFailure = { err ->
                    musePairStatus = "❌ Ghép đôi thất bại: ${err.message}"
                }
            )
            isPairingMuse = false
        }
    }

    fun checkMuseStatus(ip: String? = null) {
        val targetIp = (ip ?: speakerIp).trim()
        viewModelScope.launch {
            commandResult = "Đang kiểm tra trạng thái R1..."
            apiClient.status(speakerIp = targetIp).fold(
                onSuccess = { res ->
                    val paired = res.optBoolean("muse_paired", false)
                    val devId = res.optString("device_id", "")
                    val net = res.optString("network", "")
                    isMusePaired = paired
                    commandResult = "Trạng thái R1:\n• ID thiết bị: $devId\n• Trạng thái Muse: ${if (paired) "ĐÃ GHÉP ĐÔI ✅" else "Chưa ghép đôi ❌"}\n• Mạng: $net"
                },
                onFailure = { err ->
                    commandResult = "Không kết nối được R1: ${err.message}"
                }
            )
        }
    }

    fun setSpeakerVolume(volume: Int, ip: String? = null) {
        val targetIp = (ip ?: speakerIp).trim()
        speakerVolume = volume.coerceIn(0, 100)
        viewModelScope.launch {
            commandResult = "Đang đặt âm lượng $volume%..."
            apiClient.setVolume(speakerIp = targetIp, volume = volume).fold(
                onSuccess = {
                    commandResult = "✅ Đã đặt âm lượng thành $volume%"
                },
                onFailure = { err ->
                    commandResult = "❌ Lỗi đổi âm lượng: ${err.message}"
                }
            )
        }
    }

    fun testTts(text: String, ip: String? = null) {
        val targetIp = (ip ?: speakerIp).trim()
        viewModelScope.launch {
            commandResult = "Đang gửi TTS: \"$text\"..."
            apiClient.testTts(speakerIp = targetIp, text = text).fold(
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

    fun refreshWifiNetworks() {
        viewModelScope.launch {
            wifiNetworks = wifiScanner.scan()

            status = if (wifiNetworks.isEmpty()) {
                "Chưa có kết quả quét. Hãy cấp quyền vị trí và thử lại."
            } else {
                "Đã tìm thấy " + wifiNetworks.size + " mạng Wi‑Fi."
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
                    discoveryLog += "SSID trên R1: ${discovered.ssid}"
                } else {
                    discoveryLog += "UDP Discovery: không nhận được phản hồi trong 4 giây."
                    discoveryLog += "Thử tiếp địa chỉ gần nhất: $speakerIp"
                }

                val targetIp = speakerIp
                val report = installer.diagnoseAgent(targetIp).getOrElse {
                    "Không lấy được chẩn đoán ADB: ${it.javaClass.simpleName}: ${it.message}"
                }
                diagnosticLogs = discoveryLog + report.lineSequence().toList()
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
        return buildString {
            append("=== MUSE R1 DIAGNOSTICS ($dateStr) ===\n")
            append("Speaker: $speakerIp\n")
            append("Status: $status\n")
            append("------------------------------------------\n")
            diagnosticLogs.forEach { append(it).append('\n') }
        }
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

        val isOpen = network.isOpen
        val isHexPsk = password.length == 64 && password.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        if (!isOpen && (password.length !in 8..63 && !isHexPsk)) {
            state = ProvisioningState.Failure("Mật khẩu WPA2 cần từ 8 đến 63 ký tự hoặc khóa hex 64 ký tự", code = "INVALID_PASSWORD")
            return
        }

        viewModelScope.launch {
            state = ProvisioningState.Provisioning

            // Ép Android ưu tiên định tuyến gói tin qua Wi-Fi (bỏ qua 4G/5G khi Wi-Fi loa không có Internet)
            val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val wifiNet = cm?.allNetworks?.firstOrNull { net ->
                cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (wifiNet != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.bindProcessToNetwork(wifiNet)
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
                    security = network.security
                )

                if (httpResult.isSuccess) {
                    status = "R1 đã nhận cấu hình Wi‑Fi! Đang đợi R1 kết nối vào mạng ${network.ssid}..."
                    waitForR1OnHomeNetwork(targetSsid = network.ssid)
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
                return@launch
            }

            val deviceName = device.name ?: "Muse R1"
            status = "Đang gửi Wi‑Fi tới $deviceName qua Bluetooth..."

            bluetoothProvisioner.provisionWifi(
                device = device,
                ssid = network.ssid,
                password = password
            ).fold(
                onSuccess = { ip ->
                    if (!ip.isNullOrBlank()) {
                        currentSpeakerIp = ip
                        status = "R1 đã kết nối Wi‑Fi nhà tại $ip! Đang kiểm tra kết nối..."
                        waitForR1OnHomeNetwork(knownIp = ip, targetSsid = network.ssid)
                    } else {
                        status = "Đã nhận xác nhận Bluetooth từ R1. Đang xác minh trên Wi‑Fi nhà..."
                        waitForR1OnHomeNetwork(targetSsid = network.ssid)
                    }
                },
                onFailure = { error ->
                    state = ProvisioningState.Failure(
                        "Gửi cấu hình qua Bluetooth thất bại (${error.message ?: "ngắt kết nối"}). Vui lòng kết nối Wi‑Fi điện thoại vào trạm phát của loa (Phicomm_R1_xxx) để truyền trực tiếp qua Wi‑Fi.",
                        code = "BLUETOOTH_FAILED"
                    )
                }
            )
        }
    }

    private suspend fun waitForR1OnHomeNetwork(
        bluetoothError: String? = null,
        knownIp: String? = null,
        targetSsid: String? = null
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

        repeat(24) {
            delay(5000)
            val discovered = discoveryClient.discover()
            if (discovered != null && !discovered.ipAddress.startsWith("192.168.43.")) {
                state = ProvisioningState.Success(discovered.ipAddress)
                status = "Đã xác minh R1 trên Wi‑Fi nhà: " + discovered.ipAddress
                return
            }
        }

        state = ProvisioningState.Failure(
            message = if (bluetoothError == null) {
                "Không tìm thấy R1 trên Wi‑Fi nhà sau 2 phút. Hãy kiểm tra mật khẩu và đảm bảo điện thoại đã kết nối vào cùng mạng Wi‑Fi nhà."
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
