package vn.tutu.musemobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.tutu.musemobile.domain.ProvisioningViewModel
import vn.tutu.musemobile.domain.ProvisioningState

@Composable
fun ProvisioningScreen(viewModel: ProvisioningViewModel) {
    val state = viewModel.state

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Đang cấu hình R1",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(16.dp))

        when (state) {
            is ProvisioningState.CheckingR1 -> {
                LinearProgressIndicator()
                Text("Đang kiểm tra Muse R1 Agent...")
            }

            is ProvisioningState.SelectingWifi -> {
                Text("Hãy chọn Wi‑Fi nhà.")
            }

            is ProvisioningState.Provisioning -> {
                LinearProgressIndicator()
                Text("Đã gửi cấu hình Wi‑Fi. R1 đang kết nối mạng nhà...")
            }

            is ProvisioningState.WaitingForR1 -> {
                LinearProgressIndicator()
                Text("Đang xác nhận R1 đã vào Wi‑Fi nhà...")
            }

            is ProvisioningState.Success -> {
                Text("R1 đã kết nối Wi‑Fi nhà.")
                Text("IP: ${state.ip}")
            }

            is ProvisioningState.Failure -> {
                Text("Cấu hình thất bại: ${state.message}")
                Button(onClick = { viewModel.retry() }) {
                    Text("Thử lại")
                }
            }

            ProvisioningState.Idle -> {
                Text("Sẵn sàng cấu hình.")
            }
        }
    }
}
