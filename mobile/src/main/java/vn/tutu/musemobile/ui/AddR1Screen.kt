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
fun AddR1Screen(
    viewModel: ProvisioningViewModel,
    onRequestWifiPermission: () -> Unit,
    onR1Found: () -> Unit
) {
    var speakerIp by remember { mutableStateOf("192.168.43.1") }
    var apkUrl by remember {
        mutableStateOf("https://github.com/thanhtupppp/muse-android-r1/releases/download/v0.1.0/app-debug.apk")
    }

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

            Text("1. Kết nối Wi‑Fi nhà hoặc 4G, bấm 'Tải APK về máy'.")
            Text("2. Giữ nút tròn trên loa R1 ~6 giây để mở hotspot.")
            Text("3. Đổi Wi‑Fi điện thoại sang Phicomm_R1_xxx.")
            Text("4. Bấm 'Cài APK lên R1 qua ADB' (không cần internet).")
            Text("5. Chọn Wi‑Fi nhà và truyền qua Bluetooth.")

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = apkUrl,
                onValueChange = { apkUrl = it },
                label = { Text("URL APK Muse R1 Agent") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    viewModel.downloadAgentApk(apkUrl.trim())
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (viewModel.isApkDownloaded)
                        "1. Tải lại APK về máy (Đã có sẵn file ✔️)"
                    else
                        "1. Tải APK về máy (khi có Internet)"
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = speakerIp,
                onValueChange = { speakerIp = it },
                label = { Text("Địa chỉ loa") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    viewModel.setSpeakerIp(speakerIp.trim())
                    viewModel.installR1Agent(apkUrl.trim())
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("2. Cài APK lên R1 qua ADB")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    viewModel.setSpeakerIp(speakerIp.trim())
                    viewModel.checkR1()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Kiểm tra kết nối R1")
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(onClick = onRequestWifiPermission) {
                Text("Cấp quyền quét Wi‑Fi")
            }

            TextButton(onClick = onR1Found) {
                Text("Chọn Wi‑Fi nhà")
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(text = viewModel.status)
        }
    }
}
