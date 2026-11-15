# ADR-0006: Multi-tenant (dùng chung DB, `@TenantId` + RLS fail-closed)

- Trạng thái: chấp nhận (triển khai theo plan `docs/superpowers/plans/2026-11-15-multi-tenant.md`)
- Spec: `docs/superpowers/specs/2026-11-14-multi-tenant-design.md`

## Bối cảnh

Hệ thống phục vụ nhiều thư viện (tenant) trên cùng hạ tầng. Cần cô lập dữ liệu chắc chắn, chi phí vận hành thấp (không DB riêng cho mỗi tenant) và có quản trị nền tảng (platform admin).

## Quyết định

1. **Dùng chung DB, cột `tenant_id`.** Mỗi bảng nghiệp vụ có `tenant_id`; entity JPA gắn `@TenantId` (Hibernate tự lọc và điền theo `TenantContext`).
2. **Row-Level Security fail-closed ở PostgreSQL.** Mỗi bảng tenant bật `ENABLE` + `FORCE ROW LEVEL SECURITY`; policy so `tenant_id` với biến phiên `app.tenant_id`. Không có tenant trong ngữ cảnh thì không thấy dòng nào và không ghi được. `TenantRlsConnectionInitializer` đặt biến này mỗi lần mượn connection.
3. **Vai trò DB `<svc>_app`** (`identity_app`, `catalog_app`, `circulation_app`, `audit_app`): NOSUPERUSER, NOBYPASSRLS, không sở hữu bảng nên chịu RLS. Service chạy bằng vai trò này; migration-service chạy bằng chủ bảng. Vai trò được tạo trong `infrastructure/postgres/init-databases.sql` (R7), quyền được cấp bởi Flyway callback `afterMigrate.sql` và thu quyền ghi `flyway_schema_history` (R13).
4. **Token theo tenant.** JWT mang claim `tenantId` (và `platformAdmin`); `permissions`/`admin` tính theo role của tenant đó. `PermissionInterceptor` trả 403 `TENANT_REQUIRED` khi thiếu claim (R1). Đổi tenant bằng `POST /api/auth/switch-tenant {tenantId, refreshToken}`.
5. **Tenant mặc định** `00000000-0000-0000-0000-0000000000d1`: mọi dữ liệu và user có sẵn được gán vào đây khi migrate; chạy local dùng tenant này.
6. **Platform admin** (`credentials.platform_admin`): token không có `tenantId`, quản trị tenant qua `/api/platform/**`; tạo tenant mới chép role từ `role_templates` trong `TenantContext.runAs(tenantMới)` (R6).
7. **Kiểm tra kiến trúc**: `TenantArchitectureRules` (tenant-common) dùng trong `TenantArchitectureRulesTest` của identity, catalog, circulation, support: entity có `tenant_id` phải có `@TenantId` (ngoại lệ tường minh `credentials, users, external_logins, permissions, tenants, organizations, tenant_memberships, refresh_tokens, known_tenants`); không native query ngoài danh sách đã rà (`SpringDataBookCopyRepository`, `PermissionJpaRepository`, `SpringDataAuditLogRepository`).
8. **Dữ liệu ngoài Postgres**: Mongo `raw_events` có `tenantId`, `_id = {tenantId}:{eventId}` (script `infrastructure/mongo/backfill-tenant-id.js`); Redis dùng tiền tố `tenant:{id}:` (`infrastructure/redis/README.md`).

## Các ruling quan trọng

- **R2**: `tenant_memberships` và `refresh_tokens` có cột `tenant_id` nhưng KHÔNG RLS, KHÔNG `@TenantId`: đăng nhập/refresh phải đọc trước khi biết tenant; lọc bằng `user_id`/hash token ở tầng ứng dụng. Chỉ hai bảng này dựa vào lọc ở ứng dụng.
- **R3**: mọi đọc có tenant khi cấp token (roles, user_roles, department) bọc trong `TenantContext.runAs(tenantĐượcChọn, ...)`.
- **R5**: `users.department_id` được chép sang `tenant_memberships.department_id`, cột cũ giữ tới khi V14 bỏ.
- **R7**: `CREATE ROLE` là mức cluster nên nằm ở init SQL, migration chỉ GRANT; volume Postgres cũ cần `docker compose down -v` hoặc chạy tay SQL.
- **R13**: grant theo target bằng callback `afterMigrate.sql` (chạy lại được khi vai trò tạo sau) thay cho `R__`; sau khi bỏ `R__` cần `mvn clean` (target/classes cũ).
- **R20**: cấp token chạy ngoài transaction bao ngoài (Login/Refresh/SwitchTenantSession/ExchangeExternalTicket không còn `@Transactional`); `SaveRefreshTokenPort.rotate()` atomic; `GetUserAccessUseCase` giữ `REQUIRES_NEW` làm lưới an toàn. Tránh giữ 2 connection mỗi lần cấp token (cạn pool Hikari).
- **R19**: replay refresh token cũ sau switch-tenant kích hoạt reuse-detection và thu hồi toàn bộ phiên (chấp nhận, an toàn hơn).

