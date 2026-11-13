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
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
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
        val bearerToken: String
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val transportSendLock = Any()
    private val invokeExecutor: ExecutorService = Executors.newFixedThreadPool(2)

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
    var activeNoiseHost: String = MusePreferences.DEFAULT_NOISE_HOST
        private set

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
                activeNoiseHost = target.noiseHost
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

        if (refreshToken.isNotEmpty() || !sdkToken.isNullOrEmpty()) {
            val effectiveRefresh = refreshToken.ifEmpty { sdkToken.orEmpty() }
            val (tokens, refreshStatus) = MuseApi.refreshDeviceToken(
                refreshToken = effectiveRefresh,
                deviceId = deviceId,
                sdkToken = sdkToken,
                apiBaseUrl = apiBase
            )
            if (tokens != null) {
                MusePreferences.saveMuseSessionTokens(context, tokens.accessToken, tokens.refreshToken)
            } else if (refreshStatus == 401 || refreshStatus == 403) {
                Log.w(TAG, "Muse token refresh bị từ chối (HTTP $refreshStatus)")
                return Triple(null, "REFRESH_HTTP_$refreshStatus", true)
            }
        }

        val accessToken = MusePreferences.getMuseAccessToken(context)
        if (accessToken.isEmpty()) {
            return Triple(null, "MISSING_ACCESS_TOKEN", false)
        }

        val (vms, vmStatus) = MuseApi.fetchVms(accessToken, apiBaseUrl = apiBase)
        if (vmStatus == 401 || vmStatus == 403) {
            Log.w(TAG, "fetch_vms bị từ chối (HTTP $vmStatus)")
            return Triple(null, "FETCH_VMS_HTTP_$vmStatus", true)
        }
        val selectedVm = vms.firstOrNull { it.isDefault } ?: vms.firstOrNull()
        if (selectedVm == null) {
            return Triple(null, "NO_LEASED_VM", false)
        }

        val fallbackHost = MusePreferences.getNoiseHost(context)
        val noiseHost = MuseApi.extractNoiseHost(selectedVm.vmUrl, fallbackHost)
        val vmId = MuseApi.deriveVmId(selectedVm.vmId, selectedVm.vmUrl)
        val wsUrl = if (selectedVm.vmUrl.contains("/v1/noise") &&
            (selectedVm.vmUrl.startsWith("wss://") || selectedVm.vmUrl.startsWith("ws://"))
        ) {
            selectedVm.vmUrl
        } else {
            buildNoiseUrl(noiseHost, vmId)
        }
        return Triple(
            ResolvedTarget(
                mode = MusePreferences.getMusePairingMode(context),
                noiseHost = noiseHost,
                vmId = vmId,
                wsUrl = wsUrl,
                bearerToken = selectedVm.vmAuthToken
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

            // Bước 2: Mở stream POST /link-control và gửi link.register
            val registerId = UUID.randomUUID().toString()
            val controlStreamId: Long
            synchronized(transportSendLock) {
                val (sid, openFrames) = transport.startStreamRequest("POST", CONTROL_PATH)
                controlStreamId = sid
                if (!sendBinaryFramesLocked(ws, openFrames)) {
                    return closeAndFail(ws, "CONTROL_OPEN_SEND_FAILED", SessionOutcome.CLOSED)
                }
                val regJson = MuseLinkProtocol.buildRegisterMessage(
                    requestId = registerId,
                    nodeId = deviceId,
                    displayName = "Phicomm R1 ($deviceId)",
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
            Log.i(TAG, "Đã gửi link.register (node=${deviceId.lowercase()}, stream=$controlStreamId)")

            // Bước 3: Nhận ServiceFrame và xử lý link.invoke / link.unpaired
            val jsonDecoder = LengthPrefixedJsonDecoder()
            while (isRunning) {
                val event = events.poll(5, TimeUnit.SECONDS) ?: continue
                when (event) {
                    is WsEvent.Binary -> {
                        val frame = transport.decryptFrame(event.bytes) ?: continue
                        if (frame.streamId != controlStreamId) continue
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
                                Log.w(TAG, "Muse đã hủy ghép đôi thiết bị ($ev)")
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

    private fun closeAndFail(ws: WebSocket, error: String, outcome: SessionOutcome): SessionOutcome {
        lastError = error
        controlRegistered = false
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

        fun buildNoiseUrl(noiseHost: String, vmId: String): String {
            val cleanHost = MusePreferences.normalizeHost(noiseHost).ifEmpty { MusePreferences.DEFAULT_NOISE_HOST }
            val encodedVm = URLEncoder.encode(vmId.trim(), "UTF-8").replace("+", "%20")
            return "wss://$cleanHost/v1/noise?vm_id=$encodedVm"
        }
    }
}
