# Wi-Fi Provisioning

## Luồng thực tế trong source hiện tại

Mobile cài Agent qua ADB over Wi-Fi, sau đó gửi cấu hình mạng nhà qua Bluetooth SPP. UDP discovery xác nhận loa xuất hiện lại trên mạng nhà.

```text
R1 phát hotspot Phicomm_R1_xxx
        ↓
Điện thoại kết nối hotspot của R1
        ↓
Muse Mobile tải sẵn Agent APK khi còn Internet
        ↓
ADB TCP 5555: push APK và chạy pm install
        ↓
AgentService khởi động trên R1
        ↓
Bluetooth SPP: gửi SSID/password WPA2
        ↓
R1 dùng WifiManager (Android 5.1.1 / API 22)
        ↓
R1 kết nối Wi-Fi nhà
        ↓
UDP discovery :47808 xác nhận R1 xuất hiện lại
```

## Kênh kết nối

- **ADB TCP 5555:** cài Agent APK. Mobile kiểm tra shell, file APK, kết quả cài đặt và trạng thái AgentService.
- **Bluetooth SPP:** kênh provisioning chính. UUID: `00001101-0000-1000-8000-00805F9B34FB`.
- **UDP 47808:** discovery sau khi loa chuyển sang Wi-Fi nhà.
- **HTTP :8080:** Agent cung cấp GET `/health`, GET `/status` và POST `/provision-wifi`. Mobile hiện không dùng POST làm luồng provisioning chính. Endpoint POST chưa có xác thực riêng; không expose ra mạng không tin cậy.

## Android 5.1.1

R1 dùng API 22, nên Agent sử dụng `WifiConfiguration` và `WifiManager`. Khi SSID đã tồn tại, phải gán `configuration.networkId` trước khi gọi `updateNetwork(configuration)`, sau đó dùng ID trả về để enable network.

## Giao thức Bluetooth

Mobile gửi các dòng `MUSE_WIFI_CONFIG`, `ssid=...`, `password=...`, `security=WPA2`, kết thúc bằng `END`. Agent trả một dòng `OK` hoặc `ERROR: ...`. Mobile chỉ báo thành công khi nhận chính xác `OK`; EOF hoặc phản hồi rỗng là lỗi.

## Bảo mật và vận hành

- Không ghi SSID, mật khẩu Wi-Fi hoặc Muse token vào log.
- Chỉ hỗ trợ WPA2 ở phiên bản hiện tại.
- ADB chỉ dùng trong bước cài Agent; luồng gửi mật khẩu Wi-Fi thông thường dùng Bluetooth SPP.
- HTTP `POST /provision-wifi` chưa có xác thực; cần giới hạn vòng đời server và bổ sung auth trước khi dùng làm luồng chính.

## Xử lý sự cố

| Hiện tượng | Kiểm tra |
|---|---|
| Mobile không kết nối ADB | Điện thoại đang ở hotspot R1; ADB TCP 5555 đang bật và chấp nhận khóa ADB của app |
| ADB push thành công nhưng install lỗi | Kiểm tra output shell, dung lượng `/data` và kết quả `pm install` |
| Không tìm thấy Muse R1 Bluetooth | Bật Bluetooth, ghép đôi loa và kiểm tra tên thiết bị bắt đầu bằng `Muse R1` |
| R1 từ chối Wi-Fi | Kiểm tra SSID, mật khẩu WPA2 và khả năng tương thích 2.4 GHz |
| Mobile không thấy loa sau provision | Kiểm tra hai thiết bị cùng mạng và UDP broadcast :47808 |
| Cần khôi phục | Dùng ADB recovery khi điện thoại kết nối lại được với hotspot R1 |
