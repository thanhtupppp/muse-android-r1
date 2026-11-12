package vn.tutu.muser1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Kênh khôi phục/kỹ thuật qua ADB.
 *
 * Cấu hình Wi‑Fi:
 * adb shell am broadcast -a vn.tutu.muser1.PROVISION_WIFI \
 *   --es ssid "TEN_WIFI_NHA" --es password "MAT_KHAU"
 *
 * Cấu hình Muse token:
 * adb shell am broadcast -a vn.tutu.muser1.CONFIGURE_MUSE \
 *   --es muse_token "mgst_..."
 */
class ProvisionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PROVISION_WIFI -> provisionWifi(context, intent)
            ACTION_CONFIGURE_MUSE -> configureMuse(context, intent)
        }
    }

    private fun provisionWifi(context: Context, intent: Intent) {
        val ssid = intent.getStringExtra(EXTRA_SSID)?.trim().orEmpty()
        val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()

        if (ssid.isEmpty() || password.length < 8) {
            Log.e(TAG, "Invalid Wi‑Fi provisioning payload")
            return
        }

        val result = WifiProvisioner.connect(context, ssid, password)
        Log.i(TAG, "Wi‑Fi provisioning result: $result")
    }

    private fun configureMuse(context: Context, intent: Intent) {
        val museToken = intent.getStringExtra(EXTRA_MUSE_TOKEN)?.trim().orEmpty()

        if (museToken.isEmpty()) {
            Log.e(TAG, "Muse token is empty")
            return
        }

        val saved = MuseGadgetClient(context, MusePreferences.getDeviceId(context))
            .setToken(museToken)

        if (saved) {
            AgentService.start(context)
        }
    }

    companion object {
        private const val TAG = "MuseR1"

        const val ACTION_PROVISION_WIFI = "vn.tutu.muser1.PROVISION_WIFI"
        const val ACTION_CONFIGURE_MUSE = "vn.tutu.muser1.CONFIGURE_MUSE"

        const val EXTRA_SSID = "ssid"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_MUSE_TOKEN = "muse_token"
    }
}
