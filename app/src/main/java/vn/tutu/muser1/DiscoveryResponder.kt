package vn.tutu.muser1

import android.content.Context
import android.net.wifi.WifiManager
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
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (socket?.isBound == true) {
            return
        }

        try {
            // Bind socket TRƯỚC khi giữ MulticastLock: trước đây lock được lấy
            // trước, nếu bind văng exception thì lock rò và giữ radio thức mãi.
            socket = DatagramSocket(port).apply {
                broadcast = true
            }

            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            try {
                multicastLock = wifiManager?.createMulticastLock("muse_r1_discovery")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Không thể giữ MulticastLock", e)
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
            // Dọn dẹp một phần đã khởi tạo để lần start() sau thử lại sạch.
            try {
                if (multicastLock?.isHeld == true) {
                    multicastLock?.release()
                }
            } catch (_: Exception) {
            }
            multicastLock = null
            socket?.close()
            socket = null
        }
    }

    fun stop() {
        listenerJob?.cancel()
        listenerJob = null
        socket?.close()
        socket = null
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    private fun buildResponse(): String {
        // Chỉ trả về thông tin tối thiểu để Mobile nhận diện dịch vụ: KHÔNG
        // còn device_id và SSID mạng nhà như trước (bất kỳ host nào trong LAN
        // cũng quét được discovery này). IP của loa lấy từ địa chỉ nguồn của
        // gói phản hồi; chi tiết trạng thái lấy qua /status (đã có auth).
        return JSONObject()
            .put("service", "muse-r1-agent")
            .put("agent_version", BuildConfig.VERSION_NAME)
            .put("muse_paired", MusePreferences.isMusePaired(context))
            .toString()
    }

    companion object {
        private const val TAG = "MuseR1"
        const val DEFAULT_PORT = 47808
    }
}