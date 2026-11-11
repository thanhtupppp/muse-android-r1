# Muse Android R1

Ứng dụng Android Kotlin/Compose tối giản dành cho loa **Phicomm R1**, dùng để cấu hình Wi‑Fi nhà qua ADB và làm nền tảng cho các tính năng device-agent sau này.

## Tính năng v0.1

- Nhập SSID và mật khẩu Wi‑Fi nhà.
- Nhận cấu hình qua ADB intent.
- Đề xuất kết nối Wi‑Fi bằng `WifiNetworkSuggestion` trên Android 10+.
- Fallback mở Android Wi‑Fi Settings.
- Hiển thị trạng thái mạng, SSID và IP.
- Chuẩn bị kiến trúc cho MQTT/WebSocket, TTS, cập nhật và điều khiển loa sau này.

## Build

```bash
./gradlew :app:assembleDebug
```

APK debug sẽ nằm tại:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Cài đặt lên R1

1. Giữ nút tròn trên đỉnh loa khoảng 6 giây để R1 vào chế độ cấu hình Wi‑Fi.
2. Kết nối máy tính vào hotspot `Phicomm_R1_xxx`.
3. Kết nối ADB:

```bat
adb connect 192.168.43.1:5555
adb devices
```

4. Cài APK:

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

5. Gửi Wi‑Fi nhà qua ADB intent:

```bat
adb shell am start -n vn.tutu.muser1/.MainActivity --es ssid "TEN_WIFI_NHA" --es password "MAT_KHAU"
```

6. Khi loa đã chuyển sang Wi‑Fi nhà, kết nối lại theo IP mới:

```bat
adb connect <IP_MOI_CUA_LOA>:5555
adb shell ip addr show wlan0
```

## Kiểm tra

```bat
adb shell dumpsys wifi | findstr /i "mWifiInfo SSID"
adb shell ip addr show wlan0
adb logcat -s MuseR1
```

## Lưu ý

- Ưu tiên Wi‑Fi 2.4 GHz, WPA2-PSK.
- Không hardcode SSID/mật khẩu trong source code.
- Không gửi mật khẩu Wi‑Fi lên server ở giai đoạn này.
- Nếu Android từ chối `WifiNetworkSuggestion`, ứng dụng sẽ mở trang Wi‑Fi Settings để cấu hình thủ công.
