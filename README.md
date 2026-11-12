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
| `app` | Phicomm R1 | Không | Wi‑Fi provisioning, Muse session, audio/TTS, local HTTP server |
| `mobile` | Điện thoại Android | Có | Thêm loa, chọn Wi‑Fi, cấu hình, điều khiển, chẩn đoán |

## Mobile provisioning flow

```text
1. Mở Muse Mobile App
2. Chọn “Thêm loa R1”
3. Giữ nút tròn trên R1 6 giây
4. Kết nối Wi‑Fi hotspot Phicomm_R1_xxx
5. App kiểm tra http://192.168.43.1:8080/health
6. Chọn Wi‑Fi nhà và nhập mật khẩu
7. Gửi POST /provision-wifi tới R1
8. R1 lưu Wi‑Fi và kết nối mạng nhà
9. App theo dõi trạng thái provisioning
```

## API cục bộ R1

```http
GET http://192.168.43.1:8080/health
```

```http
POST http://192.168.43.1:8080/provision-wifi
Content-Type: application/json

{
  "ssid": "HomeWiFi",
  "password": "home_wifi_password",
  "security": "WPA2"
}
```

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :mobile:assembleDebug
```

## Lưu ý

- R1 chạy Android 5.1.1 / API 22.
- `app` dùng `minSdk = 22`, `targetSdk = 28`.
- `mobile` dùng `minSdk = 26`, `targetSdk = 34`.
- Không dùng MQTT ở giai đoạn hiện tại.
- Muse Mobile App không thay thế app Muse chính thức trong việc xác minh tài khoản hoặc pair gadget.
