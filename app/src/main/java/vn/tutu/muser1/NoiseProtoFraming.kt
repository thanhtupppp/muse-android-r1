package vn.tutu.muser1

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import kotlin.math.min

class ProtoException(message: String) : RuntimeException(message)

object ProtoCodec {
    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_DELIMITED = 2
    const val WIRE_FIXED32 = 5

    fun encodeKey(fieldNumber: Int, wireType: Int): ByteArray =
        encodeVarint(((fieldNumber.toLong() shl 3) or (wireType.toLong() and 0x07L)))

    fun encodeVarint(value: Long): ByteArray {
        val out = ByteArrayOutputStream(10)
        var v = value
        while ((v and -0x80L) != 0L) {
            out.write(((v and 0x7fL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7fL).toInt())
        return out.toByteArray()
    }

    fun varintField(fieldNumber: Int, value: Long): ByteArray =
        encodeKey(fieldNumber, WIRE_VARINT) + encodeVarint(value)

    fun delimitedField(fieldNumber: Int, payload: ByteArray): ByteArray =
        encodeKey(fieldNumber, WIRE_DELIMITED) + encodeVarint(payload.size.toLong()) + payload

    fun stringField(fieldNumber: Int, value: String): ByteArray =
        delimitedField(fieldNumber, value.toByteArray(Charsets.UTF_8))

    fun boolField(fieldNumber: Int, value: Boolean): ByteArray =
        varintField(fieldNumber, if (value) 1L else 0L)

    fun readVarint(data: ByteArray, startOffset: Int): Pair<Long, Int> {
        var offset = startOffset
        var value = 0L
        var shift = 0
        for (i in 0 until 10) {
            if (offset >= data.size) throw ProtoException("truncated varint")
            val b = data[offset].toInt() and 0xff
            offset += 1
            if (i == 9 && (b and 0xfe) != 0) throw ProtoException("malformed varint")
            value = value or ((b.toLong() and 0x7fL) shl shift)
            if ((b and 0x80) == 0) return value to offset
            shift += 7
        }
        throw ProtoException("malformed varint")
    }

    fun readKey(data: ByteArray, offset: Int): Triple<Int, Int, Int> {
        val (key, nextOffset) = readVarint(data, offset)
        val fieldNumber = (key ushr 3).toInt()
        val wireType = (key and 0x07L).toInt()
        if (fieldNumber <= 0) throw ProtoException("invalid field number: $fieldNumber")
        if (wireType !in intArrayOf(WIRE_VARINT, WIRE_FIXED64, WIRE_DELIMITED, WIRE_FIXED32)) {
            throw ProtoException("invalid wire type: $wireType")
        }
        return Triple(fieldNumber, wireType, nextOffset)
    }

    fun readDelimited(data: ByteArray, offset: Int): Pair<ByteArray, Int> {
        val (lenLong, next) = readVarint(data, offset)
        if (lenLong < 0 || lenLong > Int.MAX_VALUE) throw ProtoException("invalid delimited length")
        val length = lenLong.toInt()
        val end = next + length
        if (end < next || end > data.size) throw ProtoException("truncated delimited field")
        return data.copyOfRange(next, end) to end
    }

    fun skipField(data: ByteArray, offset: Int, wireType: Int): Int = when (wireType) {
        WIRE_VARINT -> readVarint(data, offset).second
        WIRE_FIXED64 -> {
            val end = offset + 8
            if (end > data.size) throw ProtoException("truncated fixed64 field")
            end
        }
        WIRE_DELIMITED -> readDelimited(data, offset).second
        WIRE_FIXED32 -> {
            val end = offset + 4
            if (end > data.size) throw ProtoException("truncated fixed32 field")
            end
        }
        else -> throw ProtoException("invalid wire type: $wireType")
    }
}

data class NoiseTransportFrame(
    val chunkId: Long,
    val chunkIndex: Int,
    val totalChunks: Int,
    val payload: ByteArray
) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        if (chunkId != 0L) out.write(ProtoCodec.varintField(1, chunkId))
        if (chunkIndex != 0) out.write(ProtoCodec.varintField(2, chunkIndex.toLong() and 0xffffffffL))
        if (totalChunks != 0) out.write(ProtoCodec.varintField(3, totalChunks.toLong() and 0xffffffffL))
        if (payload.isNotEmpty()) out.write(ProtoCodec.delimitedField(4, payload))
        return out.toByteArray()
    }

