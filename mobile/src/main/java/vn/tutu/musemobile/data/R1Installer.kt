package vn.tutu.musemobile.data

import android.content.Context
import android.util.Base64
import android.util.Log
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import com.tananaev.adblib.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class R1Installer(
    private val context: Context
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun getLocalApkFile(): File {
        return File(context.filesDir, "muse_r1_agent.apk")
    }

    fun hasLocalApk(): Boolean {
        val file = getLocalApkFile()
        return file.exists() && file.length() > 50_000
    }

    suspend fun downloadAgentApk(
        apkUrl: String,
        onProgress: (Int) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(apkUrl)
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Tải APK thất bại: HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("APK response trống")
                val total = body.contentLength()
                val targetFile = getLocalApkFile()
                val tempFile = File(context.filesDir, "muse_r1_agent.apk.tmp")
                tempFile.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        var downloaded = 0L
                        var lastReportedPercent = -1
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val percent = ((downloaded * 100) / total).toInt()
                                if (percent != lastReportedPercent) {
                                    lastReportedPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                        output.flush()
                    }
                }
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
                targetFile
            }
        }
    }

    suspend fun installAgent(
        speakerIp: String,
        apkFile: File,
        onProgress: (message: String, progress: Float?) -> Unit = { _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!apkFile.exists() || apkFile.length() < 50_000) {
                throw IOException("File APK không hợp lệ hoặc kích thước quá nhỏ (${apkFile.length()} bytes)")
            }
            val apkSizeMb = String.format("%.2f MB", apkFile.length() / (1024.0 * 1024.0))

            val host = speakerIp.trim()
            val port = 5555
            val remotePath = "/data/local/tmp/muse_r1_agent.apk"

            onProgress("1/5: Đang kết nối TCP tới loa R1 ($host:$port)...", 0.05f)

            var (currentSocket, currentConnection) = try {
                openConnection(host, port)
            } catch (e: Exception) {
                throw IOException(
                    "Không thể kết nối đến $host:$port (${e.message ?: "timeout"}). " +
                    "Hãy chắc chắn bạn đã kết nối Wi‑Fi vào 'Phicomm_R1_xxx' và loa đã bật cổng ADB 5555.",
                    e
                )
            }

            try {
                onProgress("2/5: Bắt tay xác thực ADB với loa R1 thành công!", 0.15f)

                try {
                    runShell(currentConnection, "rm -f $remotePath")
                } catch (e: Exception) {
                    Log.w("R1Installer", "Non-fatal rm error before push", e)
                }

                onProgress("3/5: Bắt đầu truyền APK ($apkSizeMb) lên $remotePath...", 0.20f)

                pushApk(
                    connection = currentConnection,
                    apkFile = apkFile,
                    remotePath = remotePath
                ) { pushedBytes, totalBytes ->
                    val fraction = pushedBytes.toFloat() / totalBytes.toFloat()
                    val percent = (fraction * 100).toInt()
                    val pushedMb = String.format("%.2f", pushedBytes / (1024.0 * 1024.0))
                    val totalMb = String.format("%.2f", totalBytes / (1024.0 * 1024.0))
                    val currentProgress = 0.20f + fraction * 0.55f
                    onProgress("Đang đẩy file APK: $pushedMb / $totalMb MB ($percent%)", currentProgress)
                }

                onProgress("Đã đẩy file APK thành công. Xác nhận kênh Sync OKAY...", 0.75f)

                // Give disk write & service teardown a moment to complete
                delay(600)

                onProgress("4/5: Đang thực thi: pm install -r $remotePath... (Vui lòng đợi 5-15 giây)", 0.80f)

                val installOutput = try {
                    runShell(currentConnection, "pm install -r $remotePath")
                } catch (e: Exception) {
                    Log.w("R1Installer", "Stream rejected after sync, reconnecting fresh ADB...", e)
                    onProgress("Thiết lập kết nối ADB mới để chạy pm install...", 0.82f)
                    closeQuietly(currentSocket, currentConnection)
                    val fresh = openConnection(host, port)
                    currentSocket = fresh.first
                    currentConnection = fresh.second
                    runShell(currentConnection, "pm install -r $remotePath")
                }
                Log.d("R1Installer", "Install output: $installOutput")

                if (!installOutput.contains("Success", ignoreCase = true)) {
                    throw IOException("Lệnh 'pm install' thất bại: ${installOutput.trim()}")
                }
                onProgress("Cài đặt APK thành công! (pm install: Success)", 0.90f)

                onProgress("5/5: Đang khởi động Muse R1 Agent Service...", 0.95f)
                val launchOutput = try {
                    runShell(
                        connection = currentConnection,
                        command = "monkey -p vn.tutu.muser1 -c android.intent.category.LAUNCHER 1; am startservice vn.tutu.muser1/.AgentService"
                    )
                } catch (e: Exception) {
                    Log.w("R1Installer", "Stream rejected for launch, reconnecting fresh ADB...", e)
                    closeQuietly(currentSocket, currentConnection)
                    val fresh = openConnection(host, port)
                    currentSocket = fresh.first
                    currentConnection = fresh.second
                    runShell(
                        connection = currentConnection,
                        command = "monkey -p vn.tutu.muser1 -c android.intent.category.LAUNCHER 1; am startservice vn.tutu.muser1/.AgentService"
                    )
                }
                Log.d("R1Installer", "Launch output: $launchOutput")

                onProgress("Hoàn tất! Muse R1 Agent đã được cài đặt và khởi động trên loa.", 1.0f)
            } finally {
                closeQuietly(currentSocket, currentConnection)
            }
        }
    }

    private fun openConnection(host: String, port: Int): Pair<Socket, AdbConnection> {
        val socket = Socket().apply {
            connect(InetSocketAddress(host, port), 8_000)
            soTimeout = 60_000
            tcpNoDelay = true
        }
        val crypto = getOrCreateAdbCrypto()
        val connection = AdbConnection.create(socket, crypto)
        connection.connect()
        return Pair(socket, connection)
    }

    private fun closeQuietly(socket: Socket?, connection: AdbConnection?) {
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    private fun getOrCreateAdbCrypto(): AdbCrypto {
        val base64 = AdbBase64 { data ->
            Base64.encodeToString(data, Base64.NO_WRAP)
        }
        val privKey = File(context.filesDir, "adbkey")
        val pubKey = File(context.filesDir, "adbkey.pub")
        return try {
            if (privKey.exists() && pubKey.exists()) {
                AdbCrypto.loadAdbKeyPair(base64, privKey, pubKey)
            } else {
                AdbCrypto.generateAdbKeyPair(base64).also {
                    it.saveAdbKeyPair(privKey, pubKey)
                }
            }
        } catch (e: Exception) {
            Log.w("R1Installer", "Failed to load/save ADB key pair, generating transient key", e)
            AdbCrypto.generateAdbKeyPair(base64)
        }
    }

    private fun pushApk(
        connection: AdbConnection,
        apkFile: File,
        remotePath: String,
        onByteProgress: (pushed: Long, total: Long) -> Unit = { _, _ -> }
    ) {
        val stream = connection.open("sync:")
        try {
            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            val mode = 33188 // 0100644 octal (S_IFREG | 0644)

            val sendHeader = ByteArray(8 + pathBytes.size)
            writeString(sendHeader, 0, "SEND")
            writeInt(sendHeader, 4, (mode shl 16) or pathBytes.size)
            System.arraycopy(pathBytes, 0, sendHeader, 8, pathBytes.size)
            stream.write(sendHeader)

            val totalBytes = apkFile.length()
            var pushedBytes = 0L
            var lastReportedPercent = -1

            onByteProgress(0L, totalBytes)

            apkFile.inputStream().use { input ->
                val buffer = ByteArray(2048)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    val chunk = ByteArray(8 + read)
                    writeString(chunk, 0, "DATA")
                    writeInt(chunk, 4, read)
                    System.arraycopy(buffer, 0, chunk, 8, read)
                    stream.write(chunk)

                    pushedBytes += read
                    if (totalBytes > 0) {
                        val percent = ((pushedBytes * 100) / totalBytes).toInt()
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            onByteProgress(pushedBytes, totalBytes)
                        }
                    }
                }
            }

            val done = ByteArray(8)
            writeString(done, 0, "DONE")
            writeInt(done, 4, (System.currentTimeMillis() / 1000).toInt())
            stream.write(done)

            readSyncResponse(stream)

            // Gracefully terminate the sync service
            try {
                val quit = ByteArray(8)
                writeString(quit, 0, "QUIT")
                writeInt(quit, 4, 0)
                stream.write(quit)
            } catch (_: Exception) {
            }
        } finally {
            try {
                stream.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readSyncResponse(stream: AdbStream) {
        val response = stream.read()
        if (response.size < 4) {
            throw IOException("Phản hồi sync không hợp lệ (size = ${response.size})")
        }
        val id = String(response, 0, 4, Charsets.US_ASCII)
        if (id == "FAIL") {
            val msg = if (response.size > 8) {
                String(response, 8, response.size - 8, Charsets.UTF_8)
            } else {
                "lỗi không xác định"
            }
            throw IOException("ADB sync từ chối file: $msg")
        }
        if (id != "OKAY") {
            throw IOException("Phản hồi ADB sync không mong muốn: $id")
        }
    }

    private fun runShell(
        connection: AdbConnection,
        command: String
    ): String {
        // Thử theo thứ tự:
        // 1. exec:<cmd> (chạy trực tiếp raw pipe, không cần PTY /dev/ptmx - tránh lỗi rejected trên Android cũ)
        // 2. shell:<cmd> (chạy qua subprocess PTY)
        // 3. shell: (mở shell tương tác và gửi command\nexit\n vào stdin)
        val targets = listOf("exec:$command", "shell:$command", "shell:")
        var lastException: Exception? = null

        for (target in targets) {
            try {
                val stream = connection.open(target)
                val output = StringBuilder()
                try {
                    if (target == "shell:") {
                        val inputCmd = "$command\nexit\n"
                        stream.write(inputCmd.toByteArray(Charsets.UTF_8))
                    }
                    while (!stream.isClosed) {
                        try {
                            val buffer = stream.read()
                            output.append(String(buffer, Charsets.UTF_8))
                        } catch (_: Exception) {
                            break
                        }
                    }
                } finally {
                    try {
                        stream.close()
                    } catch (_: Exception) {
                    }
                }
                return output.toString()
            } catch (e: Exception) {
                lastException = e
                Log.w("R1Installer", "Target '$target' bị từ chối, thử phương thức tiếp theo...", e)
            }
        }
        throw lastException ?: IOException("Lệnh không thể thực thi: $command")
    }

    private fun writeString(
        target: ByteArray,
        offset: Int,
        value: String
    ) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        System.arraycopy(bytes, 0, target, offset, bytes.size)
    }

    private fun writeInt(
        target: ByteArray,
        offset: Int,
        value: Int
    ) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
