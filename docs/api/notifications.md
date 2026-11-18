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

## Thông báo `CONTRACT_EXPIRING` (ADR-0012)

Khi hợp đồng thuê dịch vụ của tenant sắp hết hạn (mốc 30, 7, 1 ngày trước ngày kết thúc, mốc gấp nhất chưa gửi), identity phát event `TenantContractExpiringSoon` (topic `identity-events`) và support-service tạo **một thông báo cho từng quản trị viên (role `is_admin`) đang hoạt động của tenant**:

- `type` = `CONTRACT_EXPIRING`, `message` tiếng Việt, ví dụ: `Hợp đồng thuê dịch vụ "Gói A" sẽ hết hạn vào 31/12/2026 (còn khoảng 30 ngày). Vui lòng liên hệ để gia hạn.` Không có số tiền hay ghi chú nội bộ.
- Đi qua các API thông báo sẵn có ở trên, không có API mới; không có migration. Idempotent: giao lại event không nhân bản thông báo.
- Kafka best-effort: mất event là mất thông báo của mốc đó; nguồn sự thật là `GET /api/contracts/current` (`docs/api/tenant-contracts.md`).

## Thông báo theo sự kiện qua email (ADR-0030, gói C)

support-service nghe sự kiện nghiệp vụ, ghi thông báo trong ứng dụng (như trên) và **xếp một lượt gửi email** (`delivery`) cho từng người nhận. Job nền xả hàng chờ mỗi 5 giây: tra email ở identity, dựng nội dung từ mẫu tiếng Việt, gửi SMTP (Mailpit khi chạy local). Thông báo trong ứng dụng luôn được ghi, không tắt được; chỉ kênh email có cấu hình bật/tắt.

Migration: `audit/V6__notification_deliveries.sql` (bảng `notification_deliveries`, `notification_templates`, `notification_channel_settings`, `notification_preferences`, `notification_tenants`).

### Loại thông báo và nhóm

| `type` | Nhóm (`category`) | Sự kiện nguồn | Tham số mẫu cho phép |
|---|---|---|---|
| `ORDER_PLACED`, `ORDER_PAID` | `ORDER` | `sales.events` `OrderPlaced`, `OrderPaid` | `order_no`, `grand_total` |
| `ORDER_READY_FOR_PICKUP`, `ORDER_SHIPPING`, `ORDER_COMPLETED` | `ORDER` | `OrderReadyForPickup`, `OrderShipping`, `OrderCompleted` (đơn online) | `order_no` |
| `ORDER_CANCELLED` | `ORDER` | `OrderCancelled` | `order_no`, `cancel_reason` |
| `REFUND_COMPLETED` | `REFUND` (bắt buộc) | `RefundCompleted` | `order_no`, `amount` |
| `INVOICE_ISSUED` | `INVOICE` | `InvoiceIssued` (sales.events) và `notification.direct` (khách lẻ) | `invoice_no`, `order_no`, `grand_total`, `issued_at` |
| `LOAN_DUE_SOON` | `LOAN_REMINDER` | `borrowing.events` `LoanDueSoon` (gói D) | `due_date`, `days_before`, `item_count` |
| `LOAN_OVERDUE` | `LOAN_REMINDER` | `LoanOverdueReminder` (gói D) | `due_date`, `days_overdue`, `reminder_no`, `max_reminders`, `item_count` |
| `RESERVATION_READY` | `RESERVATION` | `reservation.events` `ReservationReadyEvent` | `hold_until` |
| `FINE_ISSUED` | `FINE` | `fine.events` `FineIssuedEvent` | `amount`, `reason` |
| `CONTRACT_EXPIRING` | `ACCOUNT` (bắt buộc) | `identity-events` `TenantContractExpiringSoon` (quản trị tenant) | `contract_name`, `end_date`, `days` |

