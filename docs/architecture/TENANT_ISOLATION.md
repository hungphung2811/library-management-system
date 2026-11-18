# Đánh giá cô lập tenant (hiện trạng và khoảng trống cho Application/credential mới)

- Ngày: 2026-11-18. Người viết: SA. Thuộc Phase 1 của yêu cầu "SaaS API Authentication"; đi cùng `SAAS_AUTHENTICATION_AUDIT.md` và `SAAS_API_AUTHENTICATION_DESIGN.md`.
- Nguồn: code thật trên `main` gồm thay đổi chưa commit. Không chạy test trong phiên này: mọi câu "test chứng minh" chỉ nêu **tên lớp test có thật**, không khẳng định đang xanh (ADR-0006 còn mục "Kiểm chứng còn nợ").
- Icon: ✅ đã kiểm chứng bằng code, ⚠️ chưa kiểm chứng hoặc suy luận, ❌ khoảng trống, ℹ️ ghi chú.

## 1. Mô hình cô lập: ba lớp

```
Lớp 1 (ứng dụng)   : TenantContext (thread-local) ← TenantContextFilter ← claim tenantId của JWT đã xác thực
Lớp 2 (ORM)        : Hibernate @TenantId + TenantIdentifierResolver (không có tenant → UUID nil, không khớp dòng nào)
Lớp 3 (CSDL)       : PostgreSQL RLS ENABLE + FORCE, policy theo current_setting('app.tenant_id'), vai trò <svc>_app NOBYPASSRLS
Bảng toàn cục      : không RLS, không @TenantId, liệt kê trong TENANT_EXEMPT_TABLES, use case lọc tenant tường minh
```

| | Nhận định | Bằng chứng |
|---|---|---|
| ✅ | `TenantContext` giữ `CURRENT` (một tenant) và `ALL` (đọc mọi tenant, chỉ platform admin); `require()` ném lỗi khi không có tenant hoặc đang ALL (chặn ghi) | `shared-libraries/tenant-common/src/main/java/lms/tenant/TenantContext.java` (`require`, `bindAllTenants`, `readFilter`) |
| ✅ | `TenantContextFilter` chỉ đọc claim `tenantId` từ `JwtAuthenticationToken`, không đọc header/body/query; `finally { TenantContext.clear(); }` | `TenantContextFilter#doFilterInternal`, `#tenantFromAuthentication` |
| ✅ | Filter đăng ký ngay sau chuỗi Spring Security (`SecurityProperties.DEFAULT_FILTER_ORDER + 1`) | `TenantAutoConfiguration.WebConfiguration#tenantContextFilterRegistration` |
| ✅ | Ngoại lệ có chủ đích: platform admin đổi tenant theo `X-Tenant-Id` hoặc query `tenantId` (chỉ khi token có `platformAdmin` và **không** có `tenantId`); người gọi khác bị bỏ qua header | `security-common/.../PermissionInterceptor#tryActAsTenant`, `#handleCrossTenantRead`, `TenantAutoConfiguration#tenantScopeBinder` |
| ✅ | Hibernate: không có tenant → `NIL_TENANT`; ALL → `ROOT` và `isRoot` bỏ điều kiện | `TenantIdentifierResolver` |

## 2. RLS và kết nối

