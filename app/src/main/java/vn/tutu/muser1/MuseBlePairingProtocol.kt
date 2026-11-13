package vn.tutu.muser1

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Meta Muse / Hatch Link BLE Pairing Protocol v5 + 0xFE chunk framing.
 * Matches D:\muse-gadget-sdk\linux\src\musegadget\{ble_framing.py,pairing.py,ble_setup.py}
 * and D:\muse-gadget-sdk\esp32\main\{ble_server.c,link_pairing.c}.
 * Pure Kotlin + standard JCA so it runs both on Android 5.1.1 (API 22) and JVM unit tests.
 */
object MuseBleFramer {
    const val FRAME_MAGIC = 0xFE
    const val HEADER_BYTES = 3
    const val DEFAULT_ATT_MTU = 23
    const val MAX_PACKET_BYTES = 160
    const val MAX_CHUNKS = 255
    const val MAX_MESSAGE_BYTES = 8192

    fun encodeChunks(payload: ByteArray, attMtu: Int = MAX_PACKET_BYTES): List<ByteArray> {
        require(payload.size <= MAX_MESSAGE_BYTES) { "BLE message exceeds $MAX_MESSAGE_BYTES bytes" }
        val maxPacket = min(MAX_PACKET_BYTES, maxOf(20, attMtu - 3))
        val chunkPayloadMax = maxOf(1, maxPacket - HEADER_BYTES)
        val totalChunks = maxOf(1, (payload.size + chunkPayloadMax - 1) / chunkPayloadMax)
        require(totalChunks <= MAX_CHUNKS) { "BLE message requires $totalChunks chunks (> $MAX_CHUNKS)" }

        if (payload.isEmpty()) {
            return listOf(byteArrayOf(FRAME_MAGIC.toByte(), 0, 1))
        }
        return (0 until totalChunks).map { idx ->
            val start = idx * chunkPayloadMax
            val end = min(payload.size, start + chunkPayloadMax)
            val partLen = end - start
            ByteArray(HEADER_BYTES + partLen).also { packet ->
                packet[0] = FRAME_MAGIC.toByte()
                packet[1] = idx.toByte()
                packet[2] = totalChunks.toByte()
                payload.copyInto(packet, destinationOffset = HEADER_BYTES, startIndex = start, endIndex = end)
            }
        }
    }

    class RxAssembler {
        private var chunks = HashMap<Int, ByteArray>()
        private var expectedTotal = 0
        private var accumulatedBytes = 0

        @Synchronized
        fun reset() {
            chunks.clear()
            expectedTotal = 0
            accumulatedBytes = 0
        }

        @Synchronized
        fun push(packet: ByteArray): ByteArray? {
            if (packet.isEmpty()) return null
            if ((packet[0].toInt() and 0xff) != FRAME_MAGIC) {
                reset()
                return if (packet.size <= MAX_MESSAGE_BYTES) packet.copyOf() else null
            }
            if (packet.size < HEADER_BYTES) {
                reset()
                return null
            }
            val index = packet[1].toInt() and 0xff
            val total = packet[2].toInt() and 0xff
            if (total < 1 || total > MAX_CHUNKS || index >= total) {
                reset()
                return null
            }
            if (expectedTotal != 0 && expectedTotal != total) {
                reset()
            }
            expectedTotal = total
            val body = packet.copyOfRange(HEADER_BYTES, packet.size)
            if (!chunks.containsKey(index)) {
                accumulatedBytes += body.size
                if (accumulatedBytes > MAX_MESSAGE_BYTES) {
                    reset()
                    return null
                }
                chunks[index] = body
            }
            if (chunks.size < expectedTotal) {
                return null
            }
            val out = ByteArrayOutputStream(accumulatedBytes)
            for (i in 0 until expectedTotal) {
                val part = chunks[i] ?: run {
                    reset()
                    return null
                }
                out.write(part)
            }
            reset()
            return out.toByteArray()
        }
    }
}

