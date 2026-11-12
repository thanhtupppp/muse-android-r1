package vn.tutu.muser1

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs

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
    private var bgExecutor: ScheduledExecutorService? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Agent service created")
        bgExecutor = Executors.newSingleThreadScheduledExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        startProvisioningServer()
        startBluetoothProvisionServer()
        startDiscoveryResponder()
        startTelemetry()
        startMuseSession()
        announceReadyWhenAvailable()
        return START_STICKY
    }

    override fun onDestroy() {
        readyAnnouncementRequested = false
        handler.removeCallbacksAndMessages(null)

        bgExecutor?.shutdownNow()
        bgExecutor = null

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

    private fun buildForegroundNotification(): Notification {
        val channelId = "muse_r1_agent_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Muse R1 Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Muse R1 Agent")
            .setContentText("Dịch vụ điều khiển loa R1 đang chạy")
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

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
        // Android 5.1.1 (API 22) does not have Settings.Global.BOOT_COUNT (API 24+).
        // Instead, calculate approximate boot epoch time: (currentTime - elapsedRealtime).
        // Across reboots, this changes by days/hours; within the same boot, it stays constant.
        val currentBootEpoch = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val lastBootEpoch = preferences.getLong(KEY_LAST_BOOT_EPOCH, 0L)

        if (lastBootEpoch > 0L && abs(currentBootEpoch - lastBootEpoch) < 60_000L) {
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

        try {
            player.start()
            preferences.edit().putLong(KEY_LAST_BOOT_EPOCH, currentBootEpoch).apply()
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

        val executor = bgExecutor ?: return

        if (mqttAgent == null) {
            val commandRouter = CommandRouter(this)
            val newAgent = MqttAgent(mqttHost, deviceId, commandRouter)
            mqttAgent = newAgent
            executor.execute {
                newAgent.connect()
            }
        }

        val agent = mqttAgent ?: return
        if (telemetryReporter == null) {
            telemetryReporter = TelemetryReporter(this, deviceId, agent)
        }

        // Run telemetry publisher periodically on background executor (not main thread)
        executor.scheduleWithFixedDelay(
            {
                if (agent.isConnected()) {
                    telemetryReporter?.publishSnapshot()
                }
            },
            5,
            TELEMETRY_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
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
        private const val NOTIFICATION_ID = 1001
        private const val TELEMETRY_INTERVAL_SECONDS = 30L
        private const val READY_ANNOUNCEMENT_DELAY_MS = 800L
        private const val READY_AUDIO_PREFS = "muse_r1_ready_audio"
        private const val KEY_LAST_BOOT_EPOCH = "last_boot_epoch_ms"

        fun start(context: Context) {
            context.startService(Intent(context, AgentService::class.java))
        }
    }
}
