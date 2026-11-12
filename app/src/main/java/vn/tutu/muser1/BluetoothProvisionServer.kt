package vn.tutu.muser1

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.IOException
import java.util.UUID

class BluetoothProvisionServer(
    private val context: Context,
    private val wifiProvisioner: WifiProvisioner = WifiProvisioner
) {

    companion object {
        private const val TAG = "MuseBluetooth"

        // UUID phải khớp với Muse Mobile
        val MUSE_R1_SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val SERVICE_NAME = "Muse R1 Provisioning"
        private const val BLUETOOTH_ENABLE_TIMEOUT_MS = 15_000L
        private const val BLUETOOTH_POLL_INTERVAL_MS = 500L
    }

    @Suppress("DEPRECATION")
    private val bluetoothAdapter: BluetoothAdapter? =
        BluetoothAdapter.getDefaultAdapter()

    private var serverSocket: BluetoothServerSocket? = null

    @Volatile
    private var isRunning = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return

        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.e(TAG, "Thiết bị R1 không có Bluetooth adapter")
            return
        }

        isRunning = true

        Thread({
            try {
                // Phicomm R1 chạy Android 5.1.1: Agent có thể bật Bluetooth
                // trực tiếp bằng BLUETOOTH_ADMIN trước khi mở máy chủ SPP.
                if (!adapter.isEnabled) {
                    Log.i(TAG, "Bluetooth đang tắt; Agent đang yêu cầu bật Bluetooth")

                    @Suppress("DEPRECATION")
                    val enableRequested = adapter.enable()

                    if (!enableRequested) {
                        Log.e(TAG, "Không thể yêu cầu hệ thống bật Bluetooth")
                        return@Thread
                    }

                    val deadline = System.currentTimeMillis() +
                        BLUETOOTH_ENABLE_TIMEOUT_MS

                    while (isRunning && !adapter.isEnabled &&
                        System.currentTimeMillis() < deadline
                    ) {
                        Thread.sleep(BLUETOOTH_POLL_INTERVAL_MS)
                    }
                }

                if (!isRunning) return@Thread

                if (!adapter.isEnabled) {
                    Log.e(TAG, "Bluetooth vẫn chưa bật sau thời gian chờ")
                    return@Thread
                }

                serverSocket = adapter.listenUsingRfcommWithServiceRecord(
                    SERVICE_NAME,
                    MUSE_R1_SPP_UUID
                )

                Log.i(TAG, "Bluetooth SPP server đã sẵn sàng")

                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    handleClient(socket)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                if (isRunning) {
                    Log.w(TAG, "Luồng Bluetooth bị gián đoạn", e)
                }
            } catch (e: IOException) {
                if (isRunning) {
                    Log.e(TAG, "Bluetooth server lỗi", e)
                }
            } finally {
                closeServer()
                isRunning = false
            }
        }, "BluetoothProvisionServerThread").start()
    }

    fun stop() {
        isRunning = false
        closeServer()
    }

    private fun handleClient(socket: BluetoothSocket) {
        socket.use { connection ->
            try {
                val payload = readPayload(connection)
                val request = parsePayload(payload)

                val ssid = request["ssid"]
                    ?: throw IOException("Thiếu SSID")

                val password = request["password"]
                    ?: throw IOException("Thiếu mật khẩu")

                Log.d(TAG, "Nhận yêu cầu cấu hình Wi-Fi")

                val success = wifiProvisioner.connect(
                    context = context,
                    ssid = ssid,
                    password = password
                )

                if (success) {
                    writeResponse(connection, "OK\n")
                    Log.d(TAG, "Đã gửi cấu hình Wi-Fi thành công")
                } else {
                    writeResponse(connection, "ERROR: Không kết nối được Wi-Fi\n")
                    Log.w(TAG, "Kết nối Wi-Fi thất bại")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi xử lý Bluetooth client", e)

                try {
                    writeResponse(
                        connection,
                        "ERROR: ${e.message ?: "Lỗi không xác định"}\n"
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun readPayload(socket: BluetoothSocket): String {
        val builder = StringBuilder()
        val buffer = ByteArray(1024)
        val input = socket.inputStream

        while (true) {
            val read = input.read(buffer)
            if (read == -1) break

            builder.append(String(buffer, 0, read, Charsets.UTF_8))
            if (builder.endsWith("END\n") || builder.endsWith("END")) break
        }

        return builder.toString()
    }

    private fun parsePayload(payload: String): Map<String, String> {
        val result = mutableMapOf<String, String>()

        payload.lines().forEach { line ->
            val trimmed = line.trim()

            if (trimmed.isNotEmpty() &&
                trimmed != "MUSE_WIFI_CONFIG" &&
                trimmed != "END"
            ) {
                val index = trimmed.indexOf('=')

                if (index > 0) {
                    val key = trimmed.substring(0, index)
                    val value = trimmed.substring(index + 1)
                    result[key] = value
                }
            }
        }

        return result
    }

    private fun writeResponse(
        socket: BluetoothSocket,
        response: String
    ) {
        val output = socket.outputStream
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun closeServer() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        } finally {
            serverSocket = null
        }
    }
}
