# Muse Android R1

Monorepo cho hệ thống Muse trên loa Phicomm R1.

```text
muse-android-r1/
├── app/       # Headless agent cài trên Phicomm R1
├── mobile/    # Ứng dụng Android cài trên điện thoại
└── docs/      # Kiến trúc, Muse integration, provisioning
```

## Thành phần

| Module | Cài ở đâu | UI | Trách nhiệm |
|---|---|---|---|
| `app` | Phicomm R1 | Không | Wi-Fi provisioning, local diagnostics, Muse session |
| `mobile` | Điện thoại Android | Có | Tải APK, cài Agent qua ADB, cấu hình Wi-Fi qua Bluetooth, discovery |

## Quy trình thêm loa R1

1. Mở Muse Mobile và tải Agent APK khi điện thoại còn Internet.
2. Giữ nút tròn trên loa khoảng 6 giây để mở hotspot.
3. Kết nối điện thoại vào `Phicomm_R1_xxx`.
4. Cài Agent APK qua ADB over Wi-Fi (TCP 5555). App kiểm tra shell, file APK, kết quả `pm install` và trạng thái `AgentService`.
5. Ghép đôi Bluetooth với loa nếu chưa ghép.
6. Chọn Wi-Fi nhà và gửi SSID/mật khẩu qua Bluetooth SPP.
7. R1 kết nối Wi-Fi nhà; Mobile dùng UDP discovery port 47808 để xác nhận loa xuất hiện lại.

Chi tiết: [docs/PROVISIONING.md](docs/PROVISIONING.md).

## Local API (diagnostic / legacy)

Agent cung cấp HTTP :8080: `GET /health`, `GET /status`, `POST /provision-wifi`. Mobile hiện dùng Bluetooth SPP làm luồng provisioning chính, không dùng HTTP POST. POST chưa có xác thực riêng; không expose cổng 8080 trên mạng không tin cậy.

## Build

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
./gradlew :mobile:testDebugUnitTest :mobile:assembleDebug
```

## ADB recovery

```bat
adb shell am broadcast -a vn.tutu.muser1.PROVISION_WIFI --es ssid "TEN_WIFI_NHA" --es password "MAT_KHAU"
adb shell am broadcast -a vn.tutu.muser1.CONFIGURE_MUSE --es muse_token "mgst_..."
```

## Platform

- R1: Android 5.1.1 / API 22; Agent minSdk 22, targetSdk 28.
- Mobile: minSdk 26, targetSdk 34.
- MQTT chưa được bật làm kênh điều khiển trong luồng hiện tại.
