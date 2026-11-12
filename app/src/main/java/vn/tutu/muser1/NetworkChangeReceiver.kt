package vn.tutu.muser1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Đảm bảo AgentService chạy khi cấu hình mạng của R1 thay đổi giữa SoftAP và STA.
 */
class NetworkChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WifiManager.WIFI_STATE_CHANGED_ACTION &&
            intent.action != WifiManager.NETWORK_STATE_CHANGED_ACTION
        ) return

        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiEnabled = wifi?.isWifiEnabled == true
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        @Suppress("DEPRECATION")
        val connected = connectivity?.activeNetworkInfo?.isConnected == true

        if (!wifiEnabled && !connected) {
            Log.d(TAG, "Wi-Fi đổi trạng thái nhưng chưa có mạng hoạt động; đợi sự kiện mạng kế tiếp")
            return
        }

        // Nếu service đang chạy rồi thì không cần gửi Intent start lại liên tục
        if (AgentService.isRunning) {
            Log.d(TAG, "AgentService đã đang chạy, bỏ qua sự kiện mạng lặp")
            return
        }

        // Debounce 2 giây ở cấp static Companion để gộp các broadcast liên tiếp từ Android
        val appContext = context.applicationContext
        synchronized(lock) {
            pendingStartTask?.let { handler.removeCallbacks(it) }
            val task = Runnable {
                if (!AgentService.isRunning) {
                    Log.i(TAG, "Wi-Fi sẵn sàng (enabled=$wifiEnabled, connected=$connected); kích hoạt AgentService")
                    try {
                        AgentService.start(appContext)
                    } catch (error: Exception) {
                        Log.e(TAG, "Không thể khởi chạy Agent sau khi đổi mạng", error)
                    }
                }
            }
            pendingStartTask = task
            handler.postDelayed(task, DEBOUNCE_DELAY_MS)
        }
    }

    companion object {
        private const val TAG = "MuseR1"
        private const val DEBOUNCE_DELAY_MS = 2_000L
        private val handler = Handler(Looper.getMainLooper())
        private val lock = Any()
        private var pendingStartTask: Runnable? = null
    }
}
