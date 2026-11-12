package vn.tutu.muser1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.i(TAG, "Trigger (${intent.action}), starting Muse R1 agent")
                try {
                    AgentService.start(context.applicationContext)
                } catch (error: Exception) {
                    Log.e(TAG, "Could not start Muse R1 agent", error)
                }
            }
        }
    }

    companion object {
        private const val TAG = "MuseR1"
    }
}
