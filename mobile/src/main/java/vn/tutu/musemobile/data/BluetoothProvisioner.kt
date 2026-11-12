package vn.tutu.musemobile.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

class BluetoothProvisioner(
    private val context: Context
) {
    companion object {
        // UUID này phải khớp với Bluetooth server trong R1 Agent
        private val MUSE_R1_SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val DEVICE_NAME_PREFIX = "Muse R1"
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_ADMIN
                    ) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun getBondedR1Devices(): List<BluetoothDevice> {
        val adapter = bluetoothAdapter ?: return emptyList()

        if (!adapter.isEnabled) {
            return emptyList()
        }

        return adapter.bondedDevices
            ?.filter {
                it.name?.startsWith(DEVICE_NAME_PREFIX) == true
            }
            ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    suspend fun findR1Device(): Result<BluetoothDevice> = withContext(Dispatchers.IO) {
        runCatching {
            val adapter = bluetoothAdapter ?: throw IOException("Thiết bị không hỗ trợ Bluetooth")
            if (!adapter.isEnabled) {
                throw IOException("Bluetooth đang tắt")
            }

            val bonded = adapter.bondedDevices ?: throw IOException("Không đọc được danh sách Bluetooth")
            val device = bonded.firstOrNull { it.name?.startsWith(DEVICE_NAME_PREFIX) == true }
                ?: throw IOException(
                    "Không tìm thấy Muse R1. Hãy bật Bluetooth trên loa và ghép thiết bị."
                )
            device
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun provisionWifi(
        device: BluetoothDevice,
        ssid: String,
        password: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val socket = device.createRfcommSocketToServiceRecord(MUSE_R1_SPP_UUID)
            socket.use { connection ->
                connection.connect()
                sendProvisionPayload(connection, ssid, password)
                readResponse(connection)
                Unit
            }
        }
    }

    private fun sendProvisionPayload(
        socket: BluetoothSocket,
        ssid: String,
        password: String
    ) {
        val payload = buildString {
            append("MUSE_WIFI_CONFIG\n")
            append("ssid=").append(ssid).append("\n")
            append("password=").append(password).append("\n")
            append("security=WPA2\n")
            append("END\n")
        }
        val output = socket.outputStream
        output.write(payload.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun readResponse(
        socket: BluetoothSocket
    ): String {
        val response = StringBuilder()
        val input = socket.inputStream
        val buffer = ByteArray(1024)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            response.append(String(buffer, 0, read, Charsets.UTF_8))
            if (response.contains("OK") || response.contains("ERROR")) {
                break
            }
        }

        val result = response.toString()
        if (result.contains("ERROR", ignoreCase = true)) {
            throw IOException("R1 từ chối cấu hình Wi‑Fi: $result")
        }
        return result
    }
}

