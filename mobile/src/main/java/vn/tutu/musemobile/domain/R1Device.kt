package vn.tutu.musemobile.domain

data class R1Device(
    val deviceId: String,
    val ipAddress: String,
    val ssid: String,
    val agentVersion: String,
    val musePaired: Boolean
)
