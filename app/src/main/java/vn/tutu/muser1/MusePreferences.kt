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
    private const val KEY_MUSE_IDENTITY_MAC = "muse_identity_mac"
    private const val KEY_DIRECT_VM_ID = "muse_direct_vm_id"
    private const val KEY_DIRECT_TOKEN = "muse_direct_token"
    private const val KEY_PROVISION_TOKEN = "provision_token"
    private const val KEY_LAST_REFRESH_OK_MS = "muse_last_refresh_ok_ms"
    private const val KEY_LAST_REFRESH_ATTEMPT_MS = "muse_last_refresh_attempt_ms"
    private const val KEY_VOICE_APP_ID = "voice_app_id"
    private const val KEY_VOICE_MIC_PROBE = "voice_mic_probe"
    private const val KEY_VOICE_CALL_PROBE = "voice_call_probe"
    const val DEFAULT_VOICE_APP_ID = "hatch-web"

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

    /**
     * Muse Gadget identity (xem [MuseIdentity]). Sinh một lần, lưu bền trong
     * private preferences và dùng lại mãi, kể cả sau khi unpair — giống hệt
     * cách bản Linux SDK giữ identity qua các lần nâng cấp và huỷ ghép đôi.
     * Không suy ra từ ANDROID_ID hay địa chỉ phần cứng.
     */
    @Synchronized
    fun getMuseIdentity(context: Context): MuseIdentity {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_MUSE_IDENTITY_MAC, null)
        if (MuseIdentity.isValidMac(saved)) {
            return MuseIdentity.fromMac(saved!!)
        }
        val created = MuseIdentity.generate()
        prefs.edit().putString(KEY_MUSE_IDENTITY_MAC, created.mac).apply()
        return created
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
            .getString(KEY_PROVISION_TOKEN, "")
            .orEmpty()
    }

    fun saveProvisionToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROVISION_TOKEN, token)
            .apply()
    }

    /**
     * Trả về provision token dùng để xác thực HTTP :8080 / SPP sau khi loa đã
     * được cấu hình Muse; chưa có thì sinh ngẫu nhiên một lần và lưu bền.
     * Token được in ra logcat đúng một lần lúc sinh (giống mã cài đặt in trên
     * nhãn thiết bị) để chủ loa lấy lại qua ADB khi cần; Mobile nhận token qua
     * response của lần cấu hình Muse đầu tiên và tự đính kèm sau đó.
     */
    @Synchronized
    fun ensureProvisionToken(context: Context): String {
        val existing = getProvisionToken(context)
        if (existing.isNotEmpty()) return existing
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        val created = bytes.joinToString("") { "%02x".format(it) }
        saveProvisionToken(context, created)
        android.util.Log.i(
            "MuseR1",
            "Provision token cho :8080 đã được tạo (lưu lại để dùng khi cần qua ADB): $created"
        )
        return created
    }

    fun getLastRefreshSuccessMs(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_REFRESH_OK_MS, 0L)
    }

    fun setLastRefreshSuccessMs(context: Context, timeMs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_REFRESH_OK_MS, timeMs)
            .apply()
    }

    fun getLastRefreshAttemptMs(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_REFRESH_ATTEMPT_MS, 0L)
    }

    fun setLastRefreshAttemptMs(context: Context, timeMs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_REFRESH_ATTEMPT_MS, timeMs)
            .apply()
    }

    /** Voice app id đặt trên query của upgrade Noise cho phiên voice call (mặc định như SDK CLI). */
    fun getVoiceAppId(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VOICE_APP_ID, DEFAULT_VOICE_APP_ID)
            .orEmpty()
            .ifEmpty { DEFAULT_VOICE_APP_ID }
    }

    fun setVoiceMicProbe(context: Context, json: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VOICE_MIC_PROBE, json)
            .apply()
    }

    fun getVoiceMicProbe(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VOICE_MIC_PROBE, "")
            .orEmpty()
    }

    fun setVoiceCallProbe(context: Context, json: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VOICE_CALL_PROBE, json)
            .apply()
    }

    fun getVoiceCallProbe(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VOICE_CALL_PROBE, "")
            .orEmpty()
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
            // SDK token là cách NGƯỜI DÙNG đã cấu hình; access/refresh token
            // chỉ là hệ quả phái sinh của lần refresh đầu tiên. Báo "oauth"
            // khi còn mgst_ sẽ làm /status đổi tên chế độ một cách khó hiểu
            // ngay sau khi kết nối thành công lần đầu.
            getMuseToken(context).startsWith("mgst_") -> "sdk_token"
            getMuseAccessToken(context).isNotEmpty() || getMuseRefreshToken(context).isNotEmpty() -> "oauth"
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
            .remove(KEY_LAST_REFRESH_OK_MS)
            .remove(KEY_LAST_REFRESH_ATTEMPT_MS)
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
