package vn.tutu.muser1

import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

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
    private val commandRouter: CommandRouter
) : MqttCallback {

    private var client: MqttClient? = null

    fun connect() {
        try {
            val brokerUrl = normalizeBrokerUrl(host)
            val clientId = "muse-r1-$deviceId-${System.currentTimeMillis()}"

            val newClient = MqttClient(brokerUrl, clientId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10
                keepAlive = 30
            }

            newClient.setCallback(this)
            newClient.connect(options)

            val commandTopic = "muse/r1/$deviceId/cmd"
            newClient.subscribe(commandTopic, 1)

            publish("muse/r1/$deviceId/status", "\"online\"")
            client = newClient

            Log.i(TAG, "MQTT connected: $brokerUrl, topic=$commandTopic")
        } catch (error: Exception) {
            Log.e(TAG, "MQTT connection failed", error)
        }
    }

    fun disconnect() {
        try {
            client?.disconnect()
        } catch (error: Exception) {
            Log.w(TAG, "MQTT disconnect failed", error)
        }

        client = null
    }

    fun isConnected(): Boolean {
        return client?.isConnected == true
    }

    fun publish(topic: String, payload: String) {
        try {
            val mqttClient = client ?: return

            if (!mqttClient.isConnected) {
                return
            }

            mqttClient.publish(topic, MqttMessage(payload.toByteArray()).apply {
                qos = 1
            })
        } catch (error: Exception) {
            Log.e(TAG, "MQTT publish failed: $topic", error)
        }
    }

    override fun connectionLost(cause: Throwable?) {
        Log.w(TAG, "MQTT connection lost", cause)
    }

    override fun messageArrived(topic: String, message: MqttMessage) {
        val payload = String(message.payload)
        Log.i(TAG, "MQTT command received: $topic -> $payload")
        commandRouter.handle(payload)
    }

    override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit

    private fun normalizeBrokerUrl(host: String): String {
        return if (host.startsWith("tcp://") || host.startsWith("ssl://") || host.startsWith("mqtt://")) {
            host.replace("mqtt://", "tcp://")
        } else {
            "tcp://$host"
        }
    }

    companion object {
        private const val TAG = "MuseR1"
    }
}