Mọi loại còn dùng được `recipient_name` và `tenant_name` (do hệ thống điền lúc gửi). Đơn POS không sinh thông báo `ORDER_*` (khách đang đứng tại quầy); hoàn tiền và hóa đơn gửi cho cả đơn POS nếu đơn có `customerUserId`. Sự kiện đơn hàng chỉ sinh email khi payload có `customerUserId` (do gói D làm giàu sự kiện sales).

### API của tôi (chỉ cần đăng nhập, không có khóa quyền)

| Method | Đường dẫn | Mô tả |
|---|---|---|
| GET | `/api/notifications/me/preferences` | Tùy chọn nhận của tôi: đủ mọi (nhóm x kênh). `{category, channel, enabled, mandatory}` |
| PUT | `/api/notifications/me/preferences` | Body `{ "items": [{ "category", "channel", "enabled" }] }`. Trả danh sách mới như GET |
| GET | `/api/notifications/me/deliveries` | 50 lượt gửi gần nhất của tôi. `{id, type, category, channel, status, skipReason, recipientAddressMasked, sentAt, createdAt}` |

- `category` thuộc `ORDER`, `INVOICE`, `LOAN_REMINDER`, `RESERVATION`, `FINE`, `REFUND`, `ACCOUNT`; `channel` hiện chỉ `EMAIL`.
- Tắt nhóm bắt buộc (`REFUND`, `ACCOUNT`) trong bất kỳ phần tử nào: cả yêu cầu bị từ chối, không ghi gì: `400 PREFERENCE_MANDATORY`.
- Không có dòng nào = nhận. Người đã tắt nhóm thì lượt gửi ghi `status = SKIPPED`, `skipReason = OPTED_OUT`.

### API quản trị tenant (khóa quyền động, mặc định chỉ role `is_admin`; không gán cho role mẫu nào)

| Method | Đường dẫn | Khóa (`Controller/method`) |
|---|---|---|
| GET | `/api/notification-deliveries?status&channel&type&from&to&page&size` | `NotificationDeliveryController/search` |
| POST | `/api/notification-deliveries/{id}/retry` | `NotificationDeliveryController/retry` |
| GET | `/api/notification-settings/channels` | `NotificationSettingsController/getChannels` |
| PUT | `/api/notification-settings/channels` | `NotificationSettingsController/updateChannels` |
| GET | `/api/notification-templates` | `NotificationTemplateController/list` |
| GET | `/api/notification-templates/{type}/{channel}` | `NotificationTemplateController/get` |
| PUT | `/api/notification-templates/{type}/{channel}` | `NotificationTemplateController/save` |
| POST | `/api/notification-templates/{type}/{channel}/preview` | `NotificationTemplateController/preview` |

Phía lms-api controller có tên trùng (`NotificationDeliveryController`...) nhưng khóa quyền do **support-service** kiểm. Admin tenant gán khóa cho role khác ở màn phân quyền khi cần (khóa tự đăng ký lần đầu endpoint được gọi).

**Lịch sử gửi** (`GET /api/notification-deliveries`): trả `{items, total, page, size}`, mới nhất trước, `size` tối đa 100 (mặc định 20). `status` thuộc `PENDING|SENDING|SENT|RETRY|DEAD|SKIPPED`; `type` là mã loại; `from`/`to` ISO-8601 theo thời điểm tạo. Mỗi phần tử: `{id, eventId, eventType, type, category, channel, status, skipReason, recipientUserId, recipientAddressMasked, attempts, nextAttemptAt, lastErrorCode, lastErrorAt, sentAt, templateSource, templateVersion, lastRetryBy, lastRetryAt, createdAt}`. Địa chỉ chỉ ở dạng che (`n***@gmail.com`); không có nội dung thư, tham số hay thông điệp lỗi gốc của SMTP.

