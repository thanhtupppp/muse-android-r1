package vn.tutu.muser1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Nhận cấu hình từ ADB hoặc Muse Mobile App.
 *
 * Cấu hình Wi‑Fi:
 * adb shell am broadcast -a vn.tutu.muser1.PROVISION_WIFI \
 *   --es ssid "TEN_WIFI_NHA" --es password "MAT_KHAU"
 *
 * Cấu hình agent:
 * adb shell am broadcast -a vn.tutu.muser1.CONFIGURE_AGENT \
 *   --es mqtt_host "tcp://192.168.1.10:1883" --es device_id "R1-001"
 *
 * Cấu hình Muse token:
 * adb shell am broadcast -a vn.tutu.muser1.CONFIGURE_MUSE \
 *   --es muse_token "mgst_..."
 */
class ProvisionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PROVISION_WIFI -> provisionWifi(context, intent)
            ACTION_CONFIGURE_AGENT -> configureAgent(context, intent)
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

        val wifiManager = context.getSystemService(WifiManager::class.java)
        val result = WifiProvisioner.connect(wifiManager, ssid, password)
        Log.i(TAG, "Wi‑Fi provisioning: $result")
    }

    private fun configureAgent(context: Context, intent: Intent) {
        val mqttHost = intent.getStringExtra(EXTRA_MQTT_HOST)?.trim().orEmpty()
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)?.trim().orEmpty()

        if (mqttHost.isEmpty() || deviceId.isEmpty()) {
            Log.e(TAG, "Invalid agent configuration payload")
            return
        }

        MusePreferences.saveAgentConfig(context, mqttHost, deviceId)
        AgentService.start(context)
        Log.i(TAG, "Agent configured: deviceId=$deviceId, mqtt=$mqttHost")
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
        const val ACTION_CONFIGURE_AGENT = "vn.tutu.muser1.CONFIGURE_AGENT"
        const val ACTION_CONFIGURE_MUSE = "vn.tutu.muser1.CONFIGURE_MUSE"

        const val EXTRA_SSID = "ssid"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_MQTT_HOST = "mqtt_host"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_MUSE_TOKEN = "muse_token"
    }
}
