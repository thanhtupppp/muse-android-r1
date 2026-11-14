package vn.tutu.muser1

import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

class VoiceCallTest {

    // --- NdjsonSplitter ---

    @Test
    fun ndjsonSplitsAcrossChunkBoundaries() {
        val splitter = NdjsonSplitter()
        assertEquals(listOf("{\"a\":1}"), splitter.feed("{\"a\":1}\n{\"b\"".toByteArray()))
        assertEquals(listOf("{\"b\":2}"), splitter.feed(":2}\n".toByteArray()))
        assertFalse(splitter.hasPartialLine())
    }

    @Test
    fun ndjsonAcceptsCrlfAndBlankLines() {
        val splitter = NdjsonSplitter()
        val lines = splitter.feed("one\r\n\n\ntwo\n".toByteArray())
        assertEquals(listOf("one", "two"), lines)
    }

    @Test
    fun ndjsonRejectsOversizedLine() {
        val splitter = NdjsonSplitter(maxLineBytes = 8)
        try {
            splitter.feed("012345678".toByteArray())
            fail("expected oversize line to throw")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    // --- PcmFrameChunker ---

    @Test
    fun frameChunkerEmitsExactFramesAndKeepsRemainder() {
        val chunker = PcmFrameChunker(frameBytes = 4)
        assertEquals(0, chunker.feed(byteArrayOf(1, 2, 3)).size)
        val frames = chunker.feed(byteArrayOf(4, 5, 6, 7, 8, 9))
        assertEquals(2, frames.size)
        assertTrue(frames[0].contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertTrue(frames[1].contentEquals(byteArrayOf(5, 6, 7, 8)))
        assertEquals(1, chunker.pendingBytes())
        assertTrue(chunker.flush()!!.contentEquals(byteArrayOf(9)))
        assertNull(chunker.flush())
    }

    // --- pcmRms / isZeroPcm ---

    @Test
    fun pcmRmsOfSilenceIsZeroAndConstantIsExact() {
        assertEquals(0.0, pcmRms(ByteArray(1920)), 0.001)
        val pcm = ByteArray(200)
        for (i in 0 until 100) {
            pcm[2 * i] = 0xE8.toByte() // 1000 & 0xFF
            pcm[2 * i + 1] = 0x03 // 1000 >> 8
        }
        assertEquals(1000.0, pcmRms(pcm), 0.001)
        assertTrue(isZeroPcm(ByteArray(10)))
        assertFalse(isZeroPcm(pcm))
    }

    // --- Pcm48To24Decimator ---

    @Test
    fun decimatorHalvesLengthAndPassesSilence() {
        val decimator = Pcm48To24Decimator()
        val out = decimator.feed(ByteArray(1920 * 2))
        assertEquals(1920, out.size)
        assertTrue(isZeroPcm(out))
    }

    @Test
    fun decimatorPassesConstantSignal() {
        val decimator = Pcm48To24Decimator()
        // Hằng số 2000 ở 48 kHz: sau khi FIR ấm lên, đầu ra ~2000.
        val input = ByteArray(1920 * 2)
        for (i in 0 until 1920) {
            input[2 * i] = (2000 and 0xFF).toByte()
            input[2 * i + 1] = ((2000 shr 8) and 0xFF).toByte()
        }
        var last = ByteArray(0)
        repeat(4) { last = decimator.feed(input) }
        assertEquals(1920, last.size)
        val value = (last[100].toInt() and 0xFF) or (last[101].toInt() shl 8)
        assertTrue("expected ~2000, got ${value.toShort()}", abs(value.toShort().toInt() - 2000) < 60)
    }

    // --- PlaybackQueue ---

    @Test
    fun playbackQueueChunksAndTracksAudible() {
        val queue = PlaybackQueue(maxBytes = 10_000)
        val loud = ByteArray(1920)
        for (i in 0 until 960) {
            loud[2 * i] = (5000 and 0xFF).toByte()
            loud[2 * i + 1] = ((5000 shr 8) and 0xFF).toByte()
        }
        assertTrue(queue.offer(loud))
        assertEquals(1920, queue.queuedBytes())
        assertEquals(1, queue.audibleChunks())
        val chunk = queue.poll()
        assertNotNull(chunk)
        assertTrue(chunk!!.audible)
        assertEquals(0, queue.queuedBytes())
        assertNull(queue.poll())
    }

    @Test
    fun playbackQueueEnforcesBound() {
        val queue = PlaybackQueue(maxBytes = 1920)
        assertTrue(queue.offer(ByteArray(1920)))
        assertFalse(queue.offer(ByteArray(2)))
        queue.clear()
        assertTrue(queue.isEmpty())
        assertTrue(queue.offer(ByteArray(1920)))
    }

    // --- VoiceEvents: admission / append ---

    @Test
    fun admissionMatchesDesignWithoutThreadId() {
        val admission = VoiceEvents.buildAdmission("call-1", "homelink-aabbcc")
        assertEquals("hatch.voice.call.start", admission.getString("type"))
        assertEquals("call-1", admission.getString("call_id"))
        assertEquals("mcu-wearable", admission.getString("device"))
        assertEquals("homelink-aabbcc", admission.getString("device_id"))
        assertEquals("audio", admission.getString("call_media_type"))
        assertFalse(admission.getBoolean("proactive_greeting_enabled"))
        assertFalse(admission.has("thread_id"))
        val offer = VoiceEvents.buildCapabilityOffer()
        assertEquals("hatch.voice.capability.offer", offer.getString("type"))
        assertEquals(1, offer.getInt("end_call_stop"))
        val ndjson = String(VoiceEvents.admissionNdjson("call-1", "homelink-aabbcc"), Charsets.UTF_8)
        assertEquals(2, ndjson.trim().lines().size)
        assertTrue(ndjson.endsWith("\n"))
    }

    @Test
    fun appendEventRoundTripsBase64Pcm() {
        val pcm = ByteArray(1920) { (it % 251).toByte() }
        val json = JSONObject(String(VoiceEvents.appendEventJson(pcm, 123_456L), Charsets.UTF_8))
        assertEquals("input_audio_buffer.append", json.getString("type"))
        assertEquals(123_456L, json.getLong("send_timestamp_ms"))
        val decoded = VoiceEvents.audioFromEvent(
            JSONObject()
                .put("type", "response.output_audio.delta")
                .put("delta", json.getString("audio"))
        )
        assertTrue(pcm.contentEquals(decoded!!))
    }

    @Test
    fun voiceNoiseUrlAttachesAppIdOnUpgradeQuery() {
        assertEquals(
            "wss://hatch.metaaivm.com/v1/noise?vm_id=vm-1&app_id=hatch-web",
            VoiceCall.buildVoiceNoiseUrl("hatch.metaaivm.com", "vm-1", "hatch-web")
        )
        assertEquals(
            "wss://hatch.metaaivm.com/v1/noise?vm_id=vm-1",
            VoiceCall.buildVoiceNoiseUrl("hatch.metaaivm.com", "vm-1", "")
        )
    }

    // --- VoiceEvents: audio extraction ---

    @Test
    fun audioFromContinuousDeltaValidatesFormat() {
        val pcm = ByteArray(100) { 7 }
        val event = JSONObject()
            .put("type", "continuous_audio_video.delta")
            .put(
                "audio_delta", JSONObject()
                    .put("audio_frames", pcm.toByteString().base64())
                    .put("num_channels", 1)
                    .put("format", JSONObject().put("type", "audio/pcm").put("rate", 24000))
            )
        assertTrue(pcm.contentEquals(VoiceEvents.audioFromEvent(event)!!))
    }

    @Test
    fun audioFromEventRejectsWrongRateAndBadBase64() {
        val wrongRate = JSONObject()
            .put("type", "continuous_audio_video.delta")
            .put(
                "audio_delta", JSONObject()
                    .put("audio_frames", "AAAA")
                    .put("format", JSONObject().put("type", "audio/pcm").put("rate", 16000))
            )
        try {
            VoiceEvents.audioFromEvent(wrongRate)
            fail("expected wrong rate to throw")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
        val badBase64 = JSONObject()
            .put("type", "response.audio.delta")
            .put("delta", "!!!not-base64!!!")
        try {
            VoiceEvents.audioFromEvent(badBase64)
            fail("expected bad base64 to throw")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun audioFromEventIgnoresNonAudioAndZeroDelta() {
        assertNull(VoiceEvents.audioFromEvent(JSONObject().put("type", "response.created")))
        val zero = VoiceEvents.audioFromEvent(
            JSONObject()
                .put("type", "response.output_audio.delta")
                .put("delta", ByteArray(1920).toByteString().base64())
        )
        assertNotNull(zero)
        assertTrue(isZeroPcm(zero!!))
    }

    // --- VoiceEvents: readiness / stop control / end-call ack ---

    @Test
    fun readinessRequiresMatchingCallIdAndSessionId() {
        val event = JSONObject()
            .put("type", "hatch.voice.session")
            .put("call_id", "call-9")
            .put("session_id", "sess-1")
        assertEquals("sess-1", VoiceEvents.sessionIdFromReadiness(event, "call-9"))
        assertNull(VoiceEvents.sessionIdFromReadiness(event, "call-other"))
        val blank = JSONObject()
            .put("type", "hatch.voice.session")
            .put("call_id", "call-9")
            .put("session_id", "  ")
        assertNull(VoiceEvents.sessionIdFromReadiness(blank, "call-9"))
        assertNull(VoiceEvents.sessionIdFromReadiness(JSONObject().put("type", "response.created"), "call-9"))
    }

    @Test
    fun stopControlNeedsSessionControlItemAndControlId() {
        val byName = JSONObject()
            .put("type", "conversation.item.added")
            .put("item", JSONObject().put("type", "session_control").put("call_id", "ctl-1").put("name", " Stop "))
        assertTrue(VoiceEvents.isStopControl(byName))
        val byAction = JSONObject()
            .put("type", "conversation.item.added")
            .put("item", JSONObject().put("type", "session_control").put("call_id", "ctl-1").put("action", "stop"))
        assertTrue(VoiceEvents.isStopControl(byAction))
        val noControlId = JSONObject()
            .put("type", "conversation.item.added")
            .put("item", JSONObject().put("type", "session_control").put("name", "stop"))
        assertFalse(VoiceEvents.isStopControl(noControlId))
        val notControl = JSONObject()
            .put("type", "conversation.item.added")
            .put("item", JSONObject().put("type", "message").put("call_id", "ctl-1").put("name", "stop"))
        assertFalse(VoiceEvents.isStopControl(notControl))
    }

    @Test
    fun endCallAckIsStrictLikeSdk() {
        assertTrue(
            VoiceEvents.endCallAcknowledged(
                200, """{"ok":true,"status":200,"result":{"ok":true,"type":"hatch.voice.call.ended"}}"""
            )
        )
        // Probe Phase 0 nới lỏng (ok HOẶC result.ok) — VoiceCall thì không.
        assertFalse(VoiceEvents.endCallAcknowledged(200, """{"ok":true}"""))
        assertFalse(VoiceEvents.endCallAcknowledged(200, """{"result":{"ok":true}}"""))
        assertFalse(VoiceEvents.endCallAcknowledged(500, """{"ok":true,"result":{"ok":true}}"""))
        assertFalse(VoiceEvents.endCallAcknowledged(200, "not json"))
        val body = VoiceEvents.buildEndCallBody("call-1", 1_000L, 500L)
        assertEquals("call-1", body.getString("call_id"))
        // ended không được sớm hơn started + 1ms (đúng _cleanup của SDK).
        assertTrue(body.getString("call_ended_at") >= body.getString("call_started_at"))
    }

    // --- VoiceTurnTracker ---

    @Test
    fun turnTrackerSettlesWholeItemUpToResponse() {
        val tracker = VoiceTurnTracker()
        assertTrue(
            tracker.onTurnStarted(
                JSONObject().put("submission_message_id", "s1").put("item_id", "item-a")
            )
        )
        assertTrue(
            tracker.onTurnStarted(
                JSONObject().put("submission_message_id", "s2").put("item_id", "item-a")
            )
        )
        assertTrue(
            tracker.onTurnStarted(
                JSONObject().put("submission_message_id", "s3").put("item_id", "item-b")
            )
        )
        assertTrue(tracker.hasActiveTurns())
        // Settlement của s2 về hưu cả s1+s2 (cùng item, theo thứ tự tới).
        assertTrue(tracker.onTurnSettled(JSONObject().put("response_message_id", "s2")))
        assertTrue(tracker.hasActiveTurns()) // s3 (item-b) vẫn còn
        assertTrue(tracker.onTurnSettled(JSONObject().put("response_message_id", "s3")))
        assertFalse(tracker.hasActiveTurns())
        // Sự kiện lỗi định dạng không được tính.
        assertFalse(tracker.onTurnStarted(JSONObject().put("item_id", "item-x")))
        assertFalse(tracker.onTurnSettled(JSONObject().put("response_message_id", "unknown")))
    }
}
