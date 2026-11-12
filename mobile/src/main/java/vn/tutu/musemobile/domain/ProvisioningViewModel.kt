package vn.tutu.musemobile.domain

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
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

    private val apiClient = R1ApiClient()
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

    private var selectedNetwork: WifiNetwork? = null
    private var speakerIp = "192.168.43.1"
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
        speakerIp = value
    }

    fun setPassword(value: String) {
        password = value
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
                    speakerIp = discovered.ipAddress
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
            state = ProvisioningState.Failure("Chưa chọn Wi‑Fi nhà")
            return
        }

        val isOpen = network.isOpen
        val isHexPsk = password.length == 64 && password.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        if (!isOpen && (password.length !in 8..63 && !isHexPsk)) {
            state = ProvisioningState.Failure("Mật khẩu WPA2 cần từ 8 đến 63 ký tự hoặc khóa hex 64 ký tự")
            return
        }

        viewModelScope.launch {
            state = ProvisioningState.Provisioning

            val device = bluetoothDevice ?: bluetoothProvisioner.findR1Device().getOrElse {
                state = ProvisioningState.Failure(
                    it.message ?: "Chưa chọn Muse R1 qua Bluetooth"
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
                        speakerIp = ip
                        status = "R1 đã kết nối Wi‑Fi nhà tại $ip! Đang kiểm tra kết nối..."
                        waitForR1OnHomeNetwork(knownIp = ip)
                    } else {
                        status = "Đã nhận xác nhận Bluetooth từ R1. Đang xác minh trên Wi‑Fi nhà..."
                        waitForR1OnHomeNetwork()
                    }
                },
                onFailure = { error ->
                    // The R1 may switch off its setup hotspot before the Bluetooth
                    // response reaches the phone. Verify on the home LAN before failing.
                    status = "Mất xác nhận Bluetooth (${error.message ?: "không rõ lỗi"}). Đang thử tìm R1 trên Wi‑Fi nhà..."
                    waitForR1OnHomeNetwork(
                        bluetoothError = error.message ?: "Không nhận được xác nhận Bluetooth"
                    )
                }
            )
        }
    }

    private suspend fun waitForR1OnHomeNetwork(
        bluetoothError: String? = null,
        knownIp: String? = null
    ) {
        state = ProvisioningState.WaitingForR1
        status = when {
            knownIp != null -> "R1 đã báo IP $knownIp. Đang kết nối thử..."
            bluetoothError == null -> "R1 đang kết nối Wi‑Fi nhà. Đang tìm R1 qua mạng nội bộ..."
            else -> "Bluetooth bị ngắt; đang xác minh R1 qua Wi‑Fi nhà. Hãy để điện thoại kết nối router nhà."
        }

        // Nếu loa đã gửi lại IP thật qua Bluetooth, probe trực tiếp trước
        if (knownIp != null) {
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
            if (discovered != null) {
                state = ProvisioningState.Success(discovered.ipAddress)
                status = "Đã xác minh R1 trên Wi‑Fi nhà: " + discovered.ipAddress
                return
            }
        }

        state = ProvisioningState.Failure(
            if (bluetoothError == null) {
                "Không tìm thấy R1 trên Wi‑Fi nhà sau 2 phút. Hãy kết nối điện thoại vào Wi‑Fi nhà rồi thử lại."
            } else {
                "Không nhận được xác nhận Bluetooth và cũng không tìm thấy R1 trên Wi‑Fi nhà. Bluetooth: $bluetoothError. Hãy kết nối điện thoại vào Wi‑Fi nhà rồi xác minh lại."
            }
        )
    }

    fun retry() {
        state = ProvisioningState.Idle
        status = "Sẵn sàng thử lại."
    }

    fun downloadAgentApk(apkUrl: String) {
        if (isDownloading) return
        isDownloading = true
        downloadProgress = 0
        viewModelScope.launch {
            try {
                status = "Bắt đầu tải APK từ: $apkUrl"
                installer.downloadAgentApk(apkUrl) { percent ->
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
                    val downloadResult = installer.downloadAgentApk(apkUrl) { percent ->
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
                    apkFile = localApk
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
