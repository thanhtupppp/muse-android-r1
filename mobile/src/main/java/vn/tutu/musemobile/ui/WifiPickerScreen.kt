package vn.tutu.musemobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import vn.tutu.musemobile.domain.ProvisioningState
import vn.tutu.musemobile.domain.ProvisioningViewModel
import vn.tutu.musemobile.ui.components.InfoBanner
import vn.tutu.musemobile.ui.components.LoadingButton

@Composable
fun WifiPickerScreen(
    viewModel: ProvisioningViewModel,
    onBack: () -> Unit,
    onProvisioningStarted: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val networks = viewModel.wifiNetworks.filter {
        !it.ssid.startsWith("Phicomm_R1_", ignoreCase = true) &&
            !it.ssid.startsWith("PhiWiFi-", ignoreCase = true)
    }
    val selectedNetwork = viewModel.selectedNetwork

    LaunchedEffect(Unit) {
        if (viewModel.wifiNetworks.isEmpty()) {
            viewModel.refreshWifiNetworks()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Chọn Wi‑Fi nhà",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Loa sẽ kết nối vào mạng bạn chọn.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { viewModel.refreshWifiNetworks() }) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Quét lại Wi‑Fi"
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (networks.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Chưa tìm thấy mạng Wi‑Fi nào.\nHãy bấm quét lại hoặc kiểm tra quyền vị trí.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(networks) { network ->
                    val isSelected = selectedNetwork?.bssid == network.bssid ||
                        (selectedNetwork != null && selectedNetwork.bssid.isEmpty() && selectedNetwork.ssid == network.ssid)

                    androidx.compose.material3.Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        colors = if (isSelected) {
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        } else {
                            CardDefaults.cardColors()
                        },
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        border = if (isSelected) {
                            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            null
                        },
                        onClick = { viewModel.selectWifiNetwork(network) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Wifi,
                                contentDescription = null,
                                tint = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                            Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = network.ssid,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                                )
                                Text(
                                    text = "${network.bandLabel} • ${network.signalLabel} • ${network.security}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!network.isOpen) {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = "Mạng có mật khẩu",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Đã chọn",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (selectedNetwork != null && !selectedNetwork.isOpen) {
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    viewModel.setPassword(it)
                },
                label = { Text("Mật khẩu cho ${selectedNetwork.ssid}") },
                singleLine = true,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                        Text(if (passwordVisible) "Ẩn" else "Hiện")
                    }
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
        } else if (selectedNetwork != null && selectedNetwork.isOpen) {
            Text(
                text = "Mạng '${selectedNetwork.ssid}' là mạng mở, không cần mật khẩu.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Text(
                text = "Vui lòng chọn một mạng Wi‑Fi ở trên để tiếp tục.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        InfoBanner(
            text = "Lưu ý: trước khi bấm Kết nối, hãy đảm bảo điện thoại đang kết nối vào Wi‑Fi của loa (Phicomm_R1_xxx) hoặc đã chọn loa qua Bluetooth."
        )

        Spacer(modifier = Modifier.height(12.dp))

        val canConnect = selectedNetwork != null && (
            selectedNetwork.isOpen ||
                password.length in 8..63 ||
                (password.length == 64 && password.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
            )

        LoadingButton(
            text = "Kết nối loa với Wi‑Fi này",
            loadingText = "",
            loading = false,
            enabled = canConnect,
            onClick = {
                viewModel.setPassword(password)
                viewModel.startProvisioning()
                if (viewModel.state !is ProvisioningState.Failure) {
                    onProvisioningStarted()
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium
        ) {
            Text("Quay lại")
        }

        if (viewModel.status.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = viewModel.status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
