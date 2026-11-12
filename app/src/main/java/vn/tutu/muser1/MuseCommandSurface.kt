package vn.tutu.muser1

import android.content.Context
import android.media.AudioManager
import android.util.Log
import org.json.JSONObject

/**
 * Command surface an toàn cho Muse R1.
 *
 * Không cho phép chạy shell command tùy ý.
 * Mọi lệnh hệ thống phải nằm trong whitelist.
 */
class MuseCommandSurface(private val context: Context) {

    fun execute(rawCommand: String): String {
        return try {
            val command = JSONObject(rawCommand)
            val name = command.optString("name")

            when (name) {
                "muse_r1.set_volume" -> setVolume(command)
                "muse_r1.get_status" -> getStatus()
                "muse_r1.tts" -> requestTts(command)
                "muse_r1.stop_audio" -> stopAudio()
                else -> errorResponse("Unsupported command: $name")
            }
        } catch (error: Exception) {
            Log.e(TAG, "Invalid Muse command", error)
            errorResponse("Invalid command payload")
        }
    }

    private fun setVolume(command: JSONObject): String {
        val volume = command.optInt("volume", -1)

        if (volume !in 0..100) {
            return errorResponse("Volume must be between 0 and 100")
        }

        val audioManager = context.getSystemService(AudioManager::class.java)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetVolume = (volume * maxVolume) / 100

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)

        return successResponse(
            JSONObject()
                .put("volume_percent", volume)
                .put("stream_volume", targetVolume)
                .put("max_stream_volume", maxVolume)
        )
    }

    private fun getStatus(): String {
        return successResponse(
            JSONObject()
                .put("network", NetworkStateObserver.currentSummary(context))
                .put("muse_paired", MusePreferences.getMuseToken(context).isNotEmpty())
        )
    }

    private fun requestTts(command: JSONObject): String {
        val text = command.optString("text").trim()

        if (text.isEmpty()) {
            return errorResponse("TTS text is required")
        }

        // TTS engine sẽ được tích hợp ở giai đoạn tiếp theo.
        Log.i(TAG, "Muse TTS requested: $text")

        return successResponse(
            JSONObject()
                .put("accepted", true)
                .put("text", text)
        )
    }

    private fun stopAudio(): String {
        Log.i(TAG, "Stop audio requested")
        return successResponse(JSONObject().put("stopped", true))
    }

    private fun successResponse(result: JSONObject): String {
        return JSONObject()
            .put("ok", true)
            .put("result", result)
            .toString()
    }

    private fun errorResponse(message: String): String {
        return JSONObject()
            .put("ok", false)
            .put("error", message)
            .toString()
    }

    companion object {
        private const val TAG = "MuseR1"
    }
}
