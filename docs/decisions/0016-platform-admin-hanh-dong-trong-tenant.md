# ADR-0016: Platform admin đọc và ghi trong tenant bất kỳ qua header `X-Tenant-Id`

- Trạng thái: chấp nhận (cài đặt ở security-common và lms-api)
- Nền: ADR-0003 (phân quyền động), ADR-0006 (multi-tenant, `@TenantId` + RLS fail-closed), ADR-0008 (đọc xuyên tenant, chỉ đọc), ADR-0009 (module theo tenant)
- Thay thế một phần ADR-0008: quyết định "chỉ đọc, mọi thao tác ghi vẫn theo tenant trong token" không còn đúng với platform admin.

## Bối cảnh

Yêu cầu đã chốt với người dùng: tài khoản platform admin (token `platformAdmin=true`, không có `tenantId`) có quyền cao nhất, xem được mọi dữ liệu của mọi tenant và ghi được vào mọi tenant.

Hiện trạng trước ADR này (đã thử bằng tài khoản `platform.admin`):

- Đọc xuyên tenant chỉ có ở 10 endpoint GET gắn `@CrossTenantReadable` (users, departments, roles, books, book-copies, borrowings, fines, audit-logs...). Sales, `admin/menus`, `contracts`, `storefront-origins` trả 403 vì thiếu tenant hoặc thiếu module.
- Mọi thao tác ghi trả 403 `TENANT_REQUIRED`, vì token không có `tenantId` và `TenantContext.require()` ném lỗi ở phạm vi ALL.
- Token platform admin có `permissions=[]`, không `admin`, không `modules`.

## Quyết định

1. **Header `X-Tenant-Id` (UUID)** là cách duy nhất để platform admin chọn tenant đích cho thao tác ghi, và cũng dùng được cho đọc một tenant ở endpoint bất kỳ (kể cả không gắn `@CrossTenantReadable`). Query `tenantId` của ADR-0008 vẫn giữ nguyên cho các GET danh sách; khi cả hai cùng có thì query thắng.
2. **Chỗ chặn duy nhất vẫn là `PermissionInterceptor`.** Điều kiện: token `platformAdmin=true` **và không có** claim `tenantId` **và** request có header `X-Tenant-Id`. Khi đó interceptor:
   - gọi `TenantScopeBinder.bindSingle(tenantId)` (đặt `TenantContext`), nên `@TenantId` và RLS chạy đúng như với người dùng của tenant đó;
   - bỏ qua kiểm khóa quyền (coi như admin của tenant đích) và kiểm module `@RequiresModule` (platform admin không bị hợp đồng thuê giới hạn);
   - vẫn đăng ký khóa `Controller/method` vào `permissions` như thường;
   - ghi log INFO có cấu trúc `platform-audit operation=act-as-tenant actor=<sub> tenantId=<uuid> method=<GET|POST|...> path=<mẫu đường dẫn>`.
3. **Không có header thì giữ hành vi cũ:** endpoint `@CrossTenantReadable` GET vẫn đọc mọi tenant; mọi endpoint khác trả 403 `TENANT_REQUIRED`. Không có chế độ "ghi vào tất cả tenant" cùng lúc: mỗi lần ghi nhắm đúng một tenant.
4. **`X-Tenant-Id` sai định dạng UUID** từ platform admin: 400 `INVALID_TENANT_ID`. Từ người gọi khác (có `tenantId` trong token, hoặc không phải platform admin): header bị bỏ qua hoàn toàn, luồng phân quyền như cũ (admin tenant A gửi header tenant B vẫn bị khóa trong A).
5. **Endpoint `@PlatformAdminOnly`** không đổi (vẫn kiểm `platformAdmin`, không bind tenant).
6. **`lms-api` chuyển nguyên header** `X-Tenant-Id` của người gọi xuống mọi lời gọi `/internal/**` (trong `TokenRelayInterceptor`), không diễn giải, giống cách ADR-0008 xử lý query `tenantId`.
7. **Không đổi RLS, không migration.** Thao tác ghi dùng đúng chính sách `tenant_isolation` hiện có với `app.tenant_id` = tenant đích, nên `WITH CHECK` vẫn bảo đảm dòng ghi thuộc tenant đích.