    companion object {
        const val MAX_CHUNK_PAYLOAD = 65489
        const val MAX_TOTAL_CHUNKS = 1024
        const val MAX_FRAME_BYTES = 16 * 1024 * 1024

        fun decode(data: ByteArray): NoiseTransportFrame {
            var chunkId = 0L
            var chunkIndex = 0
            var totalChunks = 0
            var payload = ByteArray(0)
            var offset = 0
            while (offset < data.size) {
                val (fieldNumber, wireType, nextOffset) = ProtoCodec.readKey(data, offset)
                offset = nextOffset
                when (fieldNumber) {
                    1 -> {
                        if (wireType != ProtoCodec.WIRE_VARINT) throw ProtoException("chunk_id wire type")
                        val (v, n) = ProtoCodec.readVarint(data, offset)
                        chunkId = v
                        offset = n
                    }
                    2 -> {
                        if (wireType != ProtoCodec.WIRE_VARINT) throw ProtoException("chunk_index wire type")
                        val (v, n) = ProtoCodec.readVarint(data, offset)
                        chunkIndex = v.toInt()
                        offset = n
                    }
                    3 -> {
                        if (wireType != ProtoCodec.WIRE_VARINT) throw ProtoException("total_chunks wire type")
                        val (v, n) = ProtoCodec.readVarint(data, offset)
                        totalChunks = v.toInt()
                        offset = n
                    }
                    4 -> {
                        if (wireType != ProtoCodec.WIRE_DELIMITED) throw ProtoException("payload wire type")
                        val (b, n) = ProtoCodec.readDelimited(data, offset)
                        payload = b
                        offset = n
                    }
                    else -> offset = ProtoCodec.skipField(data, offset, wireType)
                }
            }
            return NoiseTransportFrame(chunkId, chunkIndex, totalChunks, payload)
        }

        fun encodeFrames(data: ByteArray, chunkId: Long = SecureRandom().nextLong().let { if (it == 0L) 1L else it }): List<ByteArray> {
            val totalChunks = maxOf(1, (data.size + MAX_CHUNK_PAYLOAD - 1) / MAX_CHUNK_PAYLOAD)
            if (totalChunks > MAX_TOTAL_CHUNKS) {
                throw IllegalArgumentException("Payload too large for noise framing ($totalChunks chunks)")
            }
            if (data.isEmpty()) {
                return listOf(NoiseTransportFrame(chunkId, 0, 1, ByteArray(0)).encode())
            }
            return (0 until totalChunks).map { idx ->
                val start = idx * MAX_CHUNK_PAYLOAD
                val end = min(data.size, start + MAX_CHUNK_PAYLOAD)
                NoiseTransportFrame(chunkId, idx, totalChunks, data.copyOfRange(start, end)).encode()
            }
        }
    }
}

class NoiseFrameDecoder {
    private data class Assembly(
        val totalChunks: Int,
        val chunks: MutableMap<Int, ByteArray> = HashMap(),
        var totalBytes: Int = 0
    )

    private val pending = HashMap<Long, Assembly>()

