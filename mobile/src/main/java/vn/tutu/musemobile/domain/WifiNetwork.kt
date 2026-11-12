package vn.tutu.musemobile.domain

data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val frequencyMhz: Int,
    val signalLevel: Int,
    val security: String = "WPA2"
) {
    init {
        require(frequencyMhz > 0) { "Tần số Wi-Fi không hợp lệ: $frequencyMhz" }
    }

    val is24Ghz: Boolean
        get() = frequencyMhz in 2400..2500

    val bandLabel: String
        get() = when {
            frequencyMhz in 2400..2500 -> "2.4 GHz"
            frequencyMhz in 4900..5900 -> "5 GHz"
            frequencyMhz in 5925..7125 -> "6 GHz"
            else -> "Khác"
        }

    val signalLabel: String
        get() = when {
            signalLevel >= -50 -> "Rất mạnh"
            signalLevel >= -65 -> "Mạnh"
            signalLevel >= -75 -> "Trung bình"
            else -> "Yếu"
        }

    val isOpen: Boolean
        get() = security.equals("OPEN", ignoreCase = true) || security.equals("NONE", ignoreCase = true)
}
