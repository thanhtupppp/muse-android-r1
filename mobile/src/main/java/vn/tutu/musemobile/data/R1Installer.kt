package vn.tutu.musemobile.data

import android.content.Context
import android.util.Log
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbKeyPair
import com.tananaev.adblib.AdbStream
import kotlinx.coroutines.Dispatchers
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
                val file = File(
                    context.cacheDir,
                    "muse_r1_agent.apk"
                )
                file.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        var downloaded = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val percent = ((downloaded * 100) / total).toInt()
                                onProgress(percent)
                            }
                        }
                        output.flush()
                    }
                }
                file
            }
        }
    }

    suspend fun installAgent(
        speakerIp: String,
        apkFile: File,
        onProgress: (String) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val host = speakerIp.trim()
            val port = 5555
            onProgress("Đang kết nối ADB tới R1...")
            Socket().apply {
                connect(InetSocketAddress(host, port), 10_000)
                soTimeout = 30_000
            }.use { socket ->
                val keyPair = AdbKeyPair.generate()
                val connection = AdbConnection.create(socket, keyPair)
                connection.connect()
                onProgress("Đã kết nối ADB tới R1")
                val remotePath = "/data/local/tmp/muse_r1_agent.apk"
                onProgress("Đang đẩy APK lên R1...")
                pushApk(
                    connection = connection,
                    apkFile = apkFile,
                    remotePath = remotePath
                )
                onProgress("Đang cài APK lên R1...")
                val installOutput = runShell(
                    connection = connection,
                    command = "pm install -r $remotePath"
                )
                Log.d("R1Installer", "Install output: $installOutput")
                if (!installOutput.contains("Success", ignoreCase = true)) {
                    throw IOException("Cài APK thất bại: $installOutput")
                }
                onProgress("Đang khởi động Muse R1 Agent...")
                runShell(
                    connection = connection,
                    command = "monkey -p vn.tutu.museagent -c android.intent.category.LAUNCHER 1"
                )
                onProgress("Đã cài và khởi động Muse R1 Agent")
            }
        }
    }

    private fun pushApk(
        connection: AdbConnection,
        apkFile: File,
        remotePath: String
    ) {
        val stream = connection.open("sync:")
        try {
            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            val mode = "0644".toInt(16)
            val sendHeader = ByteArray(8 + pathBytes.size)
            writeString(sendHeader, 0, "SEND")
            writeInt(sendHeader, 4, (mode shl 16) or pathBytes.size)
            System.arraycopy(pathBytes, 0, sendHeader, 8, pathBytes.size)
            stream.write(sendHeader)
            apkFile.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    val chunk = ByteArray(8 + read)
                    writeString(chunk, 0, "DATA")
                    writeInt(chunk, 4, read)
                    System.arraycopy(buffer, 0, chunk, 8, read)
                    stream.write(chunk)
                }
            }
            val done = ByteArray(8)
            writeString(done, 0, "DONE")
            writeInt(done, 4, (System.currentTimeMillis() / 1000).toInt())
            stream.write(done)
            stream.close()
        } finally {
            try {
                stream.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun runShell(
        connection: AdbConnection,
        command: String
    ): String {
        val stream = connection.open("shell:$command")
        val output = StringBuilder()
        try {
            val input = stream.source
            val buffer = ByteArray(4096)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.append(String(buffer, 0, read, Charsets.UTF_8))
            }
        } finally {
            try {
                stream.close()
            } catch (_: Exception) {
            }
        }
        return output.toString()
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

