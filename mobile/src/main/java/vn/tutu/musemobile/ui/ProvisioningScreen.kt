package vn.tutu.musemobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.tutu.musemobile.domain.ProvisioningState
import vn.tutu.musemobile.domain.ProvisioningViewModel

@Composable
fun ProvisioningScreen(
    viewModel: ProvisioningViewModel,
    onBack: () -> Unit = {},
    onDone: () -> Unit = {}
) {
    val state = viewModel.state

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Cấu hình Wi‑Fi R1",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(24.dp))

        when (state) {
            is ProvisioningState.CheckingR1 -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(16.dp))
                Text("Đang kiểm tra Muse R1 Agent...")
            }

            is ProvisioningState.SelectingWifi -> {
                Text("Hãy chọn Wi‑Fi nhà để cấu hình cho loa.")
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onBack) {
                    Text("Quay lại chọn Wi‑Fi")
                }
            }

            is ProvisioningState.Provisioning -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(16.dp))
                Text("Đang gửi cấu hình Wi‑Fi tới R1...")
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = {
                    viewModel.retry()
                    onBack()
                }) {
                    Text("Hủy")
                }
            }

            is ProvisioningState.WaitingForR1 -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(16.dp))
                Text("Đang xác nhận R1 đã kết nối Wi‑Fi nhà...")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Hãy chuyển Wi‑Fi điện thoại về mạng nhà để tìm thấy loa.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = {
                    viewModel.retry()
                    onBack()
                }) {
                    Text("Hủy / Quay lại")
                }
            }

            is ProvisioningState.Success -> {
                Text(
                    text = "🎉 R1 đã kết nối Wi‑Fi nhà thành công!",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Địa chỉ IP: ${state.ip}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        viewModel.setSpeakerIp(state.ip)
                        viewModel.retry(resetNetwork = true)
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Hoàn tất & Ghép đôi Muse AI 🚀")
                }
            }

            is ProvisioningState.Failure -> {
                val errorDetail = if (!state.code.isNullOrBlank()) "[${state.code}] ${state.message}" else state.message
                Text(
                    text = "Cấu hình thất bại",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorDetail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.retry(resetNetwork = true)
                            onBack()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Đổi mạng")
                    }
                    Button(
                        onClick = {
                            viewModel.startProvisioning()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Thử lại")
                    }
                }
            }

            ProvisioningState.Idle -> {
                Text("Sẵn sàng cấu hình.")
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onBack) {
                    Text("Quay lại")
                }
            }
        }

        if (viewModel.status.isNotBlank() && state !is ProvisioningState.Success) {
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = viewModel.status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