| | Nhận định | Bằng chứng |
|---|---|---|
| ✅ | Vai trò chạy app `<svc>_app` tạo với `NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE`, không sở hữu bảng | `infrastructure/postgres/init-databases.sql` (dòng `CREATE USER identity_app ...`, `sales_app`, ...) |
| ✅ | `FORCE ROW LEVEL SECURITY` nên chủ bảng cũng chịu RLS (migration ghi dữ liệu tenant phải `set_config`) | `identity/V13__tenant_isolation.sql`, CLAUDE.md mục Multi-tenant |
| ✅ | Biến phiên đặt bằng `SELECT set_config('app.tenant_id', ?, false), set_config('app.tenant_scope', ?, false)` **cấp phiên (is_local = false)**, không phải cấp transaction như mô tả trong yêu cầu; đặt lúc mượn kết nối và commit ngay | `TenantRlsConnectionInitializer.SET_SQL`, `#apply`, `#set` |
| ✅ | Khi trả kết nối về pool: rollback việc dở rồi đặt lại rỗng, nên giá trị không rò sang lần mượn kế tiếp | `TenantRlsConnectionInitializer#reset`, `ResettingHandler#invoke` (nhánh `close`) |
| ✅ | Thiếu biến → `NULLIF('', '')::uuid` = NULL → không khớp dòng nào (fail-closed) | javadoc quy ước policy trong `TenantRlsConnectionInitializer` |
| ⚠️ | Giới hạn đã ghi trong code: `runAs` lồng bên trong một transaction đang giữ kết nối **không** cập nhật biến phiên (kết nối đã mượn trước đó giữ tenant cũ) | javadoc `TenantRlsConnectionInitializer` ("Giới hạn: đổi tenant giữa chừng một transaction...") |
| ✅ | Đọc xuyên tenant cho platform admin: policy PERMISSIVE `platform_read_all` FOR SELECT khi `app.tenant_scope = 'all'`; ghi vẫn qua `tenant_isolation`; `role_permissions` có thêm policy RESTRICTIVE chặn ghi ở scope `all` | `identity/V15__platform_read_all.sql`, `catalog/V3__platform_read_all.sql`, `circulation/V7__platform_read_all.sql`, `audit/V4__platform_read_all.sql` |
| ✅ | `TokenService` đọc role/quyền trong `TenantContext.callAs(tenantĐãChọn, ...)` và có bất biến R20 "không gọi trong transaction đang mở" (tránh lệch tenant của kết nối và cạn pool) | `services/identity-service/.../auth/application/TokenService.java` (javadoc, `#issue`) |

Test có thật trong repo liên quan đến lớp này (chưa chạy trong phiên này):

| | Lớp test | Nội dung theo tên method |
|---|---|---|
| ⚠️ | `shared-libraries/tenant-common/src/test/java/lms/tenant/rls/TenantRlsIT.java` | `runAs` A chỉ thấy dòng của A; Hibernate tự điền `tenant_id`; không tenant thì không thấy/không ghi được; insert tenant khác bị từ chối; SQL native cũng bị lọc; chủ bảng vẫn bị RLS vì FORCE; kết nối dùng lại không rò tenant giữa các lần mượn |
| ⚠️ | `services/identity-service/src/test/java/lms/identity/integration/PlatformScopeLeakIT.java` | request ALL rồi request người dùng tenant A trên cùng kết nối chỉ thấy A |
| ⚠️ | `services/catalog-service/src/test/java/lms/catalog/integration/CatalogCrossTenantPoolIT.java` | kết nối tái dùng sau request ALL không rò phạm vi |
| ⚠️ | `services/catalog-service/src/test/java/lms/catalog/integration/CatalogTenantIsolationIT.java` | cùng ISBN/mã vạch ở hai tenant; dữ liệu B không hiện cho token A; `lookup` bỏ id tenant khác; token không `tenantId` bị 403; không tenant thì RLS không thấy dòng |
| ⚠️ | `CirculationTenantIsolationIT`, `identity .../integration/TenantIsolationIT`, `support .../AuditTenantIsolationIT`, `system .../PlatformReadAllIT`, `*CrossTenantReadIT` | cô lập và đọc xuyên tenant theo service |
| ⚠️ | `services/sales-service/src/test/java/lms/sales/integration/SalesFlowIT.java` | javadoc ghi "cách ly tenant mọi bảng" bằng vai trò `sales_app`, có `tenantB` |

## 3. Repository và truy vấn

