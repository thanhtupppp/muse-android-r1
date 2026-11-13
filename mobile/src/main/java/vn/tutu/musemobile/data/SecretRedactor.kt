package vn.tutu.musemobile.data

import java.security.MessageDigest

/**
 * Che bí mật trong log/thông báo phía Mobile (nhật ký cài đặt, chẩn
 * đoán, thân phản hồi lỗi được trích vào thông báo). Cùng quy tắc với
 * SecretRedactor phía Agent — giá trị bí mật thay bằng VÂN TAY debug
 * (độ dài + 8 ký tự đầu của SHA-256 hex) để hai đầu đối chiếu "có đang
 * dùng cùng một mật khẩu/token không" mà không lộ giá trị. Vân tay chỉ
 * có ý nghĩa khi cả hai phía tính giống hệt nhau, nên hai bản phải
 * luôn được sửa cùng nhau.
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
        // Bỏ qua giá trị "Bearer" ở mẫu khóa=giá trị (xem bản phía Agent).
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
