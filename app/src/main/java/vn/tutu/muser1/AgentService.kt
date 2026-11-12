package vn.tutu.muser1

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log

class AgentService : Service() {

    private var provisionServer: LocalProvisionServer? = null
    private var discoveryResponder: DiscoveryResponder? = null
    private var bluetoothProvisionServer: BluetoothProvisionServer? = null
    private var museSessionAdapter: MuseSessionAdapter? = null
    private var mqttAgent: MqttAgent? = null
    private var telemetryReporter: TelemetryReporter? = null
    private var readyPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var readyAnnouncementRequested = false

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
        announceReadyWhenAvailable()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(telemetryTask)
        handler.removeCallbacks(readyAnnouncementTask)
        readyPlayer?.release()
        readyPlayer = null
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

    private fun announceReadyWhenAvailable() {
        if (readyAnnouncementRequested) return
        readyAnnouncementRequested = true
        handler.postDelayed(readyAnnouncementTask, READY_ANNOUNCEMENT_DELAY_MS)
    }

    private val readyAnnouncementTask = Runnable {
        if (provisionServer?.wasStarted() != true) {
            Log.w(TAG, "Ready announcement skipped: HTTP server is not ready")
            return@Runnable
        }

        val preferences = getSharedPreferences(READY_AUDIO_PREFS, Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val lastPlayed = preferences.getLong(KEY_LAST_PLAYED_ELAPSED, 0L)

        // elapsedRealtime resets on a real reboot, but not on service restarts.
        if (lastPlayed > 0L && now >= lastPlayed) {
            Log.i(TAG, "Ready announcement already played during this boot")
            return@Runnable
        }

        val player = MediaPlayer.create(this, R.raw.agent_ready)
        if (player == null) {
            Log.e(TAG, "Could not create ready announcement player")
            return@Runnable
        }

        readyPlayer = player
        player.setOnCompletionListener {
            Log.i(TAG, "Ready announcement playback completed")
            it.release()
            if (readyPlayer === it) readyPlayer = null
        }
        player.setOnErrorListener { mediaPlayer, what, extra ->
            Log.e(TAG, "Ready announcement playback failed: what=$what extra=$extra")
            mediaPlayer.release()
            if (readyPlayer === mediaPlayer) readyPlayer = null
            true
        }

        preferences.edit().putLong(KEY_LAST_PLAYED_ELAPSED, now).apply()
        try {
            player.start()
            Log.i(TAG, "Playing Muse R1 ready announcement")
        } catch (error: Exception) {
            Log.e(TAG, "Could not start ready announcement playback", error)
            player.release()
            readyPlayer = null
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
        private const val READY_ANNOUNCEMENT_DELAY_MS = 800L
        private const val READY_AUDIO_PREFS = "muse_r1_ready_audio"
        private const val KEY_LAST_PLAYED_ELAPSED = "last_played_elapsed_ms"

        fun start(context: Context) {
            context.startService(Intent(context, AgentService::class.java))
        }
    }
}
