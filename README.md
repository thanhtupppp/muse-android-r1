# Muse Android R1

**Muse Android R1** là một Android headless agent dành cho loa Phicomm R1 (Android 5.1.1 / RK3229).

Ứng dụng không có giao diện người dùng. Nó chạy nền để:

- Cấu hình Wi‑Fi nhà qua ADB hoặc lệnh cục bộ.
- Khởi động lại service sau khi R1 reboot.
- Duy trì kết nối MQTT tới server/broker của bạn.
- Nhận lệnh điều khiển từ **Muse Mobile App**.
- Chuẩn bị tích hợp **Meta Muse Gadget SDK**.

## Kiến trúc

```text
Muse Mobile App
        │
        │ MQTT / WebSocket
        ▼
Muse R1 Agent (headless)
        │
        ├── Wi‑Fi provisioning
        ├── Foreground service
        ├── MQTT command channel
        ├── Audio / TTS controller
        └── Muse Gadget integration
```

## Phiên bản Android

Phicomm R1 chạy Android 5.1.1 (API 22). Do đó:

- `minSdk = 22`
- `targetSdk = 28`
- Wi‑Fi dùng `WifiManager.addNetwork()`, `enableNetwork()` và `reconnect()`
- Không dùng `WifiNetworkSuggestion` làm luồng chính

## Build

```bash
./gradlew :app:assembleDebug
```

APK debug:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Cấu hình Wi‑Fi qua ADB

1. Giữ nút tròn trên đỉnh R1 khoảng 6 giây để vào chế độ cấu hình Wi‑Fi.
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

5. Gửi Wi‑Fi nhà:

```bat
adb shell am broadcast -a vn.tutu.muser1.PROVISION_WIFI --es ssid "TEN_WIFI_NHA" --es password "MAT_KHAU"
```

6. Khi R1 đã vào Wi‑Fi nhà, kết nối lại theo IP mới:

```bat
adb connect <IP_MOI_CUA_LOA>:5555
adb shell ip addr show wlan0
```

## Cấu hình MQTT qua ADB

```bat
adb shell am broadcast -a vn.tutu.muser1.CONFIGURE_AGENT --es mqtt_host "mqtt://192.168.1.10:1883" --es device_id "R1-001"
```

## Kiểm tra

```bat
adb shell dumpsys wifi | findstr /i "mWifiInfo SSID"
adb shell ip addr show wlan0
adb shell dumpsys activity services vn.tutu.muser1
adb logcat -s MuseR1
```

## Muse Gadget SDK

Repo tham chiếu: https://github.com/facebookincubator/muse-gadget-sdk

Giai đoạn hiện tại triển khai khung agent và command router. Việc tích hợp Muse protocol sẽ dựa trên:

- SDK token và pairing.
- `system.run` với command whitelist.
- `device.health` để báo trạng thái thiết bị.
- `send-user-msg` để gửi sự kiện vào phiên Muse.
- Command riêng cho loa: TTS, phát nhạc, âm lượng, Wi‑Fi, reboot.

SDK token phải được lưu trong private storage và không được commit, log hoặc gửi qua URL.
