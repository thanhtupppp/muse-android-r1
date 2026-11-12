# Muse Android R1

**Muse Android R1** là Android headless agent dành cho loa Phicomm R1 (Android 5.1.1 / RK3229).

Ứng dụng không có UI. Nó chạy nền để:

- Cấu hình Wi‑Fi nhà qua ADB.
- Khởi động lại sau reboot.
- Duy trì MQTT tới server/broker của bạn.
- Nhận lệnh từ **Muse Mobile App**.
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
        ├── Telemetry
        └── Muse Gadget integration
```

## Android target

Phicomm R1 chạy Android 5.1.1 (API 22):

- `minSdk = 22`
- `targetSdk = 28`
- Wi‑Fi dùng `WifiManager.addNetwork()`, `enableNetwork()`, `reconnect()`
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

## Cấu hình MQTT

```bat
adb shell am broadcast -a vn.tutu.muser1.CONFIGURE_AGENT --es mqtt_host "tcp://192.168.1.10:1883" --es device_id "R1-001"
```

## Cấu hình Muse token

```bat
adb shell am broadcast -a vn.tutu.muser1.CONFIGURE_MUSE --es muse_token "mgst_..."
```

## MQTT topics

| Topic | Chiều | Nội dung |
|---|---|---|
| `muse/r1/{deviceId}/cmd` | Phone → R1 | Lệnh điều khiển |
| `muse/r1/{deviceId}/status` | R1 → Phone | online/offline |
| `muse/r1/{deviceId}/event` | R1 → Phone | Sự kiện và lỗi |
| `muse/r1/{deviceId}/telemetry` | R1 → Phone | Trạng thái thiết bị định kỳ |

## Lệnh MQTT mẫu

```json
{"action":"set_volume","value":60}
```

```json
{"action":"tts","text":"Xin chào Tu"}
```

```json
{"action":"muse_status"}
```

## Muse Gadget SDK

Repo tham chiếu:

https://github.com/facebookincubator/muse-gadget-sdk

Xem chi tiết tại [`docs/MUSE_INTEGRATION.md`](docs/MUSE_INTEGRATION.md).

## Kiểm tra

```bat
adb shell dumpsys wifi | findstr /i "mWifiInfo SSID"
adb shell ip addr show wlan0
adb shell dumpsys activity services vn.tutu.muser1
adb logcat -s MuseR1
```
