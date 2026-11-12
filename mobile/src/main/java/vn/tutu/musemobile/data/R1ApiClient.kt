package vn.tutu.musemobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class R1ApiClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

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

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val sample = rawBody.take(500)
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

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val sample = rawBody.take(500)
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

            val resolvedSecurity = security ?: if (password.isEmpty()) "OPEN" else "WPA2"

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

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val sample = rawBody.take(500)
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
}