    fun decode(frameBytes: ByteArray): ByteArray? {
        val frame = NoiseTransportFrame.decode(frameBytes)
        if (frame.totalChunks < 1 || frame.totalChunks > NoiseTransportFrame.MAX_TOTAL_CHUNKS) {
            throw NoiseProtocolException("Invalid totalChunks: ${frame.totalChunks}")
        }
        if (frame.chunkIndex < 0 || frame.chunkIndex >= frame.totalChunks) {
            throw NoiseProtocolException("Invalid chunkIndex: ${frame.chunkIndex}")
        }
        if (frame.totalChunks == 1 && frame.chunkIndex == 0 && !pending.containsKey(frame.chunkId)) {
            return frame.payload
        }
        val assembly = pending.getOrPut(frame.chunkId) { Assembly(frame.totalChunks) }
        if (assembly.totalChunks != frame.totalChunks) {
            pending.remove(frame.chunkId)
            throw NoiseProtocolException("Mismatched totalChunks for chunkId=${frame.chunkId}")
        }
        if (!assembly.chunks.containsKey(frame.chunkIndex)) {
            assembly.totalBytes += frame.payload.size
            if (assembly.totalBytes > NoiseTransportFrame.MAX_FRAME_BYTES) {
                pending.remove(frame.chunkId)
                throw NoiseProtocolException("Noise frame exceeds maximum bytes")
            }
            assembly.chunks[frame.chunkIndex] = frame.payload
        }
        if (assembly.chunks.size < assembly.totalChunks) {
            return null
        }
        pending.remove(frame.chunkId)
        val out = ByteArrayOutputStream(assembly.totalBytes)
        for (i in 0 until assembly.totalChunks) {
            val part = assembly.chunks[i] ?: throw NoiseProtocolException("Missing chunk $i")
            out.write(part)
        }
        return out.toByteArray()
    }
}

data class NoiseHeader(val key: String, val value: String) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        if (key.isNotEmpty()) out.write(ProtoCodec.stringField(1, key))
        if (value.isNotEmpty()) out.write(ProtoCodec.stringField(2, value))
        return out.toByteArray()
    }

    companion object {
        fun decode(data: ByteArray): NoiseHeader {
            var key = ""
            var value = ""
            var offset = 0
            while (offset < data.size) {
                val (f, w, next) = ProtoCodec.readKey(data, offset)
                offset = next
                when (f) {
                    1 -> {
                        if (w != ProtoCodec.WIRE_DELIMITED) throw ProtoException("Header.key wire")
                        val (b, n) = ProtoCodec.readDelimited(data, offset)
                        key = b.toString(Charsets.UTF_8)
                        offset = n
                    }
                    2 -> {
                        if (w != ProtoCodec.WIRE_DELIMITED) throw ProtoException("Header.value wire")
                        val (b, n) = ProtoCodec.readDelimited(data, offset)
                        value = b.toString(Charsets.UTF_8)
                        offset = n
                    }
                    else -> offset = ProtoCodec.skipField(data, offset, w)
                }
            }
            return NoiseHeader(key, value)
        }
    }
}

data class ApplicationRequest(
    val verb: String,
    val path: String,
    val headers: List<NoiseHeader> = emptyList(),
    val body: ByteArray = ByteArray(0),
    val endBody: Boolean = false
) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        if (verb.isNotEmpty()) out.write(ProtoCodec.stringField(1, verb))
        if (path.isNotEmpty()) out.write(ProtoCodec.stringField(2, path))
        for (h in headers) out.write(ProtoCodec.delimitedField(3, h.encode()))
        if (body.isNotEmpty()) out.write(ProtoCodec.delimitedField(4, body))
        if (endBody) out.write(ProtoCodec.boolField(5, true))
        return out.toByteArray()
    }
}

