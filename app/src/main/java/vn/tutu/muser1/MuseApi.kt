package vn.tutu.muser1

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Muse Device API client.
 *
 * Port tối thiểu từ Linux Device SDK:
 * https://github.com/facebookincubator/muse-gadget-sdk/blob/main/linux/src/musegadget/muse_api.py
 *
 * - GET /fetch_vms: lấy danh sách VM được lease cho device token.
 * - POST /device_token/refresh: xoay access token và refresh token.
 */
object MuseApi {

    private const val TAG = "MuseR1"
    private const val API_BASE = "https://api.muse.ai"
    private const val FETCH_PATH = "/fetch_vms"
    private const val REFRESH_PATH = "/device_token/refresh"
    private const val API_VERSION = "1.0.0"
    private const val USER_AGENT = "muse-android-r1/0.2.0"

    data class MuseVm(
        val vmId: String,
        val vmName: String,
        val vmUrl: String,
        val vmAuthToken: String,
        val isDefault: Boolean
    )

    data class TokenPair(
        val accessToken: String,
        val refreshToken: String
    )

    fun resolveApiRoot(apiBaseUrl: String = ""): String {
        return apiBaseUrl.trim().ifEmpty { API_BASE }.trimEnd('/')
    }

    fun deriveVmId(rawVmId: String, vmUrl: String): String {
        val clean = rawVmId.trim()
        if (clean.isNotEmpty()) return clean
        return try {
            val uri = URI(vmUrl)
            val fromQuery = uri.rawQuery.orEmpty()
                .split('&')
                .firstOrNull { it.startsWith("vm_id=") }
                ?.substringAfter("vm_id=")
                ?.let { URLDecoder.decode(it, "UTF-8") }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (fromQuery != null) return fromQuery
            val host = uri.host.orEmpty()
            host.substringBefore('.').takeIf { it.isNotEmpty() && host.contains('.') }.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    // Đã từng có extractNoiseHost(vmUrl): cố ý KHÔNG khôi phục. Host trong
    // vm_url/vm_ws_url của fetch_vms (dạng <vm_id>.metaivm.com) không phân
    // giải được trên DNS công khai; bản chính thức không bao giờ quay số
    // tới host đó — Noise luôn đi tới noise_host đã provision (hoặc
    // DEFAULT_NOISE_HOST) với vm_id gắn ở query. Xem MuseSessionAdapter.

    fun fetchVms(accessToken: String, apiBaseUrl: String = API_BASE): Pair<List<MuseVm>, Int?> {
        val connection = openConnection(
            url = resolveApiRoot(apiBaseUrl) + FETCH_PATH,
            method = "GET",
            accessToken = "Bearer $accessToken"
        )

        return try {
            val status = connection.responseCode

            if (status != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Fetch VMs failed: HTTP $status")
                return emptyList<MuseVm>() to status
            }

            val body = readBody(connection)
            val json = JSONObject(body)

            if (json.has("error_title") || json.has("backend_error_code")) {
                Log.w(TAG, "Fetch VMs API error: $body")
                return emptyList<MuseVm>() to status
            }

            val vmList = json.optJSONArray("vm_list") ?: return emptyList<MuseVm>() to status
            val vms = mutableListOf<MuseVm>()

            for (index in 0 until vmList.length()) {
                val item = vmList.optJSONObject(index) ?: continue
                val vmUrl = item.optString("vm_ws_url", item.optString("vm_url"))
                val vmToken = item.optString("vm_auth_token")

                if (vmUrl.isNotEmpty() && vmToken.isNotEmpty()) {
                    vms.add(
                        MuseVm(
                            vmId = deriveVmId(item.optString("vm_id"), vmUrl),
                            vmName = item.optString("vm_name"),
                            vmUrl = vmUrl,
                            vmAuthToken = vmToken,
                            isDefault = item.optBoolean("default", false)
                        )
                    )
                }
            }

            vms to status
        } catch (error: Exception) {
            Log.e(TAG, "Fetch VMs transport failure", error)
            emptyList<MuseVm>() to null
        } finally {
            connection.disconnect()
        }
    }

    fun refreshDeviceToken(
        refreshToken: String,
        deviceId: String,
        sdkToken: String? = null,
        apiBaseUrl: String = API_BASE
    ): Pair<TokenPair?, Int?> {
        val cleanRefreshToken = refreshToken.trim().substringAfterLast(":")
        val payload = JSONObject()
            .put("device_id", deviceId)
            .apply {
                if (!sdkToken.isNullOrEmpty()) {
                    put("sdk_token", sdkToken)
                }
            }

        val authHeader = if (cleanRefreshToken.startsWith("mgst_")) {
            "Bearer $cleanRefreshToken"
        } else {
            "Bearer hatch_refresh:$cleanRefreshToken"
        }

        val connection = openConnection(
            url = resolveApiRoot(apiBaseUrl) + REFRESH_PATH,
            method = "POST",
            accessToken = authHeader
        )

        return try {
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
            }

            val status = connection.responseCode

            if (status != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Token refresh failed: HTTP $status")
                return null to status
            }

            val response = JSONObject(readBody(connection))
            val data = response.optJSONObject("payload") ?: response
            val accessToken = data.optString("access_token")
            val newRefreshToken = data.optString("refresh_token")

            if (accessToken.isEmpty() || newRefreshToken.isEmpty()) {
                Log.w(TAG, "Token refresh response is missing tokens")
                return null to status
            }

            TokenPair(accessToken, newRefreshToken) to status
        } catch (error: Exception) {
            Log.e(TAG, "Token refresh transport failure", error)
            null to null
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(
        url: String,
        method: String,
        accessToken: String
    ): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Authorization", accessToken)
        connection.setRequestProperty("X-API-Version", API_VERSION)
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return connection
    }

    private fun readBody(connection: HttpURLConnection): String {
        return BufferedReader(
            InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)
        ).use { reader -> reader.readText() }
    }
}
