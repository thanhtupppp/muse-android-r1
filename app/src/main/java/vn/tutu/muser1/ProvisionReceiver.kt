package vn.tutu.muser1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.Executors

/**
 * Kênh cấu hình qua broadcast — ĐÃ KHOÁ bằng signature permission
 * (vn.tutu.muser1.permission.PROVISION trong AndroidManifest): chỉ app cùng
 * chữ ký mới gửi được. Trước đây receiver exported không permission nên app
 * bất kỳ trên loa cũng giả lập được nút bấm, ghi đè Muse token hoặc ép đổi
 * Wi-Fi. ADB shell KHÔNG còn gửi được các broadcast này; dùng các đường sau:
 *
 * Cấu hình Wi‑Fi / Muse token qua ADB: dùng app Mobile, hoặc HTTP :8080 khi
 * loa đang ở trạng thái chưa cấu hình (xem LocalProvisionServer).
 *
 * Giả lập bấm nút tròn trên loa (xác nhận pair confirm_press, để kiểm chứng
 * cơ chế khi chưa chắc nút thật phát keycode nào):
 * adb shell am startservice -n vn.tutu.muser1/.AgentService \
 *   -a vn.tutu.muser1.ACTION_CONFIRM_PAIRING_PRESS
 *
 * Mở lại cửa sổ pairing BLE 10 phút (khi app Muse không còn thấy loa):
 * adb shell am startservice -n vn.tutu.muser1/.AgentService \
 *   -a vn.tutu.muser1.ACTION_REOPEN_PAIRING_WINDOW
 */
class ProvisionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PROVISION_WIFI -> provisionWifi(context, intent)
            ACTION_CONFIGURE_MUSE, ACTION_CONFIGURE_AGENT -> configureMuse(context, intent)
            ACTION_CONFIRM_PAIRING_PRESS -> {
                Log.i(TAG, "Nhận CONFIRM_PAIRING_PRESS qua broadcast (giả lập nút tròn)")
                SpeakerButtonMonitor.notePress("adb_broadcast")
            }
            ACTION_REOPEN_PAIRING_WINDOW -> {
                Log.i(TAG, "Nhận REOPEN_PAIRING_WINDOW qua broadcast: mở lại cửa sổ pairing BLE")
                AgentService.reopenPairingWindow(context)
            }
        }
    }

    private fun provisionWifi(context: Context, intent: Intent) {
        val ssid = intent.getStringExtra(EXTRA_SSID)?.trim().orEmpty()
        val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()

        if (ssid.isEmpty() || ssid.contains('\r') || ssid.contains('\n')) {
            Log.e(TAG, "Invalid SSID in Wi‑Fi provisioning broadcast")
            return
        }

        if (password.contains('\r') || password.contains('\n')) {
            Log.e(TAG, "Password contains invalid newline characters")
            return
        }

        val isHexPsk = password.length == 64 && password.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        if (password.isNotEmpty() && password.length !in 8..63 && !isHexPsk) {
            Log.e(TAG, "Invalid Wi‑Fi password length (must be empty for open or 8-63 chars / 64 hex)")
            return
        }

        // WifiProvisioner.connect() có thể mất ~50 giây (chuyển radio + chờ
        // DHCP) — chạy ngay trong onReceive sẽ vượt giới hạn ~10 giây của
        // BroadcastReceiver và có thể bị hệ thống giết giữa chừng, để lại
        // radio ở trạng thái dở dang. Đẩy xuống executor nền qua goAsync().
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        backgroundExecutor.execute {
            try {
                val result = WifiProvisioner.connect(appContext, ssid, password)
                Log.i(TAG, "Wi‑Fi provisioning broadcast result: $result")
            } catch (error: Exception) {
                Log.e(TAG, "Wi‑Fi provisioning broadcast failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun configureMuse(context: Context, intent: Intent) {
        val museToken = intent.getStringExtra(EXTRA_MUSE_TOKEN)?.trim().orEmpty()

        if (museToken.isEmpty()) {
            Log.e(TAG, "Muse token is empty")
            return
        }

        val saved = MuseGadgetClient(context, MusePreferences.getDeviceId(context))
            .setToken(museToken)

        if (saved) {
            AgentService.start(context)
        }
    }

    companion object {
        private const val TAG = "MuseR1"

        private val backgroundExecutor = Executors.newSingleThreadExecutor()

        const val ACTION_PROVISION_WIFI = "vn.tutu.muser1.PROVISION_WIFI"
        const val ACTION_CONFIGURE_MUSE = "vn.tutu.muser1.CONFIGURE_MUSE"
        const val ACTION_CONFIGURE_AGENT = "vn.tutu.muser1.CONFIGURE_AGENT"
        const val ACTION_CONFIRM_PAIRING_PRESS = "vn.tutu.muser1.CONFIRM_PAIRING_PRESS"
        const val ACTION_REOPEN_PAIRING_WINDOW = "vn.tutu.muser1.REOPEN_PAIRING_WINDOW"

        const val EXTRA_SSID = "ssid"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_MUSE_TOKEN = "muse_token"
    }
}
