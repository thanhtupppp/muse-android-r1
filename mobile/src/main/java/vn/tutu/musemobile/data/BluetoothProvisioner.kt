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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

class BluetoothProvisioner(private val context: Context) {
    companion object {
        private val MUSE_R1_SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val DEVICE_NAME_PREFIX = "Phicomm_R1_"
        private const val MAX_RESPONSE_BYTES = 1024
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val RESPONSE_TIMEOUT_MS = 45_000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    fun hasBluetoothPermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
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
        return adapter.bondedDevices?.filter { isR1Device(it) } ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    suspend fun findR1Device(): Result<BluetoothDevice> = withContext(Dispatchers.IO) { runCatching {
        val adapter = bluetoothAdapter ?: throw IOException("Thiết bị không hỗ trợ Bluetooth")
        if (!adapter.isEnabled) throw IOException("Bluetooth đang tắt")
        val bonded = getBondedR1Devices()
        bonded.firstOrNull()
            ?: throw IOException("Không tìm thấy Muse R1. Hãy bật Bluetooth trên loa và ghép thiết bị.")
    } }

    @SuppressLint("MissingPermission")
    suspend fun provisionWifi(
        device: BluetoothDevice,
        ssid: String,
        password: String,
        token: String? = null
    ): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            require(ssid.isNotBlank()) { "SSID không được để trống" }
            require(!ssid.contains('\n') && !ssid.contains('\r')) { "SSID không được chứa ký tự xuống dòng" }
            require(!password.contains('\n') && !password.contains('\r')) { "Mật khẩu không được chứa ký tự xuống dòng" }

            val isHexPsk = password.length == 64 && password.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            require(password.isEmpty() || password.length in 8..63 || isHexPsk) {
                "Mật khẩu cần rỗng (mạng mở), từ 8 đến 63 ký tự hoặc khóa hex 64 ký tự"
            }

            // Dùng Secure RFCOMM qua liên kết đã ghép đôi với R1 để mã hóa dữ liệu truyền
            val socket = device.createRfcommSocketToServiceRecord(MUSE_R1_SPP_UUID)
            try {
                try {
                    withTimeout(CONNECT_TIMEOUT_MS) {
                        socket.connect()
                    }
                } catch (e: TimeoutCancellationException) {
                    throw IOException("Quá hạn kết nối Bluetooth tới ${device.name ?: "R1"} (${CONNECT_TIMEOUT_MS / 1000}s)", e)
                }

                sendProvisionPayload(socket, ssid, password, token)

                try {
                    withTimeout(RESPONSE_TIMEOUT_MS) {
                        readResponse(socket)
                    }
                } catch (e: TimeoutCancellationException) {
                    throw IOException("Quá hạn chờ R1 xác nhận kết nối Wi-Fi (${RESPONSE_TIMEOUT_MS / 1000}s)", e)
                }
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    private fun sendProvisionPayload(
        socket: BluetoothSocket,
        ssid: String,
        password: String,
        token: String? = null
    ) {
        val security = if (password.isEmpty()) "OPEN" else "WPA2"
        val payload = buildString {
            append("MUSE_WIFI_CONFIG\n")
            append("ssid=").append(ssid).append("\n")
            append("password=").append(password).append("\n")
            append("security=").append(security).append("\n")
            if (!token.isNullOrEmpty()) {
                append("token=").append(token).append("\n")
            }
            append("END\n")
        }
        socket.outputStream.apply {
            write(payload.toByteArray(Charsets.UTF_8))
            flush()
        }
    }

    // Server replies with one newline-terminated OK (optionally with ip=...) or ERROR line.
    private fun readResponse(socket: BluetoothSocket): String? {
        val input = socket.inputStream
        val response = ByteArrayOutputStream()
        while (response.size() < MAX_RESPONSE_BYTES) {
            val next = input.read()
            if (next == -1) throw IOException("R1 đóng Bluetooth trước khi xác nhận cấu hình Wi-Fi")
            if (next == 10) break
            if (next != 13) response.write(next)
        }
        if (response.size() >= MAX_RESPONSE_BYTES) throw IOException("Phản hồi Bluetooth từ R1 vượt giới hạn")
        val line = response.toByteArray().toString(Charsets.UTF_8).trim()
        if (line.startsWith("OK", ignoreCase = true)) {
            val ipMatch = Regex("ip=([0-9.]+)").find(line)
            return ipMatch?.groupValues?.get(1)
        }
        if (line.startsWith("ERROR:", ignoreCase = true)) throw IOException("R1 từ chối cấu hình Wi-Fi: $line")
        throw IOException("Phản hồi Bluetooth không hợp lệ từ R1: ${line.ifEmpty { "<trống>" }}")
    }
}
