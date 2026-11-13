package vn.tutu.muser1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SecretRedactorTest {

    @Test
    fun redactsKeyValueSecrets() {
        val out = SecretRedactor.redact("ssid=VIETTEL24 password=matkhau123 token=abc123")
        assertEquals("ssid=VIETTEL24 password=*** token=***", out)
    }

    @Test
    fun redactsJsonStyleSecrets() {
        val out = SecretRedactor.redact("""{"password":"abc123","provision_token":"deadbeef","ssid":"Home"}""")
        assertFalse(out.contains("abc123"))
        assertFalse(out.contains("deadbeef"))
        assertFalse(out.contains("Home") && out.contains("password\":\"abc"))
    }

    @Test
    fun redactsBearerAndMuseTokens() {
        val out = SecretRedactor.redact("Authorization: Bearer xyz.123 và mgst_live_token_999")
        assertFalse(out.contains("xyz.123"))
        assertFalse(out.contains("mgst_live_token_999"))
    }

    @Test
    fun leavesOrdinaryTextAlone() {
        val text = "Wi-Fi đã kết nối và nhận DHCP IP: 192.168.1.55 (SSID='VIETTEL24')"
        assertEquals(text, SecretRedactor.redact(text))
        assertEquals("", SecretRedactor.redact(""))
    }
}
