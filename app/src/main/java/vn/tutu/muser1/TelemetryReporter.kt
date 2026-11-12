package vn.tutu.muser1

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject

/**
 * Gửi trạng thái thiết bị định kỳ qua MQTT.
 *
 * Dữ liệu chỉ bao gồm thông tin kỹ thuật an toàn:
 * - Device ID
 * - Phiên bản agent
 * - Android version
 * - Wi‑Fi / IP
 * - Bộ nhớ và dung lượng khả dụng
 * - Thời gian hoạt động
 */
class TelemetryReporter(
    private val context: Context,
    private val deviceId: String,
    private val mqttAgent: MqttAgent
) {

    fun publishSnapshot() {
        try {
            val payload = JSONObject()
                .put("device_id", deviceId)
                .put("agent_version", BuildConfig.VERSION_NAME)
                .put("android_version", Build.VERSION.RELEASE)
                .put("sdk_int", Build.VERSION.SDK_INT)
                .put("uptime_ms", android.os.SystemClock.elapsedRealtime())
                .put("available_storage_bytes", availableStorageBytes())
                .put("available_memory_bytes", availableMemoryBytes())
                .put("network", NetworkStateObserver.currentSummary(context))
                .put("muse_paired", MusePreferences.getMuseToken(context).isNotEmpty())

            mqttAgent.publish("muse/r1/$deviceId/telemetry", payload.toString())
        } catch (error: Exception) {
            Log.e(TAG, "Failed to publish telemetry", error)
        }
    }

    private fun availableStorageBytes(): Long {
        return try {
            context.filesDir.usableSpace
        } catch (error: Exception) {
            -1L
        }
    }

    private fun availableMemoryBytes(): Long {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memoryInfo)
            memoryInfo.availMem
        } catch (_: Exception) {
            val runtime = Runtime.getRuntime()
            runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
        }
    }

    companion object {
        private const val TAG = "MuseR1"
    }
}
