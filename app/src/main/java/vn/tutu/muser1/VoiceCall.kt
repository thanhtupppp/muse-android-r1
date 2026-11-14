package vn.tutu.muser1

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Voice call hai chiều với Muse qua loa Phicomm R1 — Phase 1 (MVP).
 *
 * Port từ bản Linux chính thức của Muse Gadget SDK (`voice_call.py`,
 * `voice_gate.py`, `voice_audio.py`, `link_client.py`):
 *
 * - Phiên Noise RIÊNG cho voice (phương án B trong VOICE_CALL_DESIGN.md):
 *   WebSocket thứ hai tới cùng noise host, query `vm_id` + `app_id` (mặc
 *   định "hatch-web"), bearer là vm_auth_token của fetch_vms, handshake
 *   Noise XX như phiên chính nhưng KHÔNG mở /link-control, KHÔNG
 *   link.register — phiên chính của Agent không bị xáo trộn. (Phase 0 đã
 *   chứng minh stream trên phiên chung bị VM trả HTTP 400.)
 * - Stream dài `POST /voice/realtime?call_id=<uuid>`, NDJSON hai chiều:
 *   admission `hatch.voice.call.start` (device `mcu-wearable`, gắn main
 *   chat — không thread_id) + capability offer `end_call_stop`, chờ sự
 *   kiện readiness `hatch.voice.session` khớp call_id và cho session_id
 *   (tối đa 35 giây) mới gửi audio.
 * - Uplink: PCM16LE mono 24 kHz, frame tối đa 960 mẫu (40 ms), đóng gói
 *   `input_audio_buffer.append` (base64 + send_timestamp_ms), phát đúng
 *   nhịp thời gian thực. KHÔNG commit thủ công — VAD phía provider quyết
 *   định biên lượt nói. Half-duplex: khi loa đang phát tiếng trả lời
 *   (hoặc VM giữ mic qua switch_input_hold) thì frame mic bị BỎ, không
 *   phát lại. Im lặng kéo dài >0,4 s thì gửi frame im lặng thay tiếng
 *   phòng để endpointing phía server hoạt động.
 * - Downlink: `response.output_audio.delta` / `response.audio.delta`
 *   (trường `delta`) và `continuous_audio_video.delta`
 *   (`audio_delta.audio_frames`, format audio/pcm 24 kHz mono) → hàng
 *   đợi phát tối đa 5 giây → AudioTrack. Sự kiện
 *   `conversation.item.added` với item session_control/stop → drain phần
 *   audio còn lại rồi kết thúc cuộc gọi (đúng cam kết end_call_stop).
 * - Kết thúc: `POST /voice/end-call` một lần với {call_id,
 *   call_started_at, call_ended_at} (ISO-8601), ack hợp lệ khi HTTP 2xx
 *   và body có ok=true và result.ok=true (đúng bản SDK); sau đó
 *   half-close stream realtime, drain có giới hạn, đóng phiên.
 * - Giới hạn cứng: idle 45 s (chỉ khi không còn lượt đang hoạt động),
 *   overall 300 s, cleanup 10 s.
 *
 * Đơn giản hoá có chủ đích so với bản SDK đầy đủ (để lại cho Phase 2):
 * không port ReplyGate (fencing PCM theo response_id sau barge-in) và
 * không có barge-in bằng `response.cancel` — audio về được phát theo thứ
 * tự tới, vẫn tôn trọng half-duplex gate và server hold. Theo dõi lượt
 * (turn_started/turn_settled) giữ đúng ngữ nghĩa settlement của SDK để
 * idle timeout không cúp ngang một tác vụ dài của Muse.
 */

// ---------------------------------------------------------------------------
// Hằng số giao thức (khớp voice_call.py)
// ---------------------------------------------------------------------------

object VoiceCallSpec {
    const val SAMPLE_RATE = 24_000
    const val FRAME_SAMPLES = 960
    const val FRAME_BYTES = FRAME_SAMPLES * 2
    const val FRAME_DURATION_MS = 40L

    /** Một delta audio tối đa 4 giây (MAX_AUDIO_BYTES của SDK). */
    const val MAX_AUDIO_BYTES = SAMPLE_RATE * 2 * 4

    /** Hàng đợi playback tối đa 5 giây audio (MAX_PLAYBACK_BYTES). */
    const val MAX_PLAYBACK_BYTES = SAMPLE_RATE * 2 * 5

    /** Ngưỡng RMS coi là "có tiếng" (audible / có lời nói). */
    const val AUDIBLE_RMS = 300.0

    /** Khoảng lặng đệm sau khi phát hết một chunk audible (SETTLE_SECONDS). */
    const val SETTLE_MS = 200L

    const val READY_TIMEOUT_MS = 35_000L
    const val IDLE_TIMEOUT_MS = 45_000L
    const val OVERALL_TIMEOUT_MS = 300_000L
    const val CLEANUP_TIMEOUT_MS = 10_000L
    const val HANDSHAKE_TIMEOUT_MS = 20_000L

    /** Trần một dòng NDJSON (MAX_LINE_BYTES của streaming.py). */
    const val MAX_LINE_BYTES = 1024 * 1024

    /** Trần một chunk gửi lên stream (MAX_SEND_BYTES của streaming.py). */
    const val MAX_SEND_BYTES = 64 * 1024

    val ZERO_FRAME = ByteArray(FRAME_BYTES)

    /**
     * Đường dẫn stream realtime theo thứ tự thử. Đường đầu là đường của SDK
     * hiện tại; trên phiên voice riêng VM của người dùng trả 404 cho nó (đo
     * trên thiết bị 2026-11-14) trong khi chính endpoint đó trên phiên chung
     * trả 400 và end-call chạy ở cả hai phiên - tức route realtime của app
     * hatch-web nằm ở họ tiền tố api, đúng mẫu fallback hai tiền tố mà
     * link_client của SDK dùng cho tts-stream (bản api trước, 404 mới lùi
     * về bản thường).
     */
    val REALTIME_PATHS = listOf("/voice/realtime", "/api/voice/realtime")
}

// ---------------------------------------------------------------------------
// Logic thuần (unit-test được trên JVM)
// ---------------------------------------------------------------------------

/** RMS của PCM16LE; 0 khi rỗng. */
fun pcmRms(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset): Double {
    val count = length / 2
    if (count <= 0) return 0.0
    var sum = 0.0
    var i = offset
    val end = offset + count * 2
    while (i < end) {
        val v = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
        val s = v.toShort().toInt()
        sum += s.toDouble() * s.toDouble()
        i += 2
    }
    return sqrt(sum / count)
}

fun isZeroPcm(pcm: ByteArray): Boolean {
    for (b in pcm) if (b.toInt() != 0) return false
    return true
}

/**
 * Tách dòng NDJSON từ các chunk byte đến rời rạc (port của
 * streaming.read_ndjson): LF là bắt buộc, CRLF và dòng trắng được chấp
 * nhận, một dòng vượt [maxLineBytes] là lỗi cứng.
 */
class NdjsonSplitter(private val maxLineBytes: Int = VoiceCallSpec.MAX_LINE_BYTES) {
    private val pending = ArrayList<Byte>()
    private var lineBytes = 0

    fun feed(chunk: ByteArray): List<String> {
        val lines = ArrayList<String>()
        var offset = 0
        while (offset < chunk.size) {
            var newline = -1
            for (i in offset until chunk.size) {
                if (chunk[i] == '\n'.code.toByte()) {
                    newline = i
                    break
                }
            }
            val end = if (newline < 0) chunk.size else newline + 1
            if (lineBytes + (end - offset) > maxLineBytes) {
                throw IllegalArgumentException("NDJSON line too large")
            }
            for (i in offset until end) pending.add(chunk[i])
            lineBytes += end - offset
            offset = end
            if (newline >= 0) {
                var size = pending.size
                if (size > 0 && pending[size - 1] == '\n'.code.toByte()) size--
                if (size > 0 && pending[size - 1] == '\r'.code.toByte()) size--
                val line = String(ByteArray(size) { pending[it] }, Charsets.UTF_8)
                pending.clear()
                lineBytes = 0
                if (line.isNotBlank()) lines.add(line)
            }
        }
        return lines
    }

