package vn.tutu.muser1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kiểm thử thuần JVM cho logic phân loại Wi-Fi phía loa — cùng quy tắc
 * với WifiScanner phía Mobile để hai đầu không hiểu khác nhau về một
 * mạng (đặc biệt: WPA3 thuần và EAP phải bị gọi đúng tên, không map
 * lặng lẽ về WPA2).
 */
class SpeakerWifiTest {

    @Test
    fun parseSecurity_openNetwork() {
        assertEquals("OPEN", SpeakerWifi.parseSecurity(""))
        assertEquals("OPEN", SpeakerWifi.parseSecurity(null))
        assertEquals("OPEN", SpeakerWifi.parseSecurity("[ESS]"))
    }

    @Test
    fun parseSecurity_wpa2Variants() {
        assertEquals("WPA2", SpeakerWifi.parseSecurity("[WPA2-PSK-CCMP][ESS]"))
        assertEquals("WPA2", SpeakerWifi.parseSecurity("[WPA-PSK-TKIP][WPA2-PSK-CCMP][ESS]"))
        // Hỗn hợp WPA2/WPA3 (có cả PSK lẫn SAE) vẫn đi đường WPA2.
        assertEquals("WPA2", SpeakerWifi.parseSecurity("[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]"))
    }

    @Test
    fun parseSecurity_wpa3OnlyIsNotMappedToWpa2() {
        assertEquals("WPA3", SpeakerWifi.parseSecurity("[RSN-SAE-CCMP][ESS]"))
        assertEquals("WPA3", SpeakerWifi.parseSecurity("[WPA3-SAE-CCMP]"))
    }

    @Test
    fun parseSecurity_enterpriseIsEap() {
        assertEquals("EAP", SpeakerWifi.parseSecurity("[WPA2-EAP-CCMP][ESS]"))
    }

    @Test
    fun parseSecurity_wep() {
        assertEquals("WEP", SpeakerWifi.parseSecurity("[WEP][ESS]"))
    }

    @Test
    fun frequencyBands() {
        assertTrue(SpeakerWifi.is24Ghz(2412))
        assertTrue(SpeakerWifi.is24Ghz(2472))
        assertFalse(SpeakerWifi.is24Ghz(5180))
        assertTrue(SpeakerWifi.is5GhzOrAbove(5180))
        assertTrue(SpeakerWifi.is5GhzOrAbove(5955))
        assertFalse(SpeakerWifi.is5GhzOrAbove(2437))
    }

    @Test
    fun unquoteSsid_stripsQuotesAndCrLf() {
        assertEquals("VIETTEL24", SpeakerWifi.unquoteSsid("\"VIETTEL24\""))
        assertEquals("Home", SpeakerWifi.unquoteSsid("Home\r\n"))
        assertEquals("", SpeakerWifi.unquoteSsid(null))
        assertEquals("", SpeakerWifi.unquoteSsid("\"\""))
    }
}
