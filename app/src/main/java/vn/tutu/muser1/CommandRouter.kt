package vn.tutu.muser1

import android.content.Context
import android.media.AudioManager
import android.util.Log
import org.json.JSONObject

/**
 * Router các lệnh nhận từ Muse Mobile App hoặc Muse Gadget SDK.
 *
 * Payload mẫu:
 * {"action":"set_volume","value":60}
 * {"action":"tts","text":"Xin chào Tu"}
 * {"action":"stop"}
 */
class CommandRouter(private val context: Context) {

    fun handle(rawPayload: String) {
        try {
            val payload = JSONObject(rawPayload)
            val action = payload.optString("action")

            when (action) {
                "set_volume" -> setVolume(payload.optInt("value", -1))
                "tts" -> handleTts(payload.optString("text"))
                "stop" -> Log.i(TAG, "Stop command received")
                "wifi_status" -> Log.i(TAG, NetworkStateObserver.currentSummary(context))
                else -> Log.w(TAG, "Unknown command: $action")
            }
        } catch (error: Exception) {
            Log.e(TAG, "Invalid command payload: $rawPayload", error)
        }
    }

    private fun setVolume(value: Int) {
        if (value !in 0..100) {
            Log.w(TAG, "Invalid volume: $value")
            return
        }

        val audioManager = context.getSystemService(AudioManager::class.java)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetVolume = (value * maxVolume) / 100

        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            targetVolume,
            0
        )

        Log.i(TAG, "Volume set to $value% ($targetVolume/$maxVolume)")
    }

    private fun handleTts(text: String) {
        if (text.isBlank()) {
            Log.w(TAG, "TTS text is empty")
            return
        }

        // Sẽ tích hợp TTS engine trong giai đoạn tiếp theo.
        Log.i(TAG, "TTS requested: $text")
    }

    companion object {
        private const val TAG = "MuseR1"
    }
}
