# Tích hợp Node-RED

Luồng dữ liệu: `ESP32 #1 → UART → ESP32 #2 → Relay`, hai ESP32 giao tiếp MQTT với Node-RED; Node-RED chỉ cung cấp HTTP cho **Java BFF**; BFF ghi MySQL; Frontend chỉ gọi BFF. Các file Arduino và flow Node-RED **không bị sửa**; BFF thích ứng theo hợp đồng của flow `SmartHome_Dashboard_AUTO_SETTINGS`.

## Cấu hình (application.yml / biến môi trường)

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| NODERED_BASE_URL | http://localhost:1880 | Origin Node-RED |
| NODERED_STATE_PATH | /smarthome/api/state | Snapshot phòng học |
| NODERED_COMMAND_PATH | /smarthome/api/command | Gửi lệnh |
| NODERED_ACTUATOR_DEVICE_ID | node2_actuator | device_id ghi trong command_logs |
| NODERED_SENSOR_DEVICE_ID | node1_sensor | device_id ghi trong telemetry |
| NODERED_ACK_TIMEOUT / NODERED_ACK_POLL_INTERVAL | 8000 / 400 ms | Thời gian chờ và chu kỳ hỏi xác nhận (flow tự hết hạn lệnh sau 6,5 s) |
| COLLECTOR_ENABLED | true | Bật/tắt bộ thu thập ghi MySQL |
| COLLECTOR_POLL_INTERVAL_MS / COLLECTOR_TELEMETRY_INTERVAL_MS | 2000 / 15000 | Chu kỳ đọc State / chu kỳ ghi telemetry |

Client không tự thử endpoint khác, không retry, không theo redirect và không chuyển JWT của người dùng sang Node-RED.

## GET /api/state

BFF đọc `/smarthome/api/state` (`kind: room-dashboard`) rồi trả mảng `devices`:

| Nguồn trong flow | Trường trả về |
|---|---|
| sensor.temperature / humidity (khi dht_ok) | `temp`, `humid` |
| sensor.light_percent, đảo chiều theo relay.light_adc_high_is_bright | `lux` = **độ sáng %** |
| sensor.motion (khi pir_ready) | `occupancy` |
| relay.fan_on / light_on | `FAN`, `LIGHT` |
| relay.fan_mode / light_mode | `fan_mode`, `light_mode` (AUTO / MANUAL_ON / MANUAL_OFF), `AUTO` = cả hai kênh AUTO |
| links.sensor / links.relay | `connected`, `status` ONLINE/OFFLINE |
| serverAt − sensorAgeMs / relayAgeMs | `timestamp` (giờ Việt Nam) |

Dữ liệu của liên kết đã quá hạn (flow dùng TTL 10 s) luôn là `null`, không hiển thị số cũ. Payload không có `kind: room-dashboard` trả 502.

## POST /api/control

Body `{target: FAN|LIGHT, action: ON|OFF|AUTO}` được đổi thành `{"command":"FAN_ON"}` (FAN_ON/OFF/AUTO, LIGHT_ON/OFF/AUTO). Flow trả 202 ngay khi đã publish MQTT; kết quả thật nằm ở `notice` của `/state`. BFF gửi đúng một POST rồi hỏi `/state` mỗi 400 ms:

- `notice.status = success` và `notice.command` khớp lệnh: **SUCCESS** (HTTP 200), ghi một `command_logs` với `trigger_by` là username.
- Flow trả 409 (đang chờ lệnh khác hoặc relay chưa có dữ liệu mới): **409**, chưa gửi gì, `retry_safe = true`, không ghi log.
- `notice.status = error` hoặc quá thời gian chờ: **504 TIMEOUT**, không ghi log, không tự gửi lại (không biết thiết bị đã thực thi hay chưa).
- Node-RED tắt: 503; phản hồi sai cấu trúc hoặc HTTP khác 2xx/409: 502.

Không có transaction database mở trong lúc chờ Node-RED. Khóa `pending` của flow là dùng chung: hai nơi (React và dashboard Node-RED) bấm gần nhau (dưới 6,5 s) thì lệnh sau nhận 409.

## Ghi MySQL (RoomDataCollector)

Flow không có node MySQL, nên `RoomDataCollector` (chạy nền, cấu hình ở trên) thay thế mũi tên Node-RED → MySQL:

- **telemetry**: mỗi 15 s khi cảm biến còn mới; DHT lỗi thì `temp/humid` = NULL; `lux` lưu độ sáng % (không phải Lux thật).
- **command_logs**: khi relay đổi trạng thái mà BFF không gây ra. Đổi ở chế độ AUTO → `RULE_ENGINE`; đổi ở chế độ MANUAL (ví dụ nút trên dashboard Node-RED) → `NODERED_UI`. Lệnh do chính BFF gửi chỉ được ghi một lần bởi luồng `/api/control`.
- Giới hạn: chỉ ghi khi Backend đang chạy; thay đổi giữa hai lần đọc (2 s) có thể bị bỏ sót; đổi chế độ sang AUTO mà relay không đổi thì không có bản ghi riêng; relay đổi do AUTO ngay sau khi người dùng bấm "Tự động" được ghi là `RULE_ENGINE`.
