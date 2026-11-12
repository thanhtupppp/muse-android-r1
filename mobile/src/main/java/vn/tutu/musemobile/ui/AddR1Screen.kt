package vn.tutu.musemobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
fun AddR1Screen(viewModel: ProvisioningViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    var speakerIp by remember { mutableStateOf("192.168.43.1") }
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Thêm loa Muse R1",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text("1. Giữ nút tròn trên loa khoảng 6 giây.")
            Text("2. Kết nối Wi‑Fi Phicomm_R1_xxx.")
            Text("3. Quay lại ứng dụng và kiểm tra kết nối.")

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = speakerIp,
                onValueChange = { speakerIp = it },
                label = { Text("Địa chỉ loa") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = ssid,
                onValueChange = { ssid = it },
                label = { Text("SSID Wi‑Fi nhà") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Mật khẩu Wi‑Fi nhà") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    viewModel.provision(
                        speakerIp = speakerIp.trim(),
                        ssid = ssid.trim(),
                        password = password
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Kết nối loa với Wi‑Fi nhà")
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(text = viewModel.status)
        }
    }
}
