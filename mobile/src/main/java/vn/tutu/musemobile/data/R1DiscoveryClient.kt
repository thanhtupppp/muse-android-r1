package vn.tutu.musemobile.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import vn.tutu.musemobile.domain.R1Device
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

class R1DiscoveryClient {

    suspend fun discover(timeoutMs: Int = 3000): R1Device? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null

        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = timeoutMs
            }

            val payload = DISCOVERY_QUERY.toByteArray(Charsets.UTF_8)
            val destinations = findBroadcastAddresses()
            Log.i(TAG, "Sending R1 discovery to ${destinations.joinToString()}")

            destinations.forEach { address ->
                try {
                    socket.send(DatagramPacket(payload, payload.size, address, DISCOVERY_PORT))
                } catch (error: Exception) {
                    Log.w(TAG, "Discovery send failed for $address", error)
                }
            }

            val buffer = ByteArray(2048)
            val response = DatagramPacket(buffer, buffer.size)
            val deadline = System.currentTimeMillis() + timeoutMs

            while (true) {
                val remaining = (deadline - System.currentTimeMillis()).toInt()
                if (remaining <= 0) return@withContext null
                socket.soTimeout = remaining

                try {
                    socket.receive(response)
                } catch (_: SocketTimeoutException) {
                    return@withContext null
                }

                try {
                    val json = JSONObject(String(response.data, 0, response.length, Charsets.UTF_8))
                    if (json.optString("service") != "muse-r1-agent") continue

                    return@withContext R1Device(
                        deviceId = json.optString("device_id", "unknown"),
                        ipAddress = response.address.hostAddress ?: "unknown",
                        ssid = json.optString("ssid", "unknown"),
                        agentVersion = json.optString("agent_version", "unknown"),
                        musePaired = json.optBoolean("muse_paired", false)
                    )
                } catch (error: Exception) {
                    Log.w(TAG, "Ignoring invalid R1 discovery response", error)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } catch (error: Exception) {
            Log.w(TAG, "R1 discovery failed", error)
            null
        } finally {
            socket?.close()
        }
    }

    private fun findBroadcastAddresses(): List<InetAddress> {
        val addresses = linkedSetOf<InetAddress>()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { network ->
                if (!network.isUp || network.isLoopback) return@forEach
                network.interfaceAddresses.forEach { interfaceAddress ->
                    val broadcast = interfaceAddress.broadcast
                    if (interfaceAddress.address is Inet4Address && broadcast != null) {
                        addresses += broadcast
                    }
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not enumerate interface broadcast addresses", error)
        }
        // Keep limited/global broadcast as a fallback for interfaces that do not
        // expose their subnet broadcast address.
        addresses += InetAddress.getByName("255.255.255.255")
        return addresses.toList()
    }

    companion object {
        private const val TAG = "MuseMobile"
        const val DISCOVERY_PORT = 47808
        private const val DISCOVERY_QUERY = "MUSE_R1_DISCOVERY"
    }
}
