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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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

@Composable
fun WifiPickerScreen(
    viewModel: ProvisioningViewModel,
    onBack: () -> Unit,
    onProvisioningStarted: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val networks = viewModel.wifiNetworks
    val selectedNetwork = viewModel.selectedNetwork

    LaunchedEffect(Unit) {
        if (viewModel.wifiNetworks.isEmpty()) {
            viewModel.refreshWifiNetworks()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
        ) {
            Text(
                text = "Chọn Wi‑Fi nhà",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = { viewModel.refreshWifiNetworks() }) {
                Text("Quét lại Wi‑Fi")
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (networks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Chưa tìm thấy mạng Wi‑Fi nào.\nHãy bấm 'Quét lại Wi‑Fi' hoặc kiểm tra quyền vị trí.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(networks) { network ->
                        val isSelected = selectedNetwork?.bssid == network.bssid ||
                            (selectedNetwork != null && selectedNetwork.bssid.isEmpty() && selectedNetwork.ssid == network.ssid)

                        val cardColors = if (isSelected) {
                            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        } else {
                            CardDefaults.cardColors()
                        }

                        val cardBorder = if (isSelected) {
                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        } else null

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = cardColors,
                            border = cardBorder,
                            onClick = {
                                viewModel.selectWifiNetwork(network)
                            }
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = network.ssid,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (network.isOpen) {
                                        Text(
                                            text = "Mở",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${network.bandLabel} • ${network.signalLabel} • ${network.security}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (selectedNetwork != null && !selectedNetwork.isOpen) {
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        viewModel.setPassword(it)
                    },
                    label = { Text("Mật khẩu cho ${selectedNetwork.ssid}") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(if (passwordVisible) "Ẩn" else "Hiện")
                        }
                    },
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

            Spacer(modifier = Modifier.height(16.dp))

            val canConnect = selectedNetwork != null && (
                selectedNetwork.isOpen ||
                password.length in 8..63 ||
                (password.length == 64 && password.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
            )

            Button(
                onClick = {
                    viewModel.setPassword(password)
                    viewModel.startProvisioning()
                    if (viewModel.state !is ProvisioningState.Failure) {
                        onProvisioningStarted()
                    }
                },
                enabled = canConnect,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Kết nối loa với Wi‑Fi này")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
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
}
