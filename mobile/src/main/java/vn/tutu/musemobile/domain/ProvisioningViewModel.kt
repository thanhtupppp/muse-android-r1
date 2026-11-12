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

    var wifiNetworks by mutableStateOf<List<WifiNetwork>>(emptyList())
        private set

    private var selectedNetwork: WifiNetwork? = null
    private var speakerIp = "192.168.43.1"
    private var password = ""
    private var bluetoothDevice: BluetoothDevice? = null

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
        status = "Đã chọn " + network.ssid + ". Nhập mật khẩu để tiếp tục."
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
        viewModelScope.launch {
            state = ProvisioningState.CheckingR1
            status = "Đang kiểm tra R1 tại " + speakerIp + "..."

            apiClient.health(speakerIp).fold(
                onSuccess = { reachable ->
                    status = if (reachable) {
                        "Đã tìm thấy Muse R1 Agent."
                    } else {
                        "Không tìm thấy Muse R1 Agent tại " + speakerIp + ":8080."
                    }
                },
                onFailure = {
                    status = "Không kết nối được R1. Hãy kiểm tra Wi‑Fi hotspot Phicomm_R1_xxx."
                }
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun startProvisioning() {
        val network = selectedNetwork

        if (network == null) {
            state = ProvisioningState.Failure("Chưa chọn Wi‑Fi nhà")
            return
        }

        if (password.length < 8) {
            state = ProvisioningState.Failure("Mật khẩu WPA2 cần ít nhất 8 ký tự")
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
                onSuccess = {
                    status = "Đã gửi Wi‑Fi tới R1. Đang chờ R1 kết nối..."
                    waitForR1OnHomeNetwork()
                },
                onFailure = { error ->
                    state = ProvisioningState.Failure(
                        error.message ?: "Không gửi được Wi‑Fi qua Bluetooth"
                    )
                }
            )
        }
    }

    private suspend fun waitForR1OnHomeNetwork() {
        state = ProvisioningState.WaitingForR1
        status = "R1 đang kết nối Wi‑Fi nhà. Không tắt nguồn loa."

        repeat(24) {
            delay(5000)

            val discovered = discoveryClient.discover()

            if (discovered != null) {
                state = ProvisioningState.Success(discovered.ipAddress)
                status = "R1 đã kết nối Wi‑Fi nhà: " + discovered.ipAddress
                return
            }
        }

        state = ProvisioningState.Failure(
            "Không tìm thấy R1 trên Wi‑Fi nhà sau 2 phút. Hãy kiểm tra router hoặc dùng ADB."
        )
    }

    fun retry() {
        state = ProvisioningState.Idle
        status = "Sẵn sàng thử lại."
    }

    fun installR1Agent(apkUrl: String) {
        viewModelScope.launch {
            status = "Bắt đầu tải Agent APK..."
            installer.downloadAgentApk(apkUrl) { percent ->
                status = "Đang tải Agent: $percent%"
            }.onSuccess { apkFile ->
                status = "Đang cài Agent lên R1 qua ADB..."
                installer.installAgent(
                    speakerIp = speakerIp,
                    apkFile = apkFile
                ) { message ->
                    status = message
                }.onFailure { error ->
                    status = "Lỗi cài Agent: ${error.message}"
                }
            }.onFailure { error ->
                status = "Lỗi tải APK: ${error.message}"
            }
        }
    }
}
