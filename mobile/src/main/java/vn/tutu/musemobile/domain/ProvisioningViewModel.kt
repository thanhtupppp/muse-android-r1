package vn.tutu.musemobile.domain

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.tutu.musemobile.data.R1ApiClient
import vn.tutu.musemobile.data.WifiScanner

class ProvisioningViewModel(application: Application) : AndroidViewModel(application) {

    private val apiClient = R1ApiClient()
    private val wifiScanner = WifiScanner(application)

    var state by mutableStateOf<ProvisioningState>(ProvisioningState.Idle)
        private set

    var status by mutableStateOf("Nhập địa chỉ R1 rồi kiểm tra kết nối.")
        private set

    var wifiNetworks by mutableStateOf<List<WifiNetwork>>(emptyList())
        private set

    private var selectedNetwork: WifiNetwork? = null
    private var speakerIp = "192.168.43.1"
    private var password = ""

    fun setSpeakerIp(value: String) {
        speakerIp = value
    }

    fun setPassword(value: String) {
        password = value
    }

    fun selectWifiNetwork(network: WifiNetwork) {
        selectedNetwork = network
        state = ProvisioningState.SelectingWifi
        status = "Đã chọn ${network.ssid}. Nhập mật khẩu để tiếp tục."
    }

    fun refreshWifiNetworks() {
        viewModelScope.launch {
            wifiNetworks = wifiScanner.scan()

            status = if (wifiNetworks.isEmpty()) {
                "Chưa có kết quả quét. Hãy cấp quyền vị trí và thử lại."
            } else {
                "Đã tìm thấy ${wifiNetworks.size} mạng Wi‑Fi."
            }
        }
    }

    fun checkR1() {
        viewModelScope.launch {
            state = ProvisioningState.CheckingR1
            status = "Đang kiểm tra R1 tại $speakerIp..."

            apiClient.health(speakerIp).fold(
                onSuccess = { reachable ->
                    if (reachable) {
                        status = "Đã tìm thấy Muse R1 Agent."
                    } else {
                        status = "Không tìm thấy Muse R1 Agent tại $speakerIp:8080."
                    }
                },
                onFailure = {
                    status = "Không kết nối được R1. Hãy kiểm tra Wi‑Fi hotspot Phicomm_R1_xxx."
                }
            )
        }
    }

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
            status = "Đang gửi cấu hình Wi‑Fi tới R1..."

            val result = apiClient.provisionWifi(
                speakerIp = speakerIp,
                ssid = network.ssid,
                password = password
            )

            result.fold(
                onSuccess = {
                    waitForR1()
                },
                onFailure = {
                    state = ProvisioningState.Failure(it.message ?: "Không gửi được cấu hình")
                }
            )
        }
    }

    private suspend fun waitForR1() {
        state = ProvisioningState.WaitingForR1
        status = "Đang xác nhận R1 đã vào Wi‑Fi nhà..."

        repeat(12) {
            delay(5_000)

            val statusResult = apiClient.status(speakerIp)

            statusResult.fold(
                onSuccess = { response ->
                    val networkText = response.optString("network")

                    if (networkText.contains("IP:")) {
                        val ip = networkText.substringAfter("IP:").trim()
                        state = ProvisioningState.Success(ip)
                        status = "R1 đã kết nối Wi‑Fi nhà."
                        return
                    }
                },
                onFailure = {
                    // R1 đã rời hotspot nên lỗi kết nối là bình thường.
                }
            )
        }

        state = ProvisioningState.Failure("Không xác nhận được R1 sau khi đổi Wi‑Fi.")
    }

    fun retry() {
        state = ProvisioningState.Idle
        status = "Sẵn sàng thử lại."
    }
}
