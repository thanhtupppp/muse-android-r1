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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.tutu.musemobile.domain.ProvisioningViewModel
import vn.tutu.musemobile.ui.components.AppCard
import vn.tutu.musemobile.ui.components.CardHeader
import vn.tutu.musemobile.ui.components.ChipKind
import vn.tutu.musemobile.ui.components.LoadingButton
import vn.tutu.musemobile.ui.components.StatusChip
import vn.tutu.musemobile.ui.theme.StatusColors

/**
 * Tab Tổng quan: trạng thái loa nhìn một cái là hiểu, kèm điều khiển nhanh
 * (kiểm tra kết nối, âm lượng bằng slider, thử TTS).
 */
@Composable
fun HomeScreen(viewModel: ProvisioningViewModel) {
    var speakerIp by remember(viewModel.speakerIp) { mutableStateOf(viewModel.speakerIp) }
    var checking by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ===== Thẻ hero trạng thái loa =====
        AppCard(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)) {
            CardHeader(
                icon = Icons.Filled.Speaker,
                title = "Loa Muse R1",
                subtitle = "Phicomm R1 • Muse Gadget"
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    viewModel.isMuseConnected ->
                        StatusChip("Muse: ĐÃ KẾT NỐI CLOUD", ChipKind.SUCCESS)
                    viewModel.isMusePaired ->
                        StatusChip("Muse: ĐÃ CẤU HÌNH", ChipKind.INFO)
                    else ->
                        StatusChip("Muse: CHƯA CẤU HÌNH", ChipKind.NEUTRAL)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = speakerIp,
                onValueChange = {
                    speakerIp = it
                    viewModel.setSpeakerIp(it.trim())
                },
                label = { Text("Địa chỉ IP của loa") },
                placeholder = { Text("192.168.1.x hoặc 192.168.43.1 khi ở hotspot") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            LoadingButton(
                text = "Kiểm tra trạng thái loa",
                loadingText = "Đang kiểm tra...",
                loading = checking,
                icon = Icons.Filled.Refresh,
                onClick = {
                    checking = true
                    viewModel.setSpeakerIp(speakerIp.trim())
                    viewModel.checkMuseStatus(speakerIp.trim())
                    checking = false
                }
            )
            // VM thực tế loa đang kết nối (server cấp ở chế độ SDK token) —
            // hiện kèm nút sao chép để mang sang cấu hình Direct VM khi cần.
            AnimatedVisibility(
                visible = viewModel.activeVmId.isNotEmpty(),
                enter = fadeIn() + expandVertically()
            ) {
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "VM đang kết nối",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = buildString {
                                append(viewModel.activeVmId)
                                if (viewModel.activeVmName.isNotEmpty()) {
                                    append(" • ")
                                    append(viewModel.activeVmName)
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    IconButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(viewModel.activeVmId))
                    }) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Sao chép VM ID"
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = viewModel.commandResult.isNotEmpty(),
                enter = fadeIn() + expandVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    androidx.compose.material3.Surface(
                        color = StatusColors.ConsoleBackground,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = viewModel.commandResult,
                            color = StatusColors.ConsoleText,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }

        // ===== Điều khiển nhanh =====
        AppCard {
            CardHeader(
                icon = Icons.Filled.VolumeUp,
                title = "Điều khiển nhanh",
                subtitle = "Áp dụng ngay trên loa qua mạng nội bộ"
            )
            Spacer(modifier = Modifier.height(16.dp))

            var volume by remember(viewModel.speakerVolume) {
                mutableFloatStateOf(viewModel.speakerVolume.toFloat())
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Âm lượng",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                StatusChip("${volume.toInt()}%", ChipKind.INFO)
            }
            Slider(
                value = volume,
                onValueChange = { volume = it },
                onValueChangeFinished = {
                    viewModel.setSpeakerIp(speakerIp.trim())
                    viewModel.setSpeakerVolume(volume.toInt(), speakerIp.trim())
                },
                valueRange = 0f..100f,
                steps = 19
            )

            Spacer(modifier = Modifier.height(8.dp))

            var ttsText by remember {
                mutableStateOf("Xin chào, Muse R1 đã sẵn sàng hoạt động!")
            }
            OutlinedTextField(
                value = ttsText,
                onValueChange = { ttsText = it },
                label = { Text("Câu nói thử (TTS)") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            LoadingButton(
                text = "Phát thử trên loa",
                loadingText = "Đang gửi...",
                loading = false,
                icon = Icons.Filled.PlayArrow,
                onClick = {
                    viewModel.setSpeakerIp(speakerIp.trim())
                    viewModel.testTts(ttsText, speakerIp.trim())
                }
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
