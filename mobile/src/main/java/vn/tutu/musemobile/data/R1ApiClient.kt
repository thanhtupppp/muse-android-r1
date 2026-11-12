package vn.tutu.musemobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client gọi Muse R1 Agent qua local HTTP khi điện thoại đang nối hotspot R1.
 */
class R1ApiClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun health(speakerIp: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("http://$speakerIp:8080/health")
                .build()

            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        }
    }

    suspend fun provisionWifi(
        speakerIp: String,
        ssid: String,
        password: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("ssid", ssid)
                .put("password", password)
                .put("security", "WPA2")
                .toString()

            val request = Request.Builder()
                .url("http://$speakerIp:8080/provision-wifi")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    "Đã gửi cấu hình Wi‑Fi tới R1. Đang chờ loa kết nối mạng nhà."
                } else {
                    "R1 từ chối cấu hình Wi‑Fi: HTTP ${response.code}"
                }
            }
        }
    }
}
