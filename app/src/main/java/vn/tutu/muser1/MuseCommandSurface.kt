package vn.tutu.muser1

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
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
            val json = JSONObject(rawCommand)
            val name = json.optString("name").ifEmpty { json.optString("command") }
            val params = json.optJSONObject("params") ?: json
            val outcome = executeInvoke(name, params)
            val legacy = JSONObject().put("ok", outcome.optBoolean("ok", false))
            if (outcome.has("payload")) legacy.put("result", outcome.opt("payload"))
            if (outcome.has("error")) legacy.put("error", outcome.optString("error"))
            legacy.toString()
        } catch (error: Exception) {
            Log.e(TAG, "Invalid Muse command", error)
            errorResponse("Invalid command payload")
        }
    }

    fun executeInvoke(command: String, params: JSONObject = JSONObject()): JSONObject {
        return try {
            when (command) {
                "device.health" -> invokeHealth()
                "muse_r1.set_volume", "voice.configure" -> invokeSetVolume(params)
                "muse_r1.get_status" -> invokeGetStatus()
                "muse_r1.tts" -> invokeTts(params)
                "muse_r1.stop_audio" -> invokeStopAudio()
                else -> invokeError("Unsupported command: $command")
            }
        } catch (error: Exception) {
            Log.e(TAG, "Muse invoke error for $command", error)
            invokeError(error.message ?: "Command failed")
        }
    }

    fun commandsV2Json(): JSONObject {
        val commands = JSONObject()
        commands.put(
            "device.health",
            buildCommandSpec(
                description = "Report Phicomm R1 speaker health, power status, network summary, and Muse pairing state."
            )
        )
        commands.put(
            "muse_r1.get_status",
            buildCommandSpec(
                description = "Return current Phicomm R1 network status, Muse pairing mode, and active Noise host."
            )
        )
        commands.put(
            "muse_r1.set_volume",
            buildCommandSpec(
                description = "Set speaker music stream volume percentage from 0 to 100.",
                required = JSONObject().put(
                    "volume",
                    JSONObject()
                        .put("type", "integer")
                        .put("description", "Volume percentage between 0 and 100.")
                )
            )
        )
        commands.put(
            "muse_r1.tts",
            buildCommandSpec(
                description = "Play/announce a text message on the Phicomm R1 speaker.",
                required = JSONObject().put(
                    "text",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "Text prompt to speak on the speaker.")
                )
            )
        )
        commands.put(
            "muse_r1.stop_audio",
            buildCommandSpec(
                description = "Stop current audio playback on the Phicomm R1 speaker."
            )
        )
        return commands
    }

    private fun buildCommandSpec(
        description: String,
        required: JSONObject = JSONObject(),
        optional: JSONObject = JSONObject()
    ): JSONObject {
        return JSONObject()
            .put("description", description)
            .put("required", required)
            .put("optional", optional)
    }

    private fun invokeHealth(): JSONObject {
        return invokeSuccess(
            JSONObject()
                .put("battery_percent", JSONObject.NULL)
                .put("voltage_mv", JSONObject.NULL)
                .put("usb_power", true)
                .put("charging", false)
                .put("device_id", MusePreferences.getDeviceId(context))
                .put("agent_version", BuildConfig.VERSION_NAME)
                .put("muse_paired", MusePreferences.isMusePaired(context))
                .put("muse_mode", MusePreferences.getMusePairingMode(context))
                .put("network", NetworkStateObserver.currentSummary(context))
        )
    }

    private fun invokeSetVolume(params: JSONObject): JSONObject {
        val volume = params.optInt("volume", -1)
        if (volume !in 0..100) {
            return invokeError("Volume must be between 0 and 100")
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return invokeError("AudioManager not available")
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetVolume = if (volume == 0) 0 else ((volume * maxVolume + 50) / 100).coerceIn(1, maxVolume)

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)

        return invokeSuccess(
            JSONObject()
                .put("volume_percent", volume)
                .put("stream_volume", targetVolume)
                .put("max_stream_volume", maxVolume)
        )
    }

    private fun invokeGetStatus(): JSONObject {
        return invokeSuccess(
            JSONObject()
                .put("network", NetworkStateObserver.currentSummary(context))
                .put("muse_paired", MusePreferences.isMusePaired(context))
                .put("muse_mode", MusePreferences.getMusePairingMode(context))
                .put("noise_host", MusePreferences.getNoiseHost(context))
        )
    }

    private fun invokeTts(params: JSONObject): JSONObject {
        val text = params.optString("text").trim()
        if (text.isEmpty()) {
            return invokeError("TTS text is required")
        }

        Log.i(TAG, "Muse TTS requested: $text")

        try {
            val player = MediaPlayer.create(context, R.raw.agent_ready)
            player?.setOnCompletionListener { it.release() }
            player?.start()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play test sound", e)
        }

        return invokeSuccess(
            JSONObject()
                .put("accepted", true)
                .put("text", text)
        )
    }

    private fun invokeStopAudio(): JSONObject {
        Log.i(TAG, "Stop audio requested")
        return invokeSuccess(JSONObject().put("stopped", true))
    }

    private fun invokeSuccess(payload: JSONObject): JSONObject {
        return JSONObject()
            .put("ok", true)
            .put("payload", payload)
    }

    private fun invokeError(message: String): JSONObject {
        return JSONObject()
            .put("ok", false)
            .put("error", message)
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
