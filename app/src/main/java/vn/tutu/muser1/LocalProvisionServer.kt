package vn.tutu.muser1

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream

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

    fun startServer(): Boolean {
        return try {
            start(SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "Local provisioning server started on port $port")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to start local provisioning server", error)
            false
        }
    }

    fun stopServer() {
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
                "/status" -> json(response = statusResponse())
                else -> notFound()
            }

            Method.POST -> when (uri) {
                "/provision-wifi" -> provisionWifi(session)
                else -> notFound()
            }

            else -> notFound()
        }
    }

    private fun provisionWifi(session: IHTTPSession): Response {
        return try {
            val content = mutableMapOf<String, String>()
            session.parseBody(content)

            val rawBody = content["postData"]
                ?: return badRequest("Missing JSON body")

            val payload = JSONObject(rawBody)
            val ssid = payload.optString("ssid").trim()
            val password = payload.optString("password")
            val security = payload.optString("security", "WPA2")

            if (ssid.isEmpty() || password.length < 8) {
                return badRequest("SSID is required and WPA2 password must contain at least 8 characters")
            }

            if (security != "WPA2") {
                return badRequest("Only WPA2 is supported in this version")
            }

            val wifiManager = context.getSystemService(WifiManager::class.java)
            val result = WifiProvisioner.connect(wifiManager, ssid, password)

            Log.i(TAG, "Wi‑Fi provisioning requested for $ssid")

            json(
                response = JSONObject()
                    .put("ok", true)
                    .put("message", result)
                    .put("device_id", MusePreferences.getDeviceId(context))
            )
        } catch (error: Exception) {
            Log.e(TAG, "Invalid provisioning request", error)
            badRequest("Invalid JSON body")
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
        return JSONObject()
            .put("ok", true)
            .put("device_id", MusePreferences.getDeviceId(context))
            .put("agent_version", BuildConfig.VERSION_NAME)
            .put("android_version", android.os.Build.VERSION.RELEASE)
            .put("network", NetworkStateObserver.currentSummary(context))
            .put("muse_paired", MusePreferences.isMusePaired(context))
    }

    private fun json(response: JSONObject): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            response.toString()
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
    }
}