- `skipReason`: `NO_PROVIDER`, `CHANNEL_DISABLED`, `OPTED_OUT`, `NO_ADDRESS`, `INACTIVE_USER`.
- `lastErrorCode`: `SMTP_4XX`, `SMTP_5XX`, `TIMEOUT`, `SMTP_CONNECT`, `SMTP_AUTH`, `SMTP_INVALID_MESSAGE`, `MAIL_NOT_CONFIGURED`, `RECIPIENT_LOOKUP_FAILED`, `TEMPLATE_ERROR`, `UNEXPECTED_ERROR`.
- Thử lại: lần đầu rồi tối đa 5 lần thử lại, chờ 1 phút, 5 phút, 30 phút, 2 giờ, 6 giờ; lỗi vĩnh viễn (SMTP 5xx, địa chỉ không hợp lệ) hoặc hết lượt thì `DEAD`. Giới hạn 60 thư/phút/tenant và 600 thư/phút toàn hệ thống; phần dư giữ `PENDING` tới lượt sau. Lượt kết thúc được dọn sau 180 ngày.
- `POST /{id}/retry`: chỉ lượt `DEAD`; đưa về `PENDING`, giữ `attempts`, mở một đợt thử mới (5 lần thử lại nữa), ghi `lastRetryBy`/`lastRetryAt`. Lỗi: `404 DELIVERY_NOT_FOUND` (không có hoặc của tenant khác), `409 DELIVERY_NOT_RETRYABLE` (không phải `DEAD`).

**Cấu hình kênh** (`/api/notification-settings/channels`): GET trả đủ mọi (loại x kênh) `{type, category, channel, enabled}` (mặc định `true`); PUT body `{ "items": [{ "type", "channel", "enabled" }] }` rồi trả danh sách mới. Tắt kênh email của một loại thì lượt gửi `SKIPPED/CHANNEL_DISABLED`; thông báo trong ứng dụng vẫn ghi.

**Mẫu nội dung** (`/api/notification-templates`): GET list/get trả mẫu đang hiệu lực `{type, category, channel, source, version, subject, body, allowedParams}`; `source = DEFAULT` (mặc định trong ứng dụng, phiên bản 1) hoặc `TENANT` (ghi đè). `PUT` body `{ "subject" (<=200), "body" (<=5000) }` tạo **phiên bản mới** (phiên bản cũ thôi hiệu lực, không xóa). Chỗ trống dùng `{{ten_tham_so}}` theo `allowedParams` của loại; tham số lạ: `400 TEMPLATE_PARAM_NOT_ALLOWED`, cú pháp `{{` sai: `400 TEMPLATE_INVALID`. `POST .../preview` body `{ "subject", "body" }` dựng thử bản nháp (cả hai vắng thì dựng thử mẫu hiện hành) với dữ liệu giả, không gửi.

### Hợp đồng nội bộ (không qua FE)

- identity `POST /internal/notification-recipients/lookup`, body `{tenantId, userIds[<=100]}`, header `X-Notification-Client-Secret` (biến `NOTIFICATION_CLIENT_SECRET`, trống = từ chối mọi lời gọi). Trả `{tenantDisplayName, recipients[{userId, email, displayName, active}]}`; email ưu tiên hồ sơ độc giả của tenant, chỉ người có membership ACTIVE. Không đi qua lms-api/nginx.
- Topic `notification.direct` (khách lẻ không tài khoản; audit KHÔNG nghe topic này): `{eventId, tenantId, notificationType, recipientEmail, params:{...}}`. support lưu địa chỉ tạm ở delivery và xóa khi lượt gửi kết thúc.
- Biến môi trường mới của support-service: `NOTIFICATION_CLIENT_SECRET` (cùng giá trị với identity), `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `NOTIFICATION_EMAIL_FROM`, `NOTIFICATION_DISPATCH_ENABLED`.

### Ghi chú cho FE

- Thư gửi nền: không có API "gửi ngay". Màn lịch sử nên thăm dò lại sau vài giây.
- Hiện chưa có đường dẫn (link) trong thư vì chưa có cấu hình URL gốc của storefront/trang quản trị (ADR-0030 mục 5.14).
- Quên mật khẩu: `auth.password-reset` chưa có consumer nên email quên mật khẩu **chưa được gửi** (xem ADR-0030 mục 6); không thuộc gói này.
