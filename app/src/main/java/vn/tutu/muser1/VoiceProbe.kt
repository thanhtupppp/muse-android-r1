package vn.tutu.muser1

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Phase 0 của voice call: kiểm chứng hai điều chưa biết trên phần cứng thật.
 *
 * 1) Mic của R1 có mở được cho app thường không, ở tốc độ lấy mẫu nào, và
 *    mức tín hiệu thu được (RMS/peak) — firmware loa thông minh đôi khi khoá
 *    mic cho trợ lý tích hợp, giống cách nó có thể thiếu TTS engine.
 * 2) Hiệu ứng âm thanh của nền tảng (AEC/NS/AGC) có sẵn không.
 *
 * Việc kiểm tra stream `/voice/realtime` phía VM nằm ở
 * [MuseSessionAdapter.probeVoiceCall]. Cả hai kết quả được lưu vào
 * MusePreferences và lộ qua GET /status tại `voice`.
 */
object VoiceProbe {

    private const val TAG = "MuseR1Voice"

    private val CANDIDATE_RATES = intArrayOf(24_000, 48_000, 44_100, 16_000)
    private const val READ_DURATION_MS = 300

    fun probeMicrophone(): JSONObject {
        val result = JSONObject()
            .put("aec_available", AcousticEchoCanceler.isAvailable())
            .put("ns_available", NoiseSuppressor.isAvailable())
            .put("agc_available", AutomaticGainControl.isAvailable())
            .put("probed_at_ms", System.currentTimeMillis())

        val attempts = JSONArray()
        var opened: JSONObject? = null

        for (rate in CANDIDATE_RATES) {
            if (opened != null) break
            for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)) {
                val attempt = tryOpenAndRead(rate, source)
                attempts.put(attempt)
                if (attempt.optBoolean("opened")) {
                    opened = attempt
                    break
                }
            }
        }

        result.put("attempts", attempts)
        if (opened != null) {
            result
                .put("supported", true)
                .put("sample_rate", opened.optInt("sample_rate"))
                .put("source", opened.optString("source_name"))
                .put("frames_read", opened.optInt("frames_read"))
                .put("rms", opened.optDouble("rms"))
                .put("peak", opened.optInt("peak"))
            Log.i(
                TAG,
                "Mic probe: mở được ${opened.optInt("sample_rate")} Hz " +
                    "(${opened.optString("source_name")}), rms=${opened.optDouble("rms")}, " +
                    "peak=${opened.optInt("peak")}, AEC=${result.optBoolean("aec_available")}"
            )
        } else {
            result.put("supported", false)
            Log.w(TAG, "Mic probe: KHÔNG mở được AudioRecord ở mọi cấu hình thử")
        }
        return result
    }

    private fun tryOpenAndRead(sampleRate: Int, source: Int): JSONObject {
        val sourceName = if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
            "VOICE_COMMUNICATION"
        } else {
            "MIC"
        }
        val attempt = JSONObject()
            .put("sample_rate", sampleRate)
            .put("source_name", sourceName)
            .put("opened", false)

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            return attempt.put("error", "MIN_BUFFER_$minBuffer")
        }

        var record: AudioRecord? = null
        try {
            record = AudioRecord(
                source,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, sampleRate / 2)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                return attempt.put("error", "NOT_INITIALIZED")
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return attempt.put("error", "NOT_RECORDING")
            }

            val chunk = ShortArray(1024)
            var framesRead = 0
            var sumSquares = 0.0
            var peak = 0
            val deadline = System.currentTimeMillis() + READ_DURATION_MS
            while (System.currentTimeMillis() < deadline) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) break
                framesRead += read
                for (i in 0 until read) {
                    val v = chunk[i].toInt()
                    sumSquares += v.toDouble() * v.toDouble()
                    val abs = kotlin.math.abs(v)
                    if (abs > peak) peak = abs
                }
            }
            val rms = if (framesRead > 0) sqrt(sumSquares / framesRead) else 0.0
            attempt
                .put("opened", true)
                .put("frames_read", framesRead)
                .put("rms", (rms * 10).toInt() / 10.0)
                .put("peak", peak)
            return attempt
        } catch (error: Exception) {
            return attempt.put("error", error.javaClass.simpleName)
        } finally {
            try {
                record?.stop()
            } catch (_: Exception) {
            }
            record?.release()
        }
    }
}
