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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.tutu.musemobile.data.BluetoothProvisioner
import vn.tutu.musemobile.domain.ProvisioningViewModel
import vn.tutu.musemobile.ui.components.ChipKind
import vn.tutu.musemobile.ui.components.InfoBanner
import vn.tutu.musemobile.ui.components.LoadingButton
import vn.tutu.musemobile.ui.components.StatusChip

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
    var status by remember { mutableStateOf("Bấm quét để tìm loa Phicomm R1 ở gần.") }
    var scanning by remember { mutableStateOf(false) }

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
                        val name = device.name ?: "Phicomm R1"
                        status = "Đã tìm thấy $name. Chọn thiết bị để tiếp tục."
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
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val missing = grants.filterValues { !it }.keys
        status = if (missing.isEmpty()) {
            "Đã cấp quyền. Bấm Quét thiết bị."
        } else {
            "Cần cấp đủ quyền Bluetooth và vị trí để quét loa."
        }
    }

    LaunchedEffect(Unit) {
        if (!provisioner.hasBluetoothPermissions()) {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(
                    Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            }
            permissionLauncher.launch(permissions)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Kết nối Bluetooth với R1",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Không cần ghép đôi thủ công trong cài đặt hệ thống. Bật Bluetooth trên loa rồi quét.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        LoadingButton(
            text = "Quét thiết bị Bluetooth",
            loadingText = "Đang quét thiết bị ở gần...",
            loading = scanning,
            icon = Icons.Filled.Bluetooth,
            onClick = {
                devices.clear()
                devices.addAll(provisioner.getBondedR1Devices())
                scanning = provisioner.startDiscovery()
                status = if (scanning) {
                    "Đang quét thiết bị Bluetooth ở gần..."
                } else {
                    "Không bắt đầu được quét. Kiểm tra Bluetooth và quyền ứng dụng."
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(
                text = when {
                    scanning -> "ĐANG QUÉT"
                    devices.isNotEmpty() -> "TÌM THẤY ${devices.size} THIẾT BỊ"
                    else -> "CHƯA QUÉT"
                },
                kind = when {
                    devices.isNotEmpty() -> ChipKind.SUCCESS
                    scanning -> ChipKind.WARNING
                    else -> ChipKind.NEUTRAL
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (devices.isEmpty()) {
            InfoBanner(
                text = status,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(devices, key = { it.address }) { device ->
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        onClick = {
                            provisioner.cancelDiscovery()
                            scanning = false
                            viewModel.selectBluetoothDevice(device)
                            onDeviceSelected()
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Bluetooth,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = device.name ?: "Phicomm R1",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = device.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        androidx.compose.material3.TextButton(onClick = onBack) {
            Text("Quay lại")
        }
    }
}
