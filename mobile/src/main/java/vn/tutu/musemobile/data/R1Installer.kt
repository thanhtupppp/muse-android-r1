package vn.tutu.musemobile.data

import android.content.Context
import android.util.Base64
import android.util.Log
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
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
        onProgress: (String) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val host = speakerIp.trim()
            val port = 5555
            onProgress("Đang kết nối ADB tới R1 ($host:$port)...")
            Socket().apply {
                connect(InetSocketAddress(host, port), 10_000)
                soTimeout = 30_000
            }.use { socket ->
                val crypto = getOrCreateAdbCrypto()
                val connection = AdbConnection.create(socket, crypto)
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
                    command = "monkey -p vn.tutu.muser1 -c android.intent.category.LAUNCHER 1; am startservice vn.tutu.muser1/.AgentService"
                )
                onProgress("Đã cài và khởi động Muse R1 Agent")
            }
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
            readSyncResponse(stream)
            stream.close()
        } finally {
            try {
                stream.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readSyncResponse(stream: AdbStream) {
        val response = stream.read()
        if (!String(response, Charsets.US_ASCII).contains("OKAY")) {
            throw IOException("ADB từ chối push APK")
        }
    }

    private fun runShell(
        connection: AdbConnection,
        command: String
    ): String {
        val stream = connection.open("shell:$command")
        val output = StringBuilder()
        try {
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