    /** Còn dữ liệu dở dang chưa gặp LF (dòng cuối bị cắt). */
    fun hasPartialLine(): Boolean = pending.isNotEmpty()
}

/** Gom byte PCM thành các frame đúng [frameBytes]; phần dư giữ lại. */
class PcmFrameChunker(private val frameBytes: Int = VoiceCallSpec.FRAME_BYTES) {
    private var pending = ByteArray(0)

    fun feed(data: ByteArray): List<ByteArray> {
        if (data.isEmpty()) return emptyList()
        val merged = if (pending.isEmpty()) data else pending + data
        val frames = ArrayList<ByteArray>()
        var offset = 0
        while (merged.size - offset >= frameBytes) {
            frames.add(merged.copyOfRange(offset, offset + frameBytes))
            offset += frameBytes
        }
        pending = merged.copyOfRange(offset, merged.size)
        return frames
    }

    /** Phần dư cuối (< 1 frame) khi nguồn âm thanh kết thúc; null nếu rỗng. */
    fun flush(): ByteArray? {
        if (pending.isEmpty()) return null
        val tail = pending
        pending = ByteArray(0)
        return tail
    }

    fun pendingBytes(): Int = pending.size
}

/**
 * Decimator 2:1 từ 48 kHz xuống 24 kHz — port nguyên vẹn Pcm48To24 của
 * SDK: FIR 64 tap Hann-windowed sinc, cutoff 0.45 Nyquist đầu vào, độc
 * lập với ranh giới chunk. Chỉ là đường dự phòng: Phase 0 đã chứng minh
 * R1 mở được thẳng 24 kHz.
 */
class Pcm48To24Decimator {
    private val taps: DoubleArray = lowpassTaps(64, 0.45).reversedArray()
    private var history = IntArray(63)
    private var phase = 1

