package vn.tutu.muser1

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream
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
        private const val DISCOVERABLE_DURATION_SECONDS = 3_600
        private const val MAX_PAYLOAD_BYTES = 2048
        private const val CLIENT_TIMEOUT_MS = 15_000L
    }

    @Suppress("DEPRECATION")
    private val bluetoothAdapter: BluetoothAdapter? =
        BluetoothAdapter.getDefaultAdapter()

    @Volatile
    private var serverSocket: BluetoothServerSocket? = null

    @Volatile
    private var isRunning = false

    @Volatile
    private var session = 0

    private var watchdogThread: HandlerThread? = null
    private var watchdog: Handler? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return

        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.e(TAG, "Thiết bị R1 không có Bluetooth adapter")
            return
        }

        isRunning = true
        val mySession = ++session

        val thread = HandlerThread("BluetoothWatchdogThread").apply { start() }
        watchdogThread = thread
        watchdog = Handler(thread.looper)

        Thread({
            try {
                // Phicomm R1 chạy Android 5.1.1: Agent có thể bật Bluetooth
                // trực tiếp bằng BLUETOOTH_ADMIN trước khi mở máy chủ SPP.
                if (!adapter.isEnabled) {
                    Log.i(TAG, "Bluetooth đang tắt; Agent đang yêu cầu bật Bluetooth")

                    @Suppress("DEPRECATION")
                    val enabled = adapter.enable()
                    Log.i(TAG, "Kết quả yêu cầu bật Bluetooth: $enabled")

                    val deadline = System.currentTimeMillis() + BLUETOOTH_ENABLE_TIMEOUT_MS
                    while (
                        isRunning &&
                        session == mySession &&
                        !adapter.isEnabled &&
                        System.currentTimeMillis() < deadline
                    ) {
                        Thread.sleep(BLUETOOTH_POLL_INTERVAL_MS)
                    }
                }

                if (!isRunning || session != mySession) return@Thread

                if (!adapter.isEnabled) {
                    Log.e(TAG, "Bluetooth vẫn chưa bật sau thời gian chờ")
                    return@Thread
                }

                // Android 5.1.1/API 22: make the R1 visible to nearby phones
                // as well as connectable before accepting the SPP connection.
                Log.i(TAG, "Bluetooth scan mode trước khi discoverable: ${adapter.scanMode}")
                if (adapter.scanMode != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                    val discoverable = setScanModeCompat(
                        adapter,
                        BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE,
                        DISCOVERABLE_DURATION_SECONDS
                    )
                    Log.i(TAG, "Yêu cầu bật Bluetooth discoverable: $discoverable")
                }

                val discoverableDeadline = System.currentTimeMillis() + 5_000L
                while (
                    isRunning &&
                    session == mySession &&
                    adapter.scanMode != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE &&
                    System.currentTimeMillis() < discoverableDeadline
                ) {
                    Thread.sleep(BLUETOOTH_POLL_INTERVAL_MS)
                }
                Log.i(TAG, "Bluetooth scan mode sau yêu cầu: ${adapter.scanMode}")

                // Dùng Secure RFCOMM để kênh Bluetooth được mã hóa an toàn qua bonding
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(
                    SERVICE_NAME,
                    MUSE_R1_SPP_UUID
                )

                Log.i(TAG, "Bluetooth SPP server đã sẵn sàng")

                while (isRunning && session == mySession) {
                    val socket = serverSocket?.accept() ?: break
                    handleClient(socket)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                if (isRunning && session == mySession) {
                    Log.w(TAG, "Luồng Bluetooth bị gián đoạn", e)
                }
            } catch (e: IOException) {
                if (isRunning && session == mySession) {
                    Log.e(TAG, "Bluetooth server lỗi", e)
                }
            } finally {
                closeServer()
                if (session == mySession) {
                    isRunning = false
                }
            }
        }, "BluetoothProvisionServerThread").start()
    }

    fun stop() {
        session++
        isRunning = false
        closeServer()
    }

    private fun handleClient(socket: BluetoothSocket) {
        val timeoutTask = Runnable {
            Log.w(TAG, "Bluetooth client quá hạn đọc payload $CLIENT_TIMEOUT_MS ms, đóng socket")
            runCatching { socket.close() }
        }
        watchdog?.postDelayed(timeoutTask, CLIENT_TIMEOUT_MS)

        try {
            socket.use { connection ->
                try {
                    val payload = readPayload(connection)
                    val request = parsePayload(payload)

                    // Đã nhận xong payload, hủy ngay watchdog đọc để wifiProvisioner có đủ thời gian kết nối
                    watchdog?.removeCallbacks(timeoutTask)

                    // Kiểm tra xác thực token nếu thiết bị đã được cấu hình token
                    val expectedToken = MusePreferences.getProvisionToken(context)
                    if (expectedToken.isNotEmpty()) {
                        val token = request["token"]
                        if (token != expectedToken) {
                            throw IOException("UNAUTHORIZED")
                        }
                    }

                    val ssid = request["ssid"]
                        ?: throw IOException("Thiếu SSID")

                    val password = request["password"]
                        ?: throw IOException("Thiếu mật khẩu")

                    Log.d(TAG, "Nhận yêu cầu cấu hình Wi-Fi cho SSID: $ssid")

                    val result = wifiProvisioner.connect(
                        context = context,
                        ssid = ssid,
                        password = password
                    )

                    when (result) {
                        is WifiProvisioner.ProvisionResult.Success -> {
                            writeResponse(connection, "OK ip=${result.ip}\n")
                            Log.d(TAG, "Đã cấu hình Wi-Fi thành công! IP: ${result.ip}")

                            // Tắt chế độ discoverable sau khi cấu hình xong để tăng tính bảo mật
                            bluetoothAdapter?.let { adapter ->
                                setScanModeCompat(adapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE, 0)
                            }
                        }
                        is WifiProvisioner.ProvisionResult.Failure -> {
                            writeResponse(connection, "ERROR: ${result.code}\n")
                            Log.w(TAG, "Kết nối Wi-Fi thất bại: ${result.code}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Lỗi xử lý Bluetooth client: ${e.message}", e)
                    try {
                        val msg = e.message ?: "BAD_REQUEST"
                        writeResponse(connection, "ERROR: $msg\n")
                    } catch (_: Exception) {
                    }
                }
            }
        } finally {
            watchdog?.removeCallbacks(timeoutTask)
        }
    }

    private fun readPayload(socket: BluetoothSocket): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(512)
        val input = socket.inputStream

        while (out.size() < MAX_PAYLOAD_BYTES) {
            val read = input.read(buffer)
            if (read == -1) break
            out.write(buffer, 0, read)

            val text = out.toString("UTF-8")
            val lines = text.split("\n")
            // Chỉ kết thúc khi có dòng riêng biệt là "END" (hoặc "END\r")
            if (lines.any { it.trimEnd('\r') == "END" }) {
                break
            }
        }

        if (out.size() >= MAX_PAYLOAD_BYTES) {
            throw IOException("Payload vượt quá giới hạn $MAX_PAYLOAD_BYTES bytes")
        }

        return out.toString("UTF-8")
    }

    private fun parsePayload(payload: String): Map<String, String> {
        val lines = payload.split("\n")
        val map = mutableMapOf<String, String>()

        for (rawLine in lines) {
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty() || line == "END" || line == "MUSE_WIFI_CONFIG") continue
            val parts = line.split("=", limit = 2)
            if (parts.size == 2) {
                map[parts[0].trim()] = parts[1]
            }
        }

        return map
    }

    private fun writeResponse(
        socket: BluetoothSocket,
        response: String
    ) {
        val output = socket.outputStream
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun setScanModeCompat(
        adapter: BluetoothAdapter,
        mode: Int,
        duration: Int
    ): Boolean {
        return try {
            val method = adapter.javaClass.getMethod(
                "setScanMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            (method.invoke(adapter, mode, duration) as? Boolean) ?: false
        } catch (e: NoSuchMethodException) {
            try {
                val method = adapter.javaClass.getMethod(
                    "setScanMode",
                    Int::class.javaPrimitiveType
                )
                (method.invoke(adapter, mode) as? Boolean) ?: false
            } catch (e2: Exception) {
                Log.w(TAG, "Không thể gọi setScanMode qua reflection: ${e2.message}")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi khi gọi setScanMode: ${e.message}")
            false
        }
    }

    private fun closeServer() {
        try {
            watchdog?.removeCallbacksAndMessages(null)
            watchdog = null
            watchdogThread?.quitSafely()
            watchdogThread = null
            serverSocket?.close()
        } catch (_: Exception) {
        } finally {
            serverSocket = null
        }
    }
}
