package vn.tutu.muser1

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local HTTP provisioning server cho Muse Mobile App.
 *
 * Server chỉ nên chạy khi R1 đang ở chế độ cấu hình/hotspot.
 * Mobile kết nối hotspot `Phicomm_R1_xxx` rồi gọi:
 *
 * GET  http://192.168.43.1:8080/health
 * GET  http://192.168.43.1:8080/status
 * POST http://192.168.43.1:8080/provision-wifi
 */
class LocalProvisionServer(
    private val context: Context,
    private val port: Int = DEFAULT_PORT
) : NanoHTTPD(port) {

    private val provisioningActive = AtomicBoolean(false)
    private var scheduledExecutor: ScheduledExecutorService? = null

    fun startServer(): Boolean {
        return try {
            scheduledExecutor = Executors.newSingleThreadScheduledExecutor()
            start(SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "Local provisioning server started on port $port")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to start local provisioning server", error)
            false
        }
    }

    fun stopServer() {
        // Shutdown executor trước, reset cờ kẹt, rồi mới dừng server HTTP
        scheduledExecutor?.let { exec ->
            exec.shutdownNow()
        }
        scheduledExecutor = null
        provisioningActive.set(false)

        try {
            stop()
            Log.i(TAG, "Local provisioning server stopped")
        } catch (error: Exception) {
            Log.w(TAG, "Failed to stop local provisioning server", error)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')

        return when (session.method) {
            Method.GET -> when (uri) {
                "/health" -> json(response = healthResponse())
                "/status" -> if (isAuthorized(session)) {
                    json(response = statusResponse())
                } else {
                    unauthorized()
                }
                else -> notFound()
            }

            Method.POST -> when (uri) {
                "/provision-wifi" -> provisionWifi(session)
                "/configure-muse", "/pair" -> configureMuse(session)
                "/command" -> handleCommand(session)
                else -> notFound()
            }

            else -> notFound()
        }
    }

    /**
     * Quy tắc xác thực thống nhất cho các endpoint ghi và /status:
     * - Loa chưa cấu hình Muse (trạng thái cài đặt ban đầu, thường là đang ở
     *   hotspot của chính loa): cho phép không cần token.
     * - Loa đã cấu hình: bắt buộc header `x-muse-token` trùng provision token
     *   (token được sinh một lần qua [MusePreferences.ensureProvisionToken] và
     *   được trả cho Mobile trong response cấu hình Muse thành công).
     * Trước đây check này là mã chết vì không luồng nào từng đặt token.
     */
    private fun isAuthorized(session: IHTTPSession): Boolean {
        if (!MusePreferences.isMusePaired(context)) return true
        val expected = MusePreferences.ensureProvisionToken(context)
        val provided = session.headers["x-muse-token"]?.trim().orEmpty()
        return provided.isNotEmpty() && provided == expected
    }

    private fun provisionWifi(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return unauthorized()
        }

        // Giới hạn độ dài Content-Length tránh tràn bộ nhớ
        val contentLengthStr = session.headers["content-length"]
        val contentLength = contentLengthStr?.toLongOrNull() ?: 0L
        if (contentLength > MAX_CONTENT_LENGTH_BYTES) {
            return badRequest("PAYLOAD_TOO_LARGE")
        }

        // Chống race condition: chỉ cho phép 1 phiên cấu hình Wi-Fi chạy tại một thời điểm
        if (!provisioningActive.compareAndSet(false, true)) {
            return badRequest("PROVISIONING_IN_PROGRESS")
        }

        return try {
            val content = mutableMapOf<String, String>()
            session.parseBody(content)

            val rawBody = content["postData"] ?: run {
                provisioningActive.set(false)
                return badRequest("MISSING_BODY")
            }

            val payload = JSONObject(rawBody)
            val ssid = payload.optString("ssid").trimEnd('\r', '\n')
            val password = payload.optString("password")
            val security = payload.optString("security", "WPA2").uppercase()

            if (ssid.isEmpty()) {
                provisioningActive.set(false)
                return badRequest("EMPTY_SSID")
            }

            // Kiểm tra security và định dạng password chặt chẽ
            val isValid = when (security) {
                "OPEN" -> password.isEmpty()
                "WPA2" -> password.length in 8..63 || (password.length == 64 && password.all {
                    it.isDigit() || it in 'a'..'f' || it in 'A'..'F'
                })
                else -> false
            }

            if (!isValid) {
                provisioningActive.set(false)
                return badRequest("INVALID_SECURITY_OR_PASSWORD")
            }

            Log.i(TAG, "Wi‑Fi provisioning accepted for SSID='$ssid' ($security) via HTTP")

            val executor = scheduledExecutor ?: run {
                provisioningActive.set(false)
                return badRequest("SERVER_STOPPING")
            }

            // Lập lịch hoãn 1.2 giây để NanoHTTPD kịp flush response HTTP 200 về client trước khi tắt SoftAP
            executor.schedule(
                {
                    try {
                        WifiProvisioner.connect(context, ssid, password)
                    } catch (e: Exception) {
                        Log.e(TAG, "Delayed Wi-Fi provisioning failed", e)
                    } finally {
                        provisioningActive.set(false)
                    }
                },
                1200L,
                TimeUnit.MILLISECONDS
            )

            json(
                response = JSONObject()
                    .put("ok", true)
                    .put("message", "Wi-Fi provisioning scheduled for $ssid")
            )
        } catch (e: NanoHTTPD.ResponseException) {
            provisioningActive.set(false)
            Log.e(TAG, "NanoHTTPD ResponseException: ${e.status}", e)
            newFixedLengthResponse(
                e.status,
                "application/json",
                JSONObject().put("ok", false).put("error", e.message).toString()
            )
        } catch (error: Exception) {
            provisioningActive.set(false)
            Log.e(TAG, "Invalid provisioning request", error)
            badRequest("INVALID_REQUEST")
        }
    }

    private fun configureMuse(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return unauthorized()
        }

        val contentLengthStr = session.headers["content-length"]
        val contentLength = contentLengthStr?.toLongOrNull() ?: 0L
        if (contentLength > MAX_CONTENT_LENGTH_BYTES) {
            return badRequest("PAYLOAD_TOO_LARGE")
        }

        return try {
            val content = mutableMapOf<String, String>()
            session.parseBody(content)

            val rawBody = content["postData"] ?: return badRequest("MISSING_BODY")
            val payload = JSONObject(rawBody)

            val mode = payload.optString("mode").trim().lowercase()
            val museToken = payload.optString("muse_token").trim()
            val vmId = payload.optString("vm_id").trim()
            val deviceToken = payload.optString("device_token")
                .ifBlank { payload.optString("token") }
                .trim()
            val serverHost = payload.optString("server_host")
                .ifBlank { payload.optString("noise_host") }
                .ifBlank { payload.optString("server") }
                .trim()
            val mqttHost = payload.optString("mqtt_host").trim()
            val deviceId = MusePreferences.getDeviceId(context)

            if (mqttHost.isNotEmpty()) {
                MusePreferences.saveAgentConfig(context, mqttHost, deviceId)
            }

            val isDirect = mode == "direct" || (museToken.isEmpty() && (vmId.isNotEmpty() || deviceToken.isNotEmpty()))
            if (isDirect) {
                if (vmId.isEmpty()) {
                    return badRequest("EMPTY_VM_ID: VM ID không được để trống")
                }
                if (deviceToken.isEmpty()) {
                    return badRequest("EMPTY_DEVICE_TOKEN: Device Token không được để trống")
                }

                val effectiveHost = MusePreferences.normalizeHost(serverHost)
                    .ifEmpty { MusePreferences.DEFAULT_NOISE_HOST }
                MusePreferences.saveDirectConfig(
                    context = context,
                    noiseHost = effectiveHost,
                    vmId = vmId,
                    deviceToken = deviceToken
                )
                AgentService.reloadMuseSession(context)

                Log.i(TAG, "Direct Muse VM configured: host=$effectiveHost vm_id=$vmId device=$deviceId")

                return json(
                    response = JSONObject()
                        .put("ok", true)
                        .put("mode", "direct")
                        .put("noise_host", MusePreferences.getNoiseHost(context))
                        .put("vm_id", vmId)
                        .put("device_id", deviceId)
                        .put("muse_paired", true)
                        // Mobile lưu token này để xác thực các lần gọi :8080 về sau.
                        .put("provision_token", MusePreferences.ensureProvisionToken(context))
                        .put("message", "Direct Muse VM configured ($effectiveHost / $vmId)")
                )
            }

            if (museToken.isEmpty()) {
                return badRequest("EMPTY_MUSE_TOKEN")
            }

            if (!museToken.startsWith("mgst_")) {
                return badRequest("INVALID_TOKEN_PREFIX: Muse token phải bắt đầu bằng 'mgst_'")
            }

            val gadgetClient = MuseGadgetClient(context, deviceId)
            val saved = gadgetClient.setToken(museToken)

            if (!saved) {
                return badRequest("FAILED_TO_SAVE_TOKEN")
            }

            AgentService.reloadMuseSession(context)

            Log.i(TAG, "Muse token configured successfully for device $deviceId")

            json(
                response = JSONObject()
                    .put("ok", true)
                    .put("mode", "sdk_token")
                    .put("message", "Muse token configured successfully")
                    .put("device_id", deviceId)
                    .put("muse_paired", true)
                    // Mobile lưu token này để xác thực các lần gọi :8080 về sau.
                    .put("provision_token", MusePreferences.ensureProvisionToken(context))
            )
        } catch (error: Exception) {
            Log.e(TAG, "Error configuring Muse token", error)
            badRequest("INVALID_REQUEST: ${error.message}")
        }
    }

    private fun handleCommand(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return unauthorized()
        }

        val contentLengthStr = session.headers["content-length"]
        val contentLength = contentLengthStr?.toLongOrNull() ?: 0L
        if (contentLength > MAX_CONTENT_LENGTH_BYTES) {
            return badRequest("PAYLOAD_TOO_LARGE")
        }

        return try {
            val content = mutableMapOf<String, String>()
            session.parseBody(content)

            val rawBody = content["postData"] ?: return badRequest("MISSING_BODY")
            val surface = MuseCommandSurface(context)
            val result = surface.execute(rawBody)

            json(response = JSONObject(result))
        } catch (error: Exception) {
            Log.e(TAG, "Error executing command", error)
            badRequest("INVALID_REQUEST: ${error.message}")
        }
    }

    private fun healthResponse(): JSONObject {
        return JSONObject()
            .put("ok", true)
            .put("service", "muse-r1-agent")
            .put("version", BuildConfig.VERSION_NAME)
            .put("provisioning_mode", true)
    }

    private fun statusResponse(): JSONObject {
        val btAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        val btInfo = JSONObject()
            .put("enabled", btAdapter?.isEnabled ?: false)
            .put("name", btAdapter?.name ?: "")
            .put("address", btAdapter?.address ?: "")
            .put("scan_mode", btAdapter?.scanMode ?: -1)
            .put("multi_adv", btAdapter?.isMultipleAdvertisementSupported ?: false)
            .put("le_advertiser", btAdapter?.bluetoothLeAdvertiser != null)
            // Trạng thái sống của Muse BLE server (advertising, lỗi khởi
            // động gần nhất, identity) để chẩn đoán từ xa khi app Muse
            // không thấy loa.
            .put("server", AgentService.currentBleServer?.statusJson() ?: JSONObject())

        return JSONObject()
            .put("ok", true)
            .put("agent_version", BuildConfig.VERSION_NAME)
            .put("android_version", android.os.Build.VERSION.RELEASE)
            .put("device_id", MusePreferences.getDeviceId(context))
            .put("network", NetworkStateObserver.currentSummary(context))
            .put("muse_paired", MusePreferences.isMusePaired(context))
            .put("muse_mode", MusePreferences.getMusePairingMode(context))
            .put("muse_connected", AgentService.isMuseConnected)
            .put("muse_error", AgentService.lastMuseError)
            .put("noise_host", MusePreferences.getNoiseHost(context))
            .put("vm_id", MusePreferences.getDirectVmId(context))
            .put("has_muse_token", MusePreferences.getMuseToken(context).isNotEmpty())
            .put("has_direct_token", MusePreferences.hasDirectConfig(context))
            .put("bluetooth", btInfo)
    }

    private fun json(response: JSONObject): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            response.toString()
        )
    }

    private fun unauthorized(): Response {
        return newFixedLengthResponse(
            Response.Status.UNAUTHORIZED,
            "application/json",
            JSONObject()
                .put("ok", false)
                .put("error", "UNAUTHORIZED")
                .toString()
        )
    }

    private fun badRequest(message: String): Response {
        return newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            "application/json",
            JSONObject()
                .put("ok", false)
                .put("error", message)
                .toString()
        )
    }

    private fun notFound(): Response {
        return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            "application/json",
            JSONObject()
                .put("ok", false)
                .put("error", "Not found")
                .toString()
        )
    }

    companion object {
        private const val TAG = "MuseR1"
        const val DEFAULT_PORT = 8080
        private const val SOCKET_READ_TIMEOUT = 5_000
        private const val MAX_CONTENT_LENGTH_BYTES = 16384L
    }
}
