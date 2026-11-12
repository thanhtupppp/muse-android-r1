package vn.tutu.muser1

import android.content.Context
import android.media.AudioManager
import android.util.Log
import org.json.JSONObject

/**
 * Router lệnh nhận từ Muse Mobile App.
 *
 * Payload mẫu:
 * {"action":"set_volume","value":60}
 * {"action":"tts","text":"Xin chào Tu"}
 * {"action":"stop"}
 * {"action":"muse_status"}
 */
class CommandRouter(private val context: Context) {

    private val museCommandSurface = MuseCommandSurface(context)

    fun handle(rawPayload: String) {
        try {
            val payload = JSONObject(rawPayload)
            val action = payload.optString("action")

            when (action) {
                "set_volume" -> setVolume(payload.optInt("value", -1))
                "tts" -> handleTts(payload.optString("text"))
                "stop" -> Log.i(TAG, "Stop command received")
                "wifi_status" -> Log.i(TAG, NetworkStateObserver.currentSummary(context))
                "muse_status" -> Log.i(TAG, museCommandSurface.execute("{\"name\":\"muse_r1.get_status\"}"))
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

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetVolume = if (value == 0) 0 else ((value * maxVolume + 50) / 100).coerceIn(1, maxVolume)

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        Log.i(TAG, "Volume set to $value% ($targetVolume/$maxVolume)")
    }

    private fun handleTts(text: String) {
        if (text.isBlank()) {
            Log.w(TAG, "TTS text is empty")
            return
        }

        Log.i(TAG, "TTS requested: $text")
    }

    companion object {
        private const val TAG = "MuseR1"
    }
}
