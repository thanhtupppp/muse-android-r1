package vn.tutu.muser1

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class AgentService : Service() {

    private var provisionServer: LocalProvisionServer? = null
    private var discoveryResponder: DiscoveryResponder? = null
    private var bluetoothProvisionServer: BluetoothProvisionServer? = null
    private var museBleServer: MuseBleServer? = null
    private var museSessionAdapter: MuseSessionAdapter? = null
    private var mqttAgent: MqttAgent? = null
    private var telemetryReporter: TelemetryReporter? = null
    private var readyPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var readyAnnouncementRequested = false
    private var bgExecutor: ScheduledExecutorService? = null
    private var telemetryFuture: ScheduledFuture<*>? = null

    private val readyAnnouncementTask = Runnable {
        if (provisionServer?.wasStarted() != true) {
            Log.w(TAG, "Ready announcement skipped: HTTP server is not ready")
            return@Runnable
        }

        val preferences = getSharedPreferences(READY_AUDIO_PREFS, Context.MODE_PRIVATE)
        val nowElapsed = SystemClock.elapsedRealtime()
        val lastElapsed = preferences.getLong(KEY_LAST_ELAPSED, 0L)
        var hasPlayed = preferences.getBoolean(KEY_HAS_PLAYED_THIS_BOOT, false)

        // SystemClock.elapsedRealtime() là bộ đếm phần cứng đơn điệu.
        // Nếu nowElapsed < lastElapsed nghĩa là thiết bị vừa khởi động lại (hardware reboot).
        if (nowElapsed < lastElapsed) {
            hasPlayed = false
        }

        if (hasPlayed) {
            Log.i(TAG, "Ready announcement already played during this boot")
            preferences.edit().putLong(KEY_LAST_ELAPSED, nowElapsed).apply()
            return@Runnable
        }

        val player = MediaPlayer.create(this, R.raw.agent_ready)
        if (player == null) {
            Log.e(TAG, "Could not create ready announcement player")
            return@Runnable
        }

        readyPlayer = player
        // Đăng ký để lệnh stop_audio dừng được cả tiếng chuông này.
        SpeechOutput.trackPlayer(player)
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
            preferences.edit()
                .putBoolean(KEY_HAS_PLAYED_THIS_BOOT, true)
                .putLong(KEY_LAST_ELAPSED, nowElapsed)
                .apply()
            Log.i(TAG, "Playing Muse R1 ready announcement")
        } catch (error: Exception) {
            Log.e(TAG, "Could not start ready announcement playback", error)
            player.release()
            readyPlayer = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // Mở cửa sổ cấu hình HTTP 10 phút sau mỗi lần Agent khởi động (cùng
        // nhịp với cửa sổ pairing BLE): chủ loa ở gần loa dùng Mobile cấu
        // hình lần đầu / lấy lại provision token mà không bị khoá ngoài.
        openHttpProvisionWindow()
        Log.i(TAG, "Agent service created")
        bgExecutor = Executors.newScheduledThreadPool(2)
        registerSpeakerButtonReceiver()
    }

    /**
     * Đăng ký MediaButtonReceiver làm đầu nhận media button của hệ thống, để
     * nút tròn trên đỉnh loa tới được Agent (phục vụ confirm_press khi pair).
     * Dùng API legacy registerMediaButtonEventReceiver vì thiết bị chạy
     * Android 5.1 / targetSdk 28.
     */
    @Suppress("DEPRECATION")
    private fun registerSpeakerButtonReceiver() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audioManager.registerMediaButtonEventReceiver(
                ComponentName(this, MediaButtonReceiver::class.java)
            )
            Log.i(TAG, "Đã đăng ký MediaButtonReceiver cho nút tròn trên loa")
        } catch (e: Exception) {
            Log.w(TAG, "Không đăng ký được media button receiver", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun unregisterSpeakerButtonReceiver() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audioManager.unregisterMediaButtonEventReceiver(
                ComponentName(this, MediaButtonReceiver::class.java)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Không huỷ được media button receiver", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        startProvisioningServer()
        startBluetoothProvisionServer()
        startMuseBleServer()
        if (intent?.action == ACTION_REOPEN_PAIRING_WINDOW) {
            museBleServer?.reopenPairingWindow()
            // Mở luôn cửa sổ HTTP: ADB/người ở gần loa là bằng chứng chủ sở hữu.
            openHttpProvisionWindow()
        }
        if (intent?.action == ACTION_CONFIRM_PAIRING_PRESS) {
            Log.i(TAG, "Nhận CONFIRM_PAIRING_PRESS qua intent service (giả lập nút tròn, kênh ADB)")
            SpeakerButtonMonitor.notePress("adb_service")
        }
        startDiscoveryResponder()
        startTelemetry()
        val forceReload = intent?.action == ACTION_RELOAD_MUSE_SESSION
        startMuseSession(forceRestart = forceReload)
        announceReadyWhenAvailable()
        return START_STICKY
    }

    override fun onDestroy() {
        readyAnnouncementRequested = false
        handler.removeCallbacksAndMessages(null)
        unregisterSpeakerButtonReceiver()
        SpeakerButtonMonitor.disarm()

        telemetryFuture?.cancel(false)
        telemetryFuture = null

        bgExecutor?.shutdownNow()
        bgExecutor = null

        readyPlayer?.release()
        readyPlayer = null
        SpeechOutput.shutdown()
        telemetryReporter = null

        mqttAgent?.disconnect()
        mqttAgent = null

        provisionServer?.stopServer()
        provisionServer = null

        bluetoothProvisionServer?.stop()
        bluetoothProvisionServer = null

        museBleServer?.stop()
        museBleServer = null
        setCurrentBleServer(null)

        discoveryResponder?.stop()
        discoveryResponder = null

        museSessionAdapter?.stop()
        museSessionAdapter = null

        isRunning = false
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

    private fun startBluetoothProvisionServer() {
        if (bluetoothProvisionServer == null) {
            bluetoothProvisionServer = BluetoothProvisionServer(this)
        }
        bluetoothProvisionServer?.start()
    }

    private fun startMuseBleServer() {
        if (museBleServer == null) {
            museBleServer = MuseBleServer(this) {
                startMuseSession(forceRestart = true)
            }
            setCurrentBleServer(museBleServer)
        }
        museBleServer?.start()
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

        val executor = bgExecutor?.takeUnless { it.isShutdown } ?: return

        val agent = mqttAgent ?: MqttAgent(mqttHost, deviceId, CommandRouter(this), executor).also {
            mqttAgent = it
            executor.execute { it.connectWithRetry() }
        }

        if (telemetryReporter == null) {
            telemetryReporter = TelemetryReporter(this, deviceId, agent)
        }

        // Tránh lập lịch trùng lặp nhiều task khi onStartCommand được gọi lại
        if (telemetryFuture?.isDone == false) return

        telemetryFuture = executor.scheduleWithFixedDelay(
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

    private fun startMuseSession(forceRestart: Boolean = false) {
        val deviceId = MusePreferences.getDeviceId(this)

        if (deviceId.isEmpty()) {
            Log.w(TAG, "Device ID is missing; Muse session will not start")
            return
        }

        if (!forceRestart && museSessionAdapter != null) {
            return
        }

        museSessionAdapter?.stop()
        isMuseConnected = false
        lastMuseError = ""

        val adapter = MuseSessionAdapter(
            context = this,
            deviceId = deviceId,
            commandSurface = MuseCommandSurface(this)
        ) { connected, error ->
            isMuseConnected = connected
            lastMuseError = error
        }
        museSessionAdapter = adapter
        adapter.start()
    }

    companion object {
        private const val TAG = "MuseR1"
        private const val ACTION_RELOAD_MUSE_SESSION = "vn.tutu.muser1.ACTION_RELOAD_MUSE_SESSION"
        private const val ACTION_REOPEN_PAIRING_WINDOW = "vn.tutu.muser1.ACTION_REOPEN_PAIRING_WINDOW"
        private const val ACTION_CONFIRM_PAIRING_PRESS = "vn.tutu.muser1.ACTION_CONFIRM_PAIRING_PRESS"
        private const val HTTP_PROVISION_WINDOW_MS = 10 * 60 * 1000L
        private const val NOTIFICATION_ID = 1001
        private const val TELEMETRY_INTERVAL_SECONDS = 30L
        private const val READY_ANNOUNCEMENT_DELAY_MS = 800L
        private const val READY_AUDIO_PREFS = "muse_r1_ready_audio"
        private const val KEY_LAST_ELAPSED = "last_elapsed_realtime_ms"
        private const val KEY_HAS_PLAYED_THIS_BOOT = "has_played_this_boot"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isMuseConnected: Boolean = false
            private set

        @Volatile
        var lastMuseError: String = ""
            private set

        /** BLE server đang sống, để /status đọc trạng thái quảng bá/pair. */
        @Volatile
        var currentBleServer: MuseBleServer? = null
            private set

        /**
         * Mốc thời gian (ms) mà trước đó HTTP :8080 tạm thời không bắt
         * provision token dù loa đã cấu hình: mở khi Agent khởi động và khi
         * có lệnh mở lại cửa sổ pairing (đường ADB/chủ loa ở gần). Xem
         * LocalProvisionServer.isAuthorized.
         */
        @Volatile
        var httpProvisionWindowUntilMs: Long = 0L
            private set

        fun isHttpProvisionWindowOpen(): Boolean =
            System.currentTimeMillis() < httpProvisionWindowUntilMs

        fun openHttpProvisionWindow() {
            httpProvisionWindowUntilMs =
                System.currentTimeMillis() + HTTP_PROVISION_WINDOW_MS
        }

        internal fun setCurrentBleServer(server: MuseBleServer?) {
            currentBleServer = server
        }

        fun start(context: Context) {
            val intent = Intent(context, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun reloadMuseSession(context: Context) {
            val intent = Intent(context, AgentService::class.java).apply {
                action = ACTION_RELOAD_MUSE_SESSION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Giả lập bấm nút tròn (confirm_press) qua ADB:
         * adb shell am startservice -n vn.tutu.muser1/.AgentService \
         *   -a vn.tutu.muser1.ACTION_CONFIRM_PAIRING_PRESS
         * (Đường broadcast cũ đã khoá bằng signature permission.)
         */
        fun confirmPairingPress(context: Context) {
            val intent = Intent(context, AgentService::class.java).apply {
                action = ACTION_CONFIRM_PAIRING_PRESS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        /** Mở lại cửa sổ pairing BLE qua ADB, không cần restart Agent/loa. */
        fun reopenPairingWindow(context: Context) {
            val intent = Intent(context, AgentService::class.java).apply {
                action = ACTION_REOPEN_PAIRING_WINDOW
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
