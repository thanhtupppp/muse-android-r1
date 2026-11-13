package vn.tutu.muser1

import java.security.MessageDigest

/**
 * Che bí mật trong log và thông báo lỗi trước khi chúng rời khỏi loa
 * (logcat, phản hồi chẩn đoán, nội dung copy từ app). Học theo cách các
 * app companion lớn làm: một regex redact dùng chung cho các khóa nhạy
 * cảm thay vì tin rằng mọi nơi ghi log đều tự nhớ che tay.
 *
 * Giá trị bí mật được thay bằng VÂN TAY debug — độ dài + 8 ký tự đầu
 * của SHA-256 hex: hai đầu (app ↔ loa) in cùng vân tay thì biết chắc
 * đang dùng cùng một mật khẩu/token mà không lộ giá trị, đúng kiểu
 * SSH/git đối chiếu fingerprint. Debug cần "có khớp nhau không, dài
 * bao nhiêu", không cần giá trị thật.
 *
 * Thuần chuỗi (không chạm Android API) để kiểm thử bằng unit test JVM.
 * Phía Mobile có bản sao cùng quy tắc trong `SecretRedactor` của nó —
 * vân tay chỉ có ý nghĩa khi cả hai phía tính giống hệt nhau.
 */
object SecretRedactor {

    private val keyValuePattern = Regex(
        """(?i)(password|passwd|psk|pre_shared_key|token|secret|api[-_ ]?key|authorization)(\s*["']?\s*[:=]\s*["']?)([^\s"',}\]]+)"""
    )
    private val bearerPattern = Regex("""(?i)(bearer\s+)([A-Za-z0-9._~+/=-]+)""")
    private val museTokenPattern = Regex("""mgst_[A-Za-z0-9._~+/=-]+""")

    /** Vân tay debug của một bí mật: `***(len=N, sha256:xxxxxxxx)`. */
    fun fingerprint(secret: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(secret.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "***(len=${secret.length}, sha256:${hex.substring(0, 8)})"
    }

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        // Mẫu khóa=giá trị chạy trước, NHƯNG bỏ qua giá trị "Bearer":
        // nếu không, khóa authorization sẽ nuốt chữ Bearer làm giá trị
        // và bước Bearer sau đó không còn gì để che đúng token thật.
        var out = keyValuePattern.replace(text) { m ->
            if (m.groupValues[3].equals("bearer", ignoreCase = true)) {
                m.value
            } else {
                "${m.groupValues[1]}${m.groupValues[2]}${fingerprint(m.groupValues[3])}"
            }
        }
        out = bearerPattern.replace(out) { m ->
            "${m.groupValues[1]}${fingerprint(m.groupValues[2])}"
        }
        out = museTokenPattern.replace(out) { m -> "mgst_${fingerprint(m.value)}" }
        return out
    }
}
