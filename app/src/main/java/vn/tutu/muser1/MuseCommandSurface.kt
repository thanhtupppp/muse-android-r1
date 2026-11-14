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
                "device.guide" -> invokeGuide()
                "muse_r1.set_volume", "voice.configure" -> invokeSetVolume(params)
                "muse_r1.get_status" -> invokeGetStatus()
                "muse_r1.tts" -> invokeTts(params)
                "muse_r1.stop_audio" -> invokeStopAudio()
                "muse_r1.voice_call_start" -> invokeVoiceCallStart()
                "muse_r1.voice_call_stop" -> invokeVoiceCallStop()
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
            "device.guide",
            buildCommandSpec(
                description = "Return the Markdown guide describing this device, its controls and its limits, so Muse knows how to use it. Takes no parameters; the result payload is {format: \"markdown\", guide: \"...\"}."
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
        commands.put(
            "muse_r1.voice_call_start",
            buildCommandSpec(
                description = "Start a realtime voice call on the Phicomm R1 speaker: the microphone opens and the user can talk to Muse and hear replies from the speaker. The call runs in the background (45 second idle and 5 minute overall limits) and ends by itself or with muse_r1.voice_call_stop. The result payload carries the current voice_call status object."
            )
        )
        commands.put(
            "muse_r1.voice_call_stop",
            buildCommandSpec(
                description = "End the realtime voice call currently running on the Phicomm R1 speaker, if any. The result payload carries the current voice_call status object."
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

    private fun invokeGuide(): JSONObject {
        // Theo hợp đồng protocols/DEVICE.md của Muse Gadget SDK: payload
        // là {format, guide}; guide là Markdown nhúng sẵn, tối đa 4096 byte.
        return invokeSuccess(
            JSONObject()
                .put("format", "markdown")
                .put("guide", DEVICE_GUIDE_MD)
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

        Log.i(TAG, "Muse TTS requested (${text.length} chars)")

        // TextToSpeech thật qua SpeechOutput. Trước đây lệnh này chỉ phát
        // tiếng chuông ready đóng hộp rồi báo accepted — stub.
        return when (val result = SpeechOutput.speak(context, text)) {
            SpeechOutput.SpeakResult.UNAVAILABLE ->
                invokeError("Text-to-speech engine is not available on this device")
            else -> invokeSuccess(
                JSONObject()
                    .put("accepted", true)
                    .put("engine_ready", result == SpeechOutput.SpeakResult.QUEUED)
                    .put("text", text)
            )
        }
    }

    private fun invokeStopAudio(): JSONObject {
        Log.i(TAG, "Stop audio requested")
        val result = SpeechOutput.stopAll()
        return invokeSuccess(
            JSONObject()
                .put("stopped", true)
                .put("tts_stopped", result.ttsStopped)
                .put("players_stopped", result.playersStopped)
        )
    }

    private fun invokeVoiceCallStart(): JSONObject {
        Log.i(TAG, "Voice call start requested via Muse command")
        AgentService.startVoiceCall(context)
        return invokeSuccess(
            JSONObject()
                .put("accepted", true)
                .put("voice_call", AgentService.voiceCallStatusJson())
        )
    }

    private fun invokeVoiceCallStop(): JSONObject {
        Log.i(TAG, "Voice call stop requested via Muse command")
        AgentService.stopVoiceCall(context)
        return invokeSuccess(
            JSONObject()
                .put("accepted", true)
                .put("voice_call", AgentService.voiceCallStatusJson())
        )
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

        /**
         * Nội dung device.guide: mô tả thiết bị cho Muse theo đúng quy tắc
         * của SDK (một tiêu đề #, không link/code block/HTML, không bước
         * cài đặt/ghép đôi, không bí mật; giữ dưới 2048 byte như guide mẫu).
         */
        private const val DEVICE_GUIDE_MD = """# Phicomm R1 Muse speaker

This device is a Phicomm R1 smart speaker running an Android agent that turns it into a Muse gadget. It has no screen and no camera. It plays sound, speaks text aloud, and reports its health and network status. Muse uses it only through the commands it lists; each command's description says what it does and what it returns.

## What it can do

- Speak a short text aloud with muse_r1.tts, preferring Vietnamese and falling back to the system default voice. If the device has no speech engine, the command returns an error instead of pretending to speak.
- Set the music volume from 0 to 100 with muse_r1.set_volume.
- Stop speech and any audio it is playing with muse_r1.stop_audio.
- Start a realtime voice call with muse_r1.voice_call_start and end it with muse_r1.voice_call_stop. During the call the user talks to the speaker and hears Muse answer from it.
- Report health, pairing state and network summary with device.health and muse_r1.get_status.

## Controls

The round button on top is the speaker's only physical control. Its job is to confirm Bluetooth pairing when the Muse app asks for a physical press; Muse cannot read it or use it for anything else. Volume is changed by command, not by buttons.

## Limits and recovery

- The microphone is only live during a voice call started with muse_r1.voice_call_start; it is off at all other times.
- If commands fail, device.health shows whether the speaker is paired and which network it is on. The owner recovers the speaker with the Muse Mobile app or by restarting it; beyond power there is nothing to press or unplug."""
    }
}
