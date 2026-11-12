package vn.tutu.muser1

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Muse session adapter cho Phicomm R1.
 *
 * Luồng hiện tại:
 * 1. Đọc access/refresh token đã lưu.
 * 2. Refresh token nếu cần.
 * 3. Gọi /fetch_vms để nhận VM được lease.
 * 4. Chọn VM default.
 * 5. Kết nối WebSocket tới VM.
 * 6. Gửi `link.register` khi WebSocket mở.
 *
 * Phần Noise XX đầy đủ sẽ được bổ sung sau khi port chi tiết từ Linux SDK.
 */
class MuseSessionAdapter(
    private val context: Context,
    private val deviceId: String,
    private val commandSurface: MuseCommandSurface
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var currentVm: MuseApi.MuseVm? = null

    fun start() {
        Thread {
            try {
                refreshTokensIfNeeded()
                selectAndConnectVm()
            } catch (error: Exception) {
                Log.e(TAG, "Muse session startup failed", error)
            }
        }.start()
    }

    fun stop() {
        webSocket?.close(1000, "Agent stopping")
        webSocket = null
        currentVm = null
    }

    fun isConnected(): Boolean {
        return webSocket != null
    }

    private fun refreshTokensIfNeeded() {
        val refreshToken = MusePreferences.getMuseRefreshToken(context)

        if (refreshToken.isEmpty()) {
            Log.w(TAG, "Muse refresh token is missing")
            return
        }

        val (tokens, status) = MuseApi.refreshDeviceToken(
            refreshToken = refreshToken,
            deviceId = deviceId,
            sdkToken = MusePreferences.getMuseToken(context)
        )

        if (tokens == null) {
            if (status == 401) {
                Log.w(TAG, "Muse pairing rejected. Device must be paired again.")
            }
            return
        }

        MusePreferences.saveMuseSessionTokens(
            context = context,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken
        )
    }

    private fun selectAndConnectVm() {
        val accessToken = MusePreferences.getMuseAccessToken(context)

        if (accessToken.isEmpty()) {
            Log.w(TAG, "Muse access token is missing")
            return
        }

        val (vms, status) = MuseApi.fetchVms(accessToken)

        if (status == 401) {
            Log.w(TAG, "Muse access token rejected")
            return
        }

        val selectedVm = vms.firstOrNull { it.isDefault } ?: vms.firstOrNull()

        if (selectedVm == null) {
            Log.w(TAG, "No leased Muse VM available")
            return
        }

        currentVm = selectedVm
        connectWebSocket(selectedVm)
    }

    private fun connectWebSocket(vm: MuseApi.MuseVm) {
        val request = Request.Builder()
            .url(vm.vmUrl)
            .addHeader("Authorization", "Bearer ${vm.vmAuthToken}")
            .build()

        webSocket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "Muse VM WebSocket connected: ${vm.vmName}")
                    registerLink(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.i(TAG, "Muse VM message: $text")
                    val response = commandSurface.execute(text)
                    webSocket.send(response)
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    throwable: Throwable,
                    response: Response?
                ) {
                    Log.e(TAG, "Muse VM WebSocket failed", throwable)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "Muse VM WebSocket closed: $code $reason")
                }
            }
        )
    }

    private fun registerLink(webSocket: WebSocket) {
        val registerMessage = MuseLinkProtocol.buildRegisterMessage(
            deviceId = deviceId,
            agentVersion = BuildConfig.VERSION_NAME
        )

        webSocket.send(registerMessage)
        Log.i(TAG, "link.register sent")
    }

    companion object {
        private const val TAG = "MuseR1"
    }
}
