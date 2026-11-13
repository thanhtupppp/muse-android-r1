package vn.tutu.muser1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent

/**
 * Nhận sự kiện media button của hệ thống.
 *
 * Trên Phicomm R1, nút tròn trên đỉnh loa được firmware ánh xạ thành một
 * media button key event. Keycode cụ thể có thể khác nhau giữa các bản
 * firmware, nên receiver này log MỌI keycode nhận được: bấm thử nút rồi xem
 * logcat tag "MuseR1Button" là biết máy của mình dùng keycode nào.
 *
 * AgentService đăng ký receiver này làm media button receiver qua
 * AudioManager.registerMediaButtonEventReceiver (API phù hợp Android 5.1).
 */
class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return

        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        Log.i(TAG, "MEDIA_BUTTON keycode=${event.keyCode} action=${event.action} armed=${SpeakerButtonMonitor.isArmed()}")

        if (event.action == KeyEvent.ACTION_DOWN) {
            SpeakerButtonMonitor.notePress("media_button:${event.keyCode}")
            if (SpeakerButtonMonitor.isArmed()) {
                // Đang chờ xác nhận pair: giữ sự kiện cho việc xác nhận,
                // không cho chạy tiếp tới thành phần khác (ví dụ firmware
                // loa có thể hiểu lần bấm này là lệnh Bluetooth).
                abortBroadcast()
            }
        }
    }

    companion object {
        private const val TAG = "MuseR1Button"
    }
}
