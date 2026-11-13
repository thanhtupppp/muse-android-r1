package vn.tutu.musemobile.data

/**
 * Che bí mật trong log/thông báo phía Mobile (nhật ký cài đặt, chẩn
 * đoán, thân phản hồi lỗi được trích vào thông báo). Cùng quy tắc với
 * SecretRedactor phía Agent: khóa password/token/secret/api-key/
 * authorization, Bearer token và token Muse `mgst_...`.
 */
object SecretRedactor {

    private val keyValuePattern = Regex(
        """(?i)(password|passwd|psk|pre_shared_key|token|secret|api[-_ ]?key|authorization)(\s*["']?\s*[:=]\s*["']?)([^\s"',}\]]+)"""
    )
    private val bearerPattern = Regex("""(?i)(bearer\s+)[A-Za-z0-9._~+/=-]+""")
    private val museTokenPattern = Regex("""mgst_[A-Za-z0-9._~+/=-]+""")

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        // Bearer chạy trước mẫu khóa=giá trị (xem bản phía Agent).
        var out = bearerPattern.replace(text) { m -> "${m.groupValues[1]}***" }
        out = keyValuePattern.replace(out) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}***"
        }
        out = museTokenPattern.replace(out, "mgst_***")
        return out
    }
}