### Phương án đã loại

| Phương án | Lý do loại |
|---|---|
| Thêm policy RLS `FOR ALL` theo `app.tenant_scope='all'` | Cho phép ghi dòng với `tenant_id` tùy ý, mất `WITH CHECK`; phải có migration 15 bảng, rủi ro rò rỉ biến phiên cao hơn nhiều |
| Vai trò DB BYPASSRLS cho platform admin | Vượt mọi lớp cách ly, tăng secret và pool, khó audit |
| Platform admin tự `switch-tenant` cho từng tenant | Cần là thành viên tenant, và phát token mới cho mỗi lần đổi; không "xem tất cả" được |
| Ghi hàng loạt vào mọi tenant một request | Phạm vi sự cố quá lớn, khó rollback, không có nhu cầu đã chốt |

## Hệ quả và rủi ro

- Platform admin là điểm rủi ro lớn nhất của hệ thống: lộ token nghĩa là ghi được vào mọi tenant. Giảm thiểu: bật audit `platform-audit` mọi lần, tài khoản không có sẵn ở production (chỉ tạo qua `AUTH_BOOTSTRAP_PLATFORM_ADMIN_USERNAME/PASSWORD`), bắt buộc đổi mật khẩu mặc định, cân nhắc 2FA và hạn chế mạng.
- Không có kiểm trạng thái tenant: platform admin vẫn ghi được vào tenant `SUSPENDED` hoặc hết hợp đồng (chính người này là người tạm ngưng). Nếu cần chặn, thêm kiểm ở interceptor qua danh bạ tenant (identity) ở ADR sau.
- Nghiệp vụ so sánh trực tiếp claim `admin` của JWT (ví dụ "chỉ admin được tạo/gán role `isAdmin`") sẽ không coi platform admin là admin, vì token không mang claim đó. Cần rà từng nghiệp vụ nếu muốn platform admin làm được; hiện chưa làm.
- Một số endpoint dùng `@AuthenticationPrincipal Jwt` để lấy `tenantId` từ claim thay vì `TenantContext` sẽ không thấy tenant đích. Cần rà khi phát hiện.
- Log `platform-audit` chỉ ghi ra log ứng dụng, chưa vào bảng audit (support-service). Thêm sự kiện Kafka là việc sau.

## Kiểm chứng

- Unit: `ActAsTenantInterceptorTest` (8 ca): đọc/ghi POST/DELETE với header, bỏ qua module, không header thì 403, header sai định dạng thì 400, admin tenant và người dùng thường bị bỏ qua header, `@PlatformAdminOnly` không bind.
- Chuyển tiếp liên service: interceptor đặt thuộc tính request `ActAsTenant` (chỉ khi đã xác nhận platform admin và header hợp lệ); client gọi service khác dùng `ActAsTenant.current()` để gắn lại `X-Tenant-Id`. Đã áp dụng cho các client gọi catalog (circulation: 2 adapter, sales: 1 adapter). Client gọi identity (hồ sơ độc giả theo token) chưa chuyển tiếp vì platform admin không phải độc giả.
- Đã thử end-to-end ngày 2026-11-17 (tài khoản `platform.admin`, tenant mặc định, qua lms-api): GET `/api/sales/stock-items`, `/api/admin/menus`, `/api/contracts`, `/api/books`, `/api/borrowings/search` trả 200 khi có header; POST `/api/sales/suppliers` trả 201 khi có header và 403 `TENANT_REQUIRED` khi không có; header sai định dạng trả 400 `INVALID_TENANT_ID`; tenant không tồn tại trả danh sách rỗng; admin của tenant gửi header tenant khác bị bỏ qua. Lưu ý dev: system-service và identity ở `dev-start.ps1` chạy với kiểm quyền tắt nên phải bật cờ mới thấy hành vi này.
- Còn nợ: IT với Postgres thật (platform admin ghi vào tenant A, không thấy và không ghi được vào B khi gắn A; admin tenant thường vẫn bị khóa), test `lms-api` chuyển header.
