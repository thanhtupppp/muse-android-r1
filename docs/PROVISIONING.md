# Wi-Fi Provisioning

## Mục tiêu

Cho phép **Muse Mobile App** cấu hình Wi-Fi nhà cho Phicomm R1 mà không cần ADB. ADB vẫn được giữ làm kênh khôi phục/kỹ thuật.

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
Wi-Fi nhà
```

## Endpoint

### GET /health

Kiểm tra agent đang chạy.

### GET /status

Trả về trạng thái thiết bị và kết nối mạng. Không gửi mật khẩu Wi-Fi hoặc Muse token trong response.

### POST /provision-wifi

Nhận JSON gồm `ssid`, `password` và `security` (hiện chỉ hỗ trợ WPA2).

**Cảnh báo bảo mật:** Endpoint HTTP hiện chưa có xác thực và truyền mật khẩu qua HTTP không mã hóa. Chỉ sử dụng trên mạng hotspot cô lập, tin cậy; không triển khai như một endpoint mạng LAN công khai. Việc bổ sung provisioning token/QR hoặc HTTPS có xác thực là điều kiện bắt buộc trước khi coi luồng này an toàn để phát hành rộng rãi. Không được xem việc bảo vệ ADB receiver là đã khắc phục rủi ro HTTP này.

## ADB recovery (vẫn được hỗ trợ)

Receiver vẫn exported để Android shell có thể gọi qua ADB, nhưng manifest yêu cầu `android.permission.DUMP`. Quyền này được cấp cho shell UID dùng bởi `adb shell`, không phải quyền thông thường của ứng dụng bên thứ ba.

Cấu hình Wi-Fi:

```bash
adb shell am broadcast -a vn.tutu.muser1.PROVISION_WIFI \\
  --es ssid "TEN_WIFI_NHA" --es password "MAT_KHAU"
```

Cấu hình Muse token:

```bash
adb shell am broadcast -a vn.tutu.muser1.CONFIGURE_MUSE \\
  --es muse_token "mgst_..."
```

Không đưa mật khẩu/token thật vào issue, log, shell history dùng chung hoặc ảnh chụp màn hình. Lệnh broadcast có thể để lại thông tin nhạy cảm trong lịch sử terminal.

## Android 5.1

R1 dùng Android 5.1.1, nên `Muse R1 Agent` dùng:

```kotlin
wifiManager.addNetwork(configuration)
wifiManager.enableNetwork(networkId, true)
wifiManager.reconnect()
```

Không dùng `WifiNetworkSuggestion` làm luồng chính vì API này chỉ có từ Android 10.

## Xử lý sự cố

| Hiện tượng | Kiểm tra |
|---|---|
| Mobile không thấy R1 | Điện thoại có đang nối `Phicomm_R1_xxx`? |
| `/health` không phản hồi | AgentService có chạy? Cổng 8080 có bị chiếm? |
| ADB báo Permission Denial | Xác nhận lệnh chạy qua `adb shell` (shell UID), không phải từ một ứng dụng khác |
| R1 không vào Wi-Fi nhà | Router có phát 2.4 GHz? Mật khẩu WPA2 có đúng? |
| R1 mất kết nối sau reboot | Kiểm tra `dumpsys wifi` và cấu hình đã lưu |
