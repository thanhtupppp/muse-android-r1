package vn.tutu.muser1

import android.content.Context

object MusePreferences {

    private const val PREFS_NAME = "muse_r1_prefs"
    private const val KEY_MQTT_HOST = "mqtt_host"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_MUSE_TOKEN = "muse_token"
    private const val KEY_ACCESS_TOKEN = "muse_access_token"
    private const val KEY_REFRESH_TOKEN = "muse_refresh_token"
    private const val KEY_NOISE_HOST = "muse_noise_host"
    private const val KEY_API_URL = "muse_api_url"
    private const val KEY_API_URL_V2 = "muse_api_url_v2"
    private const val KEY_USERNAME = "muse_username"
    private const val KEY_DIRECT_VM_ID = "muse_direct_vm_id"
    private const val KEY_DIRECT_TOKEN = "muse_direct_token"

    const val DEFAULT_NOISE_HOST = "hatch.metaaivm.com"

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
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_DIRECT_VM_ID)
            .remove(KEY_DIRECT_TOKEN)
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
            .remove(KEY_DIRECT_VM_ID)
            .remove(KEY_DIRECT_TOKEN)
            .apply()
    }

    fun saveBleProvisionConfig(
        context: Context,
        accessToken: String,
        refreshToken: String,
        apiUrl: String? = null,
        apiUrlV2: String? = null,
        noiseHost: String? = null,
        username: String? = null
    ) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCESS_TOKEN, accessToken.trim())
            .putString(KEY_REFRESH_TOKEN, refreshToken.trim())
            .remove(KEY_DIRECT_VM_ID)
            .remove(KEY_DIRECT_TOKEN)
            .remove(KEY_MUSE_TOKEN)

        if (!apiUrl.isNullOrBlank()) editor.putString(KEY_API_URL, apiUrl.trim())
        if (!apiUrlV2.isNullOrBlank()) editor.putString(KEY_API_URL_V2, apiUrlV2.trim())
        val normalizedHost = normalizeHost(noiseHost.orEmpty())
        if (normalizedHost.isNotEmpty()) editor.putString(KEY_NOISE_HOST, normalizedHost)
        if (!username.isNullOrBlank()) editor.putString(KEY_USERNAME, username.trim())
        editor.apply()
    }

    fun saveDirectConfig(
        context: Context,
        noiseHost: String,
        vmId: String,
        deviceToken: String
    ) {
        val host = normalizeHost(noiseHost).ifEmpty { DEFAULT_NOISE_HOST }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NOISE_HOST, host)
            .putString(KEY_DIRECT_VM_ID, vmId.trim())
            .putString(KEY_DIRECT_TOKEN, deviceToken.trim())
            .remove(KEY_MUSE_TOKEN)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
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

    fun getNoiseHost(context: Context): String {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_NOISE_HOST, "")
            .orEmpty()
        return normalizeHost(raw).ifEmpty { DEFAULT_NOISE_HOST }
    }

    fun getApiUrl(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_URL, "")
            .orEmpty()
    }

    fun getApiUrlV2(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_URL_V2, "")
            .orEmpty()
    }

    fun getUsername(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_USERNAME, "")
            .orEmpty()
    }

    fun getDirectVmId(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DIRECT_VM_ID, "")
            .orEmpty()
    }

    fun getDirectToken(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DIRECT_TOKEN, "")
            .orEmpty()
    }

    fun hasDirectConfig(context: Context): Boolean {
        return getDirectVmId(context).isNotEmpty() && getDirectToken(context).isNotEmpty()
    }

    fun getMusePairingMode(context: Context): String {
        return when {
            hasDirectConfig(context) -> "direct"
            getMuseAccessToken(context).isNotEmpty() || getMuseRefreshToken(context).isNotEmpty() -> "oauth"
            getMuseToken(context).startsWith("mgst_") -> "sdk_token"
            else -> "none"
        }
    }

    fun clearMusePairing(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_MUSE_TOKEN)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_NOISE_HOST)
            .remove(KEY_API_URL)
            .remove(KEY_API_URL_V2)
            .remove(KEY_USERNAME)
            .remove(KEY_DIRECT_VM_ID)
            .remove(KEY_DIRECT_TOKEN)
            .apply()
    }

    fun normalizeHost(raw: String): String {
        var cleaned = raw.trim()
        for (prefix in listOf("wss://", "ws://", "https://", "http://")) {
            if (cleaned.startsWith(prefix, ignoreCase = true)) {
                cleaned = cleaned.substring(prefix.length)
                break
            }
        }
        return cleaned.substringBefore('/').substringBefore('?').trim().trimEnd('/')
    }

    fun isAgentConfigured(context: Context): Boolean {
        return getMqttHost(context).isNotEmpty() && getDeviceId(context).isNotEmpty()
    }

    fun isMusePaired(context: Context): Boolean {
        return hasDirectConfig(context) ||
            getMuseToken(context).startsWith("mgst_") ||
            getMuseRefreshToken(context).isNotEmpty() ||
            getMuseAccessToken(context).isNotEmpty()
    }
}
