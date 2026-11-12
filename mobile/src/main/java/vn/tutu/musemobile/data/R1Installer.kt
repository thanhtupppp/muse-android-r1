package vn.tutu.musemobile.data

import android.content.Context
import android.util.Base64
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import com.tananaev.adblib.AdbStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class R1Installer(private val context: Context) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).build()

    fun getLocalApkFile() = File(context.filesDir, "muse_r1_agent.apk")
    fun hasLocalApk() = getLocalApkFile().let { it.isFile && it.length() > 50_000 }

    suspend fun downloadAgentApk(
        apkUrl: String,
        onProgress: (Int) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val target = getLocalApkFile()
            val temp = File(context.filesDir, "muse_r1_agent.apk.tmp")
            try {
                httpClient.newCall(Request.Builder().url(apkUrl).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("APK response trống")
                    val total = body.contentLength()
                    var downloaded = 0L
                    var last = -1
                    temp.outputStream().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                downloaded += count
                                if (total > 0) {
                                    val percent = ((downloaded * 100) / total).toInt()
                                    if (percent != last) { last = percent; onProgress(percent) }
                                }
                            }
                        }
                    }
                    if (downloaded < 50_000 || (total >= 0 && downloaded != total)) {
                        throw IOException("APK tải về không đầy đủ")
                    }
                    if (context.packageManager.getPackageArchiveInfo(temp.absolutePath, 0)
                            ?.packageName != "vn.tutu.muser1") {
                        throw IOException("File tải về không phải APK Muse R1 Agent")
                    }
                }
                if (!temp.renameTo(target)) {
                    throw IOException("Không thể thay thế APK offline; giữ nguyên bản cũ")
                }
                Result.success(target)
            } finally { temp.delete() }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    suspend fun installAgent(
        speakerIp: String,
        apkFile: File,
        onProgress: (message: String, progress: Float?) -> Unit = { _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            require(apkFile.isFile && apkFile.length() > 50_000) { "APK offline không hợp lệ" }
            val host = speakerIp.trim()
            val path = "/data/local/tmp/muse_r1_agent.apk"
            onProgress("1/5: Đang kết nối ADB $host:5555...", 0.05f)
            val (socket, connection) = openConnection(host, 5555)
            try {
                onProgress("2/5: Đã bắt tay ADB. Đang truyền APK...", 0.15f)
                pushApk(connection, apkFile, path) { pushed, total ->
                    val fraction = pushed.toFloat() / total
                    onProgress("3/5: Truyền APK ${(fraction * 100).toInt()}%", 0.20f + fraction * 0.55f)
                }
                onProgress("Đã nhận Sync OKAY. Giữ kết nối ADB hiện tại để chạy shell...", 0.76f)

                // Reuse the authenticated ADB transport for every shell command.
                // Some Android 5.1.1 adbd builds reject rapid reconnects after a sync push.
                fun execute(command: String): String {
                    val errors = mutableListOf<String>()
                    for (service in listOf("shell:", "exec:")) {
                        val stream = try {
                            connection.open(service + command)
                        } catch (e: IOException) {
                            errors += "$service ${e.message}"
                            continue
                        }
                        return readShell(stream)
                    }
                    throw IOException("R1 từ chối mở shell trên kết nối ADB hiện tại: ${errors.joinToString("; ")}")
                }

                val probe = execute("echo MUSE_SHELL_OK; if [ -s $path ]; then echo MUSE_APK_PRESENT; ls -l $path; fi")
                onProgress("Kiểm tra shell/file: ${probe.take(600)}", 0.78f)
                if (!probe.lineSequence().any { it.trim() == "MUSE_SHELL_OK" })
                    throw IOException("Chưa xác nhận được ADB shell: ${probe.take(600)}")
                if (!probe.lineSequence().any { it.trim() == "MUSE_APK_PRESENT" })
                    throw IOException("Không tìm thấy APK đã truyền trên R1")

                onProgress("4/5: Đang chạy pm install -r...", 0.80f)
                val installed = execute("pm install -r $path")
                onProgress("pm install: ${installed.take(1000)}", 0.90f)
                if (!installed.lineSequence().any { it.trim() == "Success" })
                    throw IOException("pm install không trả Success: ${installed.take(1000)}")

                onProgress("5/5: Đang khởi động Agent...", 0.95f)
                val launched = execute("am startservice -n vn.tutu.muser1/.AgentService")
                onProgress("Kết quả khởi động: ${launched.take(600)}", 0.97f)
                if (listOf("Error", "Exception", "Permission Denial", "not found").any { launched.contains(it, true) })
                    throw IOException("APK đã cài nhưng không khởi động được Agent: ${launched.take(600)}")
                val services = execute("dumpsys activity services vn.tutu.muser1")
                if (!services.contains(".AgentService"))
                    throw IOException("APK đã cài nhưng chưa xác nhận AgentService đang chạy")
                onProgress("Hoàn tất: đã cài APK và xác nhận AgentService đang chạy.", 1f)
            } finally {
                closeQuietly(socket, connection)
            }
            Result.success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    private fun openConnection(host: String, port: Int): Pair<Socket, AdbConnection> {
        val socket = Socket()
        var connection: AdbConnection? = null
        try {
            socket.connect(InetSocketAddress(host, port), 8_000)
            socket.soTimeout = 60_000
            socket.tcpNoDelay = true
            val created = AdbConnection.create(socket, getOrCreateAdbCrypto())
            connection = created
            created.connect()
            return socket to created
        } catch (e: Exception) { closeQuietly(socket, connection); throw e }
    }

    private fun closeQuietly(socket: Socket?, connection: AdbConnection?) {
        try { connection?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }

    @Synchronized
    private fun getOrCreateAdbCrypto(): AdbCrypto {
        val base64 = AdbBase64 { Base64.encodeToString(it, Base64.NO_WRAP) }
        val privateKey = File(context.filesDir, "adbkey")
        val publicKey = File(context.filesDir, "adbkey.pub")
        return if (privateKey.exists() && publicKey.exists()) {
            AdbCrypto.loadAdbKeyPair(base64, privateKey, publicKey)
        } else {
            AdbCrypto.generateAdbKeyPair(base64).also { it.saveAdbKeyPair(privateKey, publicKey) }
        }
    }

    private fun pushApk(
        connection: AdbConnection, apkFile: File, remotePath: String,
        onByteProgress: (Long, Long) -> Unit
    ) {
        val stream = connection.open("sync:")
        try {
            val path = "$remotePath,33188".toByteArray(Charsets.UTF_8)
            stream.write(packet("SEND", path.size, path))
            var pushed = 0L
            var last = -1
            apkFile.inputStream().use { input ->
                val buffer = ByteArray(2048)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    stream.write(packet("DATA", count, buffer.copyOf(count)))
                    pushed += count
                    val percent = ((pushed * 100) / apkFile.length()).toInt()
                    if (percent != last) { last = percent; onByteProgress(pushed, apkFile.length()) }
                }
            }
            stream.write(packet("DONE", (System.currentTimeMillis() / 1000).toInt()))
            readSyncResponse(stream)
            try { stream.write(packet("QUIT", 0)) } catch (_: IOException) {}
        } finally { try { stream.close() } catch (_: Exception) {} }
    }

    private fun readSyncResponse(stream: AdbStream) {
        val bytes = ByteArrayOutputStream()
        fun requireBytes(count: Int) {
            while (bytes.size() < count) {
                val chunk = stream.read()
                if (chunk.isEmpty()) throw IOException("Phản hồi sync bị ngắt")
                bytes.write(chunk)
            }
        }
        requireBytes(8)
        val header = bytes.toByteArray()
        val id = String(header, 0, 4, Charsets.US_ASCII)
        val size = (header[4].toInt() and 255) or
            ((header[5].toInt() and 255) shl 8) or
            ((header[6].toInt() and 255) shl 16) or
            ((header[7].toInt() and 255) shl 24)
        when (id) {
            "OKAY" -> if (size != 0) throw IOException("Sync OKAY có length không hợp lệ")
            "FAIL" -> {
                if (size !in 0..65536) throw IOException("Sync FAIL quá dài")
                requireBytes(8 + size)
                throw IOException("ADB sync: ${String(bytes.toByteArray(), 8, size, Charsets.UTF_8)}")
            }
            else -> throw IOException("Sync response không mong muốn: $id")
        }
    }

    private fun readShell(stream: AdbStream): String {
        val output = ByteArrayOutputStream()
        try {
            while (true) {
                val bytes = try { stream.read() } catch (e: IOException) {
                    if (stream.isClosed) break else throw e
                }
                if (output.size() + bytes.size > 1_048_576) throw IOException("Shell output quá dài")
                output.write(bytes)
            }
        } finally { try { stream.close() } catch (_: Exception) {} }
        return String(output.toByteArray(), Charsets.UTF_8)
    }

    private fun packet(id: String, value: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val result = ByteArray(8 + payload.size)
        System.arraycopy(id.toByteArray(Charsets.US_ASCII), 0, result, 0, 4)
        for (index in 0..3) result[4 + index] = (value ushr (index * 8)).toByte()
        System.arraycopy(payload, 0, result, 8, payload.size)
        return result
    }
}
