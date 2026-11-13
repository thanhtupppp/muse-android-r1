package vn.tutu.muser1

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure JVM/Android-compatible Noise_XX_25519_AESGCM_SHA256 implementation
 * matching D:\muse-gadget-sdk\linux\src\musegadget\noise\noise_xx.py.
 */
class NoiseProtocolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class NoiseCipherState(key: ByteArray) {
    private val secretKey = SecretKeySpec(key.copyOf(), "AES")
    private var nonce: Long = 0L

    init {
        require(key.size == 32) { "AES-256-GCM key must be 32 bytes" }
    }

    @Synchronized
    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val currentNonce = nextNonceOrThrow()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, buildIv(currentNonce)))
        if (ad.isNotEmpty()) {
            cipher.updateAAD(ad)
        }
        val out = cipher.doFinal(plaintext)
        advanceNonce()
        return out
    }

    @Synchronized
    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < 16) {
            throw NoiseProtocolException("AES-GCM ciphertext too short (${ciphertext.size} bytes)")
        }
        val currentNonce = nextNonceOrThrow()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, buildIv(currentNonce)))
        if (ad.isNotEmpty()) {
            cipher.updateAAD(ad)
        }
        val out = try {
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            throw NoiseProtocolException("AES-GCM authentication failed", e)
        }
        advanceNonce()
        return out
    }

    private fun nextNonceOrThrow(): Long {
        if (nonce == -1L) {
            throw NoiseProtocolException("Noise cipher nonce exhausted")
        }
        return nonce
    }

    private fun advanceNonce() {
        nonce += 1L
    }

    companion object {
        fun buildIv(n: Long): ByteArray {
            val buf = ByteBuffer.allocate(12)
            buf.putInt(0)
            buf.putLong(n)
            return buf.array()
        }
    }
}

