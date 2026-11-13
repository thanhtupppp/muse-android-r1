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
 * POST http://192.168.43.1:8080/open-pairing-window
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
                "/unconfigure-muse" -> unconfigureMuse(session)
                "/open-pairing-window", "/open-muse-pairing" -> openPairingWindow(session)
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
        // Cửa sổ cấu hình 10 phút (mở lúc Agent khởi động hoặc khi chủ loa mở
        // lại cửa sổ pairing qua ADB): cho Mobile cấu hình lần đầu/lấy lại
        // token kể cả với loa đã pair từ trước khi có cơ chế token này.
        if (AgentService.isHttpProvisionWindowOpen()) return true
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

    /**
     * Ép loa mở chế độ ghép nối BLE cho app Muse chính thức: phát nhanh
     * MuseGadget-xxxxxx trong 10 phút và chờ central kết nối. Đây là đường
     * từ nút bấm trong Muse Mobile; trước đây chỉ có đường ADB.
     *
     * Lệnh được chuyển qua AgentService (đường intent đã có) để việc bật
     * BLE/GATT vẫn chạy đúng vòng đời service. Handler chờ tối đa 2,5 giây
     * để phản hồi kèm trạng thái advertising thực tế thay vì chỉ nói "đã
     * nhận lệnh" — nếu BLE còn đang khởi động, Mobile sẽ tự thăm dò tiếp
     * qua /status.
     */
    private fun openPairingWindow(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return unauthorized()
        }

        AgentService.reopenPairingWindow(context)

        var serverStatus = JSONObject()
        val deadlineMs = System.currentTimeMillis() + PAIRING_STATUS_WAIT_MS
        while (System.currentTimeMillis() <= deadlineMs) {
            serverStatus = AgentService.currentBleServer?.statusJson() ?: JSONObject()
            if (serverStatus.optBoolean("advertising", false)) break

            val startError = serverStatus.optString("last_start_error", "")
            if (startError.isNotEmpty() && !serverStatus.optBoolean("starting", false)) break

            try {
                Thread.sleep(PAIRING_STATUS_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        val advertising = serverStatus.optBoolean("advertising", false)
        val starting = serverStatus.optBoolean("starting", false)
        val startError = serverStatus.optString("last_start_error", "")
        if (!advertising && !starting && startError.isNotEmpty()) {
            Log.w(TAG, "Không mở được cửa sổ pairing BLE: $startError")
            return badRequest("BLE_PAIRING_START_FAILED:$startError")
        }

        Log.i(
            TAG,
            "Đã yêu cầu mở cửa sổ pairing BLE từ Mobile " +
                "(advertising=$advertising, starting=$starting)"
        )
        return json(
            response = JSONObject()
                .put("ok", true)
                .put("pairing_window_requested", true)
                .put("pairing_window_minutes", PAIRING_WINDOW_MINUTES)
                .put("advertising", advertising)
                .put("starting", starting)
                .put("bluetooth", JSONObject().put("server", serverStatus))
                // Mobile lưu token này để sau khi loa pair xong vẫn gọi
                // được các endpoint :8080 mà không bị khoá ngoài.
                .put("provision_token", MusePreferences.ensureProvisionToken(context))
                .put(
                    "message",
                    if (advertising) {
                        "Speaker is advertising for Muse pairing"
                    } else {
                        "Pairing window requested; BLE is starting"
                    }
                )
        )
    }

    /**
     * Xoá toàn bộ cấu hình Muse (cả SDK token lẫn Direct VM) để đổi tài
     * khoản/token hoặc gỡ ghép đôi từ phía Mobile. Dùng lại
     * [MusePreferences.clearMusePairing] — cùng hàm với đường
     * `link.unpaired` từ server: identity của gadget và provision token
     * :8080 được GIỮ nguyên (provision token vẫn hợp lệ nên Mobile gọi
     * tiếp được ngay mà không cần vào lại cửa sổ cấu hình).
     */
    private fun unconfigureMuse(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return unauthorized()
        }
        MusePreferences.clearMusePairing(context)
        AgentService.reloadMuseSession(context)
        Log.i(TAG, "Đã xoá cấu hình Muse theo yêu cầu từ Mobile")
        return json(
            response = JSONObject()
                .put("ok", true)
                .put("mode", "none")
                .put("muse_paired", false)
                .put("provision_token", MusePreferences.ensureProvisionToken(context))
                .put("message", "Muse configuration cleared")
        )
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

    private fun parseJsonOrEmpty(raw: String): JSONObject {
        if (raw.isBlank()) return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
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
            // VM + host mà phiên hiện tại THỰC SỰ đang kết nối (ở chế độ
            // SDK token, vm_id do server cấp qua fetch_vms — đây là nơi
            // duy nhất đọc được giá trị đó để mang sang chế độ Direct).
            .put("active_vm_id", AgentService.currentSessionAdapter?.activeVmId ?: "")
            .put("active_vm_name", AgentService.currentSessionAdapter?.activeVmName ?: "")
            .put("active_noise_host", AgentService.currentSessionAdapter?.activeNoiseHost ?: "")
            .put("has_muse_token", MusePreferences.getMuseToken(context).isNotEmpty())
            .put("has_direct_token", MusePreferences.hasDirectConfig(context))
            .put("bluetooth", btInfo)
            // Kết quả lần cấu hình Wi-Fi gần nhất (mã lỗi thật phía loa,
            // thay vì chỉ có thông báo timeout chung chung phía app).
            .put("wifi_provision", parseJsonOrEmpty(MusePreferences.getLastWifiProvision(context)))
            // Phase 0 voice call: kết quả probe mic + probe stream realtime
            // gần nhất (rỗng nếu chưa chạy probe).
            .put(
                "voice",
                JSONObject()
                    .put("app_id", MusePreferences.getVoiceAppId(context))
                    .put("mic_probe", parseJsonOrEmpty(MusePreferences.getVoiceMicProbe(context)))
                    .put("call_probe", parseJsonOrEmpty(MusePreferences.getVoiceCallProbe(context)))
            )
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
        private const val PAIRING_WINDOW_MINUTES = 10
        private const val PAIRING_STATUS_WAIT_MS = 2_500L
        private const val PAIRING_STATUS_POLL_MS = 200L
    }
}
