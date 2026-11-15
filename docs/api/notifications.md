# API thông báo của tôi (`/api/notifications`)

Mọi endpoint chỉ thao tác trên thông báo của chính người đang đăng nhập (userId lấy từ `sub` của token) trong tenant của token. Cần Bearer token; không cần khóa quyền (support-service dùng `@AuthenticatedOnly`, nên không có khóa `Controller/method` nào phải gán cho role).

| Method | Đường dẫn | Mô tả |
|---|---|---|
| GET | `/api/notifications/me` | Danh sách, mới nhất trước. Mỗi phần tử có `readAt` (null = chưa đọc) |
| GET | `/api/notifications/me/unread-count` | Số thông báo chưa đọc |
| POST | `/api/notifications/{id}/read` | Đánh dấu một thông báo đã đọc |
| POST | `/api/notifications/read-all` | Đánh dấu tất cả thông báo chưa đọc là đã đọc |

Các POST không có body.

## Phản hồi

`NotificationView`: `{ id, type, message, createdAt, readAt }` (thời gian ISO-8601 UTC; `readAt` null khi chưa đọc).

- `GET /me/unread-count` -> `200 { "unread": 4 }`
- `POST /{id}/read` -> `200 NotificationView` (đã có `readAt`). Idempotent: gọi lại vẫn 200 và giữ nguyên `readAt` của lần đầu.
- `POST /read-all` -> `200 { "updated": 3 }` (số thông báo vừa đổi; 0 nếu không còn thông báo chưa đọc, vẫn 200).

## Lỗi

- 401: không có/hết hạn token.
- 404 `NOTIFICATION_NOT_FOUND`: id không tồn tại, thuộc người khác, hoặc thuộc tenant khác (không phân biệt, để không lộ sự tồn tại).
- 400: `{id}` không phải UUID (do bộ xử lý lỗi chung của support-service, mã lỗi `INVALID_AUDIT_QUERY` là tên lịch sử).
- 503: support-service không truy cập được.

## Ghi chú cho FE

- Bảng `notifications` đã có cột `read_at` từ migration audit V3, không có migration mới.
- Thông báo mới tạo qua Kafka (ví dụ reservation sẵn sàng), không có API tạo từ FE.
