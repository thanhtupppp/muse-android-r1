# Wi-Fi Provisioning

## Luồng thực tế trong source hiện tại

Mobile cài Agent qua ADB over Wi-Fi, sau đó gửi cấu hình mạng nhà **qua HTTP :8080 trước** (khi điện thoại đang nối hotspot của loa), **Bluetooth SPP là đường dự phòng** khi HTTP không tới được. UDP discovery xác nhận loa xuất hiện lại trên mạng nhà.

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
HTTP POST /provision-wifi: gửi SSID/password WPA2 (dự phòng: Bluetooth SPP)
        ↓
R1 dùng WifiManager (Android 5.1.1 / API 22)
        ↓
R1 kết nối Wi-Fi nhà
        ↓
UDP discovery :47808 xác nhận R1 xuất hiện lại
```

## Kênh kết nối

- **ADB TCP 5555:** cài Agent APK. Mobile kiểm tra shell, file APK, kết quả cài đặt và trạng thái AgentService.
- **HTTP :8080:** kênh provisioning chính khi điện thoại đang ở hotspot của loa. Agent cung cấp GET `/health`, GET `/status`, POST `/provision-wifi`, POST `/configure-muse`, POST `/open-pairing-window` và POST `/command`. Xác thực bằng provision token (header `x-muse-token`): loa chưa cấu hình Muse thì mở; đã cấu hình thì bắt buộc token, trừ cửa sổ 10 phút sau khi Agent khởi động/mở lại cửa sổ pairing. Mobile nhận token từ response cấu hình Muse (hoặc response mở pairing) và tự đính kèm sau đó.
- **Muse BLE pairing:** trong tab Muse AI của Mobile, nút **Mở chế độ ghép nối trên loa** gọi `POST /open-pairing-window`. Agent ép BLE server phát nhanh `MuseGadget-xxxxxx` trong 10 phút và Mobile chỉ báo sẵn sàng sau khi `/status` xác nhận `bluetooth.server.advertising=true`. Sau đó người dùng pair trong app Muse chính thức qua Settings → Devices → Developer mode → Add Device.
- **Bluetooth SPP:** kênh dự phòng khi HTTP không tới được. UUID: `00001101-0000-1000-8000-00805F9B34FB`. Mobile thử secure RFCOMM trước, insecure sau; loa đã cấu hình cũng kiểm tra provision token trong payload SPP.
- **UDP 47808:** discovery sau khi loa chuyển sang Wi-Fi nhà. Phản hồi chỉ chứa tên dịch vụ, phiên bản Agent và cờ đã cấu hình Muse — không chứa device_id hay SSID.

## Android 5.1.1

R1 dùng API 22, nên Agent sử dụng `WifiConfiguration` và `WifiManager`. Khi SSID đã tồn tại, phải gán `configuration.networkId` trước khi gọi `updateNetwork(configuration)`, sau đó dùng ID trả về để enable network.

## Giao thức Bluetooth

Mobile gửi các dòng `MUSE_WIFI_CONFIG`, `ssid=...`, `password=...`, `security=WPA2`, kết thúc bằng `END`. Agent trả một dòng `OK` hoặc `ERROR: ...`. Mobile chỉ báo thành công khi nhận chính xác `OK`; EOF hoặc phản hồi rỗng là lỗi.

## Quét Wi-Fi từ loa và quản lý mạng đã lưu (HTTP :8080)

- `GET /wifi-scan` — loa tự quét và trả danh sách Wi-Fi theo góc nhìn của loa (`ssid`, `rssi`, `security`, `has_2_4ghz`, `has_5ghz`, `saved`, `connected`). Mobile ưu tiên danh sách này khi chọn mạng cho loa; kết quả điện thoại quét chỉ là dự phòng. Logic quét dùng chung với đường BLE của app Muse (`SpeakerWifi`).
- `GET /wifi-saved` — các mạng đã lưu trong cấu hình của loa + `current_ssid`/`current_ip`.
- `POST /wifi-forget` `{ssid}` — quên một mạng đã lưu. Quên đúng mạng đang kết nối được lên lịch sau ~1,2 giây (response kịp về trước khi loa rớt mạng) và trả `was_connected: true, scheduled: true`.
- `POST /wifi-use` `{ssid}` — chuyển loa sang một mạng đã lưu (không cần mật khẩu), cũng lên lịch ~1,2 giây; kết quả thật ghi vào `wifi_provision` của `/status` với `action: "wifi_use"` (mã lỗi có thể gặp: `NOT_SAVED`, `SWITCH_TIMEOUT`, `ENABLE_NETWORK_FAILED`). Mobile thăm dò `/wifi-saved` tới khi `current_ssid` khớp mới báo thành công. Chuyển thất bại thì loa cố quay về mạng trước đó.

Cả bốn endpoint dùng cùng quy tắc xác thực `x-muse-token` như `/status`.

## Bảo mật và vận hành

- Không ghi mật khẩu Wi-Fi hoặc Muse token vào log (SSID chỉ ghi ở mức debug cần thiết).
- Chỉ hỗ trợ WPA2 ở phiên bản hiện tại.
- ADB dùng trong bước cài Agent và các thao tác khôi phục của chủ loa (mở lại cửa sổ pairing, giả lập nút bấm confirm_press qua intent của AgentService).
- HTTP :8080 và SPP đều đã có xác thực bằng provision token sau khi loa được cấu hình Muse; trước thời điểm đó chúng mở trong phạm vi hotspot/cặp ghép Bluetooth của chính loa (mô hình cài đặt ban đầu, tương tự `pairing_auth = none` của Muse Gadget SDK).

## Xử lý sự cố

| Hiện tượng | Kiểm tra |
|---|---|
| Mobile không kết nối ADB | Điện thoại đang ở hotspot R1; ADB TCP 5555 đang bật và chấp nhận khóa ADB của app |
| ADB push thành công nhưng install lỗi | Kiểm tra output shell, dung lượng `/data` và kết quả `pm install` |
| Không tìm thấy Muse R1 Bluetooth | Bật Bluetooth, ghép đôi loa và kiểm tra tên thiết bị bắt đầu bằng `Muse R1` |
| R1 từ chối Wi-Fi | Kiểm tra SSID, mật khẩu WPA2 và khả năng tương thích 2.4 GHz |
| Mobile không thấy loa sau provision | Kiểm tra hai thiết bị cùng mạng và UDP broadcast :47808 |
| Cần khôi phục | Dùng ADB recovery khi điện thoại kết nối lại được với hotspot R1 |
