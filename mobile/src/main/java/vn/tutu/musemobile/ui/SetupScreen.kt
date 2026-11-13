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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import vn.tutu.musemobile.domain.ProvisioningViewModel
import vn.tutu.musemobile.ui.components.AnimatedProgress
import vn.tutu.musemobile.ui.components.AppCard
import vn.tutu.musemobile.ui.components.CardHeader
import vn.tutu.musemobile.ui.components.ChipKind
import vn.tutu.musemobile.ui.components.StatusChip
import vn.tutu.musemobile.ui.components.InfoBanner
import vn.tutu.musemobile.ui.components.LoadingButton
import vn.tutu.musemobile.ui.components.LogConsole
import vn.tutu.musemobile.ui.components.StepBadge

private const val DEFAULT_APK_URL =
    "https://github.com/thanhtupppp/muse-android-r1/releases/download/v0.1.0/app-debug.apk"

/**
 * Tab Cài đặt loa: luồng 4 bước có thứ tự rõ ràng — tải APK, cài qua ADB,
 * kết nối Bluetooth (dự phòng), chọn Wi‑Fi nhà để provision.
 */
@Composable
fun SetupScreen(
    viewModel: ProvisioningViewModel,
    onOpenBluetooth: () -> Unit,
    onOpenWifi: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var speakerIp by remember(viewModel.speakerIp) { mutableStateOf(viewModel.speakerIp) }
    var apkUrl by remember { mutableStateOf(DEFAULT_APK_URL) }
    var pendingForgetSsid by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadSavedWifi()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        InfoBanner(
            text = "Luồng cài đặt: tải APK khi còn Internet → giữ nút tròn trên loa ~6 giây để mở hotspot → " +
                "nối Wi‑Fi điện thoại vào Phicomm_R1_xxx → cài APK qua ADB → chọn Wi‑Fi nhà."
        )

        // ===== Bước 1: Tải APK =====
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepBadge(number = 1, completed = viewModel.isApkDownloaded)
                Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                Text(
                    text = "Tải APK Muse R1 Agent",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = apkUrl,
                onValueChange = { apkUrl = it },
                label = { Text("URL APK Agent") },
                singleLine = true,
                enabled = !viewModel.isInstalling && !viewModel.isDownloading,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            LoadingButton(
                text = if (viewModel.isApkDownloaded) "Tải lại APK (đã có bản offline)" else "Tải APK về máy",
                loadingText = "Đang tải APK...",
                loading = viewModel.isDownloading,
                icon = Icons.Filled.Download,
                enabled = !viewModel.isInstalling,
                onClick = {
                    val url = apkUrl.trim()
                    if (url.startsWith("https://")) {
                        viewModel.downloadAgentApk(url)
                    } else {
                        Toast.makeText(context, "URL APK phải bắt đầu bằng https://", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            AnimatedVisibility(
                visible = viewModel.isDownloading,
                enter = fadeIn() + expandVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(10.dp))
                    AnimatedProgress(
                        progress = viewModel.downloadProgress?.let { it / 100f }
                    )
                    if (viewModel.downloadProgress != null) {
                        Text(
                            text = "Đang tải: ${viewModel.downloadProgress}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        // ===== Bước 2: Cài qua ADB =====
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepBadge(number = 2, completed = false)
                Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                Text(
                    text = "Cài Agent lên loa qua ADB",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = speakerIp,
                onValueChange = {
                    speakerIp = it
                    viewModel.setSpeakerIp(it.trim())
                },
                label = { Text("Địa chỉ loa (hotspot: 192.168.43.1)") },
                singleLine = true,
                enabled = !viewModel.isInstalling,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            LoadingButton(
                text = "Cài APK lên R1 qua ADB",
                loadingText = "Đang cài đặt qua ADB...",
                loading = viewModel.isInstalling,
                icon = Icons.Filled.InstallMobile,
                enabled = !viewModel.isDownloading,
                onClick = {
                    val ip = speakerIp.trim()
                    val url = apkUrl.trim()
                    viewModel.setSpeakerIp(ip)
                    if (viewModel.hasLocalApk() || url.startsWith("https://")) {
                        viewModel.installR1Agent(url)
                    } else {
                        Toast.makeText(context, "URL APK phải bắt đầu bằng https://", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            AnimatedVisibility(
                visible = viewModel.isInstalling || viewModel.installLogs.isNotEmpty(),
                enter = fadeIn() + expandVertically()
            ) {
                Column {
                    if (viewModel.installStage.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = viewModel.installStage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (viewModel.isInstalling) {
                        Spacer(modifier = Modifier.height(8.dp))
                        AnimatedProgress(progress = viewModel.installProgress)
                        if (viewModel.installProgress != null) {
                            Text(
                                text = "${(viewModel.installProgress!! * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                            )
                        }
                    }
                    if (viewModel.installLogs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        LogConsole(lines = viewModel.installLogs, maxHeight = 200.dp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${viewModel.installLogs.size} dòng nhật ký",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = {
                                clipboardManager.setText(AnnotatedString(viewModel.getInstallLogsText()))
                                Toast.makeText(context, "Đã sao chép nhật ký cài đặt", Toast.LENGTH_SHORT).show()
                            }) {
                                androidx.compose.material3.Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                                Text("Sao chép log")
                            }
                        }
                    }
                }
            }
        }

        // ===== Bước 3 + 4: Bluetooth & Wi‑Fi =====
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepBadge(number = 3, completed = false)
                Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                Text(
                    text = "Kết nối Bluetooth (đường dự phòng)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Quét và chọn loa R1 — kênh Bluetooth dùng khi không gửi được Wi‑Fi qua HTTP.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onOpenBluetooth,
                enabled = !viewModel.isInstalling,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.Bluetooth,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("Quét thiết bị Bluetooth")
            }
        }

        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepBadge(number = 4, completed = false)
                Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                Text(
                    text = "Chọn Wi‑Fi nhà cho loa",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Điện thoại cần đang ở Wi‑Fi của loa (Phicomm_R1_xxx) hoặc đã chọn loa qua Bluetooth ở bước 3.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            LoadingButton(
                text = "Chọn Wi‑Fi nhà",
                loadingText = "",
                loading = false,
                icon = Icons.Filled.Wifi,
                enabled = !viewModel.isInstalling,
                onClick = onOpenWifi
            )
        }

        // ===== Mạng Wi-Fi đã lưu trên loa =====
        AppCard {
            CardHeader(
                icon = Icons.Filled.Wifi,
                title = "Mạng Wi-Fi đã lưu trên loa",
                subtitle = "Chuyển loa sang mạng đã lưu hoặc quên mạng cũ đang lỗi để loa khỏi tự quay lại."
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (viewModel.savedWifiNetworks.isEmpty()) {
                Text(
                    text = if (viewModel.isLoadingSavedWifi) {
                        "Đang đọc danh sách mạng đã lưu từ loa..."
                    } else {
                        "Chưa đọc được mạng đã lưu nào. Hãy chắc chắn điện thoại đang liên lạc được với loa (cùng mạng hoặc hotspot của loa) rồi tải lại."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                viewModel.savedWifiNetworks.forEach { saved ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = saved.ssid,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                            if (saved.connected) {
                                StatusChip(text = "Đang dùng", kind = ChipKind.SUCCESS)
                            }
                        }
                        if (!saved.connected) {
                            TextButton(
                                onClick = { viewModel.useSavedWifi(saved.ssid) },
                                enabled = !viewModel.isSwitchingWifi
                            ) {
                                Text("Dùng")
                            }
                        }
                        TextButton(
                            onClick = {
                                if (pendingForgetSsid == saved.ssid) {
                                    pendingForgetSsid = null
                                    viewModel.forgetSavedWifi(saved.ssid)
                                } else {
                                    pendingForgetSsid = saved.ssid
                                }
                            }
                        ) {
                            Text(
                                text = if (pendingForgetSsid == saved.ssid) "Bấm nữa để quên" else "Quên",
                                color = if (pendingForgetSsid == saved.ssid) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
                if (viewModel.savedWifiNetworks.any { it.connected }) {
                    Text(
                        text = "Quên đúng mạng đang dùng sẽ làm loa rớt mạng ngay sau khi xác nhận.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.loadSavedWifi() },
                enabled = !viewModel.isLoadingSavedWifi,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Tải lại danh sách")
            }
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
