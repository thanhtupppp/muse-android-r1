package vn.tutu.muser1

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Đầu ra giọng nói dùng chung cho cả Agent: TextToSpeech thật + registry các
 * MediaPlayer đang phát, để lệnh `muse_r1.tts` nói đúng nội dung được yêu cầu
 * và `muse_r1.stop_audio` dừng được mọi thứ đang phát.
 *
 * Trước đây tts chỉ là stub: phát tiếng chuông "ready" đóng hộp bất kể text
 * được yêu cầu là gì, và stop_audio không dừng được gì cả.
 *
 * Engine TTS được khởi tạo lười trên main thread; các yêu cầu nói đến trong
 * lúc engine đang khởi tạo được xếp hàng và phát ngay khi engine sẵn sàng.
 * Nếu thiết bị không có TTS engine/dữ liệu giọng nói, [speak] trả
 * [SpeakResult.UNAVAILABLE] để caller báo lỗi trung thực thay vì giả vờ
 * đã nói. Ngôn ngữ ưu tiên tiếng Việt, không có thì rơi về locale mặc định
 * của thiết bị.
 */
object SpeechOutput {

    private const val TAG = "MuseR1"

    enum class SpeakResult {
        /** Engine đã sẵn sàng, utterance đã vào hàng đợi phát. */
        QUEUED,

        /** Engine đang khởi tạo, text đã xếp hàng chờ phát. */
        INITIALIZING,

        /** Không có TTS engine khả dụng trên thiết bị. */
        UNAVAILABLE
    }

    data class StopResult(val ttsStopped: Boolean, val playersStopped: Int)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val utteranceCounter = AtomicInteger(0)
    private val pendingTexts = ConcurrentLinkedQueue<String>()

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ttsReady = false

    @Volatile
    private var ttsFailed = false

    @Volatile
    private var ttsInitializing = false

    /** Các MediaPlayer đang sống của Agent (vd tiếng chuông ready), để stop_audio dừng được. */
    private val livePlayers: MutableSet<MediaPlayer> =
        Collections.newSetFromMap(WeakHashMap<MediaPlayer, Boolean>())

    fun speak(context: Context, text: String): SpeakResult {
        val clean = text.trim()
        if (clean.isEmpty()) return SpeakResult.UNAVAILABLE

        val engine = tts
        if (ttsReady && engine != null) {
            engine.speak(clean, TextToSpeech.QUEUE_ADD, null, nextUtteranceId())
            return SpeakResult.QUEUED
        }
        if (ttsFailed) return SpeakResult.UNAVAILABLE

        pendingTexts.add(clean)
        ensureEngine(context.applicationContext)
        return SpeakResult.INITIALIZING
    }

    private fun ensureEngine(appContext: Context) {
        if (tts != null || ttsInitializing || ttsFailed) return
        ttsInitializing = true
        // TextToSpeech nên được tạo trên main thread.
        mainHandler.post {
            if (tts != null) {
                ttsInitializing = false
                return@post
            }
            try {
                tts = TextToSpeech(appContext) { status ->
                    ttsInitializing = false
                    if (status == TextToSpeech.SUCCESS) {
                        applyLanguage(tts)
                        ttsReady = true
                        Log.i(TAG, "TTS engine sẵn sàng")
                        flushPending()
                    } else {
                        Log.e(TAG, "TTS engine khởi tạo thất bại (status=$status)")
                        ttsFailed = true
                        pendingTexts.clear()
                    }
                }
            } catch (error: Exception) {
                ttsInitializing = false
                ttsFailed = true
                pendingTexts.clear()
                Log.e(TAG, "Không tạo được TTS engine", error)
            }
        }
    }

    private fun applyLanguage(engine: TextToSpeech?) {
        if (engine == null) return
        val vietnamese = Locale("vi", "VN")
        val result = try {
            engine.setLanguage(vietnamese)
        } catch (_: Exception) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Thiết bị không có dữ liệu giọng tiếng Việt; dùng locale mặc định cho TTS")
            try {
                engine.setLanguage(Locale.getDefault())
            } catch (_: Exception) {
                // Engine tự dùng giọng mặc định của nó.
            }
        }
    }

    private fun flushPending() {
        val engine = tts ?: return
        while (true) {
            val next = pendingTexts.poll() ?: break
            engine.speak(next, TextToSpeech.QUEUE_ADD, null, nextUtteranceId())
        }
    }

    private fun nextUtteranceId(): String = "muse_tts_${utteranceCounter.incrementAndGet()}"

    /** Đăng ký một MediaPlayer của Agent để stop_audio dừng được nó. */
    fun trackPlayer(player: MediaPlayer) {
        synchronized(livePlayers) { livePlayers.add(player) }
    }

    /** Dừng TTS đang nói/hàng đợi và mọi MediaPlayer đã đăng ký. */
    fun stopAll(): StopResult {
        var ttsStopped = false
        val engine = tts
        if (engine != null) {
            try {
                engine.stop()
                ttsStopped = true
            } catch (_: Exception) {
            }
        }
        pendingTexts.clear()

        val snapshot = synchronized(livePlayers) { livePlayers.toList() }
        var playersStopped = 0
        for (player in snapshot) {
            try {
                if (player.isPlaying) {
                    player.stop()
                    playersStopped++
                }
                player.release()
            } catch (_: Exception) {
                // Player có thể đã được chủ sở hữu release trước; bỏ qua.
            }
        }
        synchronized(livePlayers) { livePlayers.removeAll(snapshot.toSet()) }
        return StopResult(ttsStopped, playersStopped)
    }

    /** Giải phóng engine khi AgentService bị huỷ. */
    fun shutdown() {
        stopAll()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ttsReady = false
        ttsFailed = false
        ttsInitializing = false
    }
}
