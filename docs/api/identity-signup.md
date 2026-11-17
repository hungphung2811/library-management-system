# Tự đăng ký (identity `POST /internal/auth/signup`)

Căn cứ: ADR-0010 B2.1, ADR-0011 (customer-api gọi identity trực tiếp), customer-api-data-authorization A5/A11. Đây là API NỘI BỘ (`/internal/**`, nginx không công khai); chỉ `customer-api` gọi. `lms-api` KHÔNG có `/api/auth/signup`: ADR-0011 chốt kênh đăng ký là `POST /public/t/{code}/auth/signup` của customer-api (ADR-0010 2.3 có nhắc `/api/auth/signup` nhưng bị ADR-0011 thay thế). nginx đã có `limit_req zone=public_form` (5r/m, burst 3) cho `POST /public/t/*/auth/signup`, không đổi.

## Hợp đồng

`POST /internal/auth/signup` (`@PublicEndpoint`; `SecurityConfiguration` của identity `permitAll` đúng đường dẫn này, method POST).

Body (allowlist, trường lạ bị BỎ QUA, không bao giờ tới use case):

| Trường | Quy tắc |
|---|---|
| `tenantCode` | bắt buộc, `<=50`. Mã công khai của cửa hàng; customer-api lấy từ ĐƯỜNG DẪN `/public/t/{code}`, KHÔNG bao giờ từ body của khách |
| `username` | bắt buộc, 3 đến 100; domain: chữ thường, số, `.`, `_`, `-` (chữ hoa được chuẩn hóa) |
| `password` | bắt buộc, 8 đến 72 |
| `fullName` | bắt buộc, `<=255` |
| `email` | BẮT BUỘC (khác ghi chú "email?" ban đầu: hồ sơ `users.email` NOT NULL và duy nhất), hợp lệ, `<=255` |

Không có role, tenantId, flag, phòng ban, trạng thái, `mustChangePassword`, `phone`. `phone` chưa lưu ở identity (chưa có chỗ; customer-api không chuyển).

Phản hồi:

| HTTP | Khi |
|---|---|
| `201` | Không thân. Tài khoản + membership + đúng một role (role `signup_default`) đã tạo trong một transaction identity_db. Chưa đăng nhập: khách gọi login riêng |
| `403 SIGNUP_NOT_AVAILABLE` | MỘT mã chung cho: tenant không tồn tại, không ACTIVE (ngưng), chưa bật SALES, chưa có role `signup_default`. Thông điệp cố định, không chứa mã tenant (chống dò tenant) |
| `409 DUPLICATE_USERNAME` | Trùng tên đăng nhập HOẶC email ở bất kỳ tenant nào; thông điệp chung "Tên đăng nhập hoặc tài khoản đã tồn tại", không nêu trường nào trùng, tenant nào giữ. Tên đăng nhập vẫn toàn hệ thống nên việc "đã tồn tại" vẫn suy ra được (rủi ro A11 đã chấp nhận), giảm bằng `limit_req` ở nginx; đóng hẳn cần xác minh email (ngoài phạm vi) |
| `400 VALIDATION_FAILED` | Sai định dạng (bean validation hoặc quy tắc hồ sơ như ký tự tên đăng nhập) |

## Quy tắc

1. Tenant qua `StorefrontTenantPort` (cùng nguồn với token storefront: tồn tại + ACTIVE + SALES bật).
2. Role = role của tenant có cờ `roles.signup_default`. Luật: role `is_admin` không bao giờ mang cờ (domain `Role`, CHECK `ck_roles_signup_not_admin`, use case kiểm lại khi tìm); tối đa một role mỗi tenant (chỉ mục duy nhất `uq_roles_signup_default_per_tenant`). Không hardcode tên role.
3. Tạo user + credential (không `mustChangePassword`) + membership rồi gán role trong MỘT transaction bắt đầu SAU `TenantContext.callAs(tenant)` (RLS). Gán role gọi `UserRoleUseCase` với `callerIsAdmin=false` nên role admin bị từ chối lớp thứ hai.
4. Chống lạm dụng: nginx `public_form`. Tenant admin gỡ cờ `signup_default` = tắt tự đăng ký.

## Cờ `signup_default` (migration V18)

