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

## Luồng session hiện tại

```text
Muse SDK token
      │
      ▼
POST /device_token/refresh
      │
      ▼
access_token + refresh_token
      │
      ▼
GET /fetch_vms
      │
      ▼
Chọn VM default
      │
      ▼
WebSocket tới VM
      │
      ▼
link.register
```

## API đã port

| API | Method | Mục đích |
|---|---|---|
| `/fetch_vms` | GET | Lấy VM được lease cho device token |
| `/device_token/refresh` | POST | Xoay access token và refresh token |

`MuseApi.fetchVms()` gửi:

- `Authorization: Bearer <access_token>`
- `X-API-Version: 1.0.0`
- `User-Agent: muse-android-r1/...`

`MuseApi.refreshDeviceToken()` gửi:

- `Authorization: Bearer hatch_refresh:<refresh_token>`
- Body: `device_id`, optional `sdk_token`

Nếu refresh trả HTTP 401, thiết bị phải pair lại với Muse. [Muse Linux SDK]

## WebSocket VM

`MuseSessionAdapter` hiện:

1. Refresh token nếu cần.
2. Gọi `/fetch_vms`.
3. Chọn VM có `default = true`, hoặc VM đầu tiên.
4. Kết nối WebSocket tới `vm_ws_url`.
5. Gửi header `Authorization: Bearer <vm_auth_token>`.
6. Gửi message `link.register` khi WebSocket mở.
7. Chuyển message nhận được vào `MuseCommandSurface`.

## Command surface an toàn

| Command | Mô tả |
|---|---|
| `muse_r1.set_volume` | Đặt âm lượng 0–100% |
| `muse_r1.get_status` | Lấy trạng thái Wi‑Fi/IP và pairing |
| `muse_r1.tts` | Yêu cầu phát TTS |
| `muse_r1.stop_audio` | Dừng phát âm thanh |

Không cho phép `system.run` chạy shell command tùy ý.
Mọi lệnh hệ thống phải được map vào whitelist.

## Bảo mật

- SDK token lưu trong private SharedPreferences.
- Access token và refresh token lưu private SharedPreferences.
- Không log token, không commit token, không gửi token qua URL.
- Không cho phép shell command tự do.
- Local MQTT cần broker riêng, username/password hoặc token.
- Chỉ thêm quyền file/system khi thực sự cần thiết.