| | Nhận định | Bằng chứng |
|---|---|---|
| ✅ | ArchUnit: entity `*JpaEntity` có cột `tenant_id` phải có `@TenantId` (trừ bảng ngoại lệ) | `tenant-common/.../architecture/TenantArchitectureRules#tenantScopedEntitiesMustUseTenantId` |
| ✅ | ArchUnit: cấm native query ngoài danh sách lớp đã rà | `TenantArchitectureRules#noUnreviewedNativeQueries` |
| ✅ | ArchUnit: `@CrossTenantReadable` chỉ trên `@GetMapping` | `TenantArchitectureRules#crossTenantReadableOnlyOnGetMapping` |
| ✅ | 19 bảng toàn cục trong `TENANT_EXEMPT_TABLES`: `credentials`, `users`, `external_logins`, `permissions`, `tenants`, `organizations`, `tenant_memberships`, `refresh_tokens`, `known_tenants`, `tenant_modules`, 5 bảng hợp đồng, 2 bảng origin storefront, 2 bảng CMS | `TenantArchitectureRules.TENANT_EXEMPT_TABLES` |
| ❌ | Bảng toàn cục có cột `tenant_id` (`refresh_tokens`, `tenant_memberships`, `tenant_modules`, hợp đồng, origin) chỉ được bảo vệ bằng điều kiện `tenant_id` trong use case; không có luật tự động nào bắt use case quên lọc | Không có ArchRule tương ứng; tiền lệ dựa vào test hai tenant (ADR-0013 T-O2) |
| ℹ️ | Quy ước index: `tenant_id` đứng đầu composite index | CLAUDE.md mục Quy ước code |

## 4. Cache

| | Nhận định | Bằng chứng |
|---|---|---|
| ✅ | `RequestCache` (Redis): khóa `lms:v1:t:{tenantId}:{namespace}:{sha256(key)}`; không cache khi không có tenant hoặc đang ALL; `getGlobal` cho dữ liệu toàn nền tảng; fail-open | `shared-libraries/cache-common/src/main/java/lms/cache/RedisRequestCache.java` (`tenantScope`, javadoc) |
| ✅ | Import Excel: phiên preview/kết quả/mật khẩu tạm lưu Redis theo `lms:import:v1:t:{tenant}:u:{user}:{importId}:*` | `shared-libraries/import-common/.../redis/RedisImportSessionStore.java` (javadoc) |
| ✅ | customer-api (Caffeine, trong bộ nhớ): `StorefrontAccessCache` theo mã tenant đã chuẩn hóa; `BannerCache` theo `UUID tenantId` | `customer-api/.../storefront/StorefrontAccessCache.java`, `customer-api/.../banner/BannerCache.java` (`Cache<UUID, List<BannerView>>`) |
| ⚠️ | Redis dùng chung một instance, cô lập chỉ bằng tiền tố khóa (không ACL theo tenant) | `docker-compose.yml` một service `redis`; chấp nhận được vì Redis không ra ngoài |
| ℹ️ | Nguyên tắc: `key` phải gồm mọi đầu vào làm đổi kết quả; không cache dữ liệu phân quyền, tồn kho | CLAUDE.md mục Cache |

## 5. File storage

| | Nhận định | Bằng chứng |
|---|---|---|
| ✅ | **Không có** lưu trữ file bền (S3/MinIO/đĩa) trong code chạy; chỉ có upload multipart của import, đọc streaming, kết quả nằm ở Redis có TTL theo tenant/user | grep `S3Client|minio|FileSystemStorage` không có kết quả; `import-common/.../xlsx/StreamingXlsxReader.java`, `RedisImportSessionStore` |
| ℹ️ | Ảnh bìa/banner là URL ngoài, không upload | ADR-0011 2.8 (catalog không có ảnh bìa) |

## 6. Job nền, Kafka, bất đồng bộ

