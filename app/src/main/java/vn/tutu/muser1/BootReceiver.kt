package vn.tutu.muser1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        // The local provisioning API and UDP discovery must run before MQTT
        // credentials/device pairing are configured.
        Log.i(TAG, "Boot completed, starting Muse R1 agent in provisioning-capable mode")
        try {
            AgentService.start(context)
        } catch (error: Exception) {
            Log.e(TAG, "Could not start Muse R1 agent after boot", error)
        }
    }

    companion object {
        private const val TAG = "MuseR1"
    }
}
