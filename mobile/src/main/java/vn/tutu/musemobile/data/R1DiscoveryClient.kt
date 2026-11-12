package vn.tutu.musemobile.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import vn.tutu.musemobile.domain.R1Device
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class R1DiscoveryClient {

    suspend fun discover(timeoutMs: Int = 3000): R1Device? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null

        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = timeoutMs
            }

            val broadcastAddress = InetAddress.getByName("255.255.255.255")
            val payload = DISCOVERY_QUERY.toByteArray()
            val packet = DatagramPacket(payload, payload.size, broadcastAddress, DISCOVERY_PORT)

            socket.send(packet)

            val buffer = ByteArray(2048)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)

            val json = JSONObject(String(response.data, 0, response.length))

            if (json.optString("service") != "muse-r1-agent") {
                return@withContext null
            }

            R1Device(
                deviceId = json.optString("device_id", "unknown"),
                ipAddress = response.address.hostAddress ?: "unknown",
                ssid = json.optString("ssid", "unknown"),
                agentVersion = json.optString("agent_version", "unknown"),
                musePaired = json.optBoolean("muse_paired", false)
            )
        } catch (error: SocketTimeoutException) {
            null
        } catch (error: Exception) {
            Log.w(TAG, "R1 discovery failed", error)
            null
        } finally {
            socket?.close()
        }
    }

    companion object {
        private const val TAG = "MuseMobile"
        const val DISCOVERY_PORT = 47808
        private const val DISCOVERY_QUERY = "MUSE_R1_DISCOVERY"
    }
}
