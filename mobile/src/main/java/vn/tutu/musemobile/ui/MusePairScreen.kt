package vn.tutu.musemobile.ui

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
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.tutu.musemobile.domain.ProvisioningViewModel
import vn.tutu.musemobile.ui.components.AppCard
import vn.tutu.musemobile.ui.components.CardHeader
import vn.tutu.musemobile.ui.components.ChipKind
import vn.tutu.musemobile.ui.components.LoadingButton
import vn.tutu.musemobile.ui.components.PasteTextField
import vn.tutu.musemobile.ui.components.StatusChip
import vn.tutu.musemobile.ui.theme.StatusColors

/**
 * Tab Ghép Muse: cấu hình kết nối Muse cho loa bằng SDK Token (mgst_) hoặc
 * Direct VM (host + VM ID + device token), trạng thái ghép đôi hiển thị rõ.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusePairScreen(viewModel: ProvisioningViewModel) {
    var speakerIp by remember(viewModel.speakerIp) { mutableStateOf(viewModel.speakerIp) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AppCard(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)) {
            CardHeader(
                icon = Icons.Filled.SmartToy,
                title = "Ghép đôi Muse AI",
                subtitle = "Kết nối loa với trợ lý Muse của bạn"
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    viewModel.isMuseConnected ->
                        StatusChip("ĐÃ KẾT NỐI CLOUD", ChipKind.SUCCESS)
                    viewModel.isMusePaired ->
                        StatusChip("ĐÃ CẤU HÌNH", ChipKind.INFO)
                    else ->
                        StatusChip("CHƯA CẤU HÌNH", ChipKind.NEUTRAL)
                }
                if (viewModel.isPairingMuse) {
                    StatusChip("ĐANG XỬ LÝ...", ChipKind.WARNING)
                }
            }
        }

        AppCard {
            Text(
                text = "Địa chỉ loa & cách kết nối",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = speakerIp,
                onValueChange = {
                    speakerIp = it
                    viewModel.setSpeakerIp(it.trim())
                },
                label = { Text("Địa chỉ IP loa R1") },
                placeholder = { Text("192.168.1.x") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = viewModel.useDirectVmConfig,
                    onClick = { viewModel.setDirectMode(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text("Direct VM")
                }
                SegmentedButton(
                    selected = !viewModel.useDirectVmConfig,
                    onClick = { viewModel.setDirectMode(false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text("SDK Token")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (viewModel.useDirectVmConfig) {
                    "Giống bản ESP32: nhập Server, VM ID và Device Token để R1 nối thẳng Noise XX tới máy chủ Muse."
                } else {
                    "Nhập Muse SDK Token (mgst_...) lấy từ gadgets.muse.ai để loa tự nhận VM."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AppCard {
            if (viewModel.useDirectVmConfig) {
                Text(
                    text = "Thông số Direct VM",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                PasteTextField(
                    value = viewModel.currentNoiseHost,
                    onValueChange = { viewModel.setNoiseHost(it) },
                    label = "Muse Server Host",
                    placeholder = "hatch.metaaivm.com"
                )
                Spacer(modifier = Modifier.height(10.dp))
                PasteTextField(
                    value = viewModel.currentVmId,
                    onValueChange = { viewModel.setVmId(it) },
                    label = "VM ID",
                    placeholder = "vm-xxxxxxxx..."
                )
                Spacer(modifier = Modifier.height(10.dp))
                PasteTextField(
                    value = viewModel.currentDeviceToken,
                    onValueChange = { viewModel.setDeviceToken(it) },
                    label = "Device Token",
                    placeholder = "Nhập device token từ Muse..."
                )
                Spacer(modifier = Modifier.height(16.dp))
                LoadingButton(
                    text = "Lưu & kết nối Direct VM",
                    loadingText = "Đang lưu cấu hình...",
                    loading = viewModel.isPairingMuse,
                    onClick = {
                        viewModel.setSpeakerIp(speakerIp.trim())
                        viewModel.configureMuseDirect(
                            ip = speakerIp.trim(),
                            serverHost = viewModel.currentNoiseHost,
                            vmId = viewModel.currentVmId,
                            deviceToken = viewModel.currentDeviceToken
                        )
                    }
                )
            } else {
                Text(
                    text = "Muse SDK Token",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                PasteTextField(
                    value = viewModel.museTokenInput,
                    onValueChange = { viewModel.setMuseTokenInput(it) },
                    label = "Muse SDK Token (mgst_...)",
                    placeholder = "mgst_xxxxxx..."
                )
                Spacer(modifier = Modifier.height(16.dp))
                LoadingButton(
                    text = if (viewModel.isMusePaired) "Cập nhật Token Muse" else "Ghép đôi với Muse",
                    loadingText = "Đang gửi token tới R1...",
                    loading = viewModel.isPairingMuse,
                    onClick = {
                        viewModel.setSpeakerIp(speakerIp.trim())
                        viewModel.configureMuseToken(speakerIp.trim(), viewModel.museTokenInput)
                    }
                )
            }

            AnimatedVisibility(
                visible = viewModel.musePairStatus.isNotEmpty(),
                enter = fadeIn() + expandVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = viewModel.musePairStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (viewModel.musePairStatus.contains("✅")) {
                            StatusColors.Success
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
            }
        }
    }
}