    fun feed(pcm: ByteArray): ByteArray {
        require(pcm.size % 2 == 0) { "48 kHz audio must contain whole PCM16LE samples" }
        val inputCount = pcm.size / 2
        val samples = IntArray(63 + inputCount)
        history.copyInto(samples, 0)
        for (i in 0 until inputCount) {
            samples[63 + i] = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)
                .let { it.toShort().toInt() }
        }
        val out = ArrayList<Short>()
        var i = phase
        while (i < samples.size - 63) {
            var acc = 0.0
            for (t in 0 until 64) acc += taps[t] * samples[i + t]
            out.add(clamp16(acc.roundToInt()).toShort())
            i += 2
        }
        phase = (phase - (samples.size - 63)) and 1
        history = samples.copyOfRange(samples.size - 63, samples.size)
        val bytes = ByteArray(out.size * 2)
        for (j in out.indices) {
            val v = out[j].toInt()
            bytes[2 * j] = (v and 0xFF).toByte()
            bytes[2 * j + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    companion object {
        /** Hann-windowed sinc low-pass; cutoff theo phân số Nyquist đầu vào. */
        fun lowpassTaps(count: Int, cutoff: Double): DoubleArray {
            val mid = (count - 1) / 2.0
            val taps = DoubleArray(count)
            var total = 0.0
            for (k in 0 until count) {
                val offset = k - mid
                val sinc = if (offset == 0.0) 1.0 else sin(PI * cutoff * offset) / (PI * cutoff * offset)
                val window = 0.5 - 0.5 * cos(2 * PI * k / (count - 1))
                taps[k] = sinc * window
                total += taps[k]
            }
            for (k in 0 until count) taps[k] /= total
            return taps
        }

        fun clamp16(value: Int): Int = value.coerceIn(-32768, 32767)
    }
}

/** Phân loại và trích xuất dữ liệu từ các sự kiện NDJSON của VM. */
object VoiceEvents {

    /**
     * Trích PCM từ một sự kiện audio (port của audio_from_event): trả
     * null khi sự kiện không mang audio; ném IllegalArgumentException
     * khi audio sai định dạng/quá lớn/base64 hỏng — phía gọi coi đó là
     * lỗi giao thức.
     */
    fun audioFromEvent(event: JSONObject): ByteArray? {
        val encoded: String
        when (event.optString("type")) {
            "continuous_audio_video.delta" -> {
                val audio = event.optJSONObject("audio_delta") ?: return null
                val format = audio.optJSONObject("format")
                if (format != null) {
                    val formatType = format.optString("type", "")
                    if (formatType.isNotEmpty() && formatType != "audio/pcm") {
                        throw IllegalArgumentException("unsupported audio format")
                    }
                    if (format.has("rate") && format.optInt("rate") != VoiceCallSpec.SAMPLE_RATE) {
                        throw IllegalArgumentException("unsupported audio rate")
                    }
                }
                if (audio.has("num_channels") && audio.optInt("num_channels", 1) != 1) {
                    throw IllegalArgumentException("voice audio must be mono")
                }
                if (!audio.has("audio_frames")) return null
                encoded = audio.optString("audio_frames")
            }
            "response.output_audio.delta", "response.audio.delta" -> {
                if (!event.has("delta")) return null
                encoded = event.optString("delta")
            }
            else -> return null
        }
        if (encoded.length > ((VoiceCallSpec.MAX_AUDIO_BYTES + 2) / 3) * 4) {
            throw IllegalArgumentException("audio delta too large")
        }
        val pcm = encoded.decodeBase64()?.toByteArray()
            ?: throw IllegalArgumentException("invalid audio base64")
        if (pcm.size % 2 != 0 || pcm.size > VoiceCallSpec.MAX_AUDIO_BYTES) {
            throw IllegalArgumentException("invalid PCM16 audio length")
        }
        return pcm
    }

    /**
     * session_id khi [event] là readiness hợp lệ cho [callId]: sự kiện
     * `hatch.voice.session` khớp call_id và mang session_id khác rỗng.
     * Null khi không phải readiness (kể cả readiness lệch danh tính).
     */
    fun sessionIdFromReadiness(event: JSONObject, callId: String): String? {
        if (event.optString("type") != "hatch.voice.session") return null
        if (event.optString("call_id") != callId) return null
        val sessionId = if (event.has("session_id") && !event.isNull("session_id")) {
            event.optString("session_id")
        } else {
            ""
        }
        return sessionId.trim().ifEmpty { null }
    }

    fun isReadinessEvent(event: JSONObject): Boolean =
        event.optString("type") == "hatch.voice.session"

    /**
     * Lệnh dừng từ VM: conversation.item.added với item session_control
     * và tên/hành động "stop". item.call_id ở đây là ID ĐIỀU KHIỂN (bắt
     * buộc có, tối đa 128 ký tự), không phải call_id của admission.
     */
    fun isStopControl(event: JSONObject): Boolean {
        if (event.optString("type") != "conversation.item.added") return false
        val item = event.optJSONObject("item") ?: return false
        if (item.optString("type") != "session_control") return false
        val controlId = item.optString("call_id")
        if (controlId.isEmpty() || controlId.length > 128) return false
        val name = item.optString("name").trim().lowercase(Locale.US)
        val action = item.optString("action").trim().lowercase(Locale.US)
        return name == "stop" || action == "stop"
    }

    /** Bản ghi admission: gắn main chat (không thread_id) theo thiết kế đã duyệt. */
    fun buildAdmission(callId: String, deviceId: String): JSONObject {
        return JSONObject()
            .put("type", "hatch.voice.call.start")
            .put("call_id", callId)
            .put("device", "mcu-wearable")
            .put("device_id", deviceId)
            .put("call_media_type", "audio")
            .put("proactive_greeting_enabled", false)
    }

    fun buildCapabilityOffer(): JSONObject {
        return JSONObject()
            .put("type", "hatch.voice.capability.offer")
            .put("end_call_stop", 1)
    }

    /** Hai bản ghi đầu stream, mỗi bản ghi một dòng NDJSON. */
    fun admissionNdjson(callId: String, deviceId: String): ByteArray {
        val text = buildAdmission(callId, deviceId).toString() + "\n" +
            buildCapabilityOffer().toString() + "\n"
        return text.toByteArray(Charsets.UTF_8)
    }

    fun appendEventJson(pcm: ByteArray, sendTimestampMs: Long): ByteArray {
        val json = JSONObject()
            .put("type", "input_audio_buffer.append")
            .put("audio", pcm.toByteString().base64())
            .put("send_timestamp_ms", sendTimestampMs)
        return (json.toString() + "\n").toByteArray(Charsets.UTF_8)
    }

    fun buildEndCallBody(callId: String, startedAtMs: Long, endedAtMs: Long): JSONObject {
        return JSONObject()
            .put("call_id", callId)
            .put("call_started_at", isoUtc(startedAtMs))
            .put("call_ended_at", isoUtc(maxOf(endedAtMs, startedAtMs + 1)))
    }

    /**
     * Ack end-call hợp lệ đúng bản SDK: HTTP 2xx VÀ body ok=true VÀ
     * body.result.ok=true. (Probe Phase 0 nới lỏng hơn — or — nhưng
     * VoiceCall theo chuẩn SDK.)
     */
    fun endCallAcknowledged(httpStatus: Int, bodyText: String): Boolean {
        if (httpStatus !in 200..299) return false
        return try {
            val body = JSONObject(bodyText)
            body.optBoolean("ok", false) &&
                body.optJSONObject("result")?.optBoolean("ok", false) == true
        } catch (_: Exception) {
            false
        }
    }

    fun isoUtc(timeMs: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(java.util.Date(timeMs))
    }
}

/**
 * Theo dõi lượt hội thoại cho idle timeout (port gọn của _track_turn):
 * turn_started đăng ký (submission_message_id → item_id); turn_settled
 * về hưu MỌI submission đang hoạt động của cùng item đó, tính tới
 * submission mang response_message_id — để một tác vụ dài của Muse
 * không bị idle timeout cúp ngang, và settlement mới làm mới cửa sổ
 * idle. Nhận diện giữ trong giới hạn cứng; bản ghi lỗi định dạng bị bỏ
 * qua thay vì giết cuộc gọi (SDK ném lỗi — MVP chọn khoan dung).
 */
class VoiceTurnTracker(
    private val maxActiveTurns: Int = 64,
    private val maxSettledTurns: Int = 1024
) {
    private val activeTurns = LinkedHashMap<String, String>()
    private val settledTurns = LinkedHashMap<String, String>()

    fun hasActiveTurns(): Boolean = activeTurns.isNotEmpty()

    /** true khi sự kiện được theo dõi thành công (tính là hoạt động). */
    fun onTurnStarted(event: JSONObject): Boolean {
        val messageId = identifier(event.optString("submission_message_id")) ?: return false
        val itemId = identifier(event.optString("item_id")) ?: return false
        val previous = activeTurns[messageId] ?: settledTurns[messageId]
        if (previous != null) {
            return previous == itemId
        }
        if (activeTurns.size >= maxActiveTurns) return false
        activeTurns[messageId] = itemId
        return true
    }

    /** true khi settlement về hưu được ít nhất một submission. */
    fun onTurnSettled(event: JSONObject): Boolean {
        val messageId = identifier(event.optString("response_message_id")) ?: return false
        val itemId = activeTurns[messageId] ?: return false
        val completed = ArrayList<String>()
        for ((key, item) in activeTurns) {
            if (item == itemId) completed.add(key)
            if (key == messageId) break
        }
        if (completed.isEmpty()) return false
        if (settledTurns.size + completed.size > maxSettledTurns) {
            // Giữ giới hạn bộ nhớ: quên bớt settlement cũ nhất.
            val iterator = settledTurns.entries.iterator()
            while (settledTurns.size + completed.size > maxSettledTurns && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        for (key in completed) {
            val item = activeTurns.remove(key)
            if (item != null) settledTurns[key] = item
        }
        return completed.isNotEmpty()
    }

    companion object {
        /** Định danh hợp lệ: chuỗi khác rỗng, không NUL, tối đa 128 byte UTF-8. */
        fun identifier(value: String?): String? {
            if (value.isNullOrEmpty() || value.contains('\u0000')) return null
            return if (value.toByteArray(Charsets.UTF_8).size <= 128) value else null
        }
    }
}

/**
 * Hàng đợi playback có giới hạn (port của _playback trong voice_call.py):
 * PCM được cắt thành các chunk ≤ 1 frame, mỗi chunk gắn cờ audible
 * (RMS ≥ 300). Tổng dung lượng không vượt [maxBytes] (mặc định 5 giây);
 * offer vượt trần trả false — phía gọi coi là lỗi đầy bộ đệm như SDK.
 */
class PlaybackQueue(private val maxBytes: Int = VoiceCallSpec.MAX_PLAYBACK_BYTES) {

    data class Chunk(val data: ByteArray, val audible: Boolean)

    private val chunks = ArrayDeque<Chunk>()
    private var bytes = 0
    private var audible = 0

    @Synchronized
    fun offer(pcm: ByteArray): Boolean {
        if (bytes + pcm.size > maxBytes) return false
        var offset = 0
        while (offset < pcm.size) {
            val part = pcm.copyOfRange(offset, minOf(offset + VoiceCallSpec.FRAME_BYTES, pcm.size))
            val isAudible = pcmRms(part) >= VoiceCallSpec.AUDIBLE_RMS
            chunks.addLast(Chunk(part, isAudible))
            if (isAudible) audible++
            offset += part.size
        }
        bytes += pcm.size
        return true
    }

    @Synchronized
    fun poll(): Chunk? {
        val chunk = if (chunks.isEmpty()) null else chunks.removeFirst()
        if (chunk != null) {
            bytes -= chunk.data.size
            if (chunk.audible) audible--
        }
        return chunk
    }

    @Synchronized
    fun queuedBytes(): Int = bytes

    @Synchronized
    fun audibleChunks(): Int = audible

    @Synchronized
    fun isEmpty(): Boolean = chunks.isEmpty()

    @Synchronized
    fun clear() {
        chunks.clear()
        bytes = 0
        audible = 0
    }
}

// ---------------------------------------------------------------------------
// Phiên Noise RIÊNG cho voice (không link.register, không /link-control)
// ---------------------------------------------------------------------------

internal class VoiceSessionException(val reason: String) : Exception(reason)

/**
 * Một kết nối WebSocket + Noise XX độc lập cho voice call, dựng giống hệt
 * cách [MuseSessionAdapter] dựng phiên chính (OkHttp WebSocket, bearer
 * trên upgrade, handshake initiator ba bản tin) nhưng reader thread
 * decrypt frame và phân phối theo stream_id vào các hàng đợi riêng.
 */
internal class VoiceNoiseSession {
    private sealed class WsEvent {
        data class Binary(val bytes: ByteArray) : WsEvent()
        data class Closed(val reason: String) : WsEvent()
        data class Failure(val status: Int?, val error: String) : WsEvent()
        object Open : WsEvent()
    }

    private val wsEvents = LinkedBlockingQueue<WsEvent>()
    private val streamQueues = ConcurrentHashMap<Long, LinkedBlockingQueue<ServiceFrame>>()
    private val sendLock = Any()
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var transport: NoiseTransport? = null

    @Volatile
    var closed: Boolean = false
        private set

    @Volatile
    var closeReason: String = ""
        private set

    private var readerThread: Thread? = null

    /** Mở WebSocket + handshake Noise XX; chặn tới khi sẵn sàng hoặc lỗi. */
    fun connect(wsUrl: String, bearerToken: String) {
        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $bearerToken")
            .build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsEvents.offer(WsEvent.Open)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                wsEvents.offer(WsEvent.Binary(bytes.toByteArray()))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Frame văn bản không thuộc giao thức Noise; bỏ qua.
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                wsEvents.offer(WsEvent.Closed("code=$code $reason"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                wsEvents.offer(WsEvent.Failure(response?.code, t.message ?: t.javaClass.simpleName))
            }
        })
        webSocket = ws
        val deadline = System.currentTimeMillis() + VoiceCallSpec.HANDSHAKE_TIMEOUT_MS
        val openEvent = pollUntil(deadline)
            ?: throw VoiceSessionException("ws_timeout")
        when (openEvent) {
            is WsEvent.Open -> Unit
            is WsEvent.Failure -> throw VoiceSessionException(
                if (openEvent.status != null) "ws_http_${openEvent.status}" else "ws_failure"
            )
            is WsEvent.Closed -> throw VoiceSessionException("ws_closed")
            else -> throw VoiceSessionException("ws_unexpected")
        }
        val handshake = NoiseXxHandshake(isInitiator = true)
        if (!sendBinary(ws, handshake.writeMessage1())) {
            throw VoiceSessionException("handshake_send_failed")
        }
        val msg2Event = pollUntil(deadline)
        val msg2 = (msg2Event as? WsEvent.Binary)?.bytes
            ?: throw VoiceSessionException("handshake_timeout")
        handshake.readMessage2(msg2)
        val (msg3, ciphers) = handshake.writeMessage3()
        if (!sendBinary(ws, msg3)) {
            throw VoiceSessionException("handshake_send_failed")
        }
        transport = NoiseTransport(ciphers.sendCipher, ciphers.recvCipher)
        readerThread = Thread({ readerLoop() }, "VoiceNoiseReader").apply {
            isDaemon = true
            start()
        }
    }

    private fun pollUntil(deadlineMs: Long): WsEvent? {
        val remaining = deadlineMs - System.currentTimeMillis()
        if (remaining <= 0) return null
        return wsEvents.poll(remaining, TimeUnit.MILLISECONDS)
    }

    private fun readerLoop() {
        while (!closed) {
            val event = wsEvents.poll(500, TimeUnit.MILLISECONDS) ?: continue
            when (event) {
                is WsEvent.Binary -> {
                    val frame = try {
                        transport?.decryptFrame(event.bytes)
                    } catch (e: Exception) {
                        Log.w(TAG, "voice frame decrypt failed: ${e.message}")
                        null
                    }
                    if (frame != null) {
                        streamQueues[frame.streamId]?.offer(frame)
                    }
                }
                is WsEvent.Closed -> markClosed("ws_closed ${event.reason}")
                is WsEvent.Failure -> markClosed("ws_failure ${event.error}")
                is WsEvent.Open -> Unit
            }
        }
    }

    private fun markClosed(reason: String) {
        if (!closed) {
            closeReason = reason
            closed = true
        }
    }

    /** Đăng ký hàng đợi TRƯỚC khi gửi các frame mở stream (tránh mất frame sớm). */
    fun registerStream(streamId: Long): LinkedBlockingQueue<ServiceFrame> {
        val queue = LinkedBlockingQueue<ServiceFrame>()
        streamQueues[streamId] = queue
        return queue
    }

    fun unregisterStream(streamId: Long) {
        streamQueues.remove(streamId)
    }

    fun sendEncrypted(frames: List<ByteArray>): Boolean {
        val ws = webSocket ?: return false
        if (closed) return false
        synchronized(sendLock) {
            for (frame in frames) {
                if (!ws.send(frame.toByteString())) return false
            }
        }
        return true
    }

    fun sendBinary(ws: WebSocket, bytes: ByteArray): Boolean {
        synchronized(sendLock) {
            return ws.send(bytes.toByteString())
        }
    }

    /** Mở một stream dài; trả (stream_id, hàng đợi frame của stream). */
    fun openStream(
        verb: String,
        path: String,
        headers: List<NoiseHeader>
    ): Pair<Long, LinkedBlockingQueue<ServiceFrame>> {
        val t = transport ?: throw VoiceSessionException("no_transport")
        val (streamId, frames) = t.startStreamRequest(verb, path, headers)
        val queue = registerStream(streamId)
        if (!sendEncrypted(frames)) {
            unregisterStream(streamId)
            throw VoiceSessionException("stream_open_send_failed")
        }
        return streamId to queue
    }

    fun sendBodyChunk(streamId: Long, data: ByteArray, endBody: Boolean = false): Boolean {
        val t = transport ?: return false
        val frames = try {
            t.encryptBodyChunk(streamId, data, endBody)
        } catch (e: Exception) {
            return false
        }
        return sendEncrypted(frames)
    }

    fun resetStream(streamId: Long, reason: String = "voice call ended") {
        val t = transport ?: return
        runCatching { sendEncrypted(t.encryptReset(streamId, reason)) }
    }

    /**
     * Một request HTTP trọn gói (không stream) như end-call: mở stream
     * riêng, chờ response tới endBody/reset/hết giờ. Trả (HTTP status,
     * body text); status âm khi lỗi vận chuyển.
     */
    fun requestOnce(
        verb: String,
        path: String,
        body: ByteArray,
        headers: List<NoiseHeader>,
        timeoutMs: Long
    ): Pair<Int, String> {
        val t = transport ?: return -1 to "no_transport"
        val (streamId, frames) = try {
            t.encryptHttpRequest(verb, path, body, headers)
        } catch (e: Exception) {
            return -1 to (e.message ?: "encrypt_failed")
        }
        val queue = registerStream(streamId)
        try {
            if (!sendEncrypted(frames)) return -1 to "send_failed"
            val deadline = System.currentTimeMillis() + timeoutMs
            var status = -1
            val bodyBytes = ArrayList<Byte>()
            while (System.currentTimeMillis() < deadline) {
                if (closed) return -1 to "session_closed"
                val frame = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                frame.reset?.let { return -1 to "reset_${it.code}" }
                frame.response?.let { response ->
                    status = response.status
                    if (response.body.isNotEmpty()) response.body.forEach { bodyBytes.add(it) }
                    if (response.endBody) return status to bodyString(bodyBytes)
                }
                frame.bodyChunk?.let { chunk ->
                    if (chunk.data.isNotEmpty()) chunk.data.forEach { bodyBytes.add(it) }
                    if (chunk.endBody) return status to bodyString(bodyBytes)
                }
            }
            return -1 to "timeout"
        } finally {
            unregisterStream(streamId)
        }
    }

    fun close() {
        markClosed("local_close")
        runCatching { webSocket?.close(1000, "voice call ended") }
        webSocket = null
        readerThread?.interrupt()
    }

    private fun bodyString(bytes: List<Byte>): String =
        String(ByteArray(bytes.size) { bytes[it] }, Charsets.UTF_8)

    companion object {
        private const val TAG = "MuseVoice"
    }
}

// ---------------------------------------------------------------------------
// Audio Android: mic + loa phát (chi tiết thiết bị, không unit-test)
// ---------------------------------------------------------------------------

/**
 * Thu mic: ưu tiên cấu hình Phase 0 đã chứng minh (24 kHz,
 * VOICE_COMMUNICATION); nếu firmware không cho thì lùi 24 kHz/MIC rồi
 * 48 kHz (qua decimator FIR xuống 24 kHz).
 */
internal class AndroidMicCapture {
    private var record: AudioRecord? = null
    private var decimator: Pcm48To24Decimator? = null
    private var rawBuffer = ShortArray(0)

    var sampleRate: Int = VoiceCallSpec.SAMPLE_RATE
        private set
    var source: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION
        private set

    fun open(): Boolean {
        val candidates = listOf(
            VoiceCallSpec.SAMPLE_RATE to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            VoiceCallSpec.SAMPLE_RATE to MediaRecorder.AudioSource.MIC,
            48_000 to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            48_000 to MediaRecorder.AudioSource.MIC
        )
        for ((rate, src) in candidates) {
            val minBuffer = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) continue
            val recorder = try {
                AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, rate / 2))
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord $rate/$src failed: ${e.message}")
                continue
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder.release() }
                continue
            }
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                continue
            }
            record = recorder
            sampleRate = rate
            source = src
            decimator = if (rate == 48_000) Pcm48To24Decimator() else null
            rawBuffer = ShortArray(if (rate == 48_000) {
                VoiceCallSpec.FRAME_SAMPLES * 2
            } else {
                VoiceCallSpec.FRAME_SAMPLES
            })
            Log.i(TAG, "mic open: ${rate}Hz source=$src")
            return true
        }
        return false
    }

    /**
     * Đọc một nhịp âm thanh (~40 ms) và trả PCM 24 kHz (có thể ít hơn 1
     * frame khi HAL trả từng mảnh; bên gọi gom qua PcmFrameChunker).
     * Âm khi lỗi thu âm.
     */
    fun readPcm24k(): ByteArray? {
        val recorder = record ?: return null
        val n = try {
            recorder.read(rawBuffer, 0, rawBuffer.size)
        } catch (e: Exception) {
            Log.w(TAG, "mic read failed: ${e.message}")
            return null
        }
        if (n <= 0) return null
        var bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = rawBuffer[i].toInt()
            bytes[2 * i] = (v and 0xFF).toByte()
            bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        decimator?.let { bytes = it.feed(bytes) }
        return bytes
    }

    fun close() {
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
    }

    companion object {
        private const val TAG = "MuseVoice"
    }
}

