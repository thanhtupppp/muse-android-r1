package vn.tutu.muser1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {

    @Test
    fun redactsKeyValueSecretsWithFingerprint() {
        val out = SecretRedactor.redact("ssid=VIETTEL24 password=matkhau123 token=abc123")
        assertEquals(
            "ssid=VIETTEL24 password=***(len=10, sha256:fc8d5c17) token=***(len=6, sha256:6ca13d52)",
            out
        )
    }

    @Test
    fun redactsJsonStyleSecrets() {
        val out = SecretRedactor.redact("""{"password":"abc123","provision_token":"deadbeef","ssid":"Home"}""")
        assertFalse(out.contains("abc123"))
        assertFalse(out.contains("deadbeef"))
        assertTrue(out.contains("len=8, sha256:2baf1f40"))
    }

    @Test
    fun redactsBearerAndMuseTokens() {
        val out = SecretRedactor.redact("Authorization: Bearer xyz.123 và mgst_live_token_999")
        assertFalse(out.contains("xyz.123"))
        assertFalse(out.contains("mgst_live_token_999"))
        // Vân tay của Bearer token phải sống sót (không bị chính khóa
        // authorization nuốt mất), và token Muse giữ tiền tố mgst_.
        assertTrue(out.contains("Bearer ***(len=7, sha256:6f97ba61)"))
        assertTrue(out.contains("mgst_***(len=19, sha256:9c62e28d)"))
    }

    @Test
    fun fingerprintIsStableAndValueSensitive() {
        assertEquals(SecretRedactor.fingerprint("abc123"), SecretRedactor.fingerprint("abc123"))
        assertFalse(SecretRedactor.fingerprint("abc123") == SecretRedactor.fingerprint("abc124"))
        assertEquals("***(len=6, sha256:6ca13d52)", SecretRedactor.fingerprint("abc123"))
    }

    @Test
    fun leavesOrdinaryTextAlone() {
        val text = "Wi-Fi đã kết nối và nhận DHCP IP: 192.168.1.55 (SSID='VIETTEL24')"
        assertEquals(text, SecretRedactor.redact(text))
        assertEquals("", SecretRedactor.redact(""))
    }
}