data class ApplicationResponse(
    val status: Int,
    val headers: List<NoiseHeader> = emptyList(),
    val body: ByteArray = ByteArray(0),
    val endBody: Boolean = false
) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        if (status != 0) out.write(ProtoCodec.varintField(1, status.toLong()))
        for (h in headers) out.write(ProtoCodec.delimitedField(2, h.encode()))
        if (body.isNotEmpty()) out.write(ProtoCodec.delimitedField(3, body))
        if (endBody) out.write(ProtoCodec.boolField(4, true))
        return out.toByteArray()
    }

    companion object {
        fun decode(data: ByteArray): ApplicationResponse {
            var status = 0
            val headers = mutableListOf<NoiseHeader>()
            var body = ByteArray(0)
            var endBody = false
            var offset = 0
            while (offset < data.size) {
                val (f, w, next) = ProtoCodec.readKey(data, offset)
                offset = next
                when (f) {
                    1 -> {
                        if (w != ProtoCodec.WIRE_VARINT) throw ProtoException("AppResp.status wire")
                        val (v, n) = ProtoCodec.readVarint(data, offset)
                        status = v.toInt()
                        offset = n
                    }
                    2 -> {
                        if (w != ProtoCodec.WIRE_DELIMITED) throw ProtoException("AppResp.headers wire")
                        val (b, n) = ProtoCodec.readDelimited(data, offset)
                        headers += NoiseHeader.decode(b)
                        offset = n
                    }
                    3 -> {
                        if (w != ProtoCodec.WIRE_DELIMITED) throw ProtoException("AppResp.body wire")
                        val (b, n) = ProtoCodec.readDelimited(data, offset)
                        body = b
                        offset = n
                    }
                    4 -> {
                        if (w != ProtoCodec.WIRE_VARINT) throw ProtoException("AppResp.end_body wire")
                        val (v, n) = ProtoCodec.readVarint(data, offset)
                        endBody = v != 0L
                        offset = n
                    }
                    else -> offset = ProtoCodec.skipField(data, offset, w)
                }
            }
            return ApplicationResponse(status, headers, body, endBody)
        }
    }
}

data class BodyChunk(val data: ByteArray, val endBody: Boolean = false) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        if (data.isNotEmpty()) out.write(ProtoCodec.delimitedField(1, data))
        if (endBody) out.write(ProtoCodec.boolField(2, true))
        return out.toByteArray()
    }

    companion object {
        fun decode(data: ByteArray): BodyChunk {
            var payload = ByteArray(0)
            var endBody = false
            var offset = 0
            while (offset < data.size) {
                val (f, w, next) = ProtoCodec.readKey(data, offset)
                offset = next
                when (f) {
                    1 -> {
                        if (w != ProtoCodec.WIRE_DELIMITED) throw ProtoException("BodyChunk.data wire")
                        val (b, n) = ProtoCodec.readDelimited(data, offset)
                        payload = b
                        offset = n
                    }
                    2 -> {
                        if (w != ProtoCodec.WIRE_VARINT) throw ProtoException("BodyChunk.end_body wire")
                        val (v, n) = ProtoCodec.readVarint(data, offset)
                        endBody = v != 0L
                        offset = n
                    }
                    else -> offset = ProtoCodec.skipField(data, offset, w)
                }
            }
            return BodyChunk(payload, endBody)
        }
    }
}

data class StreamReset(val code: Int = CANCELLED, val reason: String = "") {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        if (code != 0) out.write(ProtoCodec.varintField(1, code.toLong()))
        if (reason.isNotEmpty()) out.write(ProtoCodec.stringField(2, reason))
        return out.toByteArray()
    }

    companion object {
        const val CANCELLED = 1

        fun decode(data: ByteArray): StreamReset {
            var code = 0
            var reason = ""
            var offset = 0
            while (offset < data.size) {
                val (f, w, next) = ProtoCodec.readKey(data, offset)
                offset = next
                when (f) {
                    1 -> {
                        if (w != ProtoCodec.WIRE_VARINT) throw ProtoException("Reset.code wire")
                        val (v, n) = ProtoCodec.readVarint(data, offset)
                        code = v.toInt()
                        offset = n
                    }
                    2 -> {
                        if (w != ProtoCodec.WIRE_DELIMITED) throw ProtoException("Reset.reason wire")
                        val (b, n) = ProtoCodec.readDelimited(data, offset)
                        reason = b.toString(Charsets.UTF_8)
                        offset = n
                    }
                    else -> offset = ProtoCodec.skipField(data, offset, w)
                }
            }
            return StreamReset(code, reason)
        }
    }
}