- `roles.signup_default`, `role_templates.signup_default` (BOOLEAN, mặc định FALSE). V18 không tự mở đăng ký cho tenant nào; V20 (bên dưới) seed mẫu Khách hàng có cờ.
- `seedFromTemplates`/`seedModuleTemplates` chép cờ từ mẫu (chỉ mẫu đầu tiên nếu tenant chưa có role đăng ký, và không bao giờ cho mẫu admin).
- Đặt/tắt cờ qua `PUT /api/roles/{roleId}` (`AccessControlController/updateRole`, khóa KHÔNG đổi): thêm trường tùy chọn `signupDefault` (null = giữ). Bật cờ chỉ quản trị viên (`admin=true`), role không phải admin, tenant chưa có role đăng ký khác (`409 SIGNUP_ROLE_EXISTS`); tắt cờ không cần admin. `RoleResponse` thêm `signupDefault`. lms-api chuyển tiếp `signupDefault` ở `PUT /api/roles/{id}` (`UpdateRoleRequest.signupDefault`, tùy chọn) và `RoleView` trả `signupDefault`.
- Mẫu role Khách hàng (V20, ADR-0010 B2.3): `CUSTOMER`, module SALES, `signup_default = TRUE`, `is_admin = FALSE`, đúng 5 khóa: `ShopController/placeOrder`, `ShopController/myOrders`, `ShopController/myOrder`, `ShopController/cancelMyOrder`, `TitleController/lookup` (sales gọi catalog bằng token khách khi đặt hàng). KHÔNG có `StorefrontController/*` (đọc công khai dùng token dịch vụ) và không khóa nhân viên nào.
- Tenant mới bật SALES (tạo tenant hoặc bật module) nhận `CUSTOMER` mang cờ: tự đăng ký mở ngay (tenant admin gỡ cờ để tắt).
- Backfill tenant ĐANG bật SALES khi chạy V20: tạo `CUSTOMER`; cờ chỉ bật nếu tenant chưa có role `signup_default` nào (chỉ mục duy nhất từng phần). Trước V20 chưa tenant nào có cờ, nên thực tế MỌI tenant đang bật SALES sẽ mở tự đăng ký sau V20 (⚠️ thay đổi hành vi, tắt bằng `PUT /api/roles/{id}` `{signupDefault:false}`). Tenant đã có role signup_default khác nhận `CUSTOMER` không cờ; tenant đã có role tên `CUSTOMER` giữ nguyên (không ghi đè). Mỗi trường hợp bỏ qua ghi `WARNING` trong log migration.

## Chống leo thang quyền qua role đăng ký (A19/A5 mở rộng)

Role `signup_default` được cấp cho mọi người tự đăng ký công khai nên khóa của nó là bề mặt tấn công:
- `POST /api/roles/{roleId}/permissions` (khóa `AccessControlController/grantPermission`, không đổi): nếu role đang `signup_default`, CHỈ quản trị viên (`admin=true`) được thêm khóa; người chỉ có `ROLE_GRANT`/khóa gán quyền (không admin) nhận `403 FORBIDDEN`. Role thường không đổi hành vi. Thu hồi khóa (`DELETE .../permissions/**`) và tắt cờ vẫn không cần admin. Bật cờ (`PUT`) vẫn chỉ admin.
- Token kênh STOREFRONT chỉ mang `permissions` thuộc allowlist (xem `token-audience.md`), nên dù role khách bị gán thêm khóa nhân viên (bởi admin) thì khóa đó không nằm trong token storefront.
- Còn lại (⚠️): admin thêm khóa nhân viên vào role `signup_default` rồi khách tự đăng ký đăng nhập qua kênh STAFF (`/api/auth/login` của lms-api) vẫn nhận khóa đó trong token nhân viên; chỉ chặn được bằng quy trình, không bằng code.

## Việc của customer-api

- Gọi `POST /internal/auth/signup` với body do BFF dựng từ record allowlist `{username, password, fullName, email}` + `tenantCode` từ đường dẫn. Không chuyển trường lạ.
- Ánh xạ: `201` -> `201`; `403 SIGNUP_NOT_AVAILABLE`, `409 DUPLICATE_USERNAME`, `400 VALIDATION_FAILED` đã nằm trong allowlist lỗi; thông điệp do customer-api đặt, không chuyển nguyên.
- Sau signup, login qua kênh STOREFRONT (xem `token-audience.md`).
