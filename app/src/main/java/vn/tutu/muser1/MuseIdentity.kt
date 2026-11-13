package vn.tutu.muser1

import java.security.SecureRandom

/**
 * Danh tính Muse Gadget theo chuẩn facebookincubator/muse-gadget-sdk
 * (linux/src/musegadget/identity.py và esp32/main/identity.c):
 *
 * - [mac]: một giá trị dạng MAC, locally administered, được sinh ngẫu nhiên
 *   một lần và lưu bền. Đây KHÔNG phải địa chỉ phần cứng, nên hiệu số giữa
 *   MAC Wi-Fi và MAC Bluetooth trên R1 không thể làm lệch danh tính, và
 *   danh tính không đổi sau reboot / cấp lại token.
 * - [nodeId]: "homelink-" + 6 ký tự hex cuối của MAC.
 * - [deviceId]: "hatch-link:" + MAC.
 * - [bleName]: "MuseGadget-" + 6 ký tự hex cuối viết hoa. Hậu tố của tên BLE
 *   PHẢI trùng hậu tố của nodeId — các app Muse so sánh phần chữ sau tiền tố
 *   của hai giá trị này để nhận diện cùng một gadget.
 */
data class MuseIdentity(val mac: String) {

    /** 6 ký tự hex cuối của MAC, viết thường, không có dấu hai chấm. */
    val suffix: String
        get() = mac.replace(":", "").takeLast(6).lowercase()

    val nodeId: String
        get() = "homelink-$suffix"

    val deviceId: String
        get() = "hatch-link:${mac.lowercase()}"

    val bleName: String
        get() = "MuseGadget-${suffix.uppercase()}"

    companion object {
        private val MAC_REGEX = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")

        fun isValidMac(value: String?): Boolean =
            value != null && MAC_REGEX.matches(value.lowercase())

        /** Tạo identity từ một MAC hợp lệ; MAC được chuẩn hoá về chữ thường. */
        fun fromMac(mac: String): MuseIdentity {
            val normalized = mac.trim().lowercase()
            require(isValidMac(normalized)) { "Invalid MAC-shaped identity: $mac" }
            return MuseIdentity(normalized)
        }

        /**
         * Sinh MAC-shaped identity mới giống bản Linux SDK: octet đầu được
         * ép thành unicast + locally administered ((b & 0xFC) | 0x02).
         */
        fun generate(random: SecureRandom = SecureRandom()): MuseIdentity {
            val octets = ByteArray(6)
            random.nextBytes(octets)
            octets[0] = ((octets[0].toInt() and 0xFC) or 0x02).toByte()
            val mac = octets.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
            return MuseIdentity(mac)
        }
    }
}
