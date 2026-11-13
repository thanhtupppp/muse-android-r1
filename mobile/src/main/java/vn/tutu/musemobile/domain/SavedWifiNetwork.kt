package vn.tutu.musemobile.domain

/** Một mạng Wi-Fi đã lưu trong cấu hình của loa (từ GET /wifi-saved). */
data class SavedWifiNetwork(
    val ssid: String,
    val networkId: Int,
    val connected: Boolean
)
