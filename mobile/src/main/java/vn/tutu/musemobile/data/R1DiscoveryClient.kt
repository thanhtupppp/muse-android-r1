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
            }

            val payload = DISCOVERY_QUERY.toByteArray(Charsets.UTF_8)
            val destinations = findBroadcastAddresses()
            Log.i(TAG, "Sending R1 discovery to ${destinations.joinToString()}")

            fun sendQuery() {
                destinations.forEach { address ->
                    try {
                        socket.send(DatagramPacket(payload, payload.size, address, DISCOVERY_PORT))
                    } catch (error: Exception) {
                        Log.w(TAG, "Discovery send failed for $address: ${error.message}")
                    }
                }
            }

            sendQuery()

            val buffer = ByteArray(2048)
            val response = DatagramPacket(buffer, buffer.size)
            val deadline = System.currentTimeMillis() + timeoutMs
            var nextResendTime = System.currentTimeMillis() + 1000L

            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (now >= nextResendTime) {
                    sendQuery()
                    nextResendTime = now + 1000L
                }

                val remaining = (deadline - now).toInt()
                if (remaining <= 0) break

                // Chờ tối đa 500ms mỗi nhịp receive để có thể retry gửi lại query nếu mất gói
                socket.soTimeout = remaining.coerceAtMost(500)

                try {
                    socket.receive(response)
                } catch (_: SocketTimeoutException) {
                    continue
                }

                try {
                    val rawJson = String(response.data, 0, response.length, Charsets.UTF_8)
                    val json = JSONObject(rawJson)
                    if (json.optString("service") != "muse-r1-agent") continue

                    val deviceId = json.optString("device_id", "").trim()
                    if (deviceId.isEmpty()) continue

                    val hostAddress = response.address?.hostAddress ?: continue

                    return@withContext R1Device(
                        deviceId = deviceId,
                        ipAddress = hostAddress,
                        ssid = json.optString("ssid", "unknown"),
                        agentVersion = json.optString("agent_version", "unknown"),
                        musePaired = json.optBoolean("muse_paired", false)
                    )
                } catch (error: Exception) {
                    Log.w(TAG, "Ignoring invalid R1 discovery response", error)
                }
            }
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
            val allInterfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            // Ưu tiên interface Wi-Fi (wlan*); loại bỏ VPN (tun), di động hoặc loopback
            val wlanInterfaces = allInterfaces.filter {
                it.isUp && !it.isLoopback && it.name.startsWith("wlan", ignoreCase = true)
            }
            val targetInterfaces = if (wlanInterfaces.isNotEmpty()) {
                wlanInterfaces
            } else {
                allInterfaces.filter {
                    it.isUp && !it.isLoopback &&
                        !it.name.startsWith("dummy", ignoreCase = true) &&
                        !it.name.startsWith("tun", ignoreCase = true) &&
                        !it.name.startsWith("rmnet", ignoreCase = true)
                }
            }

            targetInterfaces.forEach { network ->
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
        // Giữ 255.255.255.255 làm fallback toàn cục
        addresses += InetAddress.getByName("255.255.255.255")
        return addresses.toList()
    }

    companion object {
        private const val TAG = "MuseMobile"
        const val DISCOVERY_PORT = 47808
        private const val DISCOVERY_QUERY = "MUSE_R1_DISCOVERY"
    }
}
