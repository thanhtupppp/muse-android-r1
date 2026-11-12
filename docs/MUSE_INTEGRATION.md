# Muse Gadget Integration

Repo tham chiếu chính thức:

https://github.com/facebookincubator/muse-gadget-sdk

## Mục tiêu

`muse-android-r1` sẽ trở thành một **Muse gadget** chạy trên Phicomm R1:

- Pair với Muse app.
- Duy trì kết nối tới Muse sau reboot.
- Nhận command từ Muse.
- Báo trạng thái và telemetry.
- Cung cấp command riêng cho loa: TTS, âm lượng, phát nhạc, Wi‑Fi, reboot.

## Cách Muse pair gadget

Theo Linux Device SDK:

1. Tạo SDK token tại `gadgets.muse.ai > Account > SDK tokens`.
2. Token có dạng `mgst_...`.
3. Bật **Settings > Devices > Developer mode** trong Muse app.
4. Chọn **Add Device** và tìm thiết bị có tiền tố `MuseGadget`.
5. Chấp nhận cảnh báo community device.
6. Chọn Wi‑Fi khi Muse hỏi.
7. Thiết bị giữ kết nối xuyên suốt các lần reboot.

Pairing mở trong 10 phút. Nếu đổi hoặc thu hồi token, cần pair lại. [Linux README]

## Kiến trúc đang triển khai

```text
MuseGadgetClient
├── Token store
├── Pairing state
├── Command surface
├── Telemetry
└── Muse session adapter (sẽ bổ sung)
```

## Command surface an toàn

Hiện tại chỉ cho phép các command sau:

| Command | Mô tả |
|---|---|
| `muse_r1.set_volume` | Đặt âm lượng 0–100% |
| `muse_r1.get_status` | Lấy trạng thái Wi‑Fi/IP và pairing |
| `muse_r1.tts` | Yêu cầu phát TTS |
| `muse_r1.stop_audio` | Dừng phát âm thanh |

Không cho phép `system.run` chạy shell command tùy ý.
Mọi lệnh hệ thống phải được map vào whitelist.

## Ví dụ command

```json
{
  "name": "muse_r1.set_volume",
  "volume": 60
}
```

```json
{
  "name": "muse_r1.tts",
  "text": "Xin chào Tu"
}
```

## Bảo mật

- SDK token lưu trong private SharedPreferences.
- Không log token, không commit token, không gửi token qua URL.
- Không cho phép shell command tự do.
- Local MQTT cần broker riêng, username/password hoặc token.
- Chỉ thêm quyền file/system khi thực sự cần thiết.
