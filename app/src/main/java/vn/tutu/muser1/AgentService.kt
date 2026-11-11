package vn.tutu.muser1

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Headless background agent cho Phicomm R1.
 *
 * Service này giữ kết nối MQTT và xử lý lệnh từ Muse Mobile App.
 * Sau này, MuseGadgetClient sẽ kết nối R1 với Meta Muse Gadget SDK.
 */
class AgentService : Service() {

    private var mqttAgent: MqttAgent? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Agent service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!MusePreferences.isAgentConfigured(this)) {
            Log.w(TAG, "Agent is not configured. Waiting for CONFIGURE_AGENT broadcast.")
            stopSelf()
            return START_NOT_STICKY
        }

        startAgent()
        return START_STICKY
    }

    override fun onDestroy() {
        mqttAgent?.disconnect()
        mqttAgent = null
        Log.i(TAG, "Agent service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAgent() {
        if (mqttAgent?.isConnected() == true) {
            return
        }

        mqttAgent?.disconnect()
        mqttAgent = MqttAgent(
            context = this,
            host = MusePreferences.getMqttHost(this),
            deviceId = MusePreferences.getDeviceId(this),
            commandRouter = CommandRouter(this)
        )

        mqttAgent?.connect()
    }

    companion object {
        private const val TAG = "MuseR1"

        fun start(context: Context) {
            context.startService(Intent(context, AgentService::class.java))
        }
    }
}