data class ServiceFrame(
    val streamId: Long,
    val request: ApplicationRequest? = null,
    val response: ApplicationResponse? = null,
    val bodyChunk: BodyChunk? = null,
    val reset: StreamReset? = null
) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        if (streamId != 0L) out.write(ProtoCodec.varintField(1, streamId))
        request?.let { out.write(ProtoCodec.delimitedField(2, it.encode())) }
        response?.let { out.write(ProtoCodec.delimitedField(3, it.encode())) }
        bodyChunk?.let { out.write(ProtoCodec.delimitedField(4, it.encode())) }
        reset?.let { out.write(ProtoCodec.delimitedField(5, it.encode())) }
        return out.toByteArray()
    }

    companion object {
        fun decode(data: ByteArray): ServiceFrame {
            var streamId = 0L
            var response: ApplicationResponse? = null
            var bodyChunk: BodyChunk? = null
            var reset: StreamReset? = null
            var offset = 0
            while (offset < data.size) {
                val (f, w, next) = ProtoCodec.readKey(data, offset)
                offset = next
                when (f) {
                    1 -> {
                        if (w != ProtoCodec.WIRE_VARINT) throw ProtoException("ServiceFrame.stream_id wire")
                        val (v, n) = ProtoCodec.readVarint(data, offset)
                        streamId = v
                        offset = n
                    }
                    3 -> {
                        if (w != ProtoCodec.WIRE_DELIMITED) throw ProtoException("ServiceFrame.response wire")
                        val (b, n) = ProtoCodec.readDelimited(data, offset)
                        response = ApplicationResponse.decode(b)
                        offset = n
                    }
                    4 -> {
                        if (w != ProtoCodec.WIRE_DELIMITED) throw ProtoException("ServiceFrame.body_chunk wire")
                        val (b, n) = ProtoCodec.readDelimited(data, offset)
                        bodyChunk = BodyChunk.decode(b)
                        offset = n
                    }
                    5 -> {
                        if (w != ProtoCodec.WIRE_DELIMITED) throw ProtoException("ServiceFrame.reset wire")
                        val (b, n) = ProtoCodec.readDelimited(data, offset)
                        reset = StreamReset.decode(b)
                        offset = n
                    }
                    else -> offset = ProtoCodec.skipField(data, offset, w)
                }
            }
            return ServiceFrame(streamId = streamId, response = response, bodyChunk = bodyChunk, reset = reset)
        }
    }
}

object ServiceEnvelopeCodec {
    const val SERVICE_DAEMON = 0

    fun encodeServiceRequest(service: Int = SERVICE_DAEMON, framePayload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        if (service != 0) out.write(ProtoCodec.varintField(1, service.toLong()))
        if (framePayload.isNotEmpty()) out.write(ProtoCodec.delimitedField(2, framePayload))
        return out.toByteArray()
    }

    fun decodeServiceResponse(data: ByteArray): ByteArray {
        var payload = ByteArray(0)
        var offset = 0
        while (offset < data.size) {
            val (f, w, next) = ProtoCodec.readKey(data, offset)
            offset = next
            when (f) {
                1 -> {
                    if (w != ProtoCodec.WIRE_DELIMITED) throw ProtoException("ServiceResponse.payload wire")
                    val (b, n) = ProtoCodec.readDelimited(data, offset)
                    payload = b
                    offset = n
                }
                else -> offset = ProtoCodec.skipField(data, offset, w)
            }
        }
        return payload
    }
}

class LengthPrefixedJsonDecoder {
    private var buffer = ByteArray(0)

