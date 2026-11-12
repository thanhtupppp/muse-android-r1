package vn.tutu.muser1

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Headless background agent cho Phicomm R1.
 *
 * Service này:
 * - Giữ kết nối MQTT.
 * - Nhận lệnh từ Muse Mobile App.
 * - Kết nối Muse VM qua WebSocket.
 * - Gửi telemetry định kỳ.
 */
class AgentService : Service() {

    private var mqttAgent: MqttAgent? = null
    private var museSessionAdapter: MuseSessionAdapter? = null
    private var telemetryReporter: TelemetryReporter? = null
    private val handler = Handler(Looper.getMainLooper())

    private val telemetryTask = object : Runnable {
        override fun run() {
            telemetryReporter?.publishSnapshot()
            handler.postDelayed(this, TELEMETRY_INTERVAL_MS)
        }
    }

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
        handler.removeCallbacks(telemetryTask)
        museSessionAdapter?.stop()
        mqttAgent?.disconnect()
        mqttAgent = null
        Log.i(TAG, "Agent service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAgent() {
        val deviceId = MusePreferences.getDeviceId(this)

        if (deviceId.isEmpty()) {
            Log.w(TAG, "Device ID is missing")
            stopSelf()
            return
        }

        mqttAgent?.disconnect()
        museSessionAdapter?.stop()

        val commandSurface = MuseCommandSurface(this)

        mqttAgent = MqttAgent(
            host = MusePreferences.getMqttHost(this),
            deviceId = deviceId,
            commandRouter = CommandRouter(this)
        )

        museSessionAdapter = MuseSessionAdapter(
            context = this,
            deviceId = deviceId,
            commandSurface = commandSurface
        )

        telemetryReporter = TelemetryReporter(this, deviceId, mqttAgent!!)

        mqttAgent?.connect()
        museSessionAdapter?.start()

        handler.removeCallbacks(telemetryTask)
        handler.postDelayed(telemetryTask, TELEMETRY_INTERVAL_MS)
    }

    companion object {
        private const val TAG = "MuseR1"
        private const val TELEMETRY_INTERVAL_MS = 30_000L

        fun start(context: Context) {
            context.startService(Intent(context, AgentService::class.java))
        }
    }
}
