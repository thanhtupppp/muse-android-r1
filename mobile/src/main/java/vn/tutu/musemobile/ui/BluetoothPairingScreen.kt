package vn.tutu.musemobile.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.tutu.musemobile.data.BluetoothProvisioner
import vn.tutu.musemobile.domain.ProvisioningViewModel

@SuppressLint("MissingPermission")
@Composable
fun BluetoothPairingScreen(
    viewModel: ProvisioningViewModel,
    onDeviceSelected: () -> Unit,
    onBack: () -> Unit
) {
    val provisioner = remember {
        BluetoothProvisioner(viewModel.getApplication())
    }

    var devices by remember {
        mutableStateOf<List<BluetoothDevice>>(emptyList())
    }

    var status by remember {
        mutableStateOf("Đang tìm Muse R1...")
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            status = "Đã cấp quyền Bluetooth. Bấm quét lại."
        } else {
            status = "Cần cấp quyền Bluetooth để tìm loa."
        }
    }

    LaunchedEffect(Unit) {
        if (!provisioner.hasBluetoothPermissions()) {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            }

            permissionLauncher.launch(permissions)
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
                text = "Kết nối Muse R1",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Bật Bluetooth trên loa, sau đó bấm Quét thiết bị.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    status = "Đang quét Bluetooth..."
                    devices = provisioner.getBondedR1Devices()
                    status = if (devices.isEmpty()) {
                        "Chưa thấy Muse R1. Hãy ghép nối trong Cài đặt Bluetooth."
                    } else {
                        "Đã tìm thấy ${devices.size} thiết bị Muse R1."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Quét thiết bị đã ghép nối")
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices) { device ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            viewModel.selectBluetoothDevice(device)
                            onDeviceSelected()
                        }
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = device.name ?: "Thiết bị không tên"
                            )
                            Text(
                                text = device.address,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(text = status)

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(onClick = onBack) {
                Text("Quay lại")
            }
        }
    }
}