object Base64UrlCodec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val DECODE_TABLE = IntArray(256) { -1 }.apply {
        ALPHABET.forEachIndexed { idx, ch -> this[ch.code] = idx }
        this['+'.code] = 62
        this['/'.code] = 63
    }

    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else -1
            sb.append(ALPHABET[b0 ushr 2])
            if (b1 >= 0) {
                sb.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                if (b2 >= 0) {
                    sb.append(ALPHABET[((b1 and 0x0f) shl 2) or (b2 ushr 6)])
                    sb.append(ALPHABET[b2 and 0x3f])
                } else {
                    sb.append(ALPHABET[(b1 and 0x0f) shl 2])
                }
            } else {
                sb.append(ALPHABET[(b0 and 0x03) shl 4])
            }
            i += 3
        }
        return sb.toString()
    }

    fun decode(input: String): ByteArray {
        val trimmed = input.trimEnd('=')
        if (trimmed.isEmpty()) return ByteArray(0)
        require(trimmed.length % 4 != 1) { "Invalid base64url length" }
        val outLen = (trimmed.length * 3) / 4
        val out = ByteArray(outLen)
        var outIdx = 0
        var i = 0
        while (i < trimmed.length) {
            val c0 = decodeChar(trimmed[i])
            val c1 = decodeChar(trimmed[i + 1])
            out[outIdx++] = ((c0 shl 2) or (c1 ushr 4)).toByte()
            if (i + 2 < trimmed.length) {
                val c2 = decodeChar(trimmed[i + 2])
                out[outIdx++] = (((c1 and 0x0f) shl 4) or (c2 ushr 2)).toByte()
                if (i + 3 < trimmed.length) {
                    val c3 = decodeChar(trimmed[i + 3])
                    out[outIdx++] = (((c2 and 0x03) shl 6) or c3).toByte()
                }
            }
            i += 4
        }
        return out
    }

    private fun decodeChar(ch: Char): Int {
        val code = ch.code
        val v = if (code in 0..255) DECODE_TABLE[code] else -1
        require(v >= 0) { "Invalid base64url char: $ch" }
        return v
    }
}

object TinyJson {
    fun encode(value: Any?): String = buildString { appendValue(this, value) }

