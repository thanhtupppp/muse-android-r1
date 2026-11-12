package vn.tutu.muser1

import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * MQTT command channel giữa Muse Mobile App và R1 Agent.
 *
 * Topic:
 * - muse/r1/{deviceId}/cmd
 * - muse/r1/{deviceId}/status
 * - muse/r1/{deviceId}/event
 * - muse/r1/{deviceId}/telemetry
 */
class MqttAgent(
    private val host: String,
    private val deviceId: String,
    private val commandRouter: CommandRouter,
    executor: Executor? = null,
    private val username: String? = null,
    private val password: String? = null
) : MqttCallbackExtended {

    @Volatile
    private var client: MqttClient? = null

    @Volatile
    private var isDisposed: Boolean = false

    // Fallback executor để đảm bảo không bao giờ chặn I/O thread của Paho
    private val dispatchExecutor: Executor = executor ?: Executors.newSingleThreadExecutor()

    fun connect(): Boolean {
        try {
            val brokerUrl = normalizeBrokerUrl(host)
            val clientId = "muse-r1-$deviceId-${System.currentTimeMillis()}"

            val newClient = MqttClient(brokerUrl, clientId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 30
                if (!this@MqttAgent.username.isNullOrEmpty()) {
                    userName = this@MqttAgent.username
                }
                if (!this@MqttAgent.password.isNullOrEmpty()) {
                    password = this@MqttAgent.password.toCharArray()
                }
                // Thiết lập LWT (Last Will and Testament) để thông báo khi mất kết nối đột ngột
                setWill(
                    "muse/r1/$deviceId/status",
                    "\"offline\"".toByteArray(Charsets.UTF_8),
                    1,
                    true
                )
            }

            // Gán client trước khi connect() để callback connectComplete luôn thấy client != null
            client = newClient
            newClient.setCallback(this)
            newClient.connect(options)

            Log.i(TAG, "MQTT connect completed to $brokerUrl")
            return true
        } catch (error: Exception) {
            client = null
            Log.e(TAG, "MQTT connection failed", error)
            return false
        }
    }

    fun connectWithRetry(maxRetries: Int = 5) {
        isDisposed = false
        var attempt = 0
        while (attempt < maxRetries && !isDisposed) {
            if (connect()) return
            attempt++
            if (attempt < maxRetries && !isDisposed) {
                try {
                    val backoff = (1000L * (1 shl attempt)).coerceAtMost(30_000L)
                    Thread.sleep(backoff)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    override fun connectComplete(reconnect: Boolean, serverURI: String) {
        val commandTopic = "muse/r1/$deviceId/cmd"
        try {
            client?.subscribe(commandTopic, 1)
            publish("muse/r1/$deviceId/status", "\"online\"", retained = true)
            Log.i(TAG, "MQTT connectComplete (reconnect=$reconnect): $serverURI, subscribed=$commandTopic")
        } catch (error: Exception) {
            Log.e(TAG, "Lỗi khi subscribe lại MQTT sau reconnect", error)
        }
    }

    fun disconnect() {
        isDisposed = true
        try {
            publish("muse/r1/$deviceId/status", "\"offline\"", retained = true)
            client?.disconnect()
        } catch (error: Exception) {
            Log.w(TAG, "MQTT disconnect failed", error)
        } finally {
            client = null
        }
    }

    fun isConnected(): Boolean {
        return client?.isConnected == true
    }

    fun publish(topic: String, payload: String, retained: Boolean = false) {
        try {
            val mqttClient = client ?: return
            if (!mqttClient.isConnected) return

            val message = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
                qos = 1
                isRetained = retained
            }
            mqttClient.publish(topic, message)
        } catch (error: Exception) {
            Log.e(TAG, "MQTT publish failed: $topic", error)
        }
    }

    override fun connectionLost(cause: Throwable?) {
        Log.w(TAG, "MQTT connection lost", cause)
    }

    override fun messageArrived(topic: String, message: MqttMessage) {
        val payload = String(message.payload, Charsets.UTF_8)
        Log.i(TAG, "MQTT command received: $topic -> $payload")
        dispatchExecutor.execute {
            commandRouter.handle(payload)
        }
    }

    override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit

    private fun normalizeBrokerUrl(host: String): String {
        return when {
            host.startsWith("tcp://") -> host
            host.startsWith("ssl://") -> host
            host.startsWith("mqtt://") -> host.replace("mqtt://", "tcp://")
            host.startsWith("mqtts://") -> host.replace("mqtts://", "ssl://")
            host.startsWith("tls://") -> host.replace("tls://", "ssl://")
            else -> "tcp://$host"
        }
    }

    companion object {
        private const val TAG = "MuseR1"
    }
}
