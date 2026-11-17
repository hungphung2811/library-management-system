# Kênh token và `aud` (storefront tách khỏi nhân viên)

Căn cứ: customer-api-data-authorization A1, A18, mục 5.3 phương án C. Đổi hợp đồng đăng nhập/làm mới của identity.

## Hợp đồng identity (`/internal/auth/*`)

- `POST /internal/auth/login`: thêm trường tùy chọn `channel` = `STAFF` | `STOREFRONT` (mặc định `STAFF`; giá trị khác -> `400 VALIDATION_FAILED`). `STOREFRONT` bắt buộc có `tenantCode` (thiếu -> `401 UNAUTHORIZED`, trước khi tra tài khoản). Thứ tự kiểm tra không đổi cho mọi kênh: tài khoản -> khóa/vô hiệu (`423 ACCOUNT_LOCKED`, `403 ACCOUNT_DISABLED`) -> mật khẩu -> tenant (`403 NO_TENANT_ACCESS`). Việc gộp mọi lỗi thành `401 LOGIN_FAILED` do customer-api làm (HttpAuthClient), identity giữ mã riêng.
- `POST /internal/auth/refresh`: thêm `channel` tùy chọn như trên. Refresh token chỉ làm mới được ĐÚNG kênh đã phát hành; lệch kênh -> `401 UNAUTHORIZED`, token không bị thu hồi. Phiên mới giữ kênh.
- `POST /internal/auth/switch-tenant`: refresh token kênh STOREFRONT bị `401` (phiên storefront gắn một tenant).
- Không cần bí mật cho `channel`: kênh STOREFRONT chỉ THU HẸP token.
- `refresh_tokens.channel` (`STAFF`|`STOREFRONT`, mặc định `STAFF`, migration V18). Mọi refresh token cũ là STAFF.

## Token kênh STOREFRONT

- `aud = ["lms-storefront"]`.
- KHÔNG có `admin`, KHÔNG có `platformAdmin`, kể cả khi tài khoản có role admin hoặc là platform admin (nhân viên đăng nhập ở storefront nhận token hạn chế). Vẫn có `tenantId`, `modules`, `roles`. Phiên storefront bắt buộc có tenant (platform admin không tenant -> 401).
- `permissions` chỉ gồm khóa thuộc ALLOWLIST cấu hình `lms.identity.token.storefront-permissions` (danh sách phân tách bằng dấu phẩy; mặc định `ShopController/*,TitleController/lookup`): mục kết thúc `/*` khớp mọi khóa cùng controller, mục khác khớp chính xác. Mọi khóa khác của role (nhân viên, `StorefrontController/*`, quản trị role...) bị loại khỏi claim của token STOREFRONT, cả khi đăng nhập lẫn làm mới. Token kênh STAFF không bị lọc. Admin tenant muốn đặt hàng phải có role mang khóa `ShopController/*`. Cấu hình rỗng (`=`) nghĩa là không khóa nào.
- Ký và JWKS không đổi.

## Token kênh STAFF (lms-api)

- Giai đoạn chuyển tiếp: KHÔNG có `aud` (mọi token cũ vẫn dùng được).
- Cờ identity `lms.identity.token.staff-audience=true`: ghi `aud = ["lms-staff"]`. Mặc định tắt.

## lms-api

`ChannelAudienceFilter`: token có `aud` chứa `lms-storefront` -> `401` (như token không hợp lệ). Cờ `lms.api.security.require-staff-audience` (mặc định `false`): khi `true` chỉ nhận token có `aud` chứa `lms-staff` (bật cùng lúc với cờ identity khi hết giai đoạn chuyển tiếp). Refresh token storefront gửi tới `POST /api/auth/refresh` -> identity trả 401 (lms-api không gửi `channel`).

## Việc của customer-api (agent khác)

1. Login: gửi `channel: "STOREFRONT"` + `tenantCode` từ đường dẫn. Refresh: `POST /internal/auth/refresh` với `channel: "STOREFRONT"` (logout không cần).
2. Bắt buộc `aud` chứa `lms-storefront` ở route cần đăng nhập. Cách rẻ nhất: `spring.security.oauth2.resourceserver.jwt.audiences: lms-storefront` (Spring Boot tự kiểm `aud`); route công khai không bị ảnh hưởng vì không có token. Token không `aud` (token `lms-api`) và token `svc` -> 401. Giữ `ShopSessionGuard`.
3. Không bao giờ chuyển `channel` từ client; BFF tự đặt.

## Việc của FE

Token storefront không dùng được ở ứng dụng nhân viên (`/api/**` trả 401) và ngược lại. Lưu riêng, không dùng chung `localStorage` với app nhân viên.
