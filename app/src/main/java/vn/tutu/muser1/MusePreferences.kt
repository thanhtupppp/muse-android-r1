package vn.tutu.muser1

import android.content.Context

object MusePreferences {

    private const val PREFS_NAME = "muse_r1_prefs"
    private const val KEY_MQTT_HOST = "mqtt_host"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_MUSE_TOKEN = "muse_token"
    private const val KEY_ACCESS_TOKEN = "muse_access_token"
    private const val KEY_REFRESH_TOKEN = "muse_refresh_token"

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

    fun saveMuseSessionTokens(
        context: Context,
        accessToken: String,
        refreshToken: String
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .apply()
    }

    fun getMqttHost(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MQTT_HOST, "")
            .orEmpty()
    }

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_DEVICE_ID, "")
        if (!saved.isNullOrEmpty()) return saved

        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
        return if (!androidId.isNullOrEmpty()) "r1-$androidId" else "r1-device"
    }

    fun getMuseToken(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MUSE_TOKEN, "")
            .orEmpty()
    }

    fun getMuseAccessToken(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACCESS_TOKEN, "")
            .orEmpty()
    }

    fun getMuseRefreshToken(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_REFRESH_TOKEN, "")
            .orEmpty()
    }

    fun getProvisionToken(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("provision_token", "")
            .orEmpty()
    }

    fun saveProvisionToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("provision_token", token)
            .apply()
    }

    fun isAgentConfigured(context: Context): Boolean {
        return getMqttHost(context).isNotEmpty() && getDeviceId(context).isNotEmpty()
    }

    fun isMusePaired(context: Context): Boolean {
        return getMuseToken(context).startsWith("mgst_") ||
            (getMuseAccessToken(context).isNotEmpty() && getMuseRefreshToken(context).isNotEmpty())
    }
}
