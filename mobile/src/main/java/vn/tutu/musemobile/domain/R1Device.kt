package vn.tutu.musemobile.domain

data class R1Device(
    val deviceId: String,
    val ipAddress: String,
    val ssid: String,
    val agentVersion: String,
    val musePaired: Boolean,
    val discoveredAt: Long = System.currentTimeMillis()
) {
    init {
        require(deviceId.isNotBlank()) { "deviceId không được rỗng" }
        require(IPV4_REGEX.matches(ipAddress)) {
            "ipAddress không hợp lệ: $ipAddress"
        }
    }

    companion object {
        private val IPV4_REGEX = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
    }
}
