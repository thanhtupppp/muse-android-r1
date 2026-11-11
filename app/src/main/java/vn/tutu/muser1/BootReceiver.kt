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

        if (!MusePreferences.isAgentConfigured(context)) {
            Log.i(TAG, "Boot completed but agent is not configured")
            return
        }

        Log.i(TAG, "Boot completed, starting Muse R1 agent")
        AgentService.start(context)
    }

    companion object {
        private const val TAG = "MuseR1"
    }
}