## Hệ quả và đánh đổi đã biết

- **FORCE RLS với migration sau**: FORCE áp cả cho chủ bảng. Migration mới ghi vào bảng có RLS (identity: `roles`, `user_roles`, `departments`, `reader_profiles`, `role_permissions`; catalog/circulation/audit: các bảng tenant) phải đặt `app.tenant_id` (`set_config`) trước khi ghi, hoặc chạy bằng superuser; nếu không sẽ ghi 0 dòng hoặc lỗi policy.
- **Hibernate `@TenantId` chưa kiểm chứng trên thực tế**: `merge` với `tenantId` null (cập nhật role, `book-copies/{id}/status`), `@TenantId` trên entity `@IdClass` (`UserRoleJpaEntity`) và `findById` lọc tenant cần chạy IT. Hai entity cùng map `tenant_memberships` (ghi + `@Immutable` đọc): đọc-sau-ghi trong cùng transaction cần IT.
- **Profile dev tắt security** (`app.security.enabled=false`) thì không có JWT nên không có `TenantContext`; ghi vào bảng tenant ở dev fail-closed. Dev cần Bearer token (hoặc bật security) để có tenant.
- **`borrow_rules` chưa nối `BorrowBookUseCase`**: bảng quy tắc mượn theo tenant tồn tại nhưng `BorrowingRecord` vẫn dùng hằng `LOAN_DAYS`/`MAX_ITEMS`; cần task riêng (cần `reader_type` từ identity).
- **Mongo dữ liệu cũ**: bản ghi `raw_events` trước khi đổi có `_id = eventId`, không `tenantId` nên vô hình với truy vấn mới và có thể nhân bản khi redeliver; chạy `backfill-tenant-id.js` một lần.
- **switch-tenant bắt buộc có `refreshToken`** (để thu hồi phiên cũ); thiếu thì từ chối.
- **Không được tắt `lms.tenant.rls.enabled` ở prod**: `ReaderProfileJpaEntity` có `@Id = user_id` trong khi PK ở DB là `(tenant_id, user_id)`; entity chỉ đúng nhờ RLS lọc theo tenant (cùng một user có hồ sơ độc giả ở hai tenant). Tắt RLS thì `findById` có thể trả nhầm dòng của tenant khác.
- **`tenant_id` điền sẵn khi dựng entity**: các adapter dựng `JpaEntity` mới rồi `save` (merge) lấy `tenantId = TenantContext.require()` trong constructor; không có tenant thì ném lỗi (fail-closed). Vẫn cần IT để kiểm chứng (mục dưới).
- **Đăng nhập theo tenant đi qua `lms-api`**: nginx đưa mọi `/api/**` (kể cả `/api/auth/*`) vào `lms-api`, `lms-api` chuyển tới identity `/internal/auth/**`. Login nhận `tenantCode`, `POST /api/auth/switch-tenant` nhận `{tenantId, refreshToken}`, token trả về có `tenantId` và `availableTenants`. Luồng này chưa được kiểm chứng đầu-cuối (test chưa chạy).
- **circulation gốc đặt `app.security.enabled=false`**: không có JWT nên không có `TenantContext`; ghi bảng tenant fail-closed cho tới khi bật security (có từ trước nhánh này).
- **Audit thao tác tổ chức (organization) không vào kho audit**: tạo/đổi tên tổ chức không thuộc tenant nào, mà audit của support chỉ ghi sự kiện có `tenantId`, nên `ManageOrganizationUseCase` chỉ ghi log INFO có cấu trúc (`platform-audit operation=... actorId=... organizationId=...`), không phát Kafka. Chưa có kho audit cấp nền tảng; cần thì thêm sau (không làm trong đợt này).
- **Audit đăng nhập thất bại không biết tenant**: `LoginFailed`/`AccountLocked` phát dưới `credentials.last_tenant_id`; tài khoản chưa có tenant gần nhất thì chỉ log WARN, không có trong audit của tenant nào.
- Khác: `audit_logs.event_id` còn UNIQUE toàn cục (event trùng id giữa tenant có thể bị bỏ qua ở Postgres, Mongo vẫn giữ); `known_tenants` của circulation chỉ seed tenant mặc định; refresh đồng thời cùng token có thể cùng thành công (có từ trước).

## Kiểm chứng còn nợ

Theo R18/R21 toàn bộ bước chạy test bị hoãn; code mới chỉ được compile. Cần chạy bù trước khi coi là xong:

```bash
mvn -B test '-Dtest=*Test,*IT' -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44
```

Kỳ vọng BUILD SUCCESS cả 13 module (đọc `Tests run` thật). Ưu tiên: IT identity (`IdentityServiceIT`, `AuthPersistenceAdapterIT`, `AccessControlPersistenceAdapterIT`, `TenantMigrationIT`), `CatalogApiIT`, `CatalogTenantIsolationIT`, `BorrowingPersistenceAdapterIT`, `AuditTenantIsolationIT`, `SupportServiceIT`, `TenantRlsIT`, `MigrationApiIT`; và các giả định Hibernate nêu trên.
