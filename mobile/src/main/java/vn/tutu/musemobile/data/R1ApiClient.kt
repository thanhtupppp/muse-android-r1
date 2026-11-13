package vn.tutu.musemobile.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class R1ApiClient(private val context: Context? = null) {

    private val baseHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun getHttpClient(): OkHttpClient {
        val ctx = context ?: return baseHttpClient
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return baseHttpClient
        val wifiNetwork = cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return baseHttpClient

        return try {
            baseHttpClient.newBuilder()
                .socketFactory(wifiNetwork.socketFactory)
                .build()
        } catch (_: Exception) {
            baseHttpClient
        }
    }

    private fun isValidIpv4(ip: String): Boolean {
        val regex = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
        return regex.matches(ip)
    }

    suspend fun health(speakerIp: String, token: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanIp = speakerIp.trim()
            require(isValidIpv4(cleanIp)) { "Địa chỉ IP không hợp lệ: $speakerIp" }

            val reqBuilder = Request.Builder()
                .url("http://$cleanIp:8080/health")
            if (!token.isNullOrEmpty()) {
                reqBuilder.addHeader("x-muse-token", token)
            }

            getHttpClient().newCall(reqBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val sample = SecretRedactor.redact(rawBody.take(500))
                    throw IOException("HTTP ${response.code} ${response.message}; body=$sample")
                }
                val json = JSONObject(rawBody)
                json.optBoolean("ok", false)
            }
        }
    }

    suspend fun status(speakerIp: String, token: String? = null): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanIp = speakerIp.trim()
            require(isValidIpv4(cleanIp)) { "Địa chỉ IP không hợp lệ: $speakerIp" }

            val reqBuilder = Request.Builder()
                .url("http://$cleanIp:8080/status")
            if (!token.isNullOrEmpty()) {
                reqBuilder.addHeader("x-muse-token", token)
            }

            getHttpClient().newCall(reqBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val sample = SecretRedactor.redact(rawBody.take(500))
                    throw IOException("HTTP ${response.code} ${response.message}; body=$sample")
                }
                val json = JSONObject(rawBody)
                if (!json.optBoolean("ok", false)) {
                    throw IOException("R1 từ chối yêu cầu status: ${json.optString("error", "unknown error")}")
                }
                json
            }
        }
    }

    suspend fun provisionWifi(
        speakerIp: String,
        ssid: String,
        password: String,
        security: String? = null,
        token: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanIp = speakerIp.trim()
            require(isValidIpv4(cleanIp)) { "Địa chỉ IP không hợp lệ: $speakerIp" }
            require(ssid.isNotBlank()) { "SSID không được để trống" }
            require(!ssid.contains('\n') && !ssid.contains('\r')) { "SSID không được chứa ký tự xuống dòng" }
            require(!password.contains('\n') && !password.contains('\r')) { "Mật khẩu không được chứa ký tự xuống dòng" }

            val resolvedSecurity = when (security?.uppercase()) {
                "OPEN" -> "OPEN"
                "WPA", "WPA2", "WPA3" -> "WPA2"
                else -> if (password.isEmpty()) "OPEN" else "WPA2"
            }

            val isHexPsk = password.length == 64 && password.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            val isValid = when (resolvedSecurity) {
                "OPEN" -> password.isEmpty()
                "WPA2" -> password.length in 8..63 || isHexPsk
                else -> false
            }
            require(isValid) { "Mật khẩu không hợp lệ cho chuẩn bảo mật $resolvedSecurity" }

            val payload = JSONObject()
                .put("ssid", ssid)
                .put("password", password)
                .put("security", resolvedSecurity)
                .toString()

            val reqBuilder = Request.Builder()
                .url("http://$cleanIp:8080/provision-wifi")
                .post(payload.toRequestBody("application/json".toMediaType()))
            if (!token.isNullOrEmpty()) {
                reqBuilder.addHeader("x-muse-token", token)
            }

            getHttpClient().newCall(reqBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val sample = SecretRedactor.redact(rawBody.take(500))
                    throw IOException("R1 từ chối cấu hình Wi-Fi: HTTP ${response.code} ${response.message}; body=$sample")
                }
                val json = runCatching { JSONObject(rawBody) }.getOrNull()
                if (json != null && !json.optBoolean("ok", false)) {
                    throw IOException("R1 báo lỗi cấu hình Wi-Fi: ${json.optString("error", rawBody)}")
                }
                "Đã gửi cấu hình Wi‑Fi tới R1 ($resolvedSecurity)."
            }
        }
    }

    suspend fun configureMuse(
        speakerIp: String,
        museToken: String,
        mqttHost: String? = null,
        token: String? = null
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanToken = museToken.trim()
            require(cleanToken.isNotEmpty()) { "Muse Token không được để trống" }
            require(cleanToken.startsWith("mgst_")) { "Muse Token phải bắt đầu bằng 'mgst_'" }

            val payload = JSONObject().apply {
                put("mode", "sdk_token")
                put("muse_token", cleanToken)
                if (!mqttHost.isNullOrBlank()) {
                    put("mqtt_host", mqttHost.trim())
                }
            }
            postConfigureMuse(speakerIp, payload, token, "Muse Token")
        }
    }

    suspend fun configureMuseDirect(
        speakerIp: String,
        serverHost: String,
        vmId: String,
        deviceToken: String,
        mqttHost: String? = null,
        token: String? = null
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanHost = serverHost.trim()
                .removePrefix("wss://")
                .removePrefix("ws://")
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .ifEmpty { "hatch.metaaivm.com" }
            val cleanVmId = vmId.trim()
            val cleanDeviceToken = deviceToken.trim()
            require(cleanVmId.isNotEmpty()) { "VM ID không được để trống" }
            require(cleanDeviceToken.isNotEmpty()) { "Device Token không được để trống" }

            val payload = JSONObject().apply {
                put("mode", "direct")
                put("server_host", cleanHost)
                put("vm_id", cleanVmId)
                put("device_token", cleanDeviceToken)
                if (!mqttHost.isNullOrBlank()) {
                    put("mqtt_host", mqttHost.trim())
                }
            }
            postConfigureMuse(speakerIp, payload, token, "Direct Muse VM")
        }
    }

    /** Xoá cấu hình Muse trên loa (đổi tài khoản/token hoặc gỡ ghép đôi). */
    suspend fun unconfigureMuse(
        speakerIp: String,
        token: String? = null
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanIp = speakerIp.trim()
            require(isValidIpv4(cleanIp)) { "Địa chỉ IP không hợp lệ: $speakerIp" }

            val reqBuilder = Request.Builder()
                .url("http://$cleanIp:8080/unconfigure-muse")
                .post("{}".toRequestBody("application/json".toMediaType()))
            if (!token.isNullOrEmpty()) {
                reqBuilder.addHeader("x-muse-token", token)
            }

            getHttpClient().newCall(reqBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val sample = SecretRedactor.redact(rawBody.take(500))
                    throw IOException("R1 từ chối xoá cấu hình Muse: HTTP ${response.code} ${response.message}; body=$sample")
                }
                val json = JSONObject(rawBody)
                if (!json.optBoolean("ok", false)) {
                    throw IOException("R1 báo lỗi: ${json.optString("error", rawBody)}")
                }
                json
            }
        }
    }

    /**
     * Yêu cầu loa ép mở cửa sổ ghép nối BLE (phát nhanh 10 phút) để app
     * Muse chính thức thấy loa trong Add Device.
     */
    suspend fun openPairingWindow(
        speakerIp: String,
        token: String? = null
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanIp = speakerIp.trim()
            require(isValidIpv4(cleanIp)) { "Địa chỉ IP không hợp lệ: $speakerIp" }

            val reqBuilder = Request.Builder()
                .url("http://$cleanIp:8080/open-pairing-window")
                .post("{}".toRequestBody("application/json".toMediaType()))
            if (!token.isNullOrEmpty()) {
                reqBuilder.addHeader("x-muse-token", token)
            }

            getHttpClient().newCall(reqBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val sample = SecretRedactor.redact(rawBody.take(500))
                    throw IOException("R1 từ chối mở chế độ ghép nối: HTTP ${response.code} ${response.message}; body=$sample")
                }
                val json = JSONObject(rawBody)
                if (!json.optBoolean("ok", false)) {
                    throw IOException("R1 báo lỗi: ${json.optString("error", rawBody)}")
                }
                json
            }
        }
    }

    /** GET JSON từ một endpoint :8080 của loa, kèm token khi có. */
    private fun getJson(speakerIp: String, path: String, token: String?, label: String): JSONObject {
        val cleanIp = speakerIp.trim()
        require(isValidIpv4(cleanIp)) { "Địa chỉ IP không hợp lệ: $speakerIp" }

        val reqBuilder = Request.Builder().url("http://$cleanIp:8080$path")
        if (!token.isNullOrEmpty()) {
            reqBuilder.addHeader("x-muse-token", token)
        }

        getHttpClient().newCall(reqBuilder.build()).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val sample = SecretRedactor.redact(rawBody.take(500))
                throw IOException("$label thất bại: HTTP ${response.code} ${response.message}; body=$sample")
            }
            val json = JSONObject(rawBody)
            if (!json.optBoolean("ok", false)) {
                throw IOException("R1 báo lỗi: ${json.optString("error", rawBody)}")
            }
            return json
        }
    }

    /** POST JSON {ssid} tới một endpoint :8080 của loa. */
    private fun postSsid(speakerIp: String, path: String, ssid: String, token: String?, label: String): JSONObject {
        val cleanIp = speakerIp.trim()
        require(isValidIpv4(cleanIp)) { "Địa chỉ IP không hợp lệ: $speakerIp" }

        val payload = JSONObject().put("ssid", ssid)
        val reqBuilder = Request.Builder()
            .url("http://$cleanIp:8080$path")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        if (!token.isNullOrEmpty()) {
            reqBuilder.addHeader("x-muse-token", token)
        }

        getHttpClient().newCall(reqBuilder.build()).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val sample = SecretRedactor.redact(rawBody.take(500))
                throw IOException("$label thất bại: HTTP ${response.code} ${response.message}; body=$sample")
            }
            val json = JSONObject(rawBody)
            if (!json.optBoolean("ok", false)) {
                throw IOException("R1 báo lỗi: ${json.optString("error", rawBody)}")
            }
            return json
        }
    }

    /**
     * Danh sách Wi-Fi do chính loa quét được (GET /wifi-scan) — góc nhìn
     * của loa, ưu tiên hơn kết quả điện thoại tự quét.
     */
    suspend fun getWifiScan(speakerIp: String, token: String? = null): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            runCatching { getJson(speakerIp, "/wifi-scan", token, "Quét Wi-Fi từ loa") }
        }

    /** Các mạng Wi-Fi đã lưu trên loa + mạng đang dùng (GET /wifi-saved). */
    suspend fun getWifiSaved(speakerIp: String, token: String? = null): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            runCatching { getJson(speakerIp, "/wifi-saved", token, "Đọc mạng đã lưu trên loa") }
        }

    /** Quên một mạng đã lưu trên loa (POST /wifi-forget). */
    suspend fun forgetWifi(speakerIp: String, ssid: String, token: String? = null): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            runCatching { postSsid(speakerIp, "/wifi-forget", ssid, token, "Quên mạng Wi-Fi") }
        }

    /** Chuyển loa sang một mạng đã lưu (POST /wifi-use, loa lên lịch thực hiện). */
    suspend fun useWifi(speakerIp: String, ssid: String, token: String? = null): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            runCatching { postSsid(speakerIp, "/wifi-use", ssid, token, "Chuyển mạng Wi-Fi") }
        }

    private fun postConfigureMuse(
        speakerIp: String,
        payload: JSONObject,
        token: String?,
        label: String
    ): JSONObject {
        val cleanIp = speakerIp.trim()
        require(isValidIpv4(cleanIp)) { "Địa chỉ IP không hợp lệ: $speakerIp" }

        val reqBuilder = Request.Builder()
            .url("http://$cleanIp:8080/configure-muse")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        if (!token.isNullOrEmpty()) {
            reqBuilder.addHeader("x-muse-token", token)
        }

        getHttpClient().newCall(reqBuilder.build()).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val sample = SecretRedactor.redact(rawBody.take(500))
                throw IOException("R1 từ chối cấu hình $label: HTTP ${response.code} ${response.message}; body=$sample")
            }
            val json = JSONObject(rawBody)
            if (!json.optBoolean("ok", false)) {
                throw IOException("R1 báo lỗi: ${json.optString("error", rawBody)}")
            }
            return json
        }
    }

    suspend fun executeCommand(
        speakerIp: String,
        command: JSONObject,
        token: String? = null
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanIp = speakerIp.trim()
            require(isValidIpv4(cleanIp)) { "Địa chỉ IP không hợp lệ: $speakerIp" }

            val reqBuilder = Request.Builder()
                .url("http://$cleanIp:8080/command")
                .post(command.toString().toRequestBody("application/json".toMediaType()))
            if (!token.isNullOrEmpty()) {
                reqBuilder.addHeader("x-muse-token", token)
            }

            getHttpClient().newCall(reqBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val sample = SecretRedactor.redact(rawBody.take(500))
                    throw IOException("Lệnh thất bại: HTTP ${response.code} ${response.message}; body=$sample")
                }
                val json = JSONObject(rawBody)
                if (!json.optBoolean("ok", false)) {
                    throw IOException(json.optString("error", "Lỗi thực thi lệnh"))
                }
                json
            }
        }
    }

    suspend fun setVolume(speakerIp: String, volume: Int, token: String? = null): Result<JSONObject> {
        val command = JSONObject()
            .put("name", "muse_r1.set_volume")
            .put("volume", volume.coerceIn(0, 100))
        return executeCommand(speakerIp, command, token)
    }

    suspend fun testTts(speakerIp: String, text: String, token: String? = null): Result<JSONObject> {
        val command = JSONObject()
            .put("name", "muse_r1.tts")
            .put("text", text)
        return executeCommand(speakerIp, command, token)
    }
}
