# ADR-0027: Xác thực API SaaS: Application, credential, scope (quyết định D1 đến D8)

- Trạng thái: Chấp nhận. Triển khai pha P1 (bảng và API quản lý); token endpoint (I2), site key ở customer-api (P2) và integration-api chưa làm.
- Nguồn: `docs/architecture/SAAS_API_AUTHENTICATION_DESIGN.md` (mục 2), `SAAS_AUTHENTICATION_AUDIT.md`, `TENANT_ISOLATION.md`.
- Liên quan: ADR-0003 (khóa Controller/method), 0006 (multi-tenant), 0011 (customer-api, token `svc`), 0012 (hợp đồng), 0013 (origin storefront).

## Quyết định

- **D1. Token client_credentials do identity phát** (endpoint RFC 6749 §4.4, ký bằng `JwtTokenIssuer` và khóa RS256/JWKS hiện có), không dùng Spring Authorization Server và không dùng IdP ngoài. Một issuer, một JWKS.
- **D2. Integration API ở module BFF mới `integration-api`** (khuôn customer-api, không DB, cổng đề xuất 8089), không gộp vào lms-api hay customer-api.
- **D3. Kiểm scope ở service bằng `@IntegrationScope("...")` và nhánh token integration trong `PermissionInterceptor`**, đặt sau bước 401 và trước các nhánh khác. Scope là hợp đồng công khai có phiên bản, không phải khóa quyền nội bộ: đây là ngoại lệ có chủ đích với "không có hằng số permission" của ADR-0003. (Pha P0 đã làm phần security-common.)
- **D4. Site key và `{code}` bổ sung cho nhau**: `/api/public/v1/**` nhận diện bằng `X-Site-Key`, đường `/public/t/{code}/**` giữ nguyên. Site key là định danh công khai, không phải bằng chứng xác thực; domain allowlist chỉ là CORS. (Pha P2.)
- **D5. Bảng mới là bảng toàn cục, không RLS**, thêm vào `TENANT_EXEMPT_TABLES`: `applications`, `application_credentials`, `application_scopes`, `application_history`. Lý do: token endpoint và cấp token theo site key tra `client_id`/`site_key` khi chưa biết tenant. Bù rủi ro IDOR: API tenant không nhận `tenantId` (lấy từ `TenantContext`), mọi truy vấn theo `(tenant_id, id)`, id của tenant khác trả `404` cùng thân, test hai tenant bắt buộc.
- **D6. Rate limit**: nginx cho khóa có sẵn trong request (IP, mã, site key) và Redis fixed-window theo application ở `integration-api` (fail-open). (Pha I3.)
- **D7. Băm client secret bằng SHA-256** của secret 32 byte CSPRNG, định dạng `lms_sk_` + base64url; lưu `secret_hash` và `secret_hint` (4 ký tự cuối); so sánh `MessageDigest.isEqual`; hiển thị đầy đủ một lần kèm `Cache-Control: no-store`. Site key `lms_pk_` + base64url(24 byte), lưu nguyên văn, UNIQUE.
- **D8. Đánh giá các bảng đề xuất**: không tạo `tenants` (dùng có sẵn), `application_domains` (thêm cột `application_id` vào `tenant_storefront_origins`), `api_sessions` (dùng `refresh_tokens` kênh STOREFRONT), `api_audit_logs` theo request (thay bằng `application_history`, log truy cập có cấu trúc, bộ đếm Redis). Tạo `applications`, `application_credentials`, `application_scopes`, `application_history`.

## Quyết định người dùng chốt cho pha P1

- Q3: tenant admin tự phục vụ qua khóa động `ApplicationController/*`; platform admin tạm ngưng/kích hoạt/thu hồi qua `PlatformApplicationController` (`@PlatformAdminOnly`); `INTEGRATION` chỉ tạo được khi tenant có hợp đồng ACTIVE (ADR-0012).
- Q5: chưa cấp `customers:*` (có trong danh mục, API cấp scope từ chối `SCOPE_NOT_GRANTABLE`).
- Q6: pha đầu chỉ cấp scope đọc (`books:read`, `inventory:read`, `orders:read`); scope ghi bị từ chối.
- Q13: hạn dùng credential mặc định 365 ngày, cấu hình được.

## Triển khai P1 (ghi chú lệch nhỏ so với thiết kế)

- Migration `identity/V32__applications.sql` (+ REVOKE UPDATE/DELETE `application_history` trong `afterMigrate.sql`). Thêm `DOMAIN_ADDED`, `DOMAIN_REMOVED` vào CHECK `action` của lịch sử.
- Context `apiclient` trong identity; domain allowlist đi qua `ManageApplicationOriginsUseCase` của context tenant (tái dùng luật ADR-0013, không lộ tenant chủ của origin trùng).
- Hợp đồng ACTIVE kiểm bằng `GetMyContractsUseCase` (ACTIVE và IN_EFFECT/IN_GRACE) qua cổng `TenantContractGate`.
- Giới hạn 20 Application mỗi tenant khóa các dòng sẵn có của tenant; tenant chưa có dòng nào thì giới hạn là mềm.
- Platform admin không tạo Application và không cấp credential hộ tenant.
