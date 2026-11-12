package vn.tutu.musemobile.domain

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import vn.tutu.musemobile.data.R1ApiClient

class ProvisioningViewModel : ViewModel() {

    private val apiClient = R1ApiClient()

    var status by mutableStateOf("Nhập thông tin Wi‑Fi nhà rồi gửi cho R1.")
        private set

    fun provision(speakerIp: String, ssid: String, password: String) {
        if (speakerIp.isBlank() || ssid.isBlank() || password.length < 8) {
            status = "Thông tin chưa hợp lệ. Mật khẩu WPA2 cần ít nhất 8 ký tự."
            return
        }

        viewModelScope.launch {
            status = "Đang kiểm tra R1 tại $speakerIp..."

            val health = apiClient.health(speakerIp)

            health.fold(
                onSuccess = { reachable ->
                    if (!reachable) {
                        status = "Không tìm thấy Muse R1 Agent tại $speakerIp:8080."
                        return@fold
                    }

                    status = "Đã tìm thấy R1. Đang gửi cấu hình Wi‑Fi..."
                    val result = apiClient.provisionWifi(speakerIp, ssid, password)

                    result.fold(
                        onSuccess = { status = it },
                        onFailure = { status = "Lỗi gửi cấu hình: ${it.message}" }
                    )
                },
                onFailure = {
                    status = "Không kết nối được R1. Hãy kiểm tra Wi‑Fi hotspot Phicomm_R1_xxx."
                }
            )
        }
    }
}
