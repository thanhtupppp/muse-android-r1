package vn.tutu.musemobile.domain

/**
 * Ánh xạ mã lỗi provisioning (phía loa ghi trong wifi_provision, hoặc
 * Mobile tự sinh) → câu hướng dẫn tiếng Việt cho người dùng. Học theo
 * cách các app companion tốt làm: lỗi không dừng ở một mã thô, mỗi mã
 * đi kèm đúng một việc cần kiểm tra/làm tiếp.
 */
object ProvisioningHelp {

    private val HELP = mapOf(
        "CONNECT_TIMEOUT" to "Loa không nối được vào mạng trong thời gian chờ. Nguyên nhân hay gặp nhất: sai mật khẩu, router ở quá xa, hoặc SSID chỉ phát 5 GHz. Kiểm tra lại mật khẩu, đặt loa gần router rồi thử lại.",
        "INVALID_PASSWORD" to "Mật khẩu không đúng định dạng WPA2 (cần 8–63 ký tự hoặc khóa hex 64 ký tự). Nhập lại mật khẩu Wi-Fi nhà.",
        "UNSUPPORTED_SECURITY" to "Mạng dùng WPA3 thuần hoặc Enterprise (EAP) — loa R1 (Android 5.1) không hỗ trợ. Đặt router sang WPA2-PSK hoặc hỗn hợp WPA2/WPA3.",
        "SOFTAP_DISABLE_FAILED" to "Loa không tắt được hotspot của chính nó để chuyển sang chế độ client. Khởi động lại loa rồi thử lại.",
        "WIFI_ENABLE_FAILED" to "Loa không bật được Wi-Fi client. Khởi động lại loa rồi thử lại.",
        "WIFI_RADIO_STILL_DISABLED" to "Radio Wi-Fi của loa vẫn tắt sau khi tắt hotspot. Khởi động lại loa rồi thử lại.",
        "ADD_NETWORK_FAILED" to "Hệ thống của loa từ chối thêm cấu hình mạng mới. Thường do danh sách mạng đã lưu quá đầy — hãy quên bớt mạng cũ trong thẻ 'Mạng Wi-Fi đã lưu trên loa' rồi thử lại.",
        "ENABLE_NETWORK_FAILED" to "Loa không kích hoạt được cấu hình mạng vừa lưu. Thử quên mạng này trong danh sách đã lưu rồi cấu hình lại từ đầu.",
        "SWITCH_TIMEOUT" to "Loa không chuyển sang mạng đã lưu trong thời gian chờ và đã cố quay về mạng trước. Kiểm tra router của mạng đích có đang hoạt động không.",
        "NOT_SAVED" to "Mạng này chưa từng được lưu trên loa. Hãy cấu hình Wi-Fi cho loa bằng mật khẩu trước, mạng sẽ tự được lưu.",
        "EMPTY_SSID" to "Tên mạng (SSID) trống. Chọn lại mạng Wi-Fi nhà.",
        "NO_WIFI_MANAGER" to "Loa không trả lời được dịch vụ Wi-Fi của hệ thống. Khởi động lại loa.",
        "WIFI_DISABLED" to "Wi-Fi của loa đang tắt (có thể đang ở chế độ hotspot). Hãy cấu hình qua hotspot của loa thay vì chuyển mạng.",
        "PROVISIONING_IN_PROGRESS" to "Loa đang bận xử lý một lần cấu hình khác. Đợi khoảng nửa phút rồi thử lại.",
        "SERVER_STOPPING" to "Dịch vụ cấu hình trên loa đang dừng. Đợi loa khởi động xong rồi thử lại.",
        "REMOVE_FAILED" to "Loa không xoá được cấu hình mạng này. Thử lại, hoặc khởi động lại loa rồi quên lại.",
        "INTERRUPTED" to "Thao tác bị ngắt giữa chừng. Thử lại lần nữa.",
        "EXCEPTION" to "Loa gặp lỗi nội bộ khi cấu hình Wi-Fi. Khởi động lại loa rồi thử lại; nếu vẫn lặp lại, gửi nhật ký chẩn đoán kèm mã này.",
        "UNAUTHORIZED" to "Loa từ chối vì thiếu/sai provision token. Mở lại cửa sổ cấu hình trên loa (hoặc cấu hình lại Muse) để Mobile lấy lại token.",
        "DISCOVERY_TIMEOUT" to "Không tìm thấy loa trên mạng nhà sau 2 phút. Kiểm tra: điện thoại đã về đúng Wi-Fi nhà chưa, mật khẩu có đúng không, và hotspot của loa có xuất hiện lại không (xuất hiện lại nghĩa là loa nối thất bại và đã tự khôi phục hotspot).",
        "BLUETOOTH_FAILED" to "Gửi cấu hình qua Bluetooth thất bại. Nếu lặp lại nhiều lần, hãy tắt rồi bật lại Bluetooth trên điện thoại, đứng gần loa và thử lại; hoặc nối Wi-Fi điện thoại vào hotspot của loa để đi đường HTTP.",
        "NO_TRANSPORT_AVAILABLE" to "Không có đường nào tới loa: HTTP không thông và Bluetooth chưa ghép đôi. Nối Wi-Fi điện thoại vào hotspot Phicomm_R1_xxx của loa rồi thử lại.",
        "NO_WIFI_SELECTED" to "Chưa chọn mạng Wi-Fi nhà. Chọn một mạng trong danh sách trước.",
        "INVALID_NETWORK" to "Không thể chọn trạm phát của chính loa làm mạng nhà. Chọn SSID do router nhà phát."
    )

    /** Câu hướng dẫn cho một mã lỗi, hoặc null nếu chưa có ánh xạ. */
    fun helpFor(code: String?): String? =
        code?.trim()?.uppercase()?.let { HELP[it] }

    /** "MÃ — hướng dẫn" để gắn vào thông báo lỗi cho người dùng. */
    fun describe(code: String?): String {
        val clean = code?.trim()?.uppercase().orEmpty()
        if (clean.isEmpty()) return ""
        val help = HELP[clean] ?: return "Mã lỗi: $clean."
        return "$clean — $help"
    }

    /** Trích mã lỗi dạng ERROR: <CODE> hoặc hậu tố :<CODE> trong thông báo. */
    fun extractCode(message: String?): String? {
        if (message.isNullOrBlank()) return null
        val errorMatch = Regex("""ERROR:\s*([A-Z][A-Z0-9_]{2,})""").find(message)
        if (errorMatch != null) return errorMatch.groupValues[1]
        val known = HELP.keys.firstOrNull { code ->
            message.uppercase().contains(code)
        }
        return known
    }
}