    private fun appendValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Number -> sb.append(value.toString())
            is String -> appendString(sb, value)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    appendString(sb, k.toString())
                    sb.append(':')
                    appendValue(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    appendValue(sb, item)
                }
                sb.append(']')
            }
            else -> appendString(sb, value.toString())
        }
    }

    private fun appendString(sb: StringBuilder, text: String) {
        sb.append('"')
        for (ch in text) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u%04x".format(ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(json: String): Map<String, Any?> {
        val parser = Parser(json)
        val res = parser.readValue()
        parser.skipWs()
        require(parser.isEof()) { "Trailing characters in JSON" }
        return res as? Map<String, Any?> ?: throw IllegalArgumentException("Expected JSON object")
    }

    private class Parser(private val src: String) {
        private var pos = 0

        fun isEof(): Boolean = pos >= src.length

        fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            skipWs()
            if (pos >= src.length) throw IllegalArgumentException("Unexpected EOF")
            return when (val ch = src[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                '-', in '0'..'9' -> readNumber()
                else -> throw IllegalArgumentException("Unexpected char '$ch' at $pos")
            }
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < src.length && src[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                if (pos >= src.length || src[pos] != '"') throw IllegalArgumentException("Expected key at $pos")
                val key = readString()
                skipWs()
                if (pos >= src.length || src[pos] != ':') throw IllegalArgumentException("Expected ':' at $pos")
                pos++
                map[key] = readValue()
                skipWs()
                if (pos >= src.length) throw IllegalArgumentException("Unterminated object")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(): List<Any?> {
            pos++ // '['
            val list = ArrayList<Any?>()
            skipWs()
            if (pos < src.length && src[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list += readValue()
                skipWs()
                if (pos >= src.length) throw IllegalArgumentException("Unterminated array")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun readString(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) throw IllegalArgumentException("Unterminated escape")
                        when (val esc = src[pos++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) throw IllegalArgumentException("Bad unicode escape")
                                sb.append(src.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Invalid escape \\$esc")
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun readNumber(): Number {
            val start = pos
            if (src[pos] == '-') pos++
            while (pos < src.length && src[pos].isDigit()) pos++
            var isFloat = false
            if (pos < src.length && src[pos] == '.') {
                isFloat = true
                pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            val token = src.substring(start, pos)
            return if (isFloat) token.toDouble() else {
                val l = token.toLong()
                if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else l
            }
        }

        private fun expect(lit: String) {
            if (!src.startsWith(lit, pos)) throw IllegalArgumentException("Expected $lit at $pos")
            pos += lit.length
        }
    }
}

class MuseBlePairingProtocol(
    deviceId: String,
    nodeId: String,
    macAddress: String,
    private val firmwareVersion: String,
    private val sdkToken: String? = null,
    private val random: SecureRandom = SecureRandom(),
    private val onWifiScan: () -> List<WifiEntry> = { emptyList() },
    private val onProvisionV2: (ProvisionV2Request, sendStatus: (String) -> Unit) -> Boolean = { _, _ -> true }
) {
    private val deviceIdLower = deviceId.lowercase()
    private val nodeIdLower = nodeId.lowercase()
    private val macLower = macAddress.lowercase()

    data class WifiEntry(val ssid: String, val rssi: Int, val secure: Boolean)

    data class ProvisionV2Request(
        val ssid: String,
        val password: String,
        val accessToken: String,
        val refreshToken: String,
        val tokenType: String,
        val username: String?,
        val apiUrl: String?,
        val apiUrlV2: String?,
        val noiseHost: String?
    )

    companion object {
        const val PAIRING_VERSION = 5
        const val MODEL = "hatch_link"
        const val CIPHER_SUITE = "p256-hkdf-sha256-aes-gcm-v1"
        const val PAIRING_AUTH = "none"
        const val POLICY_APP = "confirm_app"
        const val POLICY_PRESS = "confirm_press"
        const val RECORD_LABEL = "hatch-link ble setup v1"
        val SESSION_ID_LABEL = "hatch-link session id v1".toByteArray(Charsets.US_ASCII)

        fun sha256(vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            for (p in parts) md.update(p)
            return md.digest()
        }

        fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            for (p in parts) mac.update(p)
            return mac.doFinal()
        }

        fun hkdfExpandOneBlock(prk: ByteArray, info: ByteArray): ByteArray =
            hmacSha256(prk, info, byteArrayOf(0x01))

        fun hkdf32(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
            val prk = hmacSha256(salt, ikm)
            return try {
                hkdfExpandOneBlock(prk, info)
            } finally {
                prk.fill(0)
            }
        }

        fun encodeUncompressedP256Point(pub: ECPublicKey): ByteArray {
            val w = pub.w
            val x = bigIntTo32Bytes(w.affineX)
            val y = bigIntTo32Bytes(w.affineY)
            return ByteArray(65).apply {
                this[0] = 0x04
                x.copyInto(this, destinationOffset = 1)
                y.copyInto(this, destinationOffset = 33)
            }
        }

        fun bigIntTo32Bytes(v: BigInteger): ByteArray {
            val raw = v.toByteArray()
            val out = ByteArray(32)
            if (raw.size <= 32) {
                raw.copyInto(out, destinationOffset = 32 - raw.size)
            } else {
                raw.copyInto(out, startIndex = raw.size - 32)
            }
            return out
        }

        fun buildTranscript(
            deviceId: String,
            nodeId: String,
            mac: String,
            firmwareVersion: String,
            policy: String,
            confirmTimeoutSeconds: Int,
            mobilePubB64: String,
            devicePubB64: String,
            mobileNonceB64: String,
            deviceNonceB64: String
        ): String = listOf(
            "hatch-link-pairing-v$PAIRING_VERSION",
            "version=$PAIRING_VERSION",
            "initiator_role=mobile",
            "responder_role=link",
            "device_id=${deviceId.lowercase()}",
            "node_id=${nodeId.lowercase()}",
            "mac=${mac.lowercase()}",
            "model=$MODEL",
            "firmware_version=$firmwareVersion",
            "selected_cipher_suite=$CIPHER_SUITE",
            "pairing_auth=$PAIRING_AUTH",
            "pairing_auth_epoch=0",
            "pairing_policy=$policy",
            "confirm_timeout_seconds=$confirmTimeoutSeconds",
            "mobile_pub=$mobilePubB64",
            "device_pub=$devicePubB64",
            "mobile_nonce=$mobileNonceB64",
            "device_nonce=$deviceNonceB64"
        ).joinToString("\n")
    }

    private enum class State {
        IDLE,
        WAIT_CLIENT_FINISHED,
        READY,
        PROVISIONING
    }

    private var state = State.IDLE
    private var sessionIdB64: String = ""
    private var rxKey: ByteArray? = null
    private var txKey: ByteArray? = null
    private var rxCounter = 0L
    private var txCounter = 0L
    private var plaintextStatusBlocked = false

    @Synchronized
    fun resetSession() {
        rxKey?.fill(0)
        txKey?.fill(0)
        rxKey = null
        txKey = null
        sessionIdB64 = ""
        rxCounter = 0L
        txCounter = 0L
        state = State.IDLE
        plaintextStatusBlocked = false
    }

    /**
     * Handles an incoming JSON command and invokes [emitJson] with 0 or more JSON responses
     * (plaintext before session establishment, or `pairing_encrypted` envelopes afterward).
     */
    fun handleCommand(jsonText: String, emitJson: (String) -> Unit) {
        val root = try {
            TinyJson.parseObject(jsonText)
        } catch (_: Exception) {
            emitStatus("error_invalid_command", forcePlaintext = false, emitJson)
            return
        }
        dispatch(root, decrypted = false, emitJson = emitJson)
    }

    private fun dispatch(root: Map<String, Any?>, decrypted: Boolean, emitJson: (String) -> Unit) {
        val action = root["action"] as? String ?: ""
        if (!decrypted && action == "get_device_info") {
            val info = linkedMapOf<String, Any?>(
                "type" to "device_info",
                "node_id" to nodeIdLower,
                "version" to firmwareVersion,
                "device_id" to deviceIdLower,
                "mac" to macLower,
                "model" to MODEL,
                "pairing_protocol" to PAIRING_VERSION,
                "pairing_auth" to PAIRING_AUTH,
                "pairing_auth_epoch" to 0,
                "pairing_policy" to POLICY_APP
            )
            emitJson(TinyJson.encode(info))
            return
        }

        if (!decrypted && action == "pairing_client_hello") {
            handleClientHello(root, emitJson)
            return
        }

        if (!decrypted && action == "pairing_encrypted") {
            val plain = decryptCommand(root)
            if (plain == null) {
                emitStatus("error_pairing_decrypt", forcePlaintext = true, emitJson)
                resetSession()
                return
            }
            val inner = try {
                TinyJson.parseObject(plain)
            } catch (_: Exception) {
                emitStatus("error_invalid_command", forcePlaintext = false, emitJson)
                return
            }
            dispatch(inner, decrypted = true, emitJson = emitJson)
            return
        }

        if (!decrypted) {
            if (plaintextStatusBlocked) return
            emitStatus(
                if (action in setOf("wifi_scan", "provision", "provision_v2", "unpair")) {
                    "error_encryption_required"
                } else {
                    "error_unknown_action"
                },
                forcePlaintext = true,
                emitJson
            )
            return
        }

        when (action) {
            "pairing_client_finished" -> handleClientFinished(emitJson)
            "wifi_scan" -> handleWifiScan(emitJson)
            "provision_v2" -> handleProvisionV2(root, emitJson)
            "unpair" -> {
                emitEncryptedStatus("unpaired", emitJson)
                resetSession()
            }
            else -> emitEncryptedStatus("error_unknown_action", emitJson)
        }
    }

    @Synchronized
    private fun handleClientHello(root: Map<String, Any?>, emitJson: (String) -> Unit) {
        resetSession()

        val version = (root["version"] as? Number)?.toInt()
        val pairingAuth = root["pairing_auth"] as? String
        val requestedPolicy = (root["pairing_policy"] as? String)?.ifBlank { POLICY_APP } ?: POLICY_APP
        val mobilePubB64Raw = root["mobile_pub"] as? String
        val mobileNonceB64Raw = root["mobile_nonce"] as? String

        if (version != PAIRING_VERSION ||
            pairingAuth != PAIRING_AUTH ||
            (requestedPolicy != POLICY_APP && requestedPolicy != POLICY_PRESS) ||
            mobilePubB64Raw.isNullOrBlank() ||
            mobileNonceB64Raw.isNullOrBlank()
        ) {
            emitJson(statusJson("error_pairing_invalid_hello"))
            return
        }

        val mobilePubBytes = runCatching { Base64UrlCodec.decode(mobilePubB64Raw) }.getOrNull()
        val mobileNonceBytes = runCatching { Base64UrlCodec.decode(mobileNonceB64Raw) }.getOrNull()
        if (mobilePubBytes == null || mobilePubBytes.size != 65 || mobilePubBytes[0] != 0x04.toByte() ||
            mobileNonceBytes == null || mobileNonceBytes.size != 16
        ) {
            emitJson(statusJson("error_pairing_invalid_hello"))
            return
        }

        val ecSpec = ECGenParameterSpec("secp256r1")
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ecSpec, random)
        val deviceKp = kpg.generateKeyPair()
        val deviceEcPub = deviceKp.public as ECPublicKey
        val ecParams = deviceEcPub.params

        val mobilePoint = ECPoint(
            BigInteger(1, mobilePubBytes.copyOfRange(1, 33)),
            BigInteger(1, mobilePubBytes.copyOfRange(33, 65))
        )
        val mobilePubKey = runCatching {
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(mobilePoint, ecParams))
        }.getOrNull() ?: run {
            emitJson(statusJson("error_pairing_invalid_hello"))
            return
        }

        val ecdhShared = computeEcdh(deviceKp.private, mobilePubKey) ?: run {
            emitJson(statusJson("error_pairing_invalid_hello"))
            return
        }

        val devicePubBytes = encodeUncompressedP256Point(deviceEcPub)
        val deviceNonceBytes = ByteArray(16).also { random.nextBytes(it) }

        val mobilePubB64 = Base64UrlCodec.encode(mobilePubBytes)
        val devicePubB64 = Base64UrlCodec.encode(devicePubBytes)
        val mobileNonceB64 = Base64UrlCodec.encode(mobileNonceBytes)
        val deviceNonceB64 = Base64UrlCodec.encode(deviceNonceBytes)
        val confirmTimeoutSeconds = if (requestedPolicy == POLICY_PRESS) 60 else 0

        val transcript = buildTranscript(
            deviceId = deviceIdLower,
            nodeId = nodeIdLower,
            mac = macLower,
            firmwareVersion = firmwareVersion,
            policy = requestedPolicy,
            confirmTimeoutSeconds = confirmTimeoutSeconds,
            mobilePubB64 = mobilePubB64,
            devicePubB64 = devicePubB64,
            mobileNonceB64 = mobileNonceB64,
            deviceNonceB64 = deviceNonceB64
        )
        val transcriptHash = sha256(transcript.toByteArray(Charsets.UTF_8))
        val salt = sha256(mobileNonceBytes, deviceNonceBytes, transcriptHash)
        val sessionSecret = hkdf32(ecdhShared, salt, RECORD_LABEL.toByteArray(Charsets.US_ASCII))
        rxKey = hkdfExpandOneBlock(sessionSecret, "mobile->device".toByteArray(Charsets.US_ASCII))
        txKey = hkdfExpandOneBlock(sessionSecret, "device->mobile".toByteArray(Charsets.US_ASCII))
        sessionSecret.fill(0)

        val sidHash = sha256(SESSION_ID_LABEL, transcriptHash, ecdhShared)
        ecdhShared.fill(0)
        sessionIdB64 = Base64UrlCodec.encode(sidHash.copyOfRange(0, 16))
        rxCounter = 0L
        txCounter = 0L
        state = State.WAIT_CLIENT_FINISHED
        plaintextStatusBlocked = true

        val ready = linkedMapOf<String, Any?>(
            "type" to "pairing_ready",
            "version" to PAIRING_VERSION,
            "device_id" to deviceIdLower,
            "node_id" to nodeIdLower,
            "mac" to macLower,
            "model" to MODEL,
            "firmware_version" to firmwareVersion,
            "pairing_auth" to PAIRING_AUTH,
            "pairing_auth_epoch" to 0,
            "pairing_policy" to requestedPolicy,
            "confirm_timeout_seconds" to confirmTimeoutSeconds,
            "device_pub" to devicePubB64,
            "device_nonce" to deviceNonceB64,
            "transcript_hash" to Base64UrlCodec.encode(transcriptHash),
            "session_id" to sessionIdB64
        )
        emitJson(TinyJson.encode(ready))
    }

    private fun computeEcdh(privateKey: PrivateKey, peerPublicKey: java.security.PublicKey): ByteArray? {
        return try {
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(privateKey)
            ka.doPhase(peerPublicKey, true)
            val secret = ka.generateSecret()
            if (secret.size != 32 || secret.all { it == 0.toByte() }) null else secret
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    @Synchronized
    private fun decryptCommand(root: Map<String, Any?>): String? {
        val activeRxKey = rxKey ?: return null
        if (state == State.IDLE) return null
        val sid = root["session_id"] as? String ?: return null
        if (sid != sessionIdB64) return null
        val counterVal = when (val c = root["counter"]) {
            is String -> c.toLongOrNull()
            is Number -> c.toLong()
            else -> null
        } ?: return null
        if (counterVal != rxCounter) return null
        val cipherB64 = root["ciphertext"] as? String ?: return null
        val tagB64 = root["tag"] as? String ?: return null

        val cipherBytes = runCatching { Base64UrlCodec.decode(cipherB64) }.getOrNull() ?: return null
        val tagBytes = runCatching { Base64UrlCodec.decode(tagB64) }.getOrNull() ?: return null
        if (tagBytes.size != 16) return null

        val combined = cipherBytes + tagBytes
        val iv = buildNonce(0, counterVal)
        val aad = "$RECORD_LABEL|$sessionIdB64|m2d|$counterVal".toByteArray(Charsets.UTF_8)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(activeRxKey, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(combined)
        } catch (_: GeneralSecurityException) {
            return null
        }
        rxCounter += 1L
        return plain.toString(Charsets.UTF_8)
    }

    @Synchronized
    private fun encryptEnvelope(plainJson: String): String? {
        val activeTxKey = txKey ?: return null
        if (state == State.IDLE) return null
        val counter = txCounter
        val iv = buildNonce(1, counter)
        val aad = "$RECORD_LABEL|$sessionIdB64|d2m|$counter".toByteArray(Charsets.UTF_8)
        val out = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(activeTxKey, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))
        } catch (_: GeneralSecurityException) {
            return null
        }
        txCounter += 1L
        val ctLen = out.size - 16
        val ciphertext = out.copyOfRange(0, ctLen)
        val tag = out.copyOfRange(ctLen, out.size)
        val envelope = linkedMapOf<String, Any?>(
            "type" to "pairing_encrypted",
            "session_id" to sessionIdB64,
            "counter" to counter.toString(),
            "ciphertext" to Base64UrlCodec.encode(ciphertext),
            "tag" to Base64UrlCodec.encode(tag)
        )
        return TinyJson.encode(envelope)
    }

    @Synchronized
    private fun handleClientFinished(emitJson: (String) -> Unit) {
        if (state != State.WAIT_CLIENT_FINISHED || rxCounter != 1L) {
            emitStatus("error_pairing_decrypt", forcePlaintext = true, emitJson)
            resetSession()
            return
        }
        state = State.READY
        val confirmed = linkedMapOf<String, Any?>(
            "type" to "status",
            "status" to "pairing_confirmed"
        )
        if (!sdkToken.isNullOrBlank()) {
            confirmed["sdk_token"] = sdkToken
        }
        encryptEnvelope(TinyJson.encode(confirmed))?.let(emitJson)
    }

    private fun handleWifiScan(emitJson: (String) -> Unit) {
        if (state != State.READY && state != State.PROVISIONING) {
            emitEncryptedOrPlainStatus("error_pairing_confirm_required", emitJson)
            return
        }
        val entries = onWifiScan()
        val payload = linkedMapOf<String, Any?>(
            "type" to "wifi_scan_result",
            "networks" to entries.map {
                linkedMapOf<String, Any?>(
                    "ssid" to it.ssid,
                    "rssi" to it.rssi,
                    "secure" to it.secure
                )
            }
        )
        encryptEnvelope(TinyJson.encode(payload))?.let(emitJson)
    }

    private fun handleProvisionV2(root: Map<String, Any?>, emitJson: (String) -> Unit) {
        synchronized(this) {
            if (state != State.READY && state != State.PROVISIONING) {
                emitEncryptedOrPlainStatus("error_pairing_confirm_required", emitJson)
                return
            }
            state = State.PROVISIONING
        }

        val ssid = root["ssid"] as? String ?: ""
        val password = root["password"] as? String ?: ""
        val accessToken = root["access_token"] as? String ?: ""
        val refreshToken = root["refresh_token"] as? String ?: ""
        val tokenType = root["token_type"] as? String ?: ""
        val username = root["username"] as? String
        val apiUrl = root["api_url"] as? String
        val apiUrlV2 = root["api_url_v2"] as? String
        val noiseHost = root["noise_host"] as? String

        if (ssid.isBlank() || accessToken.isBlank() || refreshToken.isBlank() || tokenType != "device") {
            emitEncryptedStatus("error_missing_credentials", emitJson)
            return
        }

        val req = ProvisionV2Request(
            ssid = ssid,
            password = password,
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenType = tokenType,
            username = username,
            apiUrl = apiUrl,
            apiUrlV2 = apiUrlV2,
            noiseHost = noiseHost
        )
        val ok = onProvisionV2(req) { status ->
            emitEncryptedStatus(status, emitJson)
        }
        if (ok) {
            emitEncryptedStatus("auth_ok", emitJson)
        }
    }

    private fun emitEncryptedOrPlainStatus(status: String, emitJson: (String) -> Unit) {
        val env = encryptEnvelope(statusJson(status))
        if (env != null) emitJson(env) else emitJson(statusJson(status))
    }

    private fun emitEncryptedStatus(status: String, emitJson: (String) -> Unit) {
        encryptEnvelope(statusJson(status))?.let(emitJson)
    }

    private fun emitStatus(status: String, forcePlaintext: Boolean, emitJson: (String) -> Unit) {
        if (!forcePlaintext && state != State.IDLE) {
            encryptEnvelope(statusJson(status))?.let(emitJson)
        } else {
            emitJson(statusJson(status))
        }
    }

    private fun statusJson(status: String): String =
        TinyJson.encode(linkedMapOf("type" to "status", "status" to status))

    private fun buildNonce(direction: Int, counter: Long): ByteArray {
        val buf = ByteBuffer.allocate(12)
        buf.put(direction.toByte())
        buf.put(0)
        buf.put(0)
        buf.put(0)
        buf.putLong(counter)
        return buf.array()
    }
}

