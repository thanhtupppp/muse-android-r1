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
 * - Chạy local HTTP provisioning server cho Muse Mobile App.
 * - Giữ kết nối Muse session qua WebSocket.
 * - Gửi telemetry định kỳ.
 */
class AgentService : Service() {

    private var provisionServer: LocalProvisionServer? = null
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
        startProvisioningServer()
        startMuseSession()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(telemetryTask)
        provisionServer?.stopServer()
        provisionServer = null

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
            Log.i(TAG, "Provisioning server ready on port ${LocalProvisionServer.DEFAULT_PORT}")
        }
    }

    private fun startMuseSession() {
        val deviceId = MusePreferences.getDeviceId(this)

        if (deviceId.isEmpty()) {
            Log.w(TAG, "Device ID is missing; Muse session will not start")
            return
        }

        museSessionAdapter?.stop()
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