/** Phát PCM 24 kHz qua AudioTrack (MODE_STREAM, tự điều tốc bằng write + sleep). */
internal class AndroidPcmPlayer {
    private var track: AudioTrack? = null

    fun open(): Boolean {
        val minBuffer = AudioTrack.getMinBufferSize(
            VoiceCallSpec.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(if (minBuffer > 0) minBuffer else 0, VoiceCallSpec.MAX_PLAYBACK_BYTES)
        for (streamType in listOf(AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_MUSIC)) {
            val candidate = try {
                @Suppress("DEPRECATION")
                AudioTrack(
                    streamType, VoiceCallSpec.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM
                )
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack stream=$streamType failed: ${e.message}")
                continue
            }
            if (candidate.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { candidate.release() }
                continue
            }
            candidate.play()
            track = candidate
            Log.i(TAG, "player open: stream=$streamType buffer=$bufferSize")
            return true
        }
        return false
    }

    fun write(pcm: ByteArray) {
        val current = track ?: return
        var offset = 0
        while (offset < pcm.size) {
            val written = try {
                current.write(pcm, offset, pcm.size - offset)
            } catch (e: Exception) {
                Log.w(TAG, "player write failed: ${e.message}")
                return
            }
            if (written <= 0) return
            offset += written
        }
    }

    fun flush() {
        runCatching { track?.flush() }
    }

    fun close() {
        track?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
    }

    companion object {
        private const val TAG = "MuseVoice"
    }
}

// ---------------------------------------------------------------------------
// Engine cuộc gọi
// ---------------------------------------------------------------------------

class VoiceCall(
    private val context: Context,
    private val targetProvider: () -> VoiceTarget?,
    private val nodeIdProvider: () -> String,
    private val appIdProvider: () -> String,
    private val listener: (Status) -> Unit
) {

    /** Đích phiên voice: resolve từ phiên Muse chính đang hoạt động. */
    data class VoiceTarget(val noiseHost: String, val vmId: String, val bearerToken: String)

    enum class State { IDLE, CONNECTING, ADMITTING, IN_CALL, ENDING, ENDED, FAILED }

    data class Status(
        val state: State,
        val callId: String = "",
        val sessionId: String = "",
        val reason: String = "",
        val error: String = "",
        val appId: String = "",
        val vmId: String = "",
        val startedAtMs: Long = 0,
        val endedAtMs: Long = 0,
        val endCallAck: Boolean = false,
        val mediaDrainIncomplete: Boolean = false,
        val advisoryErrors: Int = 0
    ) {
        val active: Boolean
            get() = state == State.CONNECTING || state == State.ADMITTING ||
                state == State.IN_CALL || state == State.ENDING

        fun toJson(): JSONObject {
            return JSONObject()
                .put("state", state.name.lowercase(Locale.US))
                .put("active", active)
                .put("call_id", callId)
                .put("session_id", sessionId)
                .put("reason", reason)
                .put("error", error)
                .put("app_id", appId)
                .put("vm_id", vmId)
                .put("started_at_ms", startedAtMs)
                .put("ended_at_ms", endedAtMs)
                .put("end_call_ack", endCallAck)
                .put("media_drain_incomplete", mediaDrainIncomplete)
                .put("advisory_errors", advisoryErrors)
        }
    }

    @Volatile
    private var status = Status(State.IDLE)

    @Volatile
    private var workerThread: Thread? = null

    // Trạng thái trong cuộc gọi (chỉ các thread của cuộc gọi chạm vào).
    @Volatile
    private var endReason: String? = null

    @Volatile
    private var endDetail: String = ""

    @Volatile
    private var sessionHeld: Boolean = false

    @Volatile
    private var remoteEnded: Boolean = false

    @Volatile
    private var playingUntilMs: Long = 0

    @Volatile
    private var activityMs: Long = 0

    @Volatile
    private var gateEpoch: Long = 0

    @Volatile
    private var lastGateState: Boolean = false

    private var session: VoiceNoiseSession? = null
    private var realtimeStreamId: Long = -1
    private var realtimeQueue: LinkedBlockingQueue<ServiceFrame>? = null
    private var player: AndroidPcmPlayer? = null
    private var micThread: Thread? = null
    private var playbackThread: Thread? = null

    @Volatile
    private var micRunning: Boolean = false

    @Volatile
    private var playbackRunning: Boolean = false

    @Volatile
    private var activeCapture: AndroidMicCapture? = null

    private val playbackQueue = PlaybackQueue()
    private val turnTracker = VoiceTurnTracker()

    fun currentStatus(): Status = status

    /** Bắt đầu cuộc gọi trên thread nền; bỏ qua nếu đang có cuộc gọi dở. */
    @Synchronized
    fun start() {
        if (status.active || workerThread?.isAlive == true) {
            Log.i(TAG, "voice call start ignored: already active (${status.state})")
            return
        }
        endReason = null
        endDetail = ""
        val appId = appIdProvider()
        transition(Status(State.CONNECTING, appId = appId))
        workerThread = Thread({ runCall(appId) }, "VoiceCallMain").apply {
            isDaemon = true
            start()
        }
    }

    /** Yêu cầu kết thúc (từ HTTP/command/destroy); an toàn khi gọi nhiều lần. */
    fun stop(reason: String = "user_stop") {
        if (status.active && endReason == null) {
            endReason = reason
            Log.i(TAG, "voice call stop requested: $reason")
        }
    }

    private fun transition(next: Status) {
        status = next
        try {
            listener(next)
        } catch (e: Exception) {
            Log.w(TAG, "voice status listener failed: ${e.message}")
        }
    }

    private fun runCall(appId: String) {
        val startedAtMs = System.currentTimeMillis()
        val callId = UUID.randomUUID().toString()
        var sessionId = ""
        var admissionSent = false
        var endCallAck = false
        var drainIncomplete = false
        var advisoryErrors = 0
        var finalReason = ""
        var finalError = ""
        var terminalState = State.ENDED
        var previousAudioMode: Int? = null
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        fun finish(state: State, reason: String, error: String) {
            transition(
                Status(
                    state = state,
                    callId = callId,
                    sessionId = sessionId,
                    reason = reason,
                    error = SecretRedactor.redact(error),
                    appId = appId,
                    vmId = status.vmId,
                    startedAtMs = startedAtMs,
                    endedAtMs = System.currentTimeMillis(),
                    endCallAck = endCallAck,
                    mediaDrainIncomplete = drainIncomplete,
                    advisoryErrors = advisoryErrors
                )
            )
        }

        try {
            val target = targetProvider()
            if (target == null || target.vmId.isEmpty() || target.bearerToken.isEmpty()) {
                finish(State.FAILED, "no_target", "Phiên Muse chính chưa resolve được VM đích")
                return
            }
            transition(Status(State.CONNECTING, callId = callId, appId = appId, vmId = target.vmId, startedAtMs = startedAtMs))
            val voiceSession = VoiceNoiseSession()
            session = voiceSession
            voiceSession.connect(buildVoiceNoiseUrl(target.noiseHost, target.vmId, appId), target.bearerToken)
            Log.i(TAG, "voice session connected (vm=${target.vmId}, app_id=$appId)")

            // --- Admission ---
            // Thử các đường realtime theo thứ tự; 404 ở đường đầu (chưa có sự
            // kiện nào được nhận) thì reset stream và thử đường kế tiếp trên
            // cùng một phiên voice - xem VoiceCallSpec.REALTIME_PATHS.
            var realtimePathIndex = 0
            fun openRealtimeStream(): Pair<Long, LinkedBlockingQueue<ServiceFrame>> {
                val opened = voiceSession.openStream(
                    "POST",
                    "${VoiceCallSpec.REALTIME_PATHS[realtimePathIndex]}?call_id=$callId",
                    listOf(NoiseHeader("Content-Type", "application/x-ndjson; charset=utf-8"))
                )
                realtimeStreamId = opened.first
                realtimeQueue = opened.second
                return opened
            }
            val firstOpened = openRealtimeStream()
            var streamId = firstOpened.first
            var queue = firstOpened.second
            val admission = VoiceEvents.admissionNdjson(callId, nodeIdProvider())
            admissionSent = true // SDK: tính là "đã gửi" ngay khi mở stream xong
            if (!voiceSession.sendBodyChunk(streamId, admission)) {
                finalReason = "admission_send_failed"
                finalError = "Không gửi được admission lên stream voice"
                terminalState = State.FAILED
                // Vẫn đi qua cleanup để end-call như SDK.
                throw CallFlowException()
            }
            transition(Status(State.ADMITTING, callId = callId, appId = appId, vmId = target.vmId, startedAtMs = startedAtMs))

            // --- Chờ readiness ---
            activityMs = System.currentTimeMillis()
            val readyDeadline = startedAtMs + minOf(
                VoiceCallSpec.READY_TIMEOUT_MS, VoiceCallSpec.OVERALL_TIMEOUT_MS
            )
            var splitter = NdjsonSplitter()
            var httpStatus = -1
            var ready = false
            var receivedRealAudio = false
            readiness@ while (!ready) {
                if (endReason != null) {
                    finalReason = endReason ?: "user_stop"
                    break@readiness
                }
                if (voiceSession.closed) {
                    finalReason = "session_closed"
                    finalError = voiceSession.closeReason
                    terminalState = State.FAILED
                    break@readiness
                }
                if (System.currentTimeMillis() >= readyDeadline) {
                    finalReason = "readiness_timeout"
                    finalError = "VM không xác nhận sẵn sàng trong ${VoiceCallSpec.READY_TIMEOUT_MS / 1000}s"
                    break@readiness
                }
                val frame = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                if (frame.reset != null) {
                    finalReason = "stream_reset"
                    finalError = "reset ${frame.reset!!.code}: ${frame.reset!!.reason}"
                    terminalState = State.FAILED
                    break@readiness
                }
                frame.response?.let { httpStatus = it.status }
                val chunks = ArrayList<ByteArray>()
                frame.response?.body?.takeIf { it.isNotEmpty() }?.let { chunks.add(it) }
                frame.bodyChunk?.data?.takeIf { it.isNotEmpty() }?.let { chunks.add(it) }
                for (chunk in chunks) {
                    for (line in splitter.feed(chunk)) {
                        val event = parseEvent(line) ?: continue
                        val type = event.optString("type")
                        when {
                            VoiceEvents.isReadinessEvent(event) -> {
                                val sid = VoiceEvents.sessionIdFromReadiness(event, callId)
                                if (sid == null) {
                                    finalReason = "readiness_mismatch"
                                    finalError = "Sự kiện sẵn sàng không khớp call_id/session_id"
                                } else {
                                    sessionId = sid
                                    ready = true
                                }
                            }
                            type == "error" -> {
                                finalReason = "session_error"
                                finalError = "VM báo lỗi trước readiness: ${errorCode(event)}"
                                terminalState = State.FAILED
                            }
                            VoiceEvents.isStopControl(event) -> {
                                finalReason = "remote_stop"
                                finalError = "Lệnh stop tới trước readiness"
                            }
                            else -> {
                                val pcm = try {
                                    VoiceEvents.audioFromEvent(event)
                                } catch (e: Exception) {
                                    finalReason = "protocol_error"
                                    finalError = e.message ?: "audio không hợp lệ"
                                    null
                                }
                                if (finalReason.isEmpty() && pcm != null && pcm.isNotEmpty()) {
                                    if (isZeroPcm(pcm)) {
                                        // Silence keepalive: bỏ qua, không tính hoạt động.
                                    } else {
                                        finalReason = "protocol_error"
                                        finalError = "Audio về trước khi sẵn sàng"
                                    }
                                }
                            }
                        }
                        if (ready || finalReason.isNotEmpty()) break
                    }
                    if (ready || finalReason.isNotEmpty()) break
                }
                if (httpStatus in 200..299) {
                    // HTTP tốt nhưng vẫn phải chờ sự kiện readiness mới gửi audio.
                } else if (httpStatus == 404 && realtimePathIndex < VoiceCallSpec.REALTIME_PATHS.lastIndex) {
                    // Đường realtime này không có trên VM ở phiên voice: reset
                    // stream, thử đường kế tiếp trên cùng phiên và gửi lại
                    // admission (chưa có sự kiện nào được nhận nên an toàn).
                    Log.w(
                        TAG,
                        "realtime 404 at ${VoiceCallSpec.REALTIME_PATHS[realtimePathIndex]}; " +
                            "retrying ${VoiceCallSpec.REALTIME_PATHS[realtimePathIndex + 1]}"
                    )
                    voiceSession.resetStream(streamId)
                    voiceSession.unregisterStream(streamId)
                    realtimePathIndex++
                    val reopened = openRealtimeStream()
                    streamId = reopened.first
                    queue = reopened.second
                    splitter = NdjsonSplitter()
                    httpStatus = -1
                    if (!voiceSession.sendBodyChunk(streamId, admission)) {
                        finalReason = "admission_send_failed"
                        finalError = "Không gửi lại được admission lên stream voice"
                        terminalState = State.FAILED
                        break@readiness
                    }
                } else if (httpStatus >= 0) {
                    finalReason = "admission_http_$httpStatus"
                    finalError = "VM từ chối admission voice (HTTP $httpStatus)"
                    if (ready) finalReason = ""
                    break@readiness
                }
                if (finalReason.isNotEmpty()) break@readiness
            }
            if (!ready) {
                // Không vào giai đoạn media; cleanup phía dưới vẫn end-call.
                throw CallFlowException()
            }
            if (session == null) throw CallFlowException()
            Log.i(TAG, "voice call ready: session_id set, call=$callId")
            activityMs = System.currentTimeMillis()

            // --- Media ---
            runCatching {
                previousAudioMode = audioManager?.mode
                audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            val pcmPlayer = AndroidPcmPlayer()
            player = pcmPlayer
            if (!pcmPlayer.open()) {
                finalReason = "playback_unavailable"
                finalError = "Không mở được AudioTrack trên loa"
                throw CallFlowException()
            }
            transition(Status(State.IN_CALL, callId = callId, sessionId = sessionId, appId = appId, vmId = target.vmId, startedAtMs = startedAtMs))
            startPlaybackThread()
            startMicThread(callId, streamId)
            val deadlineOverall = startedAtMs + VoiceCallSpec.OVERALL_TIMEOUT_MS

            media@ while (true) {
                if (endReason != null) {
                    finalReason = endReason ?: "user_stop"
                    break@media
                }
                val nowMs = System.currentTimeMillis()
                if (nowMs >= deadlineOverall) {
                    finalReason = "overall_timeout"
                    break@media
                }
                if (!turnTracker.hasActiveTurns() &&
                    nowMs - activityMs >= VoiceCallSpec.IDLE_TIMEOUT_MS
                ) {
                    finalReason = "idle_timeout"
                    break@media
                }
                if (voiceSession.closed) {
                    finalReason = "session_closed"
                    finalError = voiceSession.closeReason
                    break@media
                }
                val frame = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                if (frame.reset != null) {
                    finalReason = "remote_reset"
                    finalError = "reset ${frame.reset!!.code}: ${frame.reset!!.reason}"
                    break@media
                }
                var streamEof = false
                frame.response?.let { resp ->
                    if (resp.status !in 200..299) {
                        finalReason = "stream_http_${resp.status}"
                    } else if (resp.endBody) {
                        streamEof = true
                    }
                }
                if (finalReason.isNotEmpty()) break@media
                val chunks = ArrayList<ByteArray>()
                frame.response?.body?.takeIf { it.isNotEmpty() }?.let { chunks.add(it) }
                frame.bodyChunk?.let { chunk ->
                    if (chunk.data.isNotEmpty()) chunks.add(chunk.data)
                    if (chunk.endBody) streamEof = true
                }
                for (chunk in chunks) {
                    for (line in splitter.feed(chunk)) {
                        val event = parseEvent(line) ?: continue
                        val type = event.optString("type")
                        when {
                            type == "error" -> {
                                val code = errorCode(event)
                                if (code == "session_dead") {
                                    finalReason = "session_error"
                                    finalError = "Phiên voice đã chết phía VM"
                                } else {
                                    advisoryErrors++
                                }
                            }
                            type == "hatch.voice.switch_input_hold" -> {
                                if (event.has("held") && event.opt("held") is Boolean) {
                                    val held = event.optBoolean("held")
                                    if (held != sessionHeld) {
                                        sessionHeld = held
                                        gateEpoch++
                                    }
                                }
                            }
                            type == "input_audio_buffer.speech_started" -> {
                                activityMs = System.currentTimeMillis()
                            }
                            type == "hatch.voice.turn_started" -> {
                                if (turnTracker.onTurnStarted(event)) {
                                    activityMs = System.currentTimeMillis()
                                }
                            }
                            type == "hatch.voice.turn_settled" -> {
                                if (turnTracker.onTurnSettled(event)) {
                                    activityMs = System.currentTimeMillis()
                                }
                            }
                            type == "response.created" || type == "response.done" ||
                                type == "response.completed" -> {
                                activityMs = System.currentTimeMillis()
                            }
                            VoiceEvents.isStopControl(event) -> {
                                drainPlayback()
                                finalReason = "remote_stop"
                            }
                            else -> {
                                val pcm = try {
                                    VoiceEvents.audioFromEvent(event)
                                } catch (e: Exception) {
                                    finalReason = "protocol_error"
                                    finalError = e.message ?: "audio không hợp lệ"
                                    null
                                }
                                if (pcm != null && pcm.isNotEmpty()) {
                                    if (isZeroPcm(pcm) && !receivedRealAudio && playbackQueue.isEmpty()) {
                                        // Zero-keepalive trước audio thật: bỏ qua.
                                    } else {
                                        if (!isZeroPcm(pcm)) receivedRealAudio = true
                                        val audibleBefore = playbackQueue.audibleChunks()
                                        if (!playbackQueue.offer(pcm)) {
                                            finalReason = "playback_overflow"
                                            finalError = "Hàng đợi phát vượt 5 giây"
                                        } else if (audibleBefore == 0 && playbackQueue.audibleChunks() > 0) {
                                            gateEpoch++
                                        }
                                    }
                                }
                            }
                        }
                        if (finalReason.isNotEmpty()) break
                    }
                    if (finalReason.isNotEmpty()) break
                }
                if (finalReason.isNotEmpty()) break@media
                if (streamEof) {
                    remoteEnded = true
                    drainPlayback()
                    finalReason = "remote_end"
                    break@media
                }
            }
        } catch (e: CallFlowException) {
            // finalReason/finalError đã được ghi ở điểm ném.
        } catch (e: VoiceSessionException) {
            if (finalReason.isEmpty()) {
                finalReason = e.reason
                finalError = e.message ?: e.reason
            }
            if (!admissionSent) terminalState = State.FAILED
        } catch (e: Exception) {
            if (finalReason.isEmpty()) {
                finalReason = "internal_error"
                finalError = e.message ?: e.javaClass.simpleName
            }
            if (!admissionSent) terminalState = State.FAILED
        } finally {
            // --- Cleanup (giới hạn tổng CLEANUP_TIMEOUT_MS như SDK) ---
            val cleanupDeadline = System.currentTimeMillis() + VoiceCallSpec.CLEANUP_TIMEOUT_MS
            transition(
                Status(
                    state = State.ENDING, callId = callId, sessionId = sessionId,
                    reason = finalReason, error = SecretRedactor.redact(finalError),
                    appId = appId, vmId = status.vmId, startedAtMs = startedAtMs,
                    advisoryErrors = advisoryErrors
                )
            )
            remoteEnded = true
            micRunning = false
            micThread?.interrupt()
            // AudioRecord.read() có thể chặn lâu trên HAL của R1: đóng
            // capture từ thread điều phối để read() nhả ra.
            activeCapture?.close()
            micThread?.join(1_000)
            micThread = null
            activeCapture = null
            if (finalReason != "remote_stop" && finalReason != "remote_end") {
                // Không drain: cắt tiếng phát ngay khi cuộc gọi kết thúc
                // vì các lý do khác (user stop, timeout, lỗi).
                playbackQueue.clear()
                player?.flush()
            }
            playbackRunning = false

            val voiceSession = session
            if (voiceSession != null && admissionSent) {
                val endBody = VoiceEvents.buildEndCallBody(callId, startedAtMs, System.currentTimeMillis())
                val (endStatus, endText) = voiceSession.requestOnce(
                    "POST", "/voice/end-call",
                    endBody.toString().toByteArray(Charsets.UTF_8),
                    listOf(NoiseHeader("Content-Type", "application/json")),
                    5_000
                )
                endCallAck = VoiceEvents.endCallAcknowledged(endStatus, endText)
                Log.i(TAG, "voice end-call: http=$endStatus ack=$endCallAck")
                if (realtimeStreamId >= 0) {
                    voiceSession.sendBodyChunk(realtimeStreamId, ByteArray(0), endBody = true)
                    val queue = realtimeQueue
                    if (queue != null) {
                        val drainDeadline = minOf(cleanupDeadline, System.currentTimeMillis() + 1_000)
                        var eof = false
                        while (System.currentTimeMillis() < drainDeadline && !eof) {
                            val frame = queue.poll(200, TimeUnit.MILLISECONDS) ?: break
                            if (frame.reset != null) eof = true
                            if (frame.response?.endBody == true) eof = true
                            if (frame.bodyChunk?.endBody == true) eof = true
                        }
                        if (!eof && endCallAck) drainIncomplete = true
                    }
                    voiceSession.resetStream(realtimeStreamId)
                    voiceSession.unregisterStream(realtimeStreamId)
                }
            }
            playbackThread?.interrupt()
            playbackThread = null
            player?.close()
            player = null
            playbackQueue.clear()
            voiceSession?.close()
            session = null
            runCatching {
                if (previousAudioMode != null) audioManager?.mode = previousAudioMode!!
            }
            if (finalReason.isEmpty()) finalReason = "ended"
            finish(terminalState, finalReason, finalError)
            workerThread = null
        }
    }

    /** Ngoại lệ nội bộ để nhảy thẳng tới cleanup mà vẫn giữ reason đã ghi. */
    private class CallFlowException : Exception()

    private fun parseEvent(line: String): JSONObject? {
        return try {
            JSONObject(line)
        } catch (e: Exception) {
            Log.w(TAG, "voice event JSON không hợp lệ: ${e.message}")
            null
        }
    }

    private fun errorCode(event: JSONObject): String {
        val error = event.optJSONObject("error") ?: return ""
        return error.optString("code", "")
    }

    /** Mic đang bị chặn khi: cuộc gọi đã kết thúc phía VM, VM giữ mic, hoặc đang phát tiếng (half-duplex). */
    private fun micGated(nowMs: Long): Boolean {
        if (remoteEnded || sessionHeld) return true
        return playbackQueue.audibleChunks() > 0 || nowMs < playingUntilMs
    }

    private fun startPlaybackThread() {
        playbackRunning = true
        playbackThread = Thread({
            val pcmPlayer = player
            while (playbackRunning) {
                val chunk = playbackQueue.poll()
                if (chunk == null) {
                    try {
                        Thread.sleep(20)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                pcmPlayer?.write(chunk.data)
                val durationMs = chunk.data.size * 1000L / (VoiceCallSpec.SAMPLE_RATE * 2)
                if (chunk.audible) {
                    playingUntilMs = System.currentTimeMillis() + durationMs + VoiceCallSpec.SETTLE_MS
                    activityMs = System.currentTimeMillis()
                }
                try {
                    Thread.sleep(durationMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "VoiceCallPlay").apply {
            isDaemon = true
            start()
        }
    }

    /** Chờ phát nốt phần audio đã xếp hàng (cho remote_stop / remote_end). */
    private fun drainPlayback() {
        remoteEnded = true
        val budgetMs = playbackQueue.queuedBytes() * 1000L / (VoiceCallSpec.SAMPLE_RATE * 2) +
            VoiceCallSpec.SETTLE_MS + 500
        val deadline = System.currentTimeMillis() + minOf(budgetMs, VoiceCallSpec.CLEANUP_TIMEOUT_MS)
        while (System.currentTimeMillis() < deadline) {
            if (playbackQueue.isEmpty() && System.currentTimeMillis() >= playingUntilMs) return
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun startMicThread(callId: String, streamId: Long) {
        micRunning = true
        micThread = Thread({
            val capture = AndroidMicCapture()
            activeCapture = capture
            if (!capture.open()) {
                Log.w(TAG, "voice mic unavailable")
                if (endReason == null) {
                    endDetail = "Không mở được mic trên loa"
                    endReason = "mic_unavailable"
                }
                micRunning = false
                return@Thread
            }
            val chunker = PcmFrameChunker()
            var lastSpeechMs = 0L
            var nextSendMs = 0L
            try {
                while (micRunning && endReason == null) {
                    val pcm = capture.readPcm24k()
                    if (pcm == null) {
                        if (endReason == null) {
                            endDetail = "Thu âm mic thất bại"
                            endReason = "mic_error"
                        }
                        break
                    }
                    for (frame in chunker.feed(pcm)) {
                        if (!micRunning || endReason != null) break
                        sendMicFrame(streamId, frame, lastSpeechMs) { updated ->
                            lastSpeechMs = updated
                        }
                        // Pacing 40 ms/frame: AudioRecord tự điều tốc phần
                        // lớn; ngủ bù nếu HAL trả nhanh hơn thời gian thực.
                        val now = System.currentTimeMillis()
                        if (nextSendMs == 0L) nextSendMs = now
                        nextSendMs += VoiceCallSpec.FRAME_DURATION_MS
                        val wait = nextSendMs - System.currentTimeMillis()
                        when {
                            wait > 0 -> Thread.sleep(wait)
                            wait < -200 -> nextSendMs = System.currentTimeMillis()
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // Đang teardown.
            } finally {
                capture.close()
                micRunning = false
            }
        }, "VoiceCallMic").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Gửi một frame mic theo đúng ngữ nghĩa SDK: đang bị gate thì BỎ
     * (không phát lại), epoch đổi giữa lúc chờ thì bỏ, im lặng kéo dài
     * thì thay bằng frame im lặng, có tiếng thì chạm activity.
     */
    private fun sendMicFrame(
        streamId: Long,
        frame: ByteArray,
        lastSpeechMs: Long,
        onSpeech: (Long) -> Unit
    ) {
        val now = System.currentTimeMillis()
        val gateNow = micGated(now)
        if (gateNow != lastGateState) {
            lastGateState = gateNow
            if (gateNow) gateEpoch++
        }
        if (gateNow) return
        val epochAtStart = gateEpoch
        val rms = pcmRms(frame)
        var outgoing = frame
        if (rms >= VoiceCallSpec.AUDIBLE_RMS) {
            onSpeech(now)
            activityMs = now
        } else if (now - lastSpeechMs > 400) {
            outgoing = if (frame.size == VoiceCallSpec.FRAME_BYTES) {
                VoiceCallSpec.ZERO_FRAME
            } else {
                ByteArray(frame.size)
            }
        }
        if (epochAtStart != gateEpoch || micGated(System.currentTimeMillis())) return
        val voiceSession = session ?: return
        // Một frame = một bản ghi append duy nhất (1920 byte PCM → ~2,6 KB
        // JSON, luôn dưới trần MAX_SEND_BYTES của stream).
        val payload = VoiceEvents.appendEventJson(outgoing, System.currentTimeMillis())
        if (!voiceSession.sendBodyChunk(streamId, payload)) {
            if (endReason == null) endReason = "session_closed"
        }
    }

    companion object {
        private const val TAG = "MuseVoice"

        /**
         * URL phiên voice: như noise_url của SDK — app_id gắn trên query
         * của upgrade WebSocket (không phải header từng request).
         */
        fun buildVoiceNoiseUrl(noiseHost: String, vmId: String, appId: String): String {
            val base = "wss://$noiseHost/v1/noise?vm_id=" + URLEncoder.encode(vmId, "UTF-8")
            return if (appId.isNotEmpty()) {
                base + "&app_id=" + URLEncoder.encode(appId, "UTF-8")
            } else {
                base
            }
        }
    }
}
