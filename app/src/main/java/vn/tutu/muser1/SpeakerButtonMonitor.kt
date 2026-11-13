package vn.tutu.muser1

import android.util.Log

/**
 * Theo dõi nút tròn trên đỉnh loa Phicomm R1 cho cơ chế pair confirm_press.
 *
 * Luồng giống bản ESP32 của Muse Gadget SDK: giao thức pair chỉ arm nút
 * SAU khi đã gửi "confirm_required" cho app. Lần bấm trước khi arm, hoặc khi
 * không có phiên pair nào đang chờ, đều bị bỏ qua (nhưng vẫn được log để
 * đối chiếu keycode trên máy thật).
 *
 * Nguồn sự kiện bấm nút:
 * - [MediaButtonReceiver]: nút tròn của R1 được firmware ánh xạ thành
 *   media button (thường là HEADSETHOOK / PLAY_PAUSE).
 * - Broadcast ADB `vn.tutu.muser1.CONFIRM_PAIRING_PRESS` qua
 *   [ProvisionReceiver]: đường dự phòng để kiểm chứng cơ chế khi chưa chắc
 *   keycode của nút trên bản firmware cụ thể.
 */
object SpeakerButtonMonitor {

    private const val TAG = "MuseR1Button"

    @Volatile
    private var armed = false

    /** Được gọi khi có lần bấm trong lúc đang arm. */
    @Volatile
    var onArmedPress: (() -> Unit)? = null

    fun isArmed(): Boolean = armed

    fun arm() {
        armed = true
        Log.i(TAG, "Đã arm nút tròn trên loa: chờ người dùng bấm để xác nhận pair")
    }

    fun disarm() {
        if (armed) {
            Log.i(TAG, "Đã disarm nút tròn trên loa")
        }
        armed = false
    }

    fun notePress(source: String) {
        Log.i(TAG, "Sự kiện bấm nút tròn (source=$source, armed=$armed)")
        if (armed) {
            onArmedPress?.invoke()
        }
    }
}
