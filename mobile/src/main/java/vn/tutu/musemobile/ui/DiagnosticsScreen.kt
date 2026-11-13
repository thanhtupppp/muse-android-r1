package vn.tutu.musemobile.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import vn.tutu.musemobile.domain.ProvisioningViewModel
import vn.tutu.musemobile.ui.components.AppCard
import vn.tutu.musemobile.ui.components.CardHeader
import vn.tutu.musemobile.ui.components.LoadingButton
import vn.tutu.musemobile.ui.components.LogConsole

/**
 * Tab Chẩn đoán: kiểm tra kết nối sâu (discovery + ADB diagnose), nhật ký
 * chẩn đoán có sao chép, và các quyền hệ thống app cần.
 */
@Composable
fun DiagnosticsScreen(
    viewModel: ProvisioningViewModel,
    onRequestWifiPermission: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var speakerIp by remember(viewModel.speakerIp) { mutableStateOf(viewModel.speakerIp) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AppCard {
            CardHeader(
                icon = Icons.Filled.Build,
                title = "Kiểm tra kết nối R1",
                subtitle = "Discovery UDP, trạng thái HTTP và chẩn đoán ADB"
            )
            Spacer(modifier = Modifier.height(12.dp))
            androidx.compose.material3.OutlinedTextField(
                value = speakerIp,
                onValueChange = {
                    speakerIp = it
                    viewModel.setSpeakerIp(it.trim())
                },
                label = { Text("Địa chỉ IP loa cần kiểm tra") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            LoadingButton(
                text = "Chạy chẩn đoán",
                loadingText = "Đang chẩn đoán R1...",
                loading = viewModel.isCheckingR1,
                onClick = {
                    viewModel.setSpeakerIp(speakerIp.trim())
                    viewModel.checkR1()
                }
            )
            AnimatedVisibility(
                visible = viewModel.diagnosticLogs.isNotEmpty(),
                enter = fadeIn() + expandVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    LogConsole(lines = viewModel.diagnosticLogs, maxHeight = 300.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${viewModel.diagnosticLogs.size} dòng",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = {
                            clipboardManager.setText(AnnotatedString(viewModel.getDiagnosticsText()))
                            Toast.makeText(context, "Đã sao chép nhật ký chẩn đoán", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text("Sao chép")
                        }
                    }
                }
            }
        }

        AppCard {
            CardHeader(
                icon = Icons.Filled.Lock,
                title = "Quyền hệ thống",
                subtitle = "App cần quyền vị trí để quét Wi‑Fi và Bluetooth"
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onRequestWifiPermission,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Cấp quyền quét Wi‑Fi / vị trí")
            }
        }

        AppCard {
            CardHeader(
                icon = Icons.Filled.Info,
                title = "Giới thiệu",
                subtitle = "Muse Mobile — điều khiển & cài đặt loa Phicomm R1"
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Ứng dụng cài Agent lên loa R1 qua ADB, gửi cấu hình Wi‑Fi qua HTTP/Bluetooth, " +
                    "ghép đôi Muse và chẩn đoán kết nối. Mọi giao tiếp cấu hình sau khi loa đã ghép đôi " +
                    "đều được xác thực bằng provision token.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (viewModel.status.isNotBlank()) {
            Text(
                text = viewModel.status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
