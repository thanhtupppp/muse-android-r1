package vn.tutu.musemobile.domain

data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val frequencyMhz: Int,
    val signalLevel: Int
) {
    val is24Ghz: Boolean
        get() = frequencyMhz in 2400..2500

    val bandLabel: String
        get() = if (is24Ghz) "2.4 GHz" else "5 GHz"

    val signalLabel: String
        get() = when {
            signalLevel >= -50 -> "Rất mạnh"
            signalLevel >= -65 -> "Mạnh"
            signalLevel >= -75 -> "Trung bình"
            else -> "Yếu"
        }
}
