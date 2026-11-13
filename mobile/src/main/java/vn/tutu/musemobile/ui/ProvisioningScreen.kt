package vn.tutu.musemobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.tutu.musemobile.domain.ProvisioningState
import vn.tutu.musemobile.domain.ProvisioningViewModel
import vn.tutu.musemobile.ui.components.AnimatedProgress
import vn.tutu.musemobile.ui.components.AppCard

/**
 * Màn hình theo dõi tiến trình provision: một thẻ trạng thái duy nhất thay
 * đổi theo ProvisioningState (đang chạy / thành công / thất bại).
 */
@Composable
fun ProvisioningScreen(
    viewModel: ProvisioningViewModel,
    onBack: () -> Unit = {},
    onDone: () -> Unit = {}
) {
    val state = viewModel.state

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        AppCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (state) {
                    is ProvisioningState.Success -> {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "R1 đã kết nối Wi‑Fi nhà!",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Địa chỉ IP mới của loa: ${state.ip}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = {
                                viewModel.setSpeakerIp(state.ip)
                                viewModel.retry(resetNetwork = true)
                                onDone()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text("Tiếp tục: Ghép đôi Muse AI")
                        }
                    }

                    is ProvisioningState.Failure -> {
                        Icon(
                            imageVector = Icons.Filled.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Cấu hình thất bại",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        val errorDetail = if (!state.code.isNullOrBlank()) {
                            "[${state.code}] ${state.message}"
                        } else {
                            state.message
                        }
                        Text(
                            text = errorDetail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.retry(resetNetwork = true)
                                    onBack()
                                },
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Text("Đổi mạng")
                            }
                            Button(
                                onClick = { viewModel.startProvisioning() },
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Text("Thử lại")
                            }
                        }
                    }

                    else -> {
                        val (title, subtitle) = when (state) {
                            is ProvisioningState.CheckingR1 ->
                                "Đang kiểm tra Agent..." to "Đang gọi tới Muse R1 Agent trên loa."
                            is ProvisioningState.SelectingWifi ->
                                "Chọn Wi‑Fi nhà" to "Hãy chọn Wi‑Fi nhà để cấu hình cho loa."
                            is ProvisioningState.Provisioning ->
                                "Đang gửi cấu hình..." to "Đang truyền SSID/mật khẩu tới R1."
                            is ProvisioningState.WaitingForR1 ->
                                "Đang chờ loa vào mạng nhà..." to
                                    "Hãy chuyển Wi‑Fi điện thoại về mạng nhà để tìm thấy loa."
                            else ->
                                "Sẵn sàng" to "Bấm Quay lại để bắt đầu cấu hình."
                        }
                        Icon(
                            imageVector = Icons.Filled.Wifi,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        AnimatedProgress(progress = null)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                            Text(
                                text = "Đang xử lý, đừng đóng ứng dụng...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = {
                                if (state !is ProvisioningState.SelectingWifi &&
                                    state !is ProvisioningState.Idle
                                ) {
                                    viewModel.retry()
                                }
                                onBack()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(if (state is ProvisioningState.SelectingWifi || state is ProvisioningState.Idle) "Quay lại chọn Wi‑Fi" else "Hủy")
                        }
                    }
                }

                if (viewModel.status.isNotBlank() && state !is ProvisioningState.Success) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = viewModel.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
