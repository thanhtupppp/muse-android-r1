package vn.tutu.musemobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.tutu.musemobile.domain.ProvisioningViewModel

@Composable
fun WifiPickerScreen(
    viewModel: ProvisioningViewModel,
    onBack: () -> Unit,
    onWifiSelected: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    val networks = viewModel.wifiNetworks

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

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(networks) { network ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            viewModel.selectWifiNetwork(network)
                            onWifiSelected()
                        }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = network.ssid)
                            Text(
                                text = network.bandLabel + " • " + network.signalLabel,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Mật khẩu Wi‑Fi") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    viewModel.setPassword(password)
                    onWifiSelected()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Kết nối loa với Wi‑Fi này")
            }

            TextButton(onClick = onBack) {
                Text("Quay lại")
            }
        }
    }
}
