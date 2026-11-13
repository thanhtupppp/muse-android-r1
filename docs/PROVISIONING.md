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

Mobile gửi các dòng `MUSE_WIFI_CONFIG`, `ssid=...`, `password=...`, `security=WPA2`, kết thúc bằng `END`. Agent có thể gửi dòng tiến độ `SAVED` (loa đã lưu cấu hình) trước dòng kết quả cuối `OK`/`ERROR: ...`. Mobile chỉ báo thành công khi nhận chính xác `OK`; EOF hoặc phản hồi rỗng là lỗi. Lỗi đường truyền Bluetooth được thử lại tối đa 1 lần; loa đã trả mã nghiệp vụ (`ERROR: <CODE>`) thì không thử lại.

## Trạng thái provisioning dùng chung

Mọi đường truyền (HTTP, SPP, BLE, ADB) dùng cùng một bộ pha trong `WifiProvisioner`: `connecting → saved → connected`, hoặc `failed` kèm mã lỗi. Pha `saved` là mốc cấu hình đã được ghi bền trên loa (tách khỏi mốc đã kết nối): SPP báo bằng dòng `SAVED`, BLE bằng trạng thái `wifi_saved`, HTTP qua bản ghi dưới đây.

Bản ghi lần thử gần nhất nằm ở `GET /status` → `wifi_provision`: `{ssid, at_ms, phase, via (http|spp|ble|adb|wifi_use…), ok, ip | code, config_saved}`. Mobile thăm dò bản ghi này trong lúc chờ loa lên mạng nhà: gặp `phase: "failed"` của đúng lần thử là dừng chờ ngay và hiển thị mã lỗi kèm hướng dẫn (`ProvisioningHelp` phía Mobile ánh xạ từng mã → câu hướng dẫn tiếng Việt), gặp `connected` thì lấy luôn IP loa báo.

Log và thông báo lỗi hai phía đều đi qua `SecretRedactor` (che password/token/secret/api-key/authorization, Bearer và token `mgst_...`) trước khi hiển thị hoặc copy ra ngoài. Giá trị bị che được thay bằng vân tay debug `***(len=N, sha256:xxxxxxxx)` (8 ký tự đầu của SHA-256) để đối chiếu hai đầu có đang dùng cùng một bí mật hay không mà không lộ giá trị.

## Quét Wi-Fi từ loa và quản lý mạng đã lưu (HTTP :8080)

- `GET /wifi-scan` — loa tự quét và trả danh sách Wi-Fi theo góc nhìn của loa (`ssid`, `rssi`, `security`, `has_2_4ghz`, `has_5ghz`, `saved`, `connected`). Mobile ưu tiên danh sách này khi chọn mạng cho loa; kết quả điện thoại quét chỉ là dự phòng. Logic quét dùng chung với đường BLE của app Muse (`SpeakerWifi`).
- `GET /wifi-saved` — các mạng đã lưu trong cấu hình của loa + `current_ssid`/`current_ip`.
- `POST /wifi-forget` `{ssid}` — quên một mạng đã lưu. Quên đúng mạng đang kết nối được lên lịch sau ~1,2 giây (response kịp về trước khi loa rớt mạng) và trả `was_connected: true, scheduled: true`.
- `POST /wifi-use` `{ssid}` — chuyển loa sang một mạng đã lưu (không cần mật khẩu), cũng lên lịch ~1,2 giây; kết quả thật ghi vào `wifi_provision` của `/status` với `action: "wifi_use"` (mã lỗi có thể gặp: `NOT_SAVED`, `SWITCH_TIMEOUT`, `ENABLE_NETWORK_FAILED`). Mobile thăm dò `/wifi-saved` tới khi `current_ssid` khớp mới báo thành công. Chuyển thất bại thì loa cố quay về mạng trước đó.

Cả bốn endpoint dùng cùng quy tắc xác thực `x-muse-token` như `/status`.

## Điều khiển vòng LED (HTTP :8080)

Học theo API LED của DomeeBox (server LAN :8081 của họ) nhưng giữ luật xác thực token của mình và trung thực về phần cứng — chặng cuối xuống đèn trên ROM R1 chưa được biết trước, nên Agent **dò đường trước**:

- Khi Agent khởi động, `LedControl.probe()` chạy nền một lần: liệt kê `/sys/class/leds/*` (tên node, các file, `max_brightness`, file `brightness` có ghi được từ uid của app không) và các service hệ thống có tên gợi ý LED/vendor (qua `ServiceManager.listServices()`). Kết quả ở `GET /status` → `led_probe` (`available`, `strategy`: `rgb_triple` / `single_brightness` / `color_file` / `none`, `channels`, `nodes`, `vendor_services`) và được lưu trong preferences.
- `GET /led` — trạng thái LED hiện tại + toàn bộ kết quả probe.
- `POST /led` `{color?: "#RRGGBB", brightness?: 0..100, off?: true}` — chỉ điều khiển thật (ghi sysfs `brightness` theo kênh) khi `led_probe.available = true`, tức có đủ node kênh ghi được. Chưa có đường thì trả lỗi `LED_UNAVAILABLE`; ghi thất bại trả `LED_WRITE_FAILED:<node>`; màu sai định dạng trả `INVALID_COLOR`. Node chỉ có file `color` (không có `brightness`) mới được tính là bằng chứng (`strategy: color_file`, vẫn `available: false`) vì định dạng ghi của file đó khác nhau theo vendor.
- Mobile: thẻ "Đèn LED vòng" ở tab Home (preset màu + slider độ sáng + nút tắt) chỉ hiện khi `led_probe.available = true`.

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
