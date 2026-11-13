package vn.tutu.muser1

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Khung tích hợp Meta Muse Gadget SDK cho Phicomm R1.
 *
 * Tham chiếu:
 * https://github.com/facebookincubator/muse-gadget-sdk
 *
 * Muse gadget cần SDK token dạng `mgst_...` để pair.
 * Pairing qua Muse app: Settings > Devices > Developer mode > Add Device.
 *
 * Giai đoạn này triển khai:
 * - Kiểm tra token.
 * - Lưu token vào private storage.
 * - Tạo command surface an toàn.
 * - Gửi telemetry/status.
 *
 * Phần Noise XX session, WebSocket tới Muse VM và giao thức pairing đầy đủ
 * sẽ được bổ sung sau khi port chi tiết từ Linux SDK.
 */
class MuseGadgetClient(
    private val context: Context,
    private val deviceId: String
) {

    private val commandSurface = MuseCommandSurface(context)

    fun hasToken(): Boolean {
        return MusePreferences.getMuseToken(context).startsWith("mgst_")
    }

    fun setToken(token: String): Boolean {
        val cleanToken = token.trim()

        if (!cleanToken.startsWith("mgst_")) {
            Log.w(TAG, "Invalid Muse SDK token format")
            return false
        }

        MusePreferences.saveMuseToken(context, cleanToken)
        Log.i(TAG, "Muse SDK token saved")
        return true
    }

    fun isPaired(): Boolean {
        return MusePreferences.isMusePaired(context)
    }

    fun buildStatusPayload(): String {
        return JSONObject()
            .put("device_id", deviceId)
            .put("agent_version", BuildConfig.VERSION_NAME)
            .put("android_version", android.os.Build.VERSION.RELEASE)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("muse_paired", isPaired())
            .put("network", NetworkStateObserver.currentSummary(context))
            .toString()
    }

    fun handleMuseCommand(rawCommand: String): String {
        return commandSurface.execute(rawCommand)
    }

    companion object {
        private const val TAG = "MuseR1"
    }
}
