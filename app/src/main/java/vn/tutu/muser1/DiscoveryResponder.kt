package vn.tutu.muser1

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket

class DiscoveryResponder(
    private val context: Context,
    private val port: Int = DEFAULT_PORT
) {

    private var socket: DatagramSocket? = null
    private var listenerJob: Job? = null

    fun start() {
        if (socket?.isBound == true) {
            return
        }

        try {
            socket = DatagramSocket(port).apply {
                broadcast = true
            }

            listenerJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ByteArray(2048)

                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket?.receive(packet)

                        val message = String(packet.data, 0, packet.length)

                        if (message.trim() != "MUSE_R1_DISCOVERY") {
                            continue
                        }

                        val response = buildResponse()
                        val responseData = response.toByteArray()

                        socket?.send(
                            DatagramPacket(
                                responseData,
                                responseData.size,
                                packet.address,
                                packet.port
                            )
                        )
                    } catch (error: Exception) {
                        Log.w(TAG, "Discovery receive failed", error)
                    }
                }
            }

            Log.i(TAG, "Discovery responder started on port $port")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to start discovery responder", error)
        }
    }

    fun stop() {
        listenerJob?.cancel()
        listenerJob = null
        socket?.close()
        socket = null
    }

    private fun buildResponse(): String {
        val wifiManager = context.getSystemService(WifiManager::class.java)
        val ssid = wifiManager.connectionInfo?.ssid
            ?.removeSurrounding("\"")
            .orEmpty()

        return JSONObject()
            .put("service", "muse-r1-agent")
            .put("device_id", MusePreferences.getDeviceId(context))
            .put("agent_version", BuildConfig.VERSION_NAME)
            .put("ssid", ssid)
            .put("muse_paired", MusePreferences.isMusePaired(context))
            .toString()
    }

    companion object {
        private const val TAG = "MuseR1"
        const val DEFAULT_PORT = 47808
    }
}