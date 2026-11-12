package vn.tutu.muser1

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

class AgentService : Service() {

    private var provisionServer: LocalProvisionServer? = null
    private var discoveryResponder: DiscoveryResponder? = null
    private var bluetoothProvisionServer: BluetoothProvisionServer? = null
    private var museSessionAdapter: MuseSessionAdapter? = null
    private var mqttAgent: MqttAgent? = null
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
        startProvisioningServer()
        startBluetoothProvisionServer()
        startDiscoveryResponder()
        startTelemetry()
        startMuseSession()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(telemetryTask)
        telemetryReporter = null

        mqttAgent?.disconnect()
        mqttAgent = null

        provisionServer?.stopServer()
        provisionServer = null

        bluetoothProvisionServer?.stop()
        bluetoothProvisionServer = null

        discoveryResponder?.stop()
        discoveryResponder = null

        museSessionAdapter?.stop()
        museSessionAdapter = null

        Log.i(TAG, "Agent service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startProvisioningServer() {
        if (provisionServer?.wasStarted() == true) {
            return
        }

        provisionServer = LocalProvisionServer(this)

        if (provisionServer?.startServer() == true) {
            Log.i(
                TAG,
                "Provisioning server ready on port ${LocalProvisionServer.DEFAULT_PORT}"
            )
        }
    }

    private fun startBluetoothProvisionServer() {
        if (bluetoothProvisionServer == null) {
            bluetoothProvisionServer = BluetoothProvisionServer(this)
        }
        bluetoothProvisionServer?.start()
    }

    private fun startDiscoveryResponder() {
        if (discoveryResponder == null) {
            discoveryResponder = DiscoveryResponder(this)
        }

        discoveryResponder?.start()
    }

    private fun startTelemetry() {
        val deviceId = MusePreferences.getDeviceId(this)
        val mqttHost = MusePreferences.getMqttHost(this)

        if (deviceId.isEmpty() || mqttHost.isEmpty()) {
            Log.d(TAG, "MQTT host or Device ID missing; Telemetry will wait")
            return
        }

        if (mqttAgent == null) {
            val commandRouter = CommandRouter(this)
            mqttAgent = MqttAgent(mqttHost, deviceId, commandRouter)
            Thread {
                mqttAgent?.connect()
            }.start()
        }

        val agent = mqttAgent ?: return
        if (telemetryReporter == null) {
            telemetryReporter = TelemetryReporter(this, deviceId, agent)
        }

        telemetryReporter?.publishSnapshot()

        handler.removeCallbacks(telemetryTask)
        handler.postDelayed(telemetryTask, TELEMETRY_INTERVAL_MS)
    }

    private fun startMuseSession() {
        val deviceId = MusePreferences.getDeviceId(this)

        if (deviceId.isEmpty()) {
            Log.w(TAG, "Device ID is missing; Muse session will not start")
            return
        }

        if (museSessionAdapter != null) {
            return
        }

        museSessionAdapter = MuseSessionAdapter(
            context = this,
            deviceId = deviceId,
            commandSurface = MuseCommandSurface(this)
        )

        museSessionAdapter?.start()
    }

    companion object {
        private const val TAG = "MuseR1"
        private const val TELEMETRY_INTERVAL_MS = 30_000L

        fun start(context: Context) {
            context.startService(Intent(context, AgentService::class.java))
        }
    }
}
