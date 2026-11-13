package vn.tutu.muser1

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MuseBlePairingProtocolTest {

    @Test
    fun chunkFramerAndAssemblerHandlesSmallAndLargeMtu() {
        val msg = "{\"action\":\"get_device_info\",\"padding\":\"0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ\"}".toByteArray()
        val smallChunks = MuseBleFramer.encodeChunks(msg, attMtu = 23)
        assertTrue(smallChunks.size > 4)
        smallChunks.forEachIndexed { idx, pkt ->
            assertTrue(pkt.size <= 20)
            assertEquals(MuseBleFramer.FRAME_MAGIC.toByte(), pkt[0])
            assertEquals(idx.toByte(), pkt[1])
            assertEquals(smallChunks.size.toByte(), pkt[2])
        }

        val rx = MuseBleFramer.RxAssembler()
        for (i in 0 until smallChunks.lastIndex) {
            assertNull(rx.push(smallChunks[i]))
        }
        val assembled = rx.push(smallChunks.last())
        assertArrayEquals(msg, assembled)

        // Single raw JSON fallback without 0xFE header
        val raw = """{"action":"get_device_info"}""".toByteArray()
        assertArrayEquals(raw, rx.push(raw))
    }

    @Test
    fun fullBlePairingV5AndProvisionV2Handshake() {
        var capturedProvision: MuseBlePairingProtocol.ProvisionV2Request? = null
        // Identity theo chuẩn Muse Gadget: deviceId "hatch-link:<mac>",
        // nodeId "homelink-<6 hex cuối>" — hậu tố phải trùng tên BLE.
        val identity = MuseIdentity.fromMac("FC:7C:02:57:41:8E")
        val protocol = MuseBlePairingProtocol(
            deviceId = identity.deviceId,
            nodeId = identity.nodeId,
            macAddress = identity.mac,
            firmwareVersion = "1.0.0",
            onWifiScan = {
                listOf(MuseBlePairingProtocol.WifiEntry("MyHomeWiFi", -48, true))
            },
            onProvisionV2 = { req, sendStatus ->
                capturedProvision = req
                sendStatus("wifi_connecting")
                sendStatus("wifi_connected")
                true
            }
        )

        // 1. get_device_info
        val infoReplies = mutableListOf<String>()
        protocol.handleCommand("""{"action":"get_device_info"}""") { infoReplies += it }
        assertEquals(1, infoReplies.size)
        val info = TinyJson.parseObject(infoReplies[0])
        assertEquals("device_info", info["type"])
        assertEquals("hatch-link:fc:7c:02:57:41:8e", info["device_id"])
        assertEquals("homelink-57418e", info["node_id"])
        assertEquals("fc:7c:02:57:41:8e", info["mac"])
        assertEquals("hatch_link", info["model"])
        assertEquals(5, (info["pairing_protocol"] as Number).toInt())
        assertEquals("none", info["pairing_auth"])
        assertEquals("confirm_app", info["pairing_policy"])

        // 2. pairing_client_hello (Mobile side P-256 keypair + nonce)
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val mobileKp = kpg.generateKeyPair()
        val mobilePubBytes = MuseBlePairingProtocol.encodeUncompressedP256Point(mobileKp.public as ECPublicKey)
        val mobileNonce = ByteArray(16) { (it + 0x11).toByte() }
        val mobilePubB64 = Base64UrlCodec.encode(mobilePubBytes)
        val mobileNonceB64 = Base64UrlCodec.encode(mobileNonce)

        val helloReq = TinyJson.encode(
            mapOf(
                "action" to "pairing_client_hello",
                "version" to 5,
                "pairing_auth" to "none",
                "pairing_policy" to "confirm_app",
                "mobile_pub" to mobilePubB64,
                "mobile_nonce" to mobileNonceB64
            )
        )
        val helloReplies = mutableListOf<String>()
        protocol.handleCommand(helloReq) { helloReplies += it }
        assertEquals(1, helloReplies.size)
        val ready = TinyJson.parseObject(helloReplies[0])
        assertEquals("pairing_ready", ready["type"])
        val devicePubB64 = ready["device_pub"] as String
        val deviceNonceB64 = ready["device_nonce"] as String
        val transcriptHashB64 = ready["transcript_hash"] as String
        val sessionIdB64 = ready["session_id"] as String

        // Mobile derives keys independently
        val devicePubBytes = Base64UrlCodec.decode(devicePubB64)
        val deviceNonce = Base64UrlCodec.decode(deviceNonceB64)
        val ecParams = (mobileKp.public as ECPublicKey).params
        val devicePubKey = java.security.KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(
                ECPoint(
                    java.math.BigInteger(1, devicePubBytes.copyOfRange(1, 33)),
                    java.math.BigInteger(1, devicePubBytes.copyOfRange(33, 65))
                ),
                ecParams
            )
        )
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(mobileKp.private as ECPrivateKey)
        ka.doPhase(devicePubKey, true)
        val ecdh = ka.generateSecret()

        val expectedTranscript = MuseBlePairingProtocol.buildTranscript(
            deviceId = identity.deviceId,
            nodeId = identity.nodeId,
            mac = identity.mac,
            firmwareVersion = "1.0.0",
            policy = "confirm_app",
            confirmTimeoutSeconds = 0,
            mobilePubB64 = mobilePubB64,
            devicePubB64 = devicePubB64,
            mobileNonceB64 = mobileNonceB64,
            deviceNonceB64 = deviceNonceB64
        )
        val expectedHash = MuseBlePairingProtocol.sha256(expectedTranscript.toByteArray(Charsets.UTF_8))
        assertEquals(Base64UrlCodec.encode(expectedHash), transcriptHashB64)

        val expectedSid = MuseBlePairingProtocol.sha256(
            MuseBlePairingProtocol.SESSION_ID_LABEL,
            expectedHash,
            ecdh
        ).copyOfRange(0, 16)
        assertEquals(Base64UrlCodec.encode(expectedSid), sessionIdB64)

        val salt = MuseBlePairingProtocol.sha256(mobileNonce, deviceNonce, expectedHash)
        val sessionSecret = MuseBlePairingProtocol.hkdf32(
            ecdh,
            salt,
            MuseBlePairingProtocol.RECORD_LABEL.toByteArray(Charsets.US_ASCII)
        )
        val m2dKey = MuseBlePairingProtocol.hkdfExpandOneBlock(sessionSecret, "mobile->device".toByteArray())
        val d2mKey = MuseBlePairingProtocol.hkdfExpandOneBlock(sessionSecret, "device->mobile".toByteArray())

        // 3. pairing_client_finished (m2d counter=0) -> pairing_confirmed (d2m counter=0)
        var m2dCounter = 0L
        var d2mCounter = 0L

        fun encryptFromMobile(json: String): String {
            val c = m2dCounter++
            val iv = ByteBuffer.allocate(12).apply { put(0); put(0); put(0); put(0); putLong(c) }.array()
            val aad = "${MuseBlePairingProtocol.RECORD_LABEL}|$sessionIdB64|m2d|$c".toByteArray()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(m2dKey, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            val combined = cipher.doFinal(json.toByteArray())
            val ct = combined.copyOfRange(0, combined.size - 16)
            val tag = combined.copyOfRange(combined.size - 16, combined.size)
            return TinyJson.encode(
                mapOf(
                    "action" to "pairing_encrypted",
                    "session_id" to sessionIdB64,
                    "counter" to c.toString(),
                    "ciphertext" to Base64UrlCodec.encode(ct),
                    "tag" to Base64UrlCodec.encode(tag)
                )
            )
        }

        fun decryptFromDevice(envelopeJson: String): Map<String, Any?> {
            val env = TinyJson.parseObject(envelopeJson)
            assertEquals("pairing_encrypted", env["type"])
            assertEquals(sessionIdB64, env["session_id"])
            val c = d2mCounter++
            assertEquals(c.toString(), env["counter"])
            val ct = Base64UrlCodec.decode(env["ciphertext"] as String)
            val tag = Base64UrlCodec.decode(env["tag"] as String)
            val iv = ByteBuffer.allocate(12).apply { put(1); put(0); put(0); put(0); putLong(c) }.array()
            val aad = "${MuseBlePairingProtocol.RECORD_LABEL}|$sessionIdB64|d2m|$c".toByteArray()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(d2mKey, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            val plain = cipher.doFinal(ct + tag).toString(Charsets.UTF_8)
            return TinyJson.parseObject(plain)
        }

        val finishedReplies = mutableListOf<String>()
        protocol.handleCommand(encryptFromMobile("""{"action":"pairing_client_finished"}""")) {
            finishedReplies += it
        }
        assertEquals(1, finishedReplies.size)
        val confirmed = decryptFromDevice(finishedReplies[0])
        assertEquals("status", confirmed["type"])
        assertEquals("pairing_confirmed", confirmed["status"])

        // 4. wifi_scan
        val scanReplies = mutableListOf<String>()
        protocol.handleCommand(encryptFromMobile("""{"action":"wifi_scan"}""")) { scanReplies += it }
        assertEquals(1, scanReplies.size)
        val scanObj = decryptFromDevice(scanReplies[0])
        assertEquals("wifi_scan_result", scanObj["type"])
        val nets = scanObj["networks"] as List<*>
        assertEquals(1, nets.size)

        // 5. provision_v2
        val provReplies = mutableListOf<String>()
        protocol.handleCommand(
            encryptFromMobile(
                """{"action":"provision_v2","ssid":"MyHomeWiFi","password":"secret123","access_token":"tok_acc","refresh_token":"tok_ref","token_type":"device","username":"tu","api_url":"https://api.example","api_url_v2":"https://v2.example","noise_host":"hatch.metaaivm.com"}"""
            )
        ) { provReplies += it }
        assertEquals(3, provReplies.size)
        assertEquals("wifi_connecting", decryptFromDevice(provReplies[0])["status"])
        assertEquals("wifi_connected", decryptFromDevice(provReplies[1])["status"])
        assertEquals("auth_ok", decryptFromDevice(provReplies[2])["status"])
        assertNotNull(capturedProvision)
        assertEquals("tok_acc", capturedProvision!!.accessToken)
        assertEquals("hatch.metaaivm.com", capturedProvision!!.noiseHost)
    }

    /** Mô phỏng phía mobile (điện thoại) tối thiểu cho các test confirm_press. */
    private class MobileSim {
        val kp: KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val nonce: ByteArray = ByteArray(16) { (it + 0x21).toByte() }
        lateinit var sessionIdB64: String
        private lateinit var m2dKey: ByteArray
        private lateinit var d2mKey: ByteArray
        private var m2dCounter = 0L
        private var d2mCounter = 0L

        fun helloJson(policy: String): String = TinyJson.encode(
            mapOf(
                "action" to "pairing_client_hello",
                "version" to 5,
                "pairing_auth" to "none",
                "pairing_policy" to policy,
                "mobile_pub" to Base64UrlCodec.encode(
                    MuseBlePairingProtocol.encodeUncompressedP256Point(kp.public as ECPublicKey)
                ),
                "mobile_nonce" to Base64UrlCodec.encode(nonce)
            )
        )

        fun acceptReady(readyJson: String, identity: MuseIdentity) {
            val ready = TinyJson.parseObject(readyJson)
            sessionIdB64 = ready["session_id"] as String
            val devicePubB64 = ready["device_pub"] as String
            val deviceNonceB64 = ready["device_nonce"] as String
            val devicePubBytes = Base64UrlCodec.decode(devicePubB64)
            val deviceNonce = Base64UrlCodec.decode(deviceNonceB64)
            val ecParams = (kp.public as ECPublicKey).params
            val devicePubKey = KeyFactory.getInstance("EC").generatePublic(
                ECPublicKeySpec(
                    ECPoint(
                        BigInteger(1, devicePubBytes.copyOfRange(1, 33)),
                        BigInteger(1, devicePubBytes.copyOfRange(33, 65))
                    ),
                    ecParams
                )
            )
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(kp.private as ECPrivateKey)
            ka.doPhase(devicePubKey, true)
            val ecdh = ka.generateSecret()

            val mobilePubB64 = Base64UrlCodec.encode(
                MuseBlePairingProtocol.encodeUncompressedP256Point(kp.public as ECPublicKey)
            )
            val transcript = MuseBlePairingProtocol.buildTranscript(
                deviceId = identity.deviceId,
                nodeId = identity.nodeId,
                mac = identity.mac,
                firmwareVersion = "1.0.0",
                policy = ready["pairing_policy"] as String,
                confirmTimeoutSeconds = (ready["confirm_timeout_seconds"] as Number).toInt(),
                mobilePubB64 = mobilePubB64,
                devicePubB64 = devicePubB64,
                mobileNonceB64 = Base64UrlCodec.encode(nonce),
                deviceNonceB64 = deviceNonceB64
            )
            val hash = MuseBlePairingProtocol.sha256(transcript.toByteArray(Charsets.UTF_8))
            assertEquals(Base64UrlCodec.encode(hash), ready["transcript_hash"])
            val expectedSid = MuseBlePairingProtocol.sha256(
                MuseBlePairingProtocol.SESSION_ID_LABEL, hash, ecdh
            ).copyOfRange(0, 16)
            assertEquals(Base64UrlCodec.encode(expectedSid), sessionIdB64)

            val salt = MuseBlePairingProtocol.sha256(nonce, deviceNonce, hash)
            val secret = MuseBlePairingProtocol.hkdf32(
                ecdh, salt, MuseBlePairingProtocol.RECORD_LABEL.toByteArray(Charsets.US_ASCII)
            )
            m2dKey = MuseBlePairingProtocol.hkdfExpandOneBlock(secret, "mobile->device".toByteArray())
            d2mKey = MuseBlePairingProtocol.hkdfExpandOneBlock(secret, "device->mobile".toByteArray())
        }

        fun encrypt(json: String): String {
            val c = m2dCounter++
            val iv = ByteBuffer.allocate(12).apply { put(0); put(0); put(0); put(0); putLong(c) }.array()
            val aad = "${MuseBlePairingProtocol.RECORD_LABEL}|$sessionIdB64|m2d|$c".toByteArray()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(m2dKey, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            val combined = cipher.doFinal(json.toByteArray())
            val ct = combined.copyOfRange(0, combined.size - 16)
            val tag = combined.copyOfRange(combined.size - 16, combined.size)
            return TinyJson.encode(
                mapOf(
                    "action" to "pairing_encrypted",
                    "session_id" to sessionIdB64,
                    "counter" to c.toString(),
                    "ciphertext" to Base64UrlCodec.encode(ct),
                    "tag" to Base64UrlCodec.encode(tag)
                )
            )
        }

        fun decrypt(envelopeJson: String): Map<String, Any?> {
            val env = TinyJson.parseObject(envelopeJson)
            assertEquals("pairing_encrypted", env["type"])
            assertEquals(sessionIdB64, env["session_id"])
            val c = d2mCounter++
            assertEquals(c.toString(), env["counter"])
            val ct = Base64UrlCodec.decode(env["ciphertext"] as String)
            val tag = Base64UrlCodec.decode(env["tag"] as String)
            val iv = ByteBuffer.allocate(12).apply { put(1); put(0); put(0); put(0); putLong(c) }.array()
            val aad = "${MuseBlePairingProtocol.RECORD_LABEL}|$sessionIdB64|d2m|$c".toByteArray()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(d2mKey, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            val plain = cipher.doFinal(ct + tag).toString(Charsets.UTF_8)
            return TinyJson.parseObject(plain)
        }
    }

    @Test
    fun confirmPressRequiresPhysicalPressBeforeReady() {
        var armed = false
        val identity = MuseIdentity.fromMac("FC:7C:02:57:41:8E")
        val protocol = MuseBlePairingProtocol(
            deviceId = identity.deviceId,
            nodeId = identity.nodeId,
            macAddress = identity.mac,
            firmwareVersion = "1.0.0",
            onWifiScan = { listOf(MuseBlePairingProtocol.WifiEntry("Home", -50, true)) },
            onConfirmRequired = { armed = true }
        )
        val sim = MobileSim()

        val replies = mutableListOf<String>()
        protocol.handleCommand(sim.helloJson("confirm_press")) { replies += it }
        assertEquals(1, replies.size)
        val ready = TinyJson.parseObject(replies[0])
        assertEquals("pairing_ready", ready["type"])
        assertEquals("confirm_press", ready["pairing_policy"])
        assertEquals(60, (ready["confirm_timeout_seconds"] as Number).toInt())
        sim.acceptReady(replies[0], identity)
        assertTrue(!armed)

        // client_finished: thiết bị gửi confirm_required TRƯỚC, arm nút sau.
        replies.clear()
        protocol.handleCommand(sim.encrypt("""{"action":"pairing_client_finished"}""")) { replies += it }
        assertEquals(1, replies.size)
        assertEquals("confirm_required", sim.decrypt(replies[0])["status"])
        assertTrue(armed)
        assertTrue(protocol.confirmationRequired())

        // Chưa bấm nút: wifi_scan vẫn bị chặn.
        replies.clear()
        protocol.handleCommand(sim.encrypt("""{"action":"wifi_scan"}""")) { replies += it }
        assertEquals("error_pairing_confirm_required", sim.decrypt(replies.single())["status"])

        // Bấm nút vật lý -> pairing_confirmed, từ đây wifi_scan mới chạy.
        replies.clear()
        assertTrue(protocol.confirmActiveSession { replies += it })
        assertEquals("pairing_confirmed", sim.decrypt(replies.single())["status"])

        replies.clear()
        protocol.handleCommand(sim.encrypt("""{"action":"wifi_scan"}""")) { replies += it }
        assertEquals("wifi_scan_result", sim.decrypt(replies.single())["type"])
    }

    @Test
    fun confirmPressTimeoutExpiresSession() {
        val identity = MuseIdentity.fromMac("FC:7C:02:57:41:8E")
        val protocol = MuseBlePairingProtocol(
            deviceId = identity.deviceId,
            nodeId = identity.nodeId,
            macAddress = identity.mac,
            firmwareVersion = "1.0.0",
            confirmPressTimeoutMs = 60L
        )
        val sim = MobileSim()

        val replies = mutableListOf<String>()
        protocol.handleCommand(sim.helloJson("confirm_press")) { replies += it }
        sim.acceptReady(replies.single(), identity)

        replies.clear()
        protocol.handleCommand(sim.encrypt("""{"action":"pairing_client_finished"}""")) { replies += it }
        assertEquals("confirm_required", sim.decrypt(replies.single())["status"])
        assertTrue(protocol.confirmationRequired())

        Thread.sleep(200)

        val expiredReplies = mutableListOf<String>()
        assertTrue(protocol.expireConfirmIfNeeded { expiredReplies += it })
        assertEquals("error_pairing_confirm_timeout", sim.decrypt(expiredReplies.single())["status"])
        assertTrue(!protocol.confirmationRequired())

        // Bấm nút sau khi hết giờ không còn tác dụng.
        assertTrue(!protocol.confirmActiveSession {})
    }
}

