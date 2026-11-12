# Wi‑Fi Provisioning

## Mục tiêu

Cho phép **Muse Mobile App** cấu hình Wi‑Fi nhà cho Phicomm R1 mà không cần ADB.

## Luồng chuẩn

```text
Điện thoại
   │
   │ kết nối hotspot Phicomm_R1_xxx
   ▼
R1 Local HTTP Server :8080
   │
   │ POST /provision-wifi
   ▼
Muse R1 Agent
   │
   │ WifiManager.addNetwork + enableNetwork + reconnect
   ▼
Wi‑Fi nhà
```

## Endpoint

### GET /health

Kiểm tra agent đang chạy.

```json
{
  "ok": true,
  "service": "muse-r1-agent",
  "version": "0.4.0-local-provisioning",
  "provisioning_mode": true
}
```

### GET /status

```json
{
  "ok": true,
  "device_id": "R1-001",
  "agent_version": "0.4.0-local-provisioning",
  "android_version": "5.1.1",
  "network": "SSID: HomeWiFi\nIP: 192.168.1.25",
  "muse_paired": false
}
```

### POST /provision-wifi

```json
{
  "ssid": "HomeWiFi",
  "password": "home_wifi_password",
  "security": "WPA2"
}
```

Phản hồi thành công:

```json
{
  "ok": true,
  "message": "Connected command sent for HomeWiFi",
  "device_id": "R1-001"
}
```

## Android 5.1

R1 dùng Android 5.1.1, nên `Muse R1 Agent` dùng:

```kotlin
wifiManager.addNetwork(configuration)
wifiManager.enableNetwork(networkId, true)
wifiManager.reconnect()
```

Không dùng `WifiNetworkSuggestion` làm luồng chính vì API này chỉ có từ Android 10.

## Bảo mật

- Server chỉ phục vụ provisioning khi R1 đang phát hotspot.
- Không log SSID, mật khẩu, Muse token.
- Không lưu mật khẩu Wi‑Fi trong Muse Mobile App.
- Chỉ hỗ trợ WPA2 ở giai đoạn đầu.
- Sau này thêm provisioning token/QR để ngăn thiết bị lạ gửi cấu hình.

## Xử lý sự cố

| Hiện tượng | Kiểm tra |
|---|---|
| Mobile không thấy R1 | Điện thoại có đang nối `Phicomm_R1_xxx`? |
| `/health` không phản hồi | AgentService có chạy? Cổng 8080 có bị chiếm? |
| R1 không vào Wi‑Fi nhà | Router có phát 2.4 GHz? Mật khẩu WPA2 có đúng? |
| R1 mất kết nối sau reboot | Kiểm tra `dumpsys wifi` và cấu hình đã lưu |
| Cần khôi phục | Dùng ADB broadcast `PROVISION_WIFI` |
