package vn.tutu.muser1

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Muse session adapter cho Phicomm R1.
 *
 * Hỗ trợ đầy đủ cả 2 chế độ:
 * - Cách B (Direct VM): kết nối thẳng `wss://<server_host>/v1/noise?vm_id=<vm_id>`
 *   với `Authorization: Bearer <device_token>`.
 * - Cách A / Legacy SDK token: refresh/fetch VM qua Meta API rồi nâng cấp Noise XX.
 *
 * Sau khi WebSocket upgrade: thực hiện Noise_XX_25519_AESGCM_SHA256 handshake (3 bước),
 * mở stream `POST /link-control`, gửi `link.register` (4-byte LE + JSON), và xử lý
 * `link.invoke` -> `link.result`.
 */
class MuseSessionAdapter(
    private val context: Context,
    private val deviceId: String,
    private val commandSurface: MuseCommandSurface,
    private val onStateChanged: ((connected: Boolean, error: String) -> Unit)? = null
) {

    private enum class SessionOutcome {
        CLOSED,
        AUTH_REJECTED,
        UNPAIRED,
        STOPPED
    }

    private sealed class WsEvent {
        object Open : WsEvent()
        class Binary(val bytes: ByteArray) : WsEvent()
        class Text(val text: String) : WsEvent()
        class Closed(val code: Int, val reason: String) : WsEvent()
        class Failure(val httpStatus: Int?, val throwable: Throwable) : WsEvent()
    }

    private data class ResolvedTarget(
        val mode: String,
        val noiseHost: String,
        val vmId: String,
        val wsUrl: String,
        val bearerToken: String,
        val vmName: String = ""
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val transportSendLock = Any()
    private val invokeExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    /**
     * Định tuyến frame cho các stream phụ (không phải /link-control): vòng
     * lặp đọc của phiên offer frame vào hàng đợi đã đăng ký theo streamId.
     * Hiện dùng cho probe voice call (Phase 0); VoiceCallEngine sau này
     * cũng đi qua đây hoặc qua phiên voice riêng.
     */
    private val auxStreams = ConcurrentHashMap<Long, LinkedBlockingQueue<ServiceFrame>>()

    @Volatile
    private var activeTransport: NoiseTransport? = null

    @Volatile
    private var activeWs: WebSocket? = null

    @Volatile
    private var webSocket: WebSocket? = null
    @Volatile
    private var controlRegistered: Boolean = false
    @Volatile
    private var isRunning: Boolean = false
    @Volatile
    var lastError: String = ""
        private set
    @Volatile
    var activeVmId: String = ""
        private set
    @Volatile
    var activeVmName: String = ""
        private set
    @Volatile
    var activeNoiseHost: String = MusePreferences.DEFAULT_NOISE_HOST
        private set

    /** Đích phiên gần nhất đã resolve thành công (cho phiên voice riêng). */
    @Volatile
    private var lastResolvedTarget: ResolvedTarget? = null

    /**
     * Đích để mở phiên Noise voice riêng (cùng VM + bearer của phiên
     * chính, kể cả chế độ Direct). Null khi chưa resolve được lần nào —
     * VoiceCall lúc đó báo no_target thay vì tự resolve trùng lặp.
     */
    fun currentVoiceTarget(): VoiceCall.VoiceTarget? {
        val target = lastResolvedTarget ?: return null
        if (target.vmId.isEmpty() || target.bearerToken.isEmpty()) return null
        return VoiceCall.VoiceTarget(target.noiseHost, target.vmId, target.bearerToken)
    }

    private var workerThread: Thread? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        lastError = ""
        val thread = Thread({ runSessionLoop() }, "MuseSessionAdapterThread")
        workerThread = thread
        thread.start()
    }

    fun stop() {
        isRunning = false
        controlRegistered = false
        try {
            webSocket?.close(1000, "Agent stopping")
        } catch (_: Exception) {
        }
        webSocket = null
        workerThread?.interrupt()
        workerThread = null
        invokeExecutor.shutdownNow()
        notifyState(false, lastError)
    }

    fun isConnected(): Boolean = webSocket != null && controlRegistered

    private fun runSessionLoop() {
        var backoffMs = 2_000L
        while (isRunning) {
            try {
                if (!MusePreferences.isMusePaired(context)) {
                    lastError = "NOT_PAIRED"
                    notifyState(false, lastError)
                    sleepQuietly(8_000L)
                    continue
                }

                val (target, resolveError, authRejected) = resolveTarget()
                if (!isRunning) break
                if (target == null) {
                    lastError = resolveError
                    notifyState(false, lastError)
                    backoffMs = if (authRejected) 60_000L else minOf(backoffMs * 2, 15_000L)
                    sleepQuietly(backoffMs)
                    continue
                }

                activeVmId = target.vmId
                activeVmName = target.vmName
                activeNoiseHost = target.noiseHost
                lastResolvedTarget = target
                val outcome = runOneSession(target)
                controlRegistered = false
                notifyState(false, lastError)

                if (!isRunning || outcome == SessionOutcome.STOPPED) break
                backoffMs = when (outcome) {
                    SessionOutcome.AUTH_REJECTED, SessionOutcome.UNPAIRED -> 60_000L
                    SessionOutcome.CLOSED -> 3_000L
                    SessionOutcome.STOPPED -> 2_000L
                }
                sleepQuietly(backoffMs)
            } catch (e: InterruptedException) {
                if (!isRunning) break
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi vòng lặp phiên Muse Noise", e)
                lastError = e.message ?: "SESSION_ERROR"
                notifyState(false, lastError)
                sleepQuietly(backoffMs)
                backoffMs = minOf(backoffMs * 2, 15_000L)
            }
        }
    }

    private fun resolveTarget(): Triple<ResolvedTarget?, String, Boolean> {
        if (MusePreferences.hasDirectConfig(context)) {
            val host = MusePreferences.getNoiseHost(context)
            val vmId = MusePreferences.getDirectVmId(context)
            val token = MusePreferences.getDirectToken(context)
            val wsUrl = buildNoiseUrl(host, vmId)
            return Triple(
                ResolvedTarget(
                    mode = "direct",
                    noiseHost = host,
                    vmId = vmId,
                    wsUrl = wsUrl,
                    bearerToken = token
                ),
                "",
                false
            )
        }

        val apiBase = MusePreferences.getApiUrlV2(context)
        val refreshToken = MusePreferences.getMuseRefreshToken(context)
        val sdkToken = MusePreferences.getMuseToken(context).takeIf { it.startsWith("mgst_") }

        // Refresh có kỷ luật như bản chính thức (service.py): chỉ refresh khi
        // access token đã quá 3 giờ hoặc chưa có access token, và giãn cách
        // các lần thử refresh thất bại tối thiểu 300 giây. Refresh ở mọi vòng
        // reconnect vừa dễ ăn rate-limit vừa tự invalidate refresh token cũ.
        // Trả về "authRejected" (true) chỉ khi refresh bị 401 — 403 không phải
        // bằng chứng token bị thu hồi (xem vm_api.c của bản ESP32).
        fun attemptRefresh(): Boolean? {
            val effectiveRefresh = refreshToken.ifEmpty { sdkToken.orEmpty() }
            if (effectiveRefresh.isEmpty()) return null
            val now = System.currentTimeMillis()
            MusePreferences.setLastRefreshAttemptMs(context, now)
            val (tokens, refreshStatus) = MuseApi.refreshDeviceToken(
                refreshToken = effectiveRefresh,
                // Body refresh của cả hai bản chính thức đều dùng node_id
                // "homelink-..." làm device_id; "hatch-link:<mac>" chỉ dùng
                // cho transcript pairing và get_device_info.
                deviceId = MusePreferences.getMuseIdentity(context).nodeId,
                sdkToken = sdkToken,
                apiBaseUrl = apiBase
            )
            if (tokens != null) {
                MusePreferences.saveMuseSessionTokens(context, tokens.accessToken, tokens.refreshToken)
                MusePreferences.setLastRefreshSuccessMs(context, now)
                return true
            }
            if (refreshStatus == 401) {
                Log.w(TAG, "Muse token refresh bị từ chối (HTTP 401)")
                return false
            }
            Log.w(TAG, "Muse token refresh thất bại tạm thời (HTTP $refreshStatus); dùng access token hiện có nếu còn")
            return null
        }

        val hasAccess = MusePreferences.getMuseAccessToken(context).isNotEmpty()
        val nowMs = System.currentTimeMillis()
        val refreshDue = !hasAccess ||
            (nowMs - MusePreferences.getLastRefreshSuccessMs(context)) >= REFRESH_AGE_MS
        val refreshThrottled =
            (nowMs - MusePreferences.getLastRefreshAttemptMs(context)) < REFRESH_RETRY_THROTTLE_MS
        if (refreshDue && !refreshThrottled) {
            if (attemptRefresh() == false) {
                return Triple(null, "REFRESH_HTTP_401", true)
            }
        }

        var accessToken = MusePreferences.getMuseAccessToken(context)
        if (accessToken.isEmpty()) {
            return Triple(null, "MISSING_ACCESS_TOKEN", false)
        }

        var (vms, vmStatus) = MuseApi.fetchVms(accessToken, apiBaseUrl = apiBase)
        if (vmStatus == 401 && refreshToken.isNotEmpty()) {
            // Access token hết hạn thật (không phải do refresh theo tuổi):
            // ép refresh đúng một lần rồi thử lại fetch_vms trước khi kết luận.
            Log.w(TAG, "fetch_vms trả 401; ép refresh một lần rồi thử lại")
            if (attemptRefresh() == false) {
                return Triple(null, "REFRESH_HTTP_401", true)
            }
            accessToken = MusePreferences.getMuseAccessToken(context)
            val retry = MuseApi.fetchVms(accessToken, apiBaseUrl = apiBase)
            vms = retry.first
            vmStatus = retry.second
        }
        if (vmStatus == 401) {
            Log.w(TAG, "fetch_vms bị từ chối (HTTP 401)")
            return Triple(null, "FETCH_VMS_HTTP_401", true)
        }
        if (vmStatus == 403) {
            // 403 không phải bằng chứng revoked: coi là lỗi tạm thời, thử lại sau.
            Log.w(TAG, "fetch_vms bị chặn tạm thời (HTTP 403)")
            return Triple(null, "FETCH_VMS_HTTP_403", false)
        }
        val selectedVm = vms.firstOrNull { it.isDefault } ?: vms.firstOrNull()
        if (selectedVm == null) {
            return Triple(null, "NO_LEASED_VM", false)
        }
        // Ghi rõ VM được chọn: khi tài khoản có nhiều VM và không VM nào
        // được đánh dấu mặc định, việc lặng lẽ lấy VM đầu tiên từng khiến
        // chẩn đoán "loa vào sai VM" rất khó lần ra.
        Log.i(
            TAG,
            "Chọn VM: name='${selectedVm.vmName}' id='${selectedVm.vmId}' " +
                "default=${selectedVm.isDefault} (tổng ${vms.size} VM)"
        )

        // Host để quay số Noise LUÔN là host đã cấu hình: app Muse gửi kèm
        // trong provision_v2, hoặc host nhập tay ở Direct VM, hoặc mặc định
        // hatch.metaaivm.com — đúng như bản chính thức (ESP32
        // noise_control.cpp chỉ quay số tới s_noise_host đã provision; Linux
        // link_client.noise_url cũng dựng URL từ noise_host + vm_id).
        // TUYỆT ĐỐI không lấy host từ vm_url/vm_ws_url của fetch_vms: host
        // dạng <vm_id>.metaivm.com không tồn tại trên DNS công khai
        // (NXDOMAIN — đã xác minh trên loa thật ngày 2026-11-14: loa nhận đủ
        // cấu hình BLE, fetch_vms chạy được, nhưng không bao giờ nối được
        // Noise vì host suy ra từ vm_url không phân giải được).
        // vm_url chỉ còn dùng để suy ra vm_id khi API không trả vm_id.
        val noiseHost = MusePreferences.getNoiseHost(context)
        val vmId = MuseApi.deriveVmId(selectedVm.vmId, selectedVm.vmUrl)
        val wsUrl = buildNoiseUrl(noiseHost, vmId)
        return Triple(
            ResolvedTarget(
                mode = MusePreferences.getMusePairingMode(context),
                noiseHost = noiseHost,
                vmId = vmId,
                wsUrl = wsUrl,
                bearerToken = selectedVm.vmAuthToken,
                vmName = selectedVm.vmName
            ),
            "",
            false
        )
    }

    private fun runOneSession(target: ResolvedTarget): SessionOutcome {
        val events = LinkedBlockingQueue<WsEvent>()
        val request = Request.Builder()
            .url(target.wsUrl)
            .addHeader("Authorization", "Bearer ${target.bearerToken}")
            .build()

        Log.i(TAG, "Đang kết nối Muse Noise (${target.mode}): ${target.wsUrl}")
        val ws = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    events.offer(WsEvent.Open)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    events.offer(WsEvent.Binary(bytes.toByteArray()))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    events.offer(WsEvent.Text(text))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    events.offer(WsEvent.Closed(code, reason))
                }

                override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                    events.offer(WsEvent.Failure(response?.code, throwable))
                }
            }
        )
        webSocket = ws

        try {
            val openEvent = events.poll(20, TimeUnit.SECONDS)
                ?: return closeAndFail(ws, "WS_CONNECT_TIMEOUT", SessionOutcome.CLOSED)
            when (openEvent) {
                is WsEvent.Open -> Unit
                is WsEvent.Failure -> {
                    val code = openEvent.httpStatus
                    val msg = if (code != null) "WS_HTTP_$code" else (openEvent.throwable.message ?: "WS_CONNECT_ERROR")
                    Log.w(TAG, "WebSocket upgrade thất bại: $msg", openEvent.throwable)
                    return closeAndFail(
                        ws,
                        msg,
                        if (code == 401 || code == 403) SessionOutcome.AUTH_REJECTED else SessionOutcome.CLOSED
                    )
                }
                is WsEvent.Closed -> return closeAndFail(ws, "WS_CLOSED_${openEvent.code}", SessionOutcome.CLOSED)
                else -> return closeAndFail(ws, "WS_UNEXPECTED_OPEN_EVENT", SessionOutcome.CLOSED)
            }

            // Bước 1: Noise_XX_25519_AESGCM_SHA256 handshake (msg1 -> msg2 -> msg3)
            val handshake = NoiseXxHandshake(isInitiator = true)
            val msg1 = handshake.writeMessage1()
            if (!ws.send(ByteString.of(*msg1))) {
                return closeAndFail(ws, "NOISE_MSG1_SEND_FAILED", SessionOutcome.CLOSED)
            }

            val msg2Event = waitForBinaryEvent(events, 20_000L)
            if (msg2Event !is WsEvent.Binary) {
                val err = when (msg2Event) {
                    is WsEvent.Failure -> msg2Event.throwable.message ?: "NOISE_MSG2_FAILURE"
                    is WsEvent.Closed -> "NOISE_MSG2_CLOSED_${msg2Event.code}"
                    else -> "NOISE_MSG2_TIMEOUT"
                }
                return closeAndFail(ws, err, SessionOutcome.CLOSED)
            }

            handshake.readMessage2(msg2Event.bytes)
            val (msg3, ciphers) = handshake.writeMessage3()
            if (!ws.send(ByteString.of(*msg3))) {
                return closeAndFail(ws, "NOISE_MSG3_SEND_FAILED", SessionOutcome.CLOSED)
            }
            Log.i(TAG, "Noise XX handshake hoàn tất với ${target.noiseHost} (vm=${target.vmId})")

            val transport = NoiseTransport(ciphers.sendCipher, ciphers.recvCipher)
            activeTransport = transport
            activeWs = ws

            // Bước 2: Mở stream POST /link-control và gửi link.register
            val registerId = UUID.randomUUID().toString()
            val controlStreamId: Long
            synchronized(transportSendLock) {
                val (sid, openFrames) = transport.startStreamRequest("POST", CONTROL_PATH)
                controlStreamId = sid
                if (!sendBinaryFramesLocked(ws, openFrames)) {
                    return closeAndFail(ws, "CONTROL_OPEN_SEND_FAILED", SessionOutcome.CLOSED)
                }
                // node_id của Muse Gadget phải là "homelink-<6 hex cuối>",
                // trùng hậu tố với tên BLE "MuseGadget-<6 HEX>".
                val museNodeId = MusePreferences.getMuseIdentity(context).nodeId
                val regJson = MuseLinkProtocol.buildRegisterMessage(
                    requestId = registerId,
                    nodeId = museNodeId,
                    displayName = "Phicomm R1 ($museNodeId)",
                    agentVersion = BuildConfig.VERSION_NAME,
                    commandsV2 = commandSurface.commandsV2Json(),
                    networkSsid = currentWifiSsid()
                )
                val regFrames = transport.encryptBodyChunk(
                    controlStreamId,
                    LengthPrefixedJsonDecoder.encode(regJson),
                    endBody = false
                )
                if (!sendBinaryFramesLocked(ws, regFrames)) {
                    return closeAndFail(ws, "REGISTER_SEND_FAILED", SessionOutcome.CLOSED)
                }
            }
            Log.i(TAG, "Đã gửi link.register (node=${MusePreferences.getMuseIdentity(context).nodeId}, stream=$controlStreamId)")

            // Bước 3: Nhận ServiceFrame và xử lý link.invoke / link.unpaired
            val jsonDecoder = LengthPrefixedJsonDecoder()
            while (isRunning) {
                val event = events.poll(5, TimeUnit.SECONDS) ?: continue
                when (event) {
                    is WsEvent.Binary -> {
                        val frame = transport.decryptFrame(event.bytes) ?: continue
                        if (frame.streamId != controlStreamId) {
                            // Stream phụ (probe voice call...): giao cho
                            // hàng đợi đã đăng ký, không nuốt mất frame.
                            auxStreams[frame.streamId]?.offer(frame)
                            continue
                        }
                        frame.reset?.let { reset ->
                            Log.w(TAG, "Control stream bị reset: code=${reset.code} reason=${reset.reason}")
                            return closeAndFail(ws, "STREAM_RESET_${reset.code}", SessionOutcome.CLOSED)
                        }

                        var data = ByteArray(0)
                        var ended = false
                        frame.response?.let { resp ->
                            if (resp.status !in 200..299) {
                                Log.w(TAG, "/link-control bị từ chối: HTTP ${resp.status}")
                                val outcome = if (resp.status == 401 || resp.status == 403) {
                                    SessionOutcome.AUTH_REJECTED
                                } else {
                                    SessionOutcome.CLOSED
                                }
                                return closeAndFail(ws, "CONTROL_HTTP_${resp.status}", outcome)
                            }
                            data = resp.body
                            ended = resp.endBody
                        }
                        frame.bodyChunk?.let { chunk ->
                            data = chunk.data
                            ended = chunk.endBody
                        }

                        for (rawMsg in jsonDecoder.push(data)) {
                            if (rawMsg.isBlank()) continue
                            val msg = try {
                                JSONObject(rawMsg)
                            } catch (e: Exception) {
                                Log.w(TAG, "Bỏ qua JSON control lỗi: ${rawMsg.take(120)}")
                                continue
                            }

                            if (msg.optString("id") == registerId && !msg.has("method")) {
                                if (msg.has("error") || !msg.optBoolean("ok", true)) {
                                    Log.e(TAG, "link.register bị VM từ chối: $rawMsg")
                                    return closeAndFail(ws, "REGISTER_REJECTED", SessionOutcome.AUTH_REJECTED)
                                }
                                controlRegistered = true
                                lastError = ""
                                notifyState(true, "")
                                Log.i(TAG, "Muse R1 đã đăng ký thành công với VM (${target.vmId})!")
                                continue
                            }

                            val ev = msg.optString("event")
                            if (ev == "link.unpaired" || ev == "node.unpaired") {
                                Log.w(TAG, "Muse đã hủy ghép đôi thiết bị ($ev); xoá pairing đã lưu và dừng reconnect")
                                // Như bản chính thức (service.py xoá pairing file khi
                                // UNPAIRED): nếu không xoá, vòng lặp cứ reconnect bằng
                                // token đã bị huỷ mỗi 60 giây mãi mãi. Identity Muse
                                // và provision token được giữ nguyên.
                                MusePreferences.clearMusePairing(context)
                                return closeAndFail(ws, "UNPAIRED", SessionOutcome.UNPAIRED)
                            }

                            if (msg.optString("method") == MuseLinkProtocol.METHOD_INVOKE) {
                                val invokeId = msg.optString("id")
                                val command = msg.optString("command")
                                val params = msg.optJSONObject("params") ?: JSONObject()
                                if (invokeId.isNotEmpty() && !invokeExecutor.isShutdown) {
                                    invokeExecutor.execute {
                                        val outcome = commandSurface.executeInvoke(command, params)
                                        val resultJson = MuseLinkProtocol.buildInvokeResult(invokeId, outcome)
                                        synchronized(transportSendLock) {
                                            if (webSocket === ws && isRunning) {
                                                val frames = transport.encryptBodyChunk(
                                                    controlStreamId,
                                                    LengthPrefixedJsonDecoder.encode(resultJson),
                                                    endBody = false
                                                )
                                                sendBinaryFramesLocked(ws, frames)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (ended) {
                            Log.i(TAG, "Control stream kết thúc bởi VM")
                            return closeAndFail(ws, "CONTROL_STREAM_ENDED", SessionOutcome.CLOSED)
                        }
                    }
                    is WsEvent.Text -> Log.d(TAG, "Bỏ qua text frame trên Noise session")
                    is WsEvent.Closed -> return closeAndFail(ws, "WS_CLOSED_${event.code}", SessionOutcome.CLOSED)
                    is WsEvent.Failure -> {
                        val code = event.httpStatus
                        val msg = event.throwable.message ?: "WS_FAILURE"
                        Log.w(TAG, "Muse Noise session lỗi: $msg", event.throwable)
                        return closeAndFail(
                            ws,
                            msg,
                            if (code == 401 || code == 403) SessionOutcome.AUTH_REJECTED else SessionOutcome.CLOSED
                        )
                    }
                    is WsEvent.Open -> Unit
                }
            }
            return SessionOutcome.STOPPED
        } finally {
            if (webSocket === ws) {
                webSocket = null
            }
            try {
                ws.close(1000, "Session ended")
            } catch (_: Exception) {
            }
        }
    }

    private fun waitForBinaryEvent(events: LinkedBlockingQueue<WsEvent>, timeoutMs: Long): WsEvent? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (isRunning) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) return null
            val ev = events.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
            when (ev) {
                is WsEvent.Binary, is WsEvent.Closed, is WsEvent.Failure -> return ev
                is WsEvent.Text, is WsEvent.Open -> continue
            }
        }
        return null
    }

    private fun sendBinaryFramesLocked(ws: WebSocket, frames: List<ByteArray>): Boolean {
        for (frame in frames) {
            if (!ws.send(ByteString.of(*frame))) {
                return false
            }
        }
        return true
    }

    /**
     * Phase 0 của voice call (xem VOICE_CALL_DESIGN.md): thử mở stream
     * `/voice/realtime` NGAY TRÊN phiên chính đang chạy (phương án A — bản
     * đầy đủ sẽ dùng phiên voice riêng kèm app id), gửi admission +
     * capability offer đúng định dạng SDK, chờ readiness tối đa 15 giây,
     * rồi end-call và đóng stream sạch sẽ.
     *
     * Kết quả trả về mô tả VM chấp nhận tới đâu (HTTP status, các loại sự
     * kiện NDJSON nhận được, có session_id hay không, end-call ack) để
     * quyết định hướng Phase 1. Chạy trên thread của người gọi — KHÔNG gọi
     * từ vòng lặp đọc của phiên.
     */
    fun probeVoiceCall(): JSONObject {
        val result = JSONObject()
            .put("probed_at_ms", System.currentTimeMillis())
            .put("mode", "shared_session")
        val transport = activeTransport
        val ws = activeWs
        if (transport == null || ws == null || !controlRegistered) {
            // Return này nằm TRƯỚC khối try/finally bên dưới — mà finally là
            // nơi duy nhất lưu kết quả. Bản đầu cứ thế quẳng mất phán quyết
            // SESSION_NOT_READY (runVoiceProbes bỏ qua giá trị trả về), nên
            // /status mãi rỗng dù probe đã chạy. Lưu tường minh trước khi về.
            result.put("accepted", false).put("error", "SESSION_NOT_READY")
            MusePreferences.setVoiceCallProbe(context, result.toString())
            return result
        }

        val callId = UUID.randomUUID().toString()
        val startedAtMs = System.currentTimeMillis()
        result.put("call_id", callId)
        var streamId = -1L
        var endCallStreamId = -1L
        var admissionSent = false

        try {
            val (sid, openFrames) = transport.startStreamRequest(
                "POST",
                "/voice/realtime?call_id=$callId",
                headers = listOf(NoiseHeader("Content-Type", "application/x-ndjson; charset=utf-8"))
            )
            streamId = sid
            val queue = LinkedBlockingQueue<ServiceFrame>()
            auxStreams[streamId] = queue

            synchronized(transportSendLock) {
                if (!sendBinaryFramesLocked(ws, openFrames)) {
                    return result.put("accepted", false).put("error", "STREAM_OPEN_SEND_FAILED")
                }
                val start = JSONObject()
                    .put("type", "hatch.voice.call.start")
                    .put("call_id", callId)
                    .put("device", "mcu-wearable")
                    .put("device_id", MusePreferences.getMuseIdentity(context).nodeId)
                    .put("call_media_type", "audio")
                    .put("proactive_greeting_enabled", false)
                val offer = JSONObject()
                    .put("type", "hatch.voice.capability.offer")
                    .put("end_call_stop", 1)
                // Cuộc gọi gắn vào main chat theo quyết định thiết kế:
                // không gửi thread_id.
                val ndjson = (start.toString() + "\n" + offer.toString() + "\n")
                    .toByteArray(Charsets.UTF_8)
                if (!sendBinaryFramesLocked(ws, transport.encryptBodyChunk(streamId, ndjson))) {
                    return result.put("accepted", false).put("error", "ADMISSION_SEND_FAILED")
                }
                admissionSent = true
            }

            // Chờ phản hồi readiness từ VM.
            val deadline = System.currentTimeMillis() + 15_000
            val bodyText = StringBuilder()
            var httpStatus = -1
            var resetCode: Int? = null
            var streamEnded = false
            while (System.currentTimeMillis() < deadline && !streamEnded) {
                val frame = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                frame.reset?.let { resetCode = it.code }
                frame.response?.let { resp ->
                    httpStatus = resp.status
                    bodyText.append(String(resp.body, Charsets.UTF_8))
                    if (resp.endBody) streamEnded = true
                }
                frame.bodyChunk?.let { chunk ->
                    bodyText.append(String(chunk.data, Charsets.UTF_8))
                    if (chunk.endBody) streamEnded = true
                }
            }

            val eventTypes = JSONArray()
            var sessionIdPresent = false
            var ready = false
            for (line in bodyText.split('\n')) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                try {
                    val event = JSONObject(trimmed)
                    val type = event.optString("type")
                    if (type.isNotEmpty()) eventTypes.put(type)
                    if (event.has("session_id") && !event.isNull("session_id")) {
                        sessionIdPresent = true
                        ready = true
                    }
                    if (event.optString("call_id") == callId &&
                        type.contains("ready", ignoreCase = true)
                    ) {
                        ready = true
                    }
                } catch (_: Exception) {
                    // Dòng không phải JSON độc lập (NDJSON bị cắt giữa chunk):
                    // bỏ qua, các sự kiện đầy đủ vẫn được ghi nhận.
                }
            }

            result
                .put("http_status", httpStatus)
                .put("event_types", eventTypes)
                .put("session_id_present", sessionIdPresent)
                .put("ready", ready)
            resetCode?.let { result.put("reset_code", it) }
            result.put("accepted", httpStatus in 200..299 && resetCode == null)

            // End-call theo giao thức mỗi khi admission đã được gửi, kể cả
            // khi readiness không tới — tránh để cuộc gọi ma phía VM.
            if (admissionSent) {
                val endBody = JSONObject()
                    .put("call_id", callId)
                    .put("call_started_at", isoUtc(startedAtMs))
                    .put("call_ended_at", isoUtc(System.currentTimeMillis()))
                val (eid, endFrames) = transport.encryptHttpRequest(
                    "POST",
                    "/voice/end-call",
                    endBody.toString().toByteArray(Charsets.UTF_8),
                    headers = listOf(NoiseHeader("Content-Type", "application/json"))
                )
                endCallStreamId = eid
                val endQueue = LinkedBlockingQueue<ServiceFrame>()
                auxStreams[endCallStreamId] = endQueue
                synchronized(transportSendLock) {
                    sendBinaryFramesLocked(ws, endFrames)
                }
                var endHttp = -1
                val endText = StringBuilder()
                val endDeadline = System.currentTimeMillis() + 5_000
                var endDone = false
                while (System.currentTimeMillis() < endDeadline && !endDone) {
                    val frame = endQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    frame.response?.let { resp ->
                        endHttp = resp.status
                        endText.append(String(resp.body, Charsets.UTF_8))
                        if (resp.endBody) endDone = true
                    }
                    frame.bodyChunk?.let { chunk ->
                        endText.append(String(chunk.data, Charsets.UTF_8))
                        if (chunk.endBody) endDone = true
                    }
                }
                var endAck = false
                try {
                    val endJson = JSONObject(endText.toString())
                    endAck = endJson.optBoolean("ok", false) ||
                        endJson.optJSONObject("result")?.optBoolean("ok", false) == true
                } catch (_: Exception) {
                }
                result
                    .put("end_call_http", endHttp)
                    .put("end_call_ack", endAck)
            }
        } catch (error: Throwable) {
            // Throwable chứ không chỉ Exception: probe là công cụ chẩn đoán,
            // một Error (NoClassDefFoundError, VerifyError trên Android cũ…)
            // cũng phải để lại dấu vết có tên trong kết quả đã lưu, thay vì
            // chỉ còn object dở dang (probed_at_ms + mode + call_id) mà
            // không có phán quyết nào — đúng vết đã gặp trên loa thật
            // ngày 2026-11-14.
            Log.e(TAG, "Voice call probe lỗi", error)
            result.put("accepted", false).put("error", error.javaClass.simpleName)
        } finally {
            if (streamId > 0) {
                auxStreams.remove(streamId)
                try {
                    synchronized(transportSendLock) {
                        sendBinaryFramesLocked(ws, transport.encryptReset(streamId, "probe_done"))
                    }
                } catch (_: Exception) {
                }
            }
            if (endCallStreamId > 0) {
                auxStreams.remove(endCallStreamId)
            }
            MusePreferences.setVoiceCallProbe(context, result.toString())
            Log.i(TAG, "Voice call probe: $result")
        }
        return result
    }

    private fun isoUtc(timeMs: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(java.util.Date(timeMs))
    }

    private fun closeAndFail(ws: WebSocket, error: String, outcome: SessionOutcome): SessionOutcome {
        lastError = error
        controlRegistered = false
        if (activeWs === ws) {
            activeTransport = null
            activeWs = null
        }
        try {
            ws.close(1000, error.take(80))
        } catch (_: Exception) {
        }
        return outcome
    }

    private fun notifyState(connected: Boolean, error: String) {
        onStateChanged?.invoke(connected, error)
    }

    private fun sleepQuietly(ms: Long) {
        if (!isRunning || ms <= 0L) return
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    @Suppress("DEPRECATION")
    private fun currentWifiSsid(): String {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ssid = runCatching { wm?.connectionInfo?.ssid }.getOrNull()?.trim('"').orEmpty()
        return if (ssid == "<unknown ssid>") "" else ssid
    }

    companion object {
        private const val TAG = "MuseR1"
        private const val CONTROL_PATH = "/link-control"

        /** Chỉ refresh khi access token đã quá 3 giờ, như bản chính thức. */
        private const val REFRESH_AGE_MS = 3 * 60 * 60 * 1000L

        /** Giãn cách tối thiểu giữa hai lần thử refresh sau một lần thất bại. */
        private const val REFRESH_RETRY_THROTTLE_MS = 300 * 1000L

        fun buildNoiseUrl(noiseHost: String, vmId: String): String {
            val cleanHost = MusePreferences.normalizeHost(noiseHost).ifEmpty { MusePreferences.DEFAULT_NOISE_HOST }
            val encodedVm = URLEncoder.encode(vmId.trim(), "UTF-8").replace("+", "%20")
            return "wss://$cleanHost/v1/noise?vm_id=$encodedVm"
        }
    }
}
