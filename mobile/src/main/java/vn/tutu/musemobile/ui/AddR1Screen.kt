package vn.tutu.musemobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.tutu.musemobile.domain.ProvisioningViewModel

@Composable
fun AddR1Screen(
    viewModel: ProvisioningViewModel,
    onRequestWifiPermission: () -> Unit,
    onR1Found: () -> Unit
) {
    var speakerIp by remember { mutableStateOf("192.168.43.1") }
    var apkUrl by remember {
        mutableStateOf("https://github.com/thanhtupppp/muse-android-r1/releases/download/v0.1.0/app-debug.apk")
    }

    val scrollState = rememberScrollState()

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(20.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Thêm loa Muse R1",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text("1. Kết nối Wi‑Fi nhà hoặc 4G, bấm 'Tải APK về máy'.", style = MaterialTheme.typography.bodyMedium)
            Text("2. Giữ nút tròn trên loa R1 ~6 giây để mở hotspot.", style = MaterialTheme.typography.bodyMedium)
            Text("3. Đổi Wi‑Fi điện thoại sang Phicomm_R1_xxx.", style = MaterialTheme.typography.bodyMedium)
            Text("4. Bấm 'Cài APK lên R1 qua ADB' (không cần internet).", style = MaterialTheme.typography.bodyMedium)
            Text("5. Chọn Wi‑Fi nhà và truyền qua Bluetooth.", style = MaterialTheme.typography.bodyMedium)

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = apkUrl,
                onValueChange = { apkUrl = it },
                label = { Text("URL APK Muse R1 Agent") },
                singleLine = true,
                enabled = !viewModel.isInstalling && !viewModel.isDownloading,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    viewModel.downloadAgentApk(apkUrl.trim())
                },
                enabled = !viewModel.isInstalling && !viewModel.isDownloading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        viewModel.isDownloading -> "Đang tải APK về máy..."
                        viewModel.isApkDownloaded -> "1. Tải lại APK về máy (Đã có sẵn offline ✔️)"
                        else -> "1. Tải APK về máy (khi có Internet)"
                    }
                )
            }

            if (viewModel.isDownloading) {
                Spacer(modifier = Modifier.height(6.dp))
                val p = viewModel.downloadProgress
                if (p != null) {
                    LinearProgressIndicator(
                        progress = { p / 100f },
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )
                    Text(
                        text = "Đang tải: $p%",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.End).padding(top = 2.dp)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = speakerIp,
                onValueChange = { speakerIp = it },
                label = { Text("Địa chỉ loa") },
                singleLine = true,
                enabled = !viewModel.isInstalling,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    viewModel.setSpeakerIp(speakerIp.trim())
                    viewModel.installR1Agent(apkUrl.trim())
                },
                enabled = !viewModel.isInstalling && !viewModel.isDownloading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (viewModel.isInstalling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Đang cài đặt qua ADB...")
                } else {
                    Text("2. Cài APK lên R1 qua ADB")
                }
            }

            // Realtime ADB installation progress & log terminal
            if (viewModel.isInstalling || viewModel.installLogs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (viewModel.isInstalling) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = if (viewModel.isInstalling) "Tiến trình cài đặt ADB" else "Nhật ký cài đặt ADB",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (viewModel.installStage.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = viewModel.installStage,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        if (viewModel.isInstalling) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val prog = viewModel.installProgress
                            if (prog != null) {
                                LinearProgressIndicator(
                                    progress = { prog },
                                    modifier = Modifier.fillMaxWidth().height(8.dp)
                                )
                                Text(
                                    text = "${(prog * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp)
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().height(8.dp)
                                )
                            }
                        }

                        val logs = viewModel.installLogs
                        if (logs.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                color = Color(0xFF1E1E1E),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 60.dp, max = 180.dp)
                            ) {
                                val logListState = rememberLazyListState()
                                LaunchedEffect(logs.size) {
                                    if (logs.isNotEmpty()) {
                                        logListState.animateScrollToItem(logs.size - 1)
                                    }
                                }
                                LazyColumn(
                                    state = logListState,
                                    modifier = Modifier.padding(8.dp)
                                ) {
                                    items(logs) { log ->
                                        val color = when {
                                            log.contains("❌") || log.contains("Lỗi") || log.contains("thất bại", ignoreCase = true) -> Color(0xFFFF6B6B)
                                            log.contains("✅") || log.contains("thành công", ignoreCase = true) -> Color(0xFF51CF66)
                                            log.contains("Bắt đầu") -> Color(0xFF74C0FC)
                                            log.contains("1/5") || log.contains("2/5") || log.contains("3/5") || log.contains("4/5") || log.contains("5/5") -> Color(0xFFFFD43B)
                                            else -> Color(0xFFE0E0E0)
                                        }
                                        Text(
                                            text = log,
                                            color = color,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    viewModel.setSpeakerIp(speakerIp.trim())
                    viewModel.checkR1()
                },
                enabled = !viewModel.isInstalling,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Kiểm tra kết nối R1")
            }

            Spacer(modifier = Modifier.height(10.dp))

            TextButton(
                onClick = onRequestWifiPermission,
                enabled = !viewModel.isInstalling
            ) {
                Text("Cấp quyền quét Wi‑Fi")
            }

            TextButton(
                onClick = onR1Found,
                enabled = !viewModel.isInstalling
            ) {
                Text("Chọn Wi‑Fi nhà")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = viewModel.status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