| | Nhận định | Bằng chứng |
|---|---|---|
| ✅ | Consumer Kafka bọc xử lý bằng `TenantAwareEventHandler.handle(envelope, ...)` → `TenantContext.runAs(envelope.tenantId, ...)`; event thiếu `tenantId` bị bỏ qua (log WARN, không log payload) | `shared-libraries/messaging-common/src/main/java/lms/messaging/TenantAwareEventHandler.java` |
| ✅ | Cả 7 lớp `@KafkaListener` trong `services/*/src/main` đều có `TenantAwareEventHandler` hoặc `TenantContext` | `ReservationEventConsumer` (catalog), `InventoryEventConsumer`, `TenantEventConsumer` (circulation), `AccessEventConsumer` (identity), `AuditEventConsumer`, `ContractExpiringConsumer`, `ReservationReadyConsumer` (support) |
| ✅ | Envelope mang `tenantId` (nullable cho event cũ) | `messaging-common/.../EventEnvelope.java` |
| ✅ | Job `@Scheduled` theo tenant lặp danh sách tenant rồi `TenantContext.runAs/callAs` từng tenant, lỗi một tenant không dừng tenant khác | `circulation/.../ExpireReservationsJob.java` (bảng `known_tenants`), `sales/.../ExpirePaymentIntentsJob.java`, `AnonymizeWalkInJob.java`, `ShopContactAnonymizationJob.java` (bảng `sales_tenants`) |
| ✅ | Job trên bảng toàn cục không cần tenant | `identity/.../RefreshTokenCleanupJob.java`, `ContractLifecycleJob.java`, `system/.../ContactAnonymizationJob.java` |
| ✅ | Import chạy nền: tenant truyền **tường minh** (`ImportActor`/`SessionKey.tenantId`) rồi `TenantContext.runAs(key.tenantId(), ...)` trên luồng của `ImportCommitExecutor` | `import-common/.../engine/ImportWorkflow.java` (dòng `TenantContext.runAs(key.tenantId(), ...)`), `ImportCommitExecutor` |
| ✅ | Không có `@Async`, `CompletableFuture` trong code service; executor duy nhất ngoài import là bộ render Markdown của CMS (bảng toàn cục) | grep `@Async|CompletableFuture|ExecutorService` trong `services/*/src/main` chỉ ra `CommonmarkMarkdownRenderer` |
| ❌ | Không có tiện ích chung truyền tenant sang luồng khác (`TaskDecorator`); mỗi nơi tự truyền. Ổn hiện tại vì ít luồng nền, nhưng là điểm dễ sai khi thêm job (ví dụ ghi thống kê sử dụng API) | `TenantContext` javadoc ("việc nền phải tự chỉ rõ tenant qua runAs/callAs") |
| ⚠️ | `known_tenants` (circulation) và `sales_tenants` (sales) là bản sao danh sách tenant đồng bộ qua event best-effort; tenant mới có thể bị job bỏ qua nếu event mất | ADR-0006 mục "Kiểm chứng còn nợ" (`known_tenants` chỉ seed tenant mặc định), ADR-0001 |

## 7. Đường xuyên tenant có chủ đích

| | Đường | Kiểm soát | Bằng chứng |
|---|---|---|---|
| ✅ | Platform admin đọc mọi tenant (`@CrossTenantReadable`, GET) | Token `platformAdmin` không `tenantId`; RLS `platform_read_all`; log `platform-audit operation=cross-tenant-read` | `PermissionInterceptor#handleCrossTenantRead`, ADR-0008 |
| ✅ | Platform admin hành động trong một tenant (`X-Tenant-Id`, mọi method) | Bind một tenant (không ALL), bỏ qua khóa/module, log `platform-audit operation=act-as-tenant` | `PermissionInterceptor#handleActAsTenant`, ADR-0016 |
| ⚠️ | Dấu vết act-as-tenant chỉ ở log ứng dụng, không lưu DB, không qua Kafka | như trên | Khoảng trống audit, không phải lỗ hổng |
| ✅ | identity cấp token theo tenant khác tenant của request (đăng nhập, đổi tenant) | `TenantContext.callAs(tenantĐãChọn)` trong `TokenService#issue` | `TokenService` |
| ✅ | Token `svc` cấp theo `{code}` (không có người dùng) | Chỉ dùng ở `@StorefrontAccessible`, đúng một tenant, TTL 5 phút | `PermissionInterceptor#handleServiceToken`, `IssueStorefrontTokenUseCase` |
| ✅ | customer-api so tenant của phiên khách với `{code}` | `ShopSessionGuard#requireSession` → `403 SHOP_SESSION_MISMATCH` | `customer-api/.../storefront/ShopSessionGuard.java` |

## 8. Khoảng trống và đề xuất cho Application/credential mới

