package vn.tutu.muser1

import android.content.Context

object MusePreferences {

    private const val PREFS_NAME = "muse_r1_prefs"
    private const val KEY_MQTT_HOST = "mqtt_host"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_MUSE_TOKEN = "muse_token"

    fun saveAgentConfig(context: Context, mqttHost: String, deviceId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MQTT_HOST, mqttHost)
            .putString(KEY_DEVICE_ID, deviceId)
            .apply()
    }

    fun saveMuseToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MUSE_TOKEN, token)
            .apply()
    }

    fun getMqttHost(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MQTT_HOST, "")
            .orEmpty()
    }

    fun getDeviceId(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEVICE_ID, "")
            .orEmpty()
    }

    fun getMuseToken(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MUSE_TOKEN, "")
            .orEmpty()
    }

    fun isAgentConfigured(context: Context): Boolean {
        return getMqttHost(context).isNotEmpty() && getDeviceId(context).isNotEmpty()
    }
}
