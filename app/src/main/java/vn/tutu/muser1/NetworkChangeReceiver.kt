package vn.tutu.muser1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Restarts the Agent after the R1 radio changes between SoftAP and STA.
 * This is intentionally limited to configured devices and Wi-Fi broadcasts.
 */
class NetworkChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WifiManager.WIFI_STATE_CHANGED_ACTION &&
            intent.action != WifiManager.NETWORK_STATE_CHANGED_ACTION
        ) return

        if (!MusePreferences.isAgentConfigured(context)) {
            Log.d(TAG, "Ignoring Wi-Fi change: Agent is not configured")
            return
        }

        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiEnabled = wifi?.isWifiEnabled == true
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        @Suppress("DEPRECATION")
        val connected = connectivity?.activeNetworkInfo?.isConnected == true

        if (wifiEnabled || connected) {
            Log.i(TAG, "Wi-Fi state changed (enabled=$wifiEnabled, connected=$connected); ensuring Agent is running")
            try {
                AgentService.start(context)
            } catch (error: Exception) {
                Log.e(TAG, "Could not restart Agent after Wi-Fi change", error)
            }
        }
    }

    companion object {
        private const val TAG = "MuseR1"
    }
}