class NoiseXxHandshake(
    private val isInitiator: Boolean,
    private val staticKeyPair: Rfc7748X25519.KeyPair = Rfc7748X25519.generateKeyPair(),
    private val prologue: ByteArray = ByteArray(0),
    private val random: SecureRandom = SecureRandom()
) {
    companion object {
        val PROTOCOL_NAME = "Noise_XX_25519_AESGCM_SHA256".toByteArray(Charsets.US_ASCII)

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

        fun hkdf2(chainingKey: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
            val tempKey = hmacSha256(chainingKey, ikm)
            val out1 = hmacSha256(tempKey, byteArrayOf(0x01))
            val out2 = hmacSha256(tempKey, out1, byteArrayOf(0x02))
            tempKey.fill(0)
            return out1 to out2
        }
    }

    data class TransportCiphers(
        val sendCipher: NoiseCipherState,
        val recvCipher: NoiseCipherState,
        val handshakeHash: ByteArray,
        val remoteStaticPublicKey: ByteArray
    )

    private var h: ByteArray
    private var ck: ByteArray
    private var k: ByteArray? = null
    private var cipherNonce: Long = 0L

    private var ephemeralKeyPair: Rfc7748X25519.KeyPair? = null
    private var remoteEphemeral: ByteArray? = null
    private var remoteStatic: ByteArray? = null
    private var step: Int = 0

    init {
        // PROTOCOL_NAME.size == 30 <= 32, so pad with 0x00 to 32 bytes.
        h = ByteArray(32)
        PROTOCOL_NAME.copyInto(h, endIndex = PROTOCOL_NAME.size)
        ck = h.copyOf()
        mixHash(prologue)
    }

    fun writeMessage1(payload: ByteArray = ByteArray(0)): ByteArray {
        check(isInitiator && step == 0) { "writeMessage1 invalid state (initiator=$isInitiator, step=$step)" }
        val e = Rfc7748X25519.generateKeyPair(random)
        ephemeralKeyPair = e
        mixHash(e.publicKey)
        val encPayload = encryptAndHash(payload)
        step = 1
        return e.publicKey + encPayload
    }

    fun readMessage1(message: ByteArray): ByteArray {
        check(!isInitiator && step == 0) { "readMessage1 invalid state (initiator=$isInitiator, step=$step)" }
        if (message.size < 32) throw NoiseProtocolException("msg1 too short: ${message.size}")
        val re = message.copyOfRange(0, 32)
        remoteEphemeral = re
        mixHash(re)
        val payload = decryptAndHash(message.copyOfRange(32, message.size))
        step = 1
        return payload
    }

    fun writeMessage2(payload: ByteArray = ByteArray(0)): ByteArray {
        check(!isInitiator && step == 1) { "writeMessage2 invalid state (initiator=$isInitiator, step=$step)" }
        val re = remoteEphemeral ?: throw NoiseProtocolException("Missing remote ephemeral")
        val e = Rfc7748X25519.generateKeyPair(random)
        ephemeralKeyPair = e
        mixHash(e.publicKey)
        mixKey(checkedDh(e.privateKey, re))
        val encS = encryptAndHash(staticKeyPair.publicKey)
        mixKey(checkedDh(staticKeyPair.privateKey, re))
        val encPayload = encryptAndHash(payload)
        step = 2
        return e.publicKey + encS + encPayload
    }

    fun readMessage2(message: ByteArray): ByteArray {
        check(isInitiator && step == 1) { "readMessage2 invalid state (initiator=$isInitiator, step=$step)" }
        if (message.size < 32 + 48 + 16) {
            throw NoiseProtocolException("msg2 too short: ${message.size}")
        }
        val e = ephemeralKeyPair ?: throw NoiseProtocolException("Missing local ephemeral")
        val re = message.copyOfRange(0, 32)
        remoteEphemeral = re
        mixHash(re)
        mixKey(checkedDh(e.privateKey, re))

        val encS = message.copyOfRange(32, 80)
        val rs = decryptAndHash(encS)
        if (rs.size != 32) throw NoiseProtocolException("Invalid remote static key length")
        remoteStatic = rs
        mixKey(checkedDh(e.privateKey, rs))

        val payload = decryptAndHash(message.copyOfRange(80, message.size))
        step = 2
        return payload
    }

    fun writeMessage3(payload: ByteArray = ByteArray(0)): Pair<ByteArray, TransportCiphers> {
        check(isInitiator && step == 2) { "writeMessage3 invalid state (initiator=$isInitiator, step=$step)" }
        val re = remoteEphemeral ?: throw NoiseProtocolException("Missing remote ephemeral")
        val rs = remoteStatic ?: throw NoiseProtocolException("Missing remote static")
        val encS = encryptAndHash(staticKeyPair.publicKey)
        mixKey(checkedDh(staticKeyPair.privateKey, re))
        val encPayload = encryptAndHash(payload)
        step = 3
        val ciphers = split(rs)
        return (encS + encPayload) to ciphers
    }

    fun readMessage3(message: ByteArray): Pair<ByteArray, TransportCiphers> {
        check(!isInitiator && step == 2) { "readMessage3 invalid state (initiator=$isInitiator, step=$step)" }
        if (message.size < 48 + 16) {
            throw NoiseProtocolException("msg3 too short: ${message.size}")
        }
        val e = ephemeralKeyPair ?: throw NoiseProtocolException("Missing local ephemeral")
        val encS = message.copyOfRange(0, 48)
        val rs = decryptAndHash(encS)
        if (rs.size != 32) throw NoiseProtocolException("Invalid remote static key length")
        remoteStatic = rs
        mixKey(checkedDh(e.privateKey, rs))
        val payload = decryptAndHash(message.copyOfRange(48, message.size))
        step = 3
        val ciphers = split(rs)
        return payload to ciphers
    }

    private fun checkedDh(priv: ByteArray, pub: ByteArray): ByteArray {
        val shared = Rfc7748X25519.scalarMult(priv, pub)
        if (Rfc7748X25519.isAllZero(shared)) {
            throw NoiseProtocolException("Rejected low-order X25519 public key")
        }
        return shared
    }

    private fun mixHash(data: ByteArray) {
        h = sha256(h, data)
    }

    private fun mixKey(dhOut: ByteArray) {
        val (newCk, tempK) = hkdf2(ck, dhOut)
        ck.fill(0)
        ck = newCk
        k?.fill(0)
        k = tempK
        cipherNonce = 0L
    }

    private fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val activeKey = k
        val ciphertext = if (activeKey != null) {
            val iv = NoiseCipherState.buildIv(cipherNonce)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(activeKey, "AES"), GCMParameterSpec(128, iv))
            if (h.isNotEmpty()) cipher.updateAAD(h)
            val out = cipher.doFinal(plaintext)
            cipherNonce += 1L
            out
        } else {
            plaintext.copyOf()
        }
        mixHash(ciphertext)
        return ciphertext
    }

    private fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val activeKey = k
        val plaintext = if (activeKey != null) {
            if (ciphertext.size < 16) throw NoiseProtocolException("Ciphertext too short")
            val iv = NoiseCipherState.buildIv(cipherNonce)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(activeKey, "AES"), GCMParameterSpec(128, iv))
            if (h.isNotEmpty()) cipher.updateAAD(h)
            val out = try {
                cipher.doFinal(ciphertext)
            } catch (e: GeneralSecurityException) {
                throw NoiseProtocolException("Handshake AES-GCM failure", e)
            }
            cipherNonce += 1L
            out
        } else {
            ciphertext.copyOf()
        }
        mixHash(ciphertext)
        return plaintext
    }

    private fun split(rs: ByteArray): TransportCiphers {
        val (k1, k2) = hkdf2(ck, ByteArray(0))
        val c1 = NoiseCipherState(k1)
        val c2 = NoiseCipherState(k2)
        ck.fill(0)
        k?.fill(0)
        k = null
        k1.fill(0)
        k2.fill(0)
        return if (isInitiator) {
            TransportCiphers(sendCipher = c1, recvCipher = c2, handshakeHash = h.copyOf(), remoteStaticPublicKey = rs.copyOf())
        } else {
            TransportCiphers(sendCipher = c2, recvCipher = c1, handshakeHash = h.copyOf(), remoteStaticPublicKey = rs.copyOf())
        }
    }
}

