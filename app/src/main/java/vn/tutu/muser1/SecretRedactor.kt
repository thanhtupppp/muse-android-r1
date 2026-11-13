package vn.tutu.muser1

/**
 * Che bí mật trong log và thông báo lỗi trước khi chúng rời khỏi loa
 * (logcat, phản hồi chẩn đoán, nội dung copy từ app). Học theo cách các
 * app companion lớn làm: một regex redact dùng chung cho các khóa nhạy
 * cảm thay vì tin rằng mọi nơi ghi log đều tự nhớ che tay.
 *
 * Thuần chuỗi (không chạm Android API) để kiểm thử bằng unit test JVM.
 * Phía Mobile có bản sao cùng quy tắc trong `SecretRedactor` của nó.
 */
object SecretRedactor {

    private val keyValuePattern = Regex(
        """(?i)(password|passwd|psk|pre_shared_key|token|secret|api[-_ ]?key|authorization)(\s*["']?\s*[:=]\s*["']?)([^\s"',}\]]+)"""
    )
    private val bearerPattern = Regex("""(?i)(bearer\s+)[A-Za-z0-9._~+/=-]+""")
    private val museTokenPattern = Regex("""mgst_[A-Za-z0-9._~+/=-]+""")

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        // Bearer phải chạy TRƯỚC mẫu khóa=giá trị: nếu không, chính khóa
        // "authorization" sẽ nuốt chữ "Bearer" làm giá trị và để lộ token
        // thật đứng ngay sau nó.
        var out = bearerPattern.replace(text) { m -> "${m.groupValues[1]}***" }
        out = keyValuePattern.replace(out) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}***"
        }
        out = museTokenPattern.replace(out, "mgst_***")
        return out
    }
}