    @Synchronized
    fun push(chunk: ByteArray): List<String> {
        if (chunk.isEmpty()) return emptyList()
        buffer += chunk
        val out = mutableListOf<String>()
        var offset = 0
        while (buffer.size - offset >= 4) {
            val lenLong = ByteBuffer.wrap(buffer, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            if (lenLong > 2L * 1024L * 1024L) {
                throw NoiseProtocolException("Length-prefixed JSON message too large: $lenLong")
            }
            val len = lenLong.toInt()
            if (buffer.size - offset - 4 < len) break
            val msgStart = offset + 4
            out += buffer.copyOfRange(msgStart, msgStart + len).toString(Charsets.UTF_8)
            offset = msgStart + len
        }
        if (offset > 0) {
            buffer = buffer.copyOfRange(offset, buffer.size)
        }
        return out
    }

    companion object {
        fun encode(json: String): ByteArray {
            val bytes = json.toByteArray(Charsets.UTF_8)
            val buf = ByteBuffer.allocate(4 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(bytes.size)
            buf.put(bytes)
            return buf.array()
        }
    }
}

class NoiseTransport(
    private val sendCipher: NoiseCipherState,
    private val recvCipher: NoiseCipherState
) {
    private val decoder = NoiseFrameDecoder()
    private var nextStreamId = 1L

    @Synchronized
    fun startStreamRequest(
        verb: String,
        path: String,
        headers: List<NoiseHeader> = emptyList(),
        service: Int = ServiceEnvelopeCodec.SERVICE_DAEMON
    ): Pair<Long, List<ByteArray>> {
        val streamId = nextStreamId++
        val frame = ServiceFrame(
            streamId = streamId,
            request = ApplicationRequest(verb = verb, path = path, headers = headers, endBody = false)
        )
        return streamId to encryptServiceFrame(service, frame)
    }

    @Synchronized
    fun encryptHttpRequest(
        verb: String,
        path: String,
        body: ByteArray = ByteArray(0),
        headers: List<NoiseHeader> = emptyList(),
        service: Int = ServiceEnvelopeCodec.SERVICE_DAEMON
    ): Pair<Long, List<ByteArray>> {
        val streamId = nextStreamId++
        val frame = ServiceFrame(
            streamId = streamId,
            request = ApplicationRequest(verb = verb, path = path, headers = headers, body = body, endBody = true)
        )
        return streamId to encryptServiceFrame(service, frame)
    }

    @Synchronized
    fun encryptBodyChunk(
        streamId: Long,
        data: ByteArray,
        endBody: Boolean = false,
        service: Int = ServiceEnvelopeCodec.SERVICE_DAEMON
    ): List<ByteArray> =
        encryptServiceFrame(service, ServiceFrame(streamId = streamId, bodyChunk = BodyChunk(data, endBody)))

    @Synchronized
    fun encryptReset(
        streamId: Long,
        reason: String = "",
        code: Int = StreamReset.CANCELLED,
        service: Int = ServiceEnvelopeCodec.SERVICE_DAEMON
    ): List<ByteArray> =
        encryptServiceFrame(service, ServiceFrame(streamId = streamId, reset = StreamReset(code, reason)))

    @Synchronized
    fun decryptFrame(ciphertext: ByteArray): ServiceFrame? {
        val plainFrame = recvCipher.decryptWithAd(ByteArray(0), ciphertext)
        val reassembled = decoder.decode(plainFrame) ?: return null
        val respPayload = ServiceEnvelopeCodec.decodeServiceResponse(reassembled)
        if (respPayload.isEmpty()) throw NoiseProtocolException("Empty ServiceResponse payload")
        return ServiceFrame.decode(respPayload)
    }

    private fun encryptServiceFrame(service: Int, frame: ServiceFrame): List<ByteArray> {
        val frameBytes = frame.encode()
        val reqBytes = ServiceEnvelopeCodec.encodeServiceRequest(service, frameBytes)
        val protoFrames = NoiseTransportFrame.encodeFrames(reqBytes)
        return protoFrames.map { sendCipher.encryptWithAd(ByteArray(0), it) }
    }
}