| | Khoảng trống | Đề xuất | Bằng chứng/tiền lệ |
|---|---|---|---|
| ❌ | Tenant của lời gọi integration phải đến **từ credential đã xác thực**; hiện chưa có loại token này | identity ghi `tenantId` (claim sẵn có) = `applications.tenant_id` vào token client_credentials; `TenantContextFilter` không cần đổi. **Không** nhận `tenantId` từ body/query/header của client integration | `TenantContextFilter#tenantFromAuthentication` |
| ❌ | Bảng ứng dụng sẽ là bảng toàn cục (token endpoint tra `client_id` khi chưa biết tenant) | Thêm vào `TENANT_EXEMPT_TABLES`; mọi use case của tenant admin lọc `WHERE tenant_id = TenantContext.require() AND id = :id`, không nhận `tenantId` từ đường dẫn ở API của tenant; id của tenant khác → 404 cùng thân | ADR-0013 2.5 (C3), `TenantStorefrontOriginController` |
| ❌ | Không có luật tự động bắt use case quên lọc tenant trên bảng toàn cục | Test hai tenant bắt buộc cho mỗi use case đọc/ghi Application (IDOR), theo mẫu `CatalogTenantIsolationIT`; cân nhắc ArchRule: repository của bảng toàn cục có `tenant_id` chỉ có method nhận `tenantId` làm tham số đầu (⚠️ chưa thiết kế chi tiết) | mục 3 |
| ❌ | Platform admin act-as-tenant bỏ qua kiểm scope/khóa | Token integration **không bao giờ** có `platformAdmin`/`admin`; nhánh integration trong interceptor đặt **trước** nhánh platform admin; header `X-Tenant-Id` bị bỏ qua với token integration (đúng hiện trạng vì chỉ platform admin được xét) | `PermissionInterceptor#tryActAsTenant` |
| ❌ | Thống kê sử dụng API ghi bất đồng bộ dễ mất tenant | Bộ đếm Redis ghi đồng bộ trong request ở BFF integration (khóa có `tenantId` + `applicationId` lấy từ token đã xác thực); nếu sau này đẩy sang luồng nền phải truyền `tenantId` tường minh như `ImportActor` | `ImportWorkflow` |
| ❌ | Cache theo application | Nếu cache kết quả tra `site_key`/`client_id` ở BFF: khóa = giá trị đã chuẩn hóa, giá trị mang `tenantId`; không dùng `RequestCache` (cần tenant trước khi biết tenant) mà Caffeine như `StorefrontAccessCache`, TTL ≤ 60 giây để thu hồi có hiệu lực | `StorefrontAccessCache` |
| ⚠️ | Bảng chỉ thêm (lịch sử credential) cần `REVOKE UPDATE, DELETE` trong `afterMigrate.sql` | Theo mẫu ADR-0013 (bỏ qua khi bảng chưa tồn tại) | `identity/afterMigrate.sql` |
| ⚠️ | Kafka best-effort: event `ApplicationCredentialRevoked` có thể mất | Thu hồi không dựa vào Kafka: identity từ chối cấp token mới ngay trong DB; access token đã cấp hết hạn theo TTL ngắn | ADR-0001 |

## 9. Kết luận

- ✅ Lớp cô lập cho bảng nghiệp vụ (RLS FORCE + `@TenantId` + `TenantContext` từ JWT + vai trò NOBYPASSRLS) có đủ cơ chế và có test IT bằng vai trò app trong repo. ⚠️ Chưa có bằng chứng các test này xanh ở thời điểm viết.
- ✅ Kafka consumer, job theo tenant và import nền đều truyền tenant tường minh.
- ❌ Điểm yếu thật nằm ở **bảng toàn cục có cột `tenant_id`**: bảo vệ hoàn toàn bằng code ứng dụng. Application/credential mới sẽ rơi vào nhóm này, nên phải đi kèm test hai tenant và quy tắc "tenant lấy từ `TenantContext`, không từ đường dẫn" cho API của tenant admin.
- ℹ️ Không đề xuất RLS cho bảng mới (lý do ở `SAAS_API_AUTHENTICATION_DESIGN.md` mục 4.3, quyết định D5).
