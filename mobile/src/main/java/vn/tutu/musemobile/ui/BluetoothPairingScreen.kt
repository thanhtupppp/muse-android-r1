package vn.tutu.musemobile.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import vn.tutu.musemobile.data.BluetoothProvisioner
import vn.tutu.musemobile.domain.ProvisioningViewModel

@SuppressLint("MissingPermission")
@Composable
fun BluetoothPairingScreen(
    viewModel: ProvisioningViewModel,
    onDeviceSelected: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val provisioner = remember { BluetoothProvisioner(viewModel.getApplication()) }
    val devices = remember { mutableStateListOf<BluetoothDevice>() }
    var status by remember { mutableStateOf("Quét để tìm loa Phicomm R1.") }

    val receiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (BluetoothDevice.ACTION_FOUND == intent.action) {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null && provisioner.isR1Device(device) &&
                        devices.none { it.address == device.address }
                    ) {
                        devices.add(device)
                        status = "Đã tìm thấy ${device.name}. Chọn thiết bị để tiếp tục."
                    }
                }
            }
        }
    }

    DisposableEffect(context) {
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            provisioner.cancelDiscovery()
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            status = "Đã cấp quyền. Bấm Quét thiết bị."
        } else {
            status = "Cần cấp quyền Bluetooth và vị trí để quét loa."
        }
    }

    LaunchedEffect(Unit) {
        if (!provisioner.hasBluetoothPermissions()) {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION)
            }
            permissionLauncher.launch(permissions)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)
        ) {
            Text("Kết nối Phicomm R1", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Text("Không cần ghép đôi thủ công. Bật Bluetooth trên loa rồi quét thiết bị.")
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    devices.clear()
                    devices.addAll(provisioner.getBondedR1Devices())
                    status = if (provisioner.startDiscovery()) {
                        "Đang quét thiết bị Bluetooth ở gần..."
                    } else {
                        "Không bắt đầu được quét. Kiểm tra Bluetooth và quyền ứng dụng."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Quét thiết bị Bluetooth") }
            Spacer(Modifier.height(16.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices, key = { it.address }) { device ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            provisioner.cancelDiscovery()
                            viewModel.selectBluetoothDevice(device)
                            onDeviceSelected()
                        }
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(device.name ?: "Phicomm R1")
                            Text(device.address, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text(status)
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onBack) { Text("Quay lại") }
        }
    }
}
