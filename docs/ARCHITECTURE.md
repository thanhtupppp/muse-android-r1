# Architecture

## Components

```text
Muse Mobile App
        │
        │ MQTT
        ▼
Muse R1 Agent
├── ProvisionReceiver
├── BootReceiver
├── AgentService
├── MqttAgent
├── CommandRouter
├── MuseCommandSurface
├── MuseGadgetClient
├── TelemetryReporter
└── WifiProvisioner
```

## Responsibilities

| Component | Responsibility |
|---|---|
| `ProvisionReceiver` | Nhận cấu hình Wi‑Fi, MQTT, Muse token qua ADB broadcast |
| `BootReceiver` | Khởi động agent sau reboot |
| `AgentService` | Duy trì agent nền và telemetry loop |
| `MqttAgent` | Kết nối MQTT, subscribe command, publish status/event |
| `CommandRouter` | Parse và điều hướng lệnh từ Muse Mobile App |
| `MuseCommandSurface` | Command whitelist an toàn cho Muse Gadget SDK |
| `MuseGadgetClient` | Chuẩn bị pairing/token/status cho Muse |
| `TelemetryReporter` | Gửi trạng thái thiết bị định kỳ |
| `WifiProvisioner` | Cấu hình Wi‑Fi bằng Android 5.1 legacy API |

## Design decisions

- R1 chạy Android 5.1.1, nên dùng legacy Wi‑Fi API.
- Agent là headless; UI thuộc Muse Mobile App.
- MQTT là kênh điều khiển chính giữa phone app và R1.
- Muse Gadget SDK là kênh AI/session riêng.
- Command surface phải whitelist, không cho phép shell tùy ý.
- SDK token lưu private storage và không log.
