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

                // R1 firmware accepts the first shell stream but rejects subsequent
                // shell streams. Run the complete install flow in one shell session.
                val command = """
                    echo MUSE_SHELL_OK
                    if [ ! -s $path ]; then
                        echo MUSE_APK_MISSING
                        exit 10
                    fi
                    echo MUSE_APK_PRESENT
                    ls -l $path
                    pm install -r $path
                    INSTALL_RC=$?
                    echo MUSE_INSTALL_RC=${'$'}INSTALL_RC
                    if [ "${'$'}INSTALL_RC" -ne 0 ]; then exit 11; fi
                    START_OUTPUT=$(am startservice -n vn.tutu.muser1/.AgentService 2>&1)
                    START_RC=$?
                    echo "${'$'}START_OUTPUT"
                    echo MUSE_START_RC=${'$'}START_RC
                    if [ "${'$'}START_RC" -ne 0 ] || echo "${'$'}START_OUTPUT" | grep -q 'Error:'; then exit 12; fi
                    sleep 2
                    SERVICE_STATE=$(dumpsys activity services vn.tutu.muser1/.AgentService 2>&1)
                    echo "${'$'}SERVICE_STATE"
                    if echo "${'$'}SERVICE_STATE" | grep -Fq 'vn.tutu.muser1/.AgentService'; then
                        echo MUSE_AGENT_RUNNING
                    else
                        echo MUSE_AGENT_NOT_RUNNING
                        exit 13
                    fi
                """.trimIndent()
                onProgress("4/5: Đang kiểm tra và cài APK trong một shell duy nhất...", 0.80f)
                val shellStream = try {
                    connection.open("shell:$command")
                } catch (e: IOException) {
                    throw IOException("R1 từ chối mở shell cài đặt: ${e.message}", e)
                }
                val output = readShell(shellStream)
                onProgress("Kết quả cài đặt: ${output.take(1800)}", 0.93f)

                if (!output.lineSequence().any { it.trim() == "MUSE_SHELL_OK" })
                    throw IOException("Không nhận được xác nhận ADB shell: ${output.take(1000)}")
                if (!output.lineSequence().any { it.trim() == "MUSE_APK_PRESENT" })
                    throw IOException("APK không tồn tại trên loa: ${output.take(1000)}")
                if (!output.lineSequence().any { it.trim() == "Success" } ||
                    !output.lineSequence().any { it.trim() == "MUSE_INSTALL_RC=0" })
                    throw IOException("pm install thất bại: ${output.take(1400)}")
                if (!output.lineSequence().any { it.trim() == "MUSE_AGENT_RUNNING" })
                    throw IOException("APK đã cài nhưng AgentService chưa chạy: ${output.take(1800)}")
                onProgress("Hoàn tất: APK đã cài và AgentService đang chạy.", 1f)
            } finally {
                closeQuietly(socket, connection)
            }
            Result.success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }


    /**
     * Collects device-side diagnostics over the authorized ADB transport.
     * The TCP probe is performed by the phone; remaining commands run on R1.
     */
    suspend fun diagnoseAgent(speakerIp: String): Result<String> = withContext(Dispatchers.IO) {
        val report = StringBuilder()
        try {
            val probe = try {
                Socket().use { probeSocket ->
                    probeSocket.connect(InetSocketAddress(speakerIp.trim(), 8080), 2_500)
                    "CONNECTED"
                }
            } catch (error: Exception) {
                "${error.javaClass.simpleName}: ${error.message ?: "no details"}"
            }
            report.append("=== PHONE TCP PROBE :8080 ===\n").append(probe).append("\n")

            val (socket, connection) = openConnection(speakerIp.trim(), 5555)
            try {
                val command = """
                    echo ===MUSE_DIAG_SERVICE===
                    dumpsys activity services vn.tutu.muser1/.AgentService 2>&1
                    echo ===MUSE_DIAG_PORT_8080===
                    (cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -i ':1F90') || echo PORT_8080_NOT_LISTENING
                    echo ===MUSE_DIAG_PROCESS===
                    ps | grep '[v]n.tutu.muser1' || echo AGENT_PROCESS_NOT_FOUND
                    echo ===MUSE_DIAG_LOGCAT===
                    logcat -d -t 120 -s MuseR1:I MuseR1:W MuseR1:E 2>&1
                """.trimIndent()
                report.append("=== ADB DEVICE DIAGNOSTICS ===\n")
                report.append(readShell(connection.open("shell:$command")))
            } finally {
                closeQuietly(socket, connection)
            }
            Result.success(report.toString())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            report.append("\nADB DIAGNOSTIC ERROR: ")
                .append(error.javaClass.simpleName)
                .append(": ")
                .append(error.message ?: "no details")
            Result.success(report.toString())
        }
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
