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

class BluetoothProvisioner(private val context: Context) {
    companion object {
        private val MUSE_R1_SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val DEVICE_NAME_PREFIX = "Phicomm_R1_"
        private const val MAX_RESPONSE_BYTES = 1024
    }
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    fun hasBluetoothPermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    @SuppressLint("MissingPermission")
    fun startDiscovery(): Boolean {
        val adapter = bluetoothAdapter ?: return false
        if (!adapter.isEnabled) return false
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        return adapter.startDiscovery()
    }

    @SuppressLint("MissingPermission")
    fun cancelDiscovery() {
        bluetoothAdapter?.cancelDiscovery()
    }

    fun isR1Device(device: BluetoothDevice): Boolean =
        device.name?.startsWith(DEVICE_NAME_PREFIX, ignoreCase = true) == true

    @SuppressLint("MissingPermission")
    fun getBondedR1Devices(): List<BluetoothDevice> {
        val adapter = bluetoothAdapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        return adapter.bondedDevices?.filter { it.name?.startsWith(DEVICE_NAME_PREFIX, ignoreCase = true) == true } ?: emptyList()
    }
    @SuppressLint("MissingPermission")
    suspend fun findR1Device(): Result<BluetoothDevice> = withContext(Dispatchers.IO) { runCatching {
        val adapter = bluetoothAdapter ?: throw IOException("Thiết bị không hỗ trợ Bluetooth")
        if (!adapter.isEnabled) throw IOException("Bluetooth đang tắt")
        val bonded = adapter.bondedDevices ?: throw IOException("Không đọc được danh sách Bluetooth")
        bonded.firstOrNull { it.name?.startsWith(DEVICE_NAME_PREFIX, ignoreCase = true) == true }
            ?: throw IOException("Không tìm thấy Muse R1. Hãy bật Bluetooth trên loa và ghép thiết bị.")
    } }
    @SuppressLint("MissingPermission")
    suspend fun provisionWifi(device: BluetoothDevice, ssid: String, password: String): Result<Unit> = withContext(Dispatchers.IO) { runCatching {
        require(ssid.isNotBlank()) { "SSID không được để trống" }
        require(password.length >= 8) { "Mật khẩu WPA2 cần ít nhất 8 ký tự" }
        device.createInsecureRfcommSocketToServiceRecord(MUSE_R1_SPP_UUID).use { socket ->
            socket.connect()
            sendProvisionPayload(socket, ssid, password)
            readResponse(socket)
        }
    } }
    private fun sendProvisionPayload(socket: BluetoothSocket, ssid: String, password: String) {
        val payload = buildString {
            append("MUSE_WIFI_CONFIG\n")
            append("ssid=").append(ssid).append("\n")
            append("password=").append(password).append("\n")
            append("security=WPA2\n")
            append("END\n")
        }
        socket.outputStream.apply { write(payload.toByteArray(Charsets.UTF_8)); flush() }
    }
    // Server replies with one newline-terminated OK or ERROR line. EOF is never success.
    private fun readResponse(socket: BluetoothSocket) {
        val input = socket.inputStream
        val response = StringBuilder()
        while (response.length < MAX_RESPONSE_BYTES) {
            val next = input.read()
            if (next == -1) throw IOException("R1 đóng Bluetooth trước khi xác nhận cấu hình Wi-Fi")
            if (next == 10) break
            if (next != 13) response.append(next.toChar())
        }
        if (response.length >= MAX_RESPONSE_BYTES) throw IOException("Phản hồi Bluetooth từ R1 vượt giới hạn")
        val line = response.toString().trim()
        if (line == "OK") return
        if (line.startsWith("ERROR:", ignoreCase = true)) throw IOException("R1 từ chối cấu hình Wi-Fi: $line")
        throw IOException("Phản hồi Bluetooth không hợp lệ từ R1: ${line.ifEmpty { "<trống>" }}")
    }
}
