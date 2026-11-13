package vn.tutu.muser1

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseProtocolTest {

    @Test
    fun x25519MatchesRfc7748Section6_1Vectors() {
        val alicePriv = hexToBytes("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val aliceExpectedPub = hexToBytes("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPriv = hexToBytes("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val bobExpectedPub = hexToBytes("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val expectedShared = hexToBytes("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")

        val alicePub = Rfc7748X25519.scalarMultBase(alicePriv)
        val bobPub = Rfc7748X25519.scalarMultBase(bobPriv)
        assertArrayEquals(aliceExpectedPub, alicePub)
        assertArrayEquals(bobExpectedPub, bobPub)

        val sharedAlice = Rfc7748X25519.scalarMult(alicePriv, bobPub)
        val sharedBob = Rfc7748X25519.scalarMult(bobPriv, alicePub)
        assertArrayEquals(expectedShared, sharedAlice)
        assertArrayEquals(expectedShared, sharedBob)
    }

    @Test
    fun x25519IteratedVectorAfter1And1000Steps() {
        // RFC 7748 Section 5.2 iterated test vector (k0 = u0 = 9)
        var k = ByteArray(32).apply { this[0] = 9 }
        var u = ByteArray(32).apply { this[0] = 9 }
        for (i in 0 until 1000) {
            val next = Rfc7748X25519.scalarMult(k, u)
            u = k
            k = next
            if (i == 0) {
                assertArrayEquals(
                    hexToBytes("422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079"),
                    k
                )
            }
        }
        assertArrayEquals(
            hexToBytes("684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51"),
            k
        )
    }

    @Test
    fun noiseXxHandshakeAndBidirectionalTransport() {
        val initiatorStatic = Rfc7748X25519.generateKeyPair()
        val responderStatic = Rfc7748X25519.generateKeyPair()

        val initiator = NoiseXxHandshake(isInitiator = true, staticKeyPair = initiatorStatic)
        val responder = NoiseXxHandshake(isInitiator = false, staticKeyPair = responderStatic)

        val msg1 = initiator.writeMessage1()
        assertEquals(32, msg1.size)
        assertEquals(0, responder.readMessage1(msg1).size)

        val msg2 = responder.writeMessage2()
        assertEquals(96, msg2.size)
        assertEquals(0, initiator.readMessage2(msg2).size)

        val (msg3, initTransportKeys) = initiator.writeMessage3()
        assertEquals(64, msg3.size)
        val (respPayload, respTransportKeys) = responder.readMessage3(msg3)
        assertEquals(0, respPayload.size)

        assertArrayEquals(initTransportKeys.handshakeHash, respTransportKeys.handshakeHash)
        assertArrayEquals(responderStatic.publicKey, initTransportKeys.remoteStaticPublicKey)
        assertArrayEquals(initiatorStatic.publicKey, respTransportKeys.remoteStaticPublicKey)

        // Verify bidirectional transport encryption (init -> resp and resp -> init)
        val helloBytes = "GET /health HTTP/1.1".toByteArray()
        val ct1 = initTransportKeys.sendCipher.encryptWithAd(ByteArray(0), helloBytes)
        val pt1 = respTransportKeys.recvCipher.decryptWithAd(ByteArray(0), ct1)
        assertArrayEquals(helloBytes, pt1)

        val replyBytes = "{\"ok\":true}".toByteArray()
        val ct2 = respTransportKeys.sendCipher.encryptWithAd(ByteArray(0), replyBytes)
        val pt2 = initTransportKeys.recvCipher.decryptWithAd(ByteArray(0), ct2)
        assertArrayEquals(replyBytes, pt2)
    }

    @Test
    fun noiseXxRejectsAllZeroLowOrderPoint() {
        val initiator = NoiseXxHandshake(isInitiator = true)
        initiator.writeMessage1()
        val badMsg2 = ByteArray(96) // starts with 32-byte all-zero ephemeral key
        assertThrows(NoiseProtocolException::class.java) {
            initiator.readMessage2(badMsg2)
        }
    }

    @Test
    fun noiseTransportAndEnvelopeFramingRoundtrip() {
        val initKey = ByteArray(32) { (it + 1).toByte() }
        val respKey = ByteArray(32) { (it + 65).toByte() }

        val clientTransport = NoiseTransport(NoiseCipherState(initKey), NoiseCipherState(respKey))
        val serverRecvCipher = NoiseCipherState(initKey)
        val serverSendCipher = NoiseCipherState(respKey)
        val serverNoiseDecoder = NoiseFrameDecoder()

        val (streamId, openFrames) = clientTransport.startStreamRequest("POST", "/link-control")
        assertEquals(1L, streamId)
        assertEquals(1, openFrames.size)

        val decryptedOpen = serverRecvCipher.decryptWithAd(ByteArray(0), openFrames[0])
        val reassembledOpen = serverNoiseDecoder.decode(decryptedOpen)
        assertNotNull(reassembledOpen)

        // Decode ServiceRequest -> ServiceFrame manually via ServiceEnvelopeCodec / framing
        val decodedOpenReq = decodeServiceRequestForTest(reassembledOpen!!)
        assertEquals(streamId, decodedOpenReq.first)
        assertEquals("POST", decodedOpenReq.second)
        assertEquals("/link-control", decodedOpenReq.third)

        // Send a length-prefixed JSON body chunk from client
        val jsonMsg = """{"type":"req","id":"r1","method":"link.register"}"""
        val framedJson = LengthPrefixedJsonDecoder.encode(jsonMsg)
        val chunkFrames = clientTransport.encryptBodyChunk(streamId, framedJson, endBody = false)
        assertEquals(1, chunkFrames.size)

        // Simulate server sending back an encrypted ServiceResponse -> ServiceFrame(BodyChunk)
        val serverJson = """{"type":"req","id":"cmd1","method":"link.invoke","command":"muse_r1.get_status"}"""
        val serverBody = LengthPrefixedJsonDecoder.encode(serverJson)
        val splitAt = 12
        val partA = ServiceFrame(streamId = streamId, bodyChunk = BodyChunk(serverBody.copyOfRange(0, splitAt), endBody = false))
        val partB = ServiceFrame(streamId = streamId, bodyChunk = BodyChunk(serverBody.copyOfRange(splitAt, serverBody.size), endBody = false))

        val respEnvA = encodeServerFrame(serverSendCipher, partA)
        val respEnvB = encodeServerFrame(serverSendCipher, partB)

        val decodedA = clientTransport.decryptFrame(respEnvA)!!
        val decodedB = clientTransport.decryptFrame(respEnvB)!!
        assertEquals(streamId, decodedA.streamId)
        assertFalse(decodedA.bodyChunk!!.endBody)

        val jsonDecoder = LengthPrefixedJsonDecoder()
        assertTrue(jsonDecoder.push(decodedA.bodyChunk!!.data).isEmpty())
        val messages = jsonDecoder.push(decodedB.bodyChunk!!.data)
        assertEquals(listOf(serverJson), messages)
    }

    @Test
    fun noiseFrameDecoderReassemblesLargeMultiChunkPayload() {
        val bigPayload = ByteArray(NoiseTransportFrame.MAX_CHUNK_PAYLOAD + 1234) { (it % 251).toByte() }
        val encodedFrames = NoiseTransportFrame.encodeFrames(bigPayload, chunkId = 42L)
        assertEquals(2, encodedFrames.size)

        val decoder = NoiseFrameDecoder()
        // Out-of-order delivery check
        assertNull(decoder.decode(encodedFrames[1]))
        val assembled = decoder.decode(encodedFrames[0])
        assertNotNull(assembled)
        assertArrayEquals(bigPayload, assembled)
    }

    private fun encodeServerFrame(cipher: NoiseCipherState, frame: ServiceFrame): ByteArray {
        val serviceResp = ProtoCodec.delimitedField(1, frame.encode())
        val noiseFrame = NoiseTransportFrame.encodeFrames(serviceResp).single()
        return cipher.encryptWithAd(ByteArray(0), noiseFrame)
    }

    private fun decodeServiceRequestForTest(data: ByteArray): Triple<Long, String, String> {
        var offset = 0
        var framePayload = ByteArray(0)
        while (offset < data.size) {
            val (f, w, next) = ProtoCodec.readKey(data, offset)
            offset = next
            if (f == 2) {
                val (b, n) = ProtoCodec.readDelimited(data, offset)
                framePayload = b
                offset = n
            } else {
                offset = ProtoCodec.skipField(data, offset, w)
            }
        }
        var streamId = 0L
        var verb = ""
        var path = ""
        var fOff = 0
        while (fOff < framePayload.size) {
            val (f, w, next) = ProtoCodec.readKey(framePayload, fOff)
            fOff = next
            when (f) {
                1 -> {
                    val (v, n) = ProtoCodec.readVarint(framePayload, fOff)
                    streamId = v
                    fOff = n
                }
                2 -> {
                    val (reqBytes, n) = ProtoCodec.readDelimited(framePayload, fOff)
                    fOff = n
                    var rOff = 0
                    while (rOff < reqBytes.size) {
                        val (rf, rw, rn) = ProtoCodec.readKey(reqBytes, rOff)
                        rOff = rn
                        when (rf) {
                            1 -> {
                                val (b, n2) = ProtoCodec.readDelimited(reqBytes, rOff)
                                verb = b.toString(Charsets.UTF_8)
                                rOff = n2
                            }
                            2 -> {
                                val (b, n2) = ProtoCodec.readDelimited(reqBytes, rOff)
                                path = b.toString(Charsets.UTF_8)
                                rOff = n2
                            }
                            else -> rOff = ProtoCodec.skipField(reqBytes, rOff, rw)
                        }
                    }
                }
                else -> fOff = ProtoCodec.skipField(framePayload, fOff, w)
            }
        }
        return Triple(streamId, verb, path)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}

