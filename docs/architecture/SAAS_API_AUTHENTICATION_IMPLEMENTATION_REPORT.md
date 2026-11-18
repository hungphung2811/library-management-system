# Báo cáo triển khai: SaaS API Authentication

- Ngày: 2026-11-19. Phạm vi: các pha P0, P1, I2, I3, P2, I5 (BE, 21 commit) và Developer Portal ở FE (1 commit, repo `library-management-system-fe`).
- Nguồn: `SAAS_AUTHENTICATION_AUDIT.md`, `SAAS_API_AUTHENTICATION_DESIGN.md`, `TENANT_ISOLATION.md`, ADR-0027, `docs/api/{applications,integration-api,public-api-v1,token-audience}.md`, `JWT_KEY_ROTATION.md`, `git show --stat` của từng commit và mã nguồn.
- Quy ước icon: ✅ đã chạy và có kết quả, ⚠️ chưa kiểm chứng hoặc một phần, ❌ lỗi hoặc thiếu, ℹ️ ghi chú.
- Commit BE: P0 `57b9770 448a9a1 8ae7ccc a07c557`; P1 `3bb1dd6 a4b77a8 012d955 03f3c2c 5340b68`; I2 `091495d 0d492c0 a64022d`; I3 `25bf537 f35284d 5b437a7 2d88d19`; P2 và I5 `a822b39 3424b56 76816eb efa314f 51ec7a1`. FE: `130c781`.

## Tóm tắt trạng thái

### Implemented (đã cài đặt)

| Icon | Hạng mục |
|---|---|
| ✅ | Context `apiclient` ở identity: Application, credential, scope, domain, lịch sử (migration V32) |
| ✅ | OAuth2 `client_credentials` (identity `/internal/oauth2/token`, ra ngoài qua integration-api), RS256, `aud = lms-integration`, TTL 5 phút |
| ✅ | Module `integration-api` (cổng 8089): token proxy, đọc `books`, `inventory`, `orders`, rate limit Redis, thu hồi tức thời, log truy cập |
| ✅ | `@IntegrationScope` và nhánh token integration trong `PermissionInterceptor`, luật ArchUnit dùng chung |
| ✅ | Public API v1 theo site key (`/api/public/v1/**`) ở customer-api, CORS theo domain Application |
| ✅ | `jti` cho mọi access token, fail-fast khóa JWT cố định, JWKS hai khóa (xoay khóa) |
| ✅ | nginx: location và vùng `limit_req` cho `/api/integration/` và `/api/public/` |
| ✅ | FE Developer Portal (danh sách, chi tiết, scope, domain, credential, lịch sử) |

### Tested (đã có bằng chứng chạy)

| Icon | Hạng mục |
|---|---|
| ✅ | IT Docker: `OAuthTokenIT`, `ApplicationIT`, `SalesIntegrationTenantIsolationIT` (RLS thật) |
| ✅ | E2E curl trên máy dev (tenant demo) cho quản lý Application, OAuth, Integration API, rate limit, thu hồi, Public Site Key, JWKS, nginx.conf |
| ✅ | FE: typecheck, build sạch, Playwright `applications.spec.ts` 18/18 (trên mock) |
| ⚠️ | Unit/ArchUnit/WebMvc: số liệu do agent be-dev báo từng pha, chưa chạy lại toàn bộ ở HEAD |

### Partially Implemented (một phần)

| Icon | Hạng mục |
|---|---|
| ⚠️ | Thống kê sử dụng: chỉ tổng request theo ngày, chỉ chạy khi rate limit bật |
| ⚠️ | Rate limit: chỉ hạn mức mặc định 600/phút/nhóm; cột `rate_limit_per_minute` của Application chưa được dùng |
| ⚠️ | Thu hồi tức thời: theo cả Application, không theo credential; thu hồi thường vẫn chờ tối đa 5 phút |
| ⚠️ | CORS preflight Public API v1 phản chiếu origin hợp lệ về cú pháp (giới hạn của trình duyệt), cần người dùng xác nhận |
| ⚠️ | Public API v1 sessions/signup: chưa có bước xác minh email |
| ⚠️ | FE Developer Portal: chưa chạy với BE thật; chưa có tab Application ở trang tenant của platform admin, chưa có tab thống kê |

### Not Implemented (chưa làm, theo quyết định đã chốt)

| Icon | Hạng mục | Mã quyết định |
|---|---|---|
| ❌ | Scope `customers:*`; scope ghi (`books/inventory/orders:write`); `Idempotency-Key` cho Integration API | Q5, Q6 |
| ❌ | Danh sách đầu sách theo `updatedSince` | Q7 |
| ❌ | `GET /categories` công khai | Q12 |
| ❌ | Giỏ hàng và đặt đơn khách vãng lai | Q4 |
| ❌ | JWKS ra internet | Q11 |
| ❌ | Rate limit theo gói thuê | Q8 |
| ❌ | Seed menu "Developer Portal" ở BE | |
| ❌ | Xác minh sở hữu domain (domain ownership) | |
| ❌ | Kiểm nginx đầu-cuối thật (mới kiểm cú pháp) và test tải (p95 token endpoint) | |

## Known Issues

| # | Mức | Vấn đề | Trạng thái |
|---|---|---|---|
| L1 | ❌ | HEAD không build sạch: commit customer-api Public API v1 (`3424b56`) phụ thuộc 4 file catalog của phiên works/series chưa commit (`CatalogClient`, `HttpCatalogClient`, `BookDtos`, `BookController`); test `TitleControllerIntegrationScopeTest` tham chiếu `MoveEditionToWorkUseCase` chưa commit | Hết khi phiên kia commit |
| L2 | ⚠️ | `GET /api/applications/{id}/usage` khi Redis chết, qua lms-api trả `502 DEPENDENCY_ERROR` thay vì `503 USAGE_UNAVAILABLE` (identity trả đúng 503; lms-api đổi mọi 5xx thành 502 theo quy ước) | Chưa sửa |
| L3 | ⚠️ | Lệch tài liệu: `aud` thực tế là chuỗi, không phải mảng; phương thức sai ở integration-api trả `404`, không phải `405` | Đang sửa (agent khác) |
| L4 | ⚠️ | Đặt đơn lần đầu lúc sales khởi động nguội trả `503` sau khoảng 6 giây dù đơn đã tạo | Idempotency theo `clientRequestId` xử lý được |
| L5 | ⚠️ | Preflight CORS Public API v1 phản chiếu mọi origin hợp lệ về cú pháp (trình duyệt không gửi `X-Site-Key` trong preflight); request chính vẫn chỉ cho origin của Application | Cần người dùng xác nhận chấp nhận |
| L6 | ⚠️ | Thu hồi `COMPROMISED` vô hiệu hóa token của cả Application, không riêng credential (token không mang `credentialId`) | Giới hạn thiết kế hiện tại |
| L7 | ℹ️ | Thu hồi thường (`MANUAL`/`ROTATED`): token đã cấp còn dùng tối đa 5 phút | Đã chấp nhận ở thiết kế |
| L8 | ⚠️ | Bộ đếm usage chỉ chạy khi rate limit bật, chỉ đếm tổng theo ngày (không tách 2xx/4xx/5xx hay theo nhóm) | Chưa làm |
| L9 | ⚠️ | Hạn mức rate limit riêng theo Application chưa làm; số mặc định 600/phút chờ đo | Chưa làm |
| L10 | ⚠️ | Script Lua rate limit chưa chạy trên Redis Cluster | Chưa kiểm chứng |

---

## 1. Kiến trúc cũ

Nguồn: `SAAS_AUTHENTICATION_AUDIT.md` mục 1 đến 12.

```
Trình duyệt nhân viên ──► nginx 8080 ──/api/**──► lms-api 8085 ──/internal/**──► service
Trình duyệt khách      ──► nginx 8080 ──/public/**► customer-api 8088 ──/internal/**──► identity, sales, system, catalog, circulation
                                  └─ /internal/** và /.well-known/** trả 404
```

| Đặc điểm trước tính năng | Ghi chú |
|---|---|
| Hai kênh token: `STAFF` (lms-api) và `STOREFRONT` (customer-api, `aud=lms-storefront`) | Không có kênh cho máy-với-máy |
| Khách chưa đăng nhập nhận diện bằng mã cửa hàng `{code}` trên đường dẫn `/public/t/{code}/**`; customer-api xin token `svc` từ identity bằng `STOREFRONT_CLIENT_SECRET` | Không có site key, không có Application |
| JWT RS256 một khóa, JWKS một khóa, không xoay | Đổi PEM làm mọi token sống mất hiệu lực |
| Khóa PEM bỏ trống thì sinh khóa tạm, chỉ log WARN kể cả `prod` | Nhiều instance ký khác khóa |
| Không có `jti` trong mọi loại token | Không thể denylist theo token |
| Không có OAuth2 client_credentials, không Spring Authorization Server | Backend của tenant không có cách xác thực riêng |
| Phân quyền động `Controller/method` (ADR-0003), tenant từ claim `tenantId`, RLS (ADR-0006) | Giữ nguyên |
| Rate limit chỉ ở nginx theo IP/mã; không có thư viện rate limit ở ứng dụng | |
| Origin storefront theo tenant (ADR-0013), chưa gắn Application | |

## 2. Kiến trúc mới

```
Backend tenant (ERP/CRM) ─ client_credentials ─► nginx /api/integration/** ─► integration-api 8089 ─┬─► identity /internal/oauth2/token
                                                                                                     ├─► catalog (books:read)
                                                                                                     └─► sales (inventory:read, orders:read)
Website tenant (trình duyệt) ─ X-Site-Key ─────► nginx /api/public/**      ─► customer-api 8088 ─────┬─► identity /internal/storefront/access-tokens (token svc)
                                                                                                     └─► sales, catalog, system (/internal/**)
Admin tenant/platform ──────────────────────────► nginx /api/**            ─► lms-api 8085 ─► identity /internal/applications/**
Redis: rate limit, bộ đếm usage, denylist thu hồi · identity: Application, credential, scope, lịch sử (identity_db)
```

| Thay đổi chính | Quyết định |
|---|---|
| Identity phát token client_credentials, ký bằng khóa/JWKS hiện có | D1 |
| Module BFF mới `integration-api` (không DB, cổng 8089) | D2 |
| `@IntegrationScope("...")` và nhánh token integration trong `PermissionInterceptor` | D3 |
| Public API v1 song song với `/public/t/{code}/**` (đường cũ giữ nguyên) | D4 |
| 4 bảng Application toàn cục, không RLS, chống IDOR ở use case | D5 |
| Rate limit Redis fixed-window theo application, fail-open; nginx theo IP và site key | D6 |
| Secret `lms_sk_` băm SHA-256, hiện một lần; site key `lms_pk_` công khai | D7 |
| Không tạo `tenants`, `application_domains`, `api_sessions`, `api_audit_logs` | D8 |

## 3. Thành phần được tái sử dụng

| Thành phần | Dùng cho |
|---|---|
| `JwtTokenIssuer`, `JwtKeyConfiguration`, JWKS, `kid` thumbprint (identity) | Ký token integration và token `svc` theo site key; một issuer, một JWKS |
| `PermissionInterceptor`, `PermissionKeyResolver`, `JwtClaims` (security-common) | Thêm nhánh token integration, không thay khóa `Controller/method` |
| `TenantContext`, RLS, `@TenantId` (tenant-common) | Cô lập dữ liệu nghiệp vụ theo claim `tenantId` |
| Bảng `tenant_storefront_origins` và luật origin ADR-0013 (`ManageApplicationOriginsUseCase`) | Domain allowlist của Application (thêm cột `application_id`) |
| `refresh_tokens` kênh STOREFRONT | Phiên khách ở Public API v1 (không tạo `api_sessions`) |
| Token `svc` và `STOREFRONT_CLIENT_SECRET` (ADR-0011) | Đọc catalog thay khách chưa đăng nhập; thêm claim `application_id` khi cấp theo site key |
| `ChannelAudienceFilter` (lms-api), `aud` bắt buộc ở customer-api | Chặn token `lms-integration` |
| `GetMyContractsUseCase` (hợp đồng ADR-0012) | Điều kiện `CONTRACT_REQUIRED` khi tạo Application INTEGRATION |
| Khuôn BFF customer-api (`client` interface + `http`, `dto`, `web`, `StartupConfigurationCheck`) | Cấu trúc `integration-api` |
| `StorefrontAccessCache` (Caffeine), `PublicExceptionHandler`, `ShopSessionGuard` | Public API v1 (cache 60 giây, lỗi thống nhất, `SHOP_SESSION_MISMATCH`) |
| Hạ tầng Redis (docker-compose), mẫu fail-open của `cache-common` | Rate limit, usage, denylist |
| ArchUnit dùng chung (`ModuleArchitectureRules`, `TenantArchitectureRules`) | Luật `IntegrationScopeArchitectureRules`, `TENANT_EXEMPT_TABLES` |
| Hệ thống khóa động `ApplicationController/*` (ADR-0003) | Phân quyền tenant admin |

## 4. Thành phần được bổ sung

| Module | Thành phần mới |
|---|---|
| security-common | `@IntegrationScope`, `CallerIdentity`, `IntegrationScopeArchitectureRules`, hằng `JwtClaims.AUDIENCE_INTEGRATION`, `APPLICATION_ID` |
| identity `apiclient` | Domain (`Application`, `ApplicationCredential`, `ClientSecret`, `SiteKey`, `ApplicationScope`...), 8 use case quản lý, `AuthenticateClientUseCase`, `ResolveSiteKeyUseCase`, `QueryApplicationUsageUseCase`, adapter JPA, adapter Redis (`RedisApplicationRevocationAdapter`, `RedisApplicationUsageAdapter`), `ApplicationController`, `PlatformApplicationController` |
| identity `auth` | `IssueClientTokenUseCase`, `IssueStorefrontTokenUseCase` (nhánh site key), `OAuthTokenController`, `InProcessClientAuthenticationAdapter`, `InProcessSiteKeyTenantAdapter`, `JwksController` hai khóa |
| integration-api (module mới) | Cấu hình bảo mật JWT (`aud`, `iss`), `OAuthTokenController`, `BookController`, `InventoryController`, `OrderController`, `RateLimitFilter` + `RedisApiRateLimiter`, `RevocationFilter` + `RedisTokenRevocationChecker`, `AccessLogFilter`, `RequestSizeLimitFilter`, `StartupConfigurationCheck`, DTO allowlist riêng |
| customer-api | `publicv1` (`PublicBookController`, `PublicBannerController`, `PublicSessionController`, `PublicOrderController`, `SiteKeyAccess`), `PublicCorsFilter` theo Application, `RequestSizeLimitFilter` |
| catalog, sales | Gắn `@IntegrationScope` lên endpoint đọc; test scope, ArchUnit, IT cô lập tenant |
| system-service | Migration identity `V32__applications.sql`, REVOKE trong `afterMigrate.sql` |
| nginx, compose | Location `/api/integration/`, `/api/public/`, vùng `oauth_token`, `integration_ip`, `public_sitekey`; service `integration-api` (profile `integration`), biến môi trường mới |
| FE | Màn Developer Portal (xem mục 5) |

## 5. Danh sách file tạo mới

Tổng 169 file BE trong 21 commit (125 mã nguồn và tài liệu, 44 file test), tính theo `git show --name-status` (file thêm mới, loại trừ file bị sửa sau). Rút gọn thư mục khi nhiều file.

| Module | Số file | Nội dung (rút gọn) |
|---|---|---|
| `shared-libraries/security-common` | 8 | `permission/IntegrationScope.java`, `CallerIdentity.java`, `architecture/IntegrationScopeArchitectureRules.java`; test: `CallerIdentityTest`, `IntegrationScopeArchitectureRulesTest`, `IntegrationScopeInterceptorTest`, 2 lớp mẫu `architecture/sample/**` |
| `services/identity-service` | 77 (60 main, 17 test) | `apiclient/domain/*` (17 file: `Application`, `ApplicationCredential`, `ClientSecret`, `SiteKey`, `ApplicationScope`, `ApplicationStatus`, `RevokeReason`, repository...); `apiclient/application/*` (13 file: `Create/Manage/Query ApplicationUseCase`, `ManageCredentialsUseCase`, `ManageApplicationDomainsUseCase`, `AuthenticateClientUseCase`, `ResolveSiteKeyUseCase`, `QueryApplicationUsageUseCase`, `ApplicationRevocationPort`, `ApplicationUsagePort`...); `apiclient/infrastructure/{persistence,web,config}/*` (JPA entity, adapter, Spring Data, `ApplicationController`, `PlatformApplicationController`, 2 exception handler, `RedisApplication*Adapter`); `auth/**` (`OAuthTokenController`, `IssueClientTokenUseCase`, `ClientAuthenticationPort`, `ClientTokenIssuerPort`, `ClientTokenException`, `SiteKeyTenantPort`, `ServiceTokenIssuerPort`, 2 adapter in-process); `tenant/application/ManageApplicationOriginsUseCase`, `ApplicationOriginException`; test 17 file gồm `ApplicationIT`, `OAuthTokenIT`, `ApplicationSecurityTest`, `ApplicationUseCasesTest`, `ApiClientDomainTest`, `ApiClientArchitectureTest`, `JwtJtiAndKeyFailFastTest`, `JwtKeyRotationTest`... |
| `integration-api` | 65 (48 main, 15 test, 2 cấu hình) | `pom.xml`, `Dockerfile`, `application.yml`, `IntegrationApiApplication`; `config/*` (16: bảo mật, JWT, `RequestSizeLimitFilter`, `StartupConfigurationCheck`, `BearerForwardingInterceptor`, `DownstreamRestClients`...); `oauth/*` (7); `catalog/*`, `inventory/*`, `orders/*` (5 file mỗi nhóm: client, http client, DTO, controller, config); `ratelimit/*` (8); `revocation/*` (7); `web/*` (`AccessLogFilter`, `IntegrationExceptionHandler`, `IntegrationApiException`); `common/*` (`PageParams`, `PageView`); 15 file test (`IntegrationReadWebTest`, `RateLimitWebTest`, `RevocationWebTest`, `IntegrationApiArchitectureTest`, `IntegrationJwtValidatorTest`...) |
| `customer-api` | 8 | `publicv1/PublicV1Configuration`, `SiteKeyAccess`, `publicv1/web/Public{Banner,Book,Order,Session}Controller`; test `PublicV1ApiTest`, `StorefrontAccessCacheSiteKeyTest` |
| `services/catalog-service` | 2 | test `IntegrationScopeArchitectureRulesTest`, `TitleControllerIntegrationScopeTest` |
| `services/sales-service` | 3 | test `IntegrationScopeArchitectureRulesTest`, `SalesIntegrationScopeWebTest`, `SalesIntegrationTenantIsolationIT` |
| `services/system-service` | 1 | `db/migration/identity/V32__applications.sql` |
| `docs` | 5 | `decisions/0027-...md`, `api/applications.md`, `api/integration-api.md`, `api/public-api-v1.md`, `architecture/JWT_KEY_ROTATION.md` |
| FE (`library-management-system-fe`, `130c781`) | 17 file (1611 dòng thêm) | `src/pages/admin/{Applications,ApplicationDetail}.tsx`, `_components/applications/*` (ApplicationFormModal, CredentialsPanel, DomainsPanel, HistoryPanel, ScopesPanel, SecretOnceModal), `_lib/applications.ts`, `e2e/applications.spec.ts`, `e2e/mock/applications.mjs`; còn lại là file sửa (mục 6) |

ℹ️ Các file thiết kế `SAAS_AUTHENTICATION_AUDIT.md`, `SAAS_API_AUTHENTICATION_DESIGN.md`, `TENANT_ISOLATION.md` đã có từ commit `b12cfd7`, không tính ở đây.

## 6. Danh sách file chỉnh sửa

49 file BE bị sửa (không phải tạo mới trong cùng chuỗi commit). Theo nhóm:

| Module | Số file | File chính và nội dung sửa |
|---|---|---|
| security-common | 3 | `permission/PermissionInterceptor.java` (nhánh token integration đặt trước mọi nhánh khác), `PermissionKeyResolver.java` (đọc `@IntegrationScope`), `JwtClaims.java` (hằng audience, claim) |
| tenant-common | 1 | `architecture/TenantArchitectureRules.java` (thêm 4 bảng vào `TENANT_EXEMPT_TABLES`) |
| identity-service | 19 | `JwtTokenIssuer`, `JwtKeyConfiguration` (jti, fail-fast, khóa trước đó), `JwksController` (hai khóa), `StorefrontAccessController`, `IssueStorefrontTokenUseCase`, `ServiceTokenIssuerPort`, `AuthBeanConfiguration`, `SecurityConfiguration` (permitAll `/internal/oauth2/token`), `application.yml`; context `tenant` (`ResolveStorefrontTenantUseCase`, `StorefrontOriginEntry`, `StorefrontOriginRepository`, `TenantStorefrontOrigins`, `TenantBeanConfiguration`, adapter, entity: cột `application_id`); 3 test |
| catalog-service | 1 | `TitleController.java` (gắn `books:read` cho `get`, `lookup`, `lookupByIsbn`) |
| sales-service | 2 | `SalesOrderController.java` (`orders:read`), `StockItemController.java` (`inventory:read`) |
| system-service | 1 | `identity/afterMigrate.sql` (REVOKE UPDATE, DELETE trên `application_history`) |
| lms-api | 4 | `ChannelAudienceFilter.java` (từ chối `aud` chứa `lms-integration`), `IdentityGatewayController.java` (`/api/applications/**` sang identity, no-store), 2 test |
| customer-api | 9 | `PublicCorsFilter`, `RequestSizeLimitFilter`, `SecurityConfiguration`, `StorefrontAccessCache`, `StorefrontAccessClient`, `HttpStorefrontAccessClient`, `StorefrontDtos`, 2 test |
| Gốc và hạ tầng | 5 | `pom.xml` (module integration-api), `docker-compose.yml`, `infrastructure/nginx/nginx.conf`, `CLAUDE.md`, `README.md` |
| Tài liệu | 3 | `docs/api/README.md`, `docs/api/customer-api.md`, `docs/api/token-audience.md` |
| FE | 6 | `src/api/permissions.ts`, `src/routes/router.tsx`, `src/ui/error-messages.ts`, `src/ui/nav-links.ts`, `e2e/helpers.ts`, `e2e/mock-gateway.mjs` |

⚠️ Working tree hiện còn nhiều thay đổi chưa commit của phiên khác (ví dụ 4 file catalog của customer-api); không thuộc danh sách này.

## 7. Database Migration

Chỉ một migration: `services/system-service/src/main/resources/db/migration/identity/V32__applications.sql` (target `identity`).

| Bảng | Vai trò | Điểm chính |
|---|---|---|
| `applications` | Application (`PUBLIC_SITE` hoặc `INTEGRATION`) | `id` = `client_id`; `site_key` UNIQUE (có đúng khi `kind=PUBLIC_SITE`, định dạng `^lms_pk_[A-Za-z0-9_-]{32,56}$`); `status` ACTIVE/SUSPENDED/DELETED; `rate_limit_per_minute`; `version`; UNIQUE `(id, tenant_id)` làm đích FK kép; UNIQUE `(tenant_id, lower(name))` cho bản chưa xóa; index `(tenant_id, status, created_at)` |
| `application_credentials` | Credential | `secret_hash CHAR(64)` (SHA-256 hex), `secret_hint` (4 ký tự cuối), `status`, `expires_at`, `last_used_at`, `revoke_reason` (ROTATED, MANUAL, APPLICATION_DELETED, COMPROMISED); FK kép `(application_id, tenant_id)`; index `(application_id, status)` |
| `application_scopes` | Scope được cấp | PK `(application_id, scope)`; CHECK 8 scope hợp lệ trong danh mục (API chỉ cho cấp 3 scope đọc) |
| `application_history` | Lịch sử chỉ thêm | 12 loại `action` (thêm `DOMAIN_ADDED`, `DOMAIN_REMOVED` so với thiết kế); trigger `application_history_append_only` chặn UPDATE/DELETE; index `(application_id, occurred_at DESC)`; không FK vì dòng gốc có thể xóa mềm |
| `tenant_storefront_origins` (sửa) | Domain allowlist | Thêm cột `application_id` (NULL = áp cho mọi Application PUBLIC_SITE của tenant, tương thích ngược); FK kép `(application_id, tenant_id)`; index một phần `WHERE application_id IS NOT NULL` |

- ✅ Bảng toàn cục ngoại lệ RLS: 4 bảng `applications`, `application_credentials`, `application_scopes`, `application_history` thêm vào `TenantArchitectureRules.TENANT_EXEMPT_TABLES` (commit `a4b77a8`). Lý do: token endpoint và cấp token theo site key tra `client_id`/`site_key` khi chưa biết tenant. Bù bằng lọc `(tenant_id, id)` ở use case và test hai tenant. Migration không ghi vào bảng RLS nên không cần `set_config('app.tenant_id', ...)`.
- ✅ `afterMigrate.sql`: `REVOKE UPDATE, DELETE ON application_history FROM identity_app` (đặt sau bước GRANT, bỏ qua nếu chưa có bảng). Lớp phòng thủ thứ hai ngoài trigger.
- ✅ Lỗi đã gặp: `secret_hash CHAR(64)` làm Hibernate validate schema lỗi (kiểu `bpchar` so với `varchar`); sửa bằng `@JdbcTypeCode(SqlTypes.CHAR)` trên entity `ApplicationCredentialJpaEntity`. Lỗi do chính `ApplicationIT` phát hiện, sau sửa `ApplicationIT` đạt 1/1.
- ℹ️ Không có migration cho catalog, sales hay audit.

## 8. API Endpoint mới

Khóa quyền: mọi endpoint tenant admin dùng khóa động ADR-0003 `ApplicationController/<method>`, tự đăng ký lần đầu được gọi; role `is_admin` đi qua sẵn.

### 8.1. Identity (nội bộ, không ra internet trực tiếp)

| Method | Đường dẫn | Quyền |
|---|---|---|
| POST | `/internal/oauth2/token` | `@PublicEndpoint` (permitAll đúng đường dẫn này), không đăng ký khóa |
| POST | `/internal/storefront/access-tokens` (thêm nhánh `{siteKey}`) | `@PublicEndpoint` + header `X-Storefront-Client-Secret` |
| GET | `/.well-known/jwks.json` (hai khóa trong cửa sổ xoay) | Công khai, chỉ mạng nội bộ |

### 8.2. Integration API (`integration-api`, qua nginx `/api/integration/**`)

| Method | Đường dẫn | Scope |
|---|---|---|
| POST | `/api/integration/v1/oauth/token` | Xác thực client (Basic hoặc form) |
| GET | `/api/integration/v1/books/{id}` | `books:read` (service: `TitleController/get`) |
| POST | `/api/integration/v1/books/lookup`, `/books/lookup-by-isbn` | `books:read` (`TitleController/lookup`, `lookupByIsbn`) |
| GET | `/api/integration/v1/inventory`, `/inventory/{titleId}` | `inventory:read` (`StockItemController/search`, `get`) |
| GET | `/api/integration/v1/orders`, `/orders/{id}` | `orders:read` (`SalesOrderController/search`, `get`) |

### 8.3. Customer-api Public API v1 (`X-Site-Key`, qua nginx `/api/public/**`)

| Method | Đường dẫn | Phiên khách |
|---|---|---|
| GET | `/api/public/v1/books`, `/books/suggestions`, `/books/{bookRef}`, `/books/{bookRef}/preview`, `/banners` | Không |
| POST | `/api/public/v1/sessions`, `/sessions/refresh`, `/sessions/logout`, `/sessions/signup` | Không (tạo phiên) |
| POST, GET | `/api/public/v1/orders`, `/orders`, `/orders/{id}` | Bắt buộc |
| POST | `/api/public/v1/orders/{id}/cancellation` | Bắt buộc |

### 8.4. Quản lý Application (tenant admin, qua lms-api `/api/applications/**` sang identity `/internal/applications/**`)

| Method | Đường dẫn | Khóa quyền |
|---|---|---|
| GET | `/api/applications` | `ApplicationController/list` |
| GET | `/api/applications/{id}` | `get` |
| POST | `/api/applications` | `create` |
| PUT | `/api/applications/{id}` | `update` |
| DELETE | `/api/applications/{id}` | `delete` |
| PUT | `/api/applications/{id}/scopes` | `replaceScopes` |
| POST | `/api/applications/{id}/credentials` | `createCredential` |
| POST | `/api/applications/{id}/credentials/{credentialId}/revocation` | `revokeCredential` |
| POST | `/api/applications/{id}/site-key/rotation` | `rotateSiteKey` |
| GET, POST | `/api/applications/{id}/domains` | `listDomains`, `addDomain` |
| DELETE | `/api/applications/{id}/domains/{domainId}` | `removeDomain` |
| GET | `/api/applications/{id}/history` | `history` |
| GET | `/api/applications/{id}/usage?from&to` | `usage` |

### 8.5. Platform admin (`@PlatformAdminOnly`, không dùng khóa)

`/api/platform/tenants/{tenantId}/applications`: GET danh sách, GET `/{id}`, GET `/{id}/history`, GET `/{id}/usage`, POST `/{id}/suspension`, POST `/{id}/reactivation`, DELETE `/{id}`. Platform admin không tạo Application và không cấp credential hộ tenant.

⚠️ Danh sách đầy đủ mã lỗi nằm ở `docs/api/applications.md`, `integration-api.md`, `public-api-v1.md`.

## 9. Authentication Flow

### 9.1. Public Browser (website tenant)

1. Tenant admin tạo Application `PUBLIC_SITE`, khai domain; nhận `siteKey` công khai `lms_pk_...`.
2. Trình duyệt gọi `/api/public/v1/**` kèm `X-Site-Key`. customer-api kiểm định dạng (sai thì trả ngay), tra qua `StorefrontAccessCache` (SHA-256 của key, cache 60 giây), gọi identity `POST /internal/storefront/access-tokens {siteKey}` bằng `STOREFRONT_CLIENT_SECRET`.
3. Identity trả token `svc` (TTL 5 phút, `permissions` rỗng, thêm claim `application_id`) cùng `allowedOrigins` của Application. customer-api dùng token `svc` đọc catalog (chỉ đọc, trường công khai).
4. Ghi (đặt/hủy đơn) cần phiên khách: `POST /sessions` sinh token kênh STOREFRONT (`aud = lms-storefront`). `ShopSessionGuard` đối chiếu tenant của phiên với tenant của site key (`403 SHOP_SESSION_MISMATCH`). Đặt đơn bắt buộc `clientRequestId`.
5. Mọi lý do key không dùng được (thiếu, sai, đã xoay, Application ngưng/xóa, tenant ngưng, thiếu module SALES) cùng một `404 SHOP_NOT_AVAILABLE`.

### 9.2. Backend OAuth2 client_credentials

1. Tenant admin tạo Application `INTEGRATION` (cần hợp đồng ACTIVE), cấp scope, tạo credential; `clientSecret` hiện một lần.
2. Backend gọi `POST /api/integration/v1/oauth/token` (Basic hoặc form, `grant_type=client_credentials`, tùy chọn `scope`). integration-api chuyển form sang identity.
3. Identity tra credential theo `client_id`, so SHA-256 bằng `MessageDigest.isEqual`, kiểm Application ACTIVE, loại INTEGRATION, tenant hoạt động, credential chưa hết hạn/thu hồi; lọc scope theo module tenant bật.
4. Trả `{access_token, token_type: Bearer, expires_in: 300, scope}`, RS256, `aud = lms-integration`, claim `iss`, `sub`=`client_id`=`application_id`, `tenantId`, `scope`, `modules`, `jti`, `iat`, `exp`. Không có `admin`, `platformAdmin`, `roles`, `permissions`, `svc`, `sid`.
5. Gọi `/api/integration/v1/**` bằng Bearer: integration-api xác thực JWT (aud, iss) rồi kiểm thu hồi, scope, rate limit; chuyển nguyên token xuống catalog/sales. Service kiểm lại bằng `@IntegrationScope`, module và tenant qua `PermissionInterceptor`.
6. Hết hạn thì xin token mới (không có refresh token).

### 9.3. Admin (giữ nguyên)

Nhân viên đăng nhập qua `lms-api` (kênh STAFF), phân quyền động `Controller/method` và RLS không đổi. Token `lms-integration` và `lms-storefront` bị `ChannelAudienceFilter` trả 401 ở `/api/**`. Quản lý Application dùng chính luồng này (khóa `ApplicationController/*`).

## 10. Tenant Isolation Strategy

| Lớp | Cơ chế |
|---|---|
| Nguồn tenant | Token integration: claim `tenantId` do identity ký. Không bao giờ lấy từ body, query hay header; `X-Tenant-Id` và `?tenantId=` bị bỏ qua (có test) |
| Dữ liệu nghiệp vụ | RLS PostgreSQL theo `app.tenant_id` (ADR-0006), vai trò `<svc>_app` NOBYPASSRLS; `SalesIntegrationTenantIsolationIT` chạy RLS thật |
| Bảng Application | Bảng toàn cục không RLS (D5), bảo vệ bằng điều kiện `(tenant_id, id)` trong use case: id của tenant khác trả `404` cùng thân với "không tồn tại" |
| Chống leo thang | DTO allowlist riêng ở integration-api (không `tenantId`, giá vốn, PII khách); token integration chỉ vào method có `@IntegrationScope`, mọi endpoint khác `403` |
| FK kép | `(application_id, tenant_id)` ở credential và origin chặn lệch tenant ở mức schema |
| Redis | Khóa gắn tenant: `lms:rl:v1:t:{tenantId}:a:{applicationId}:{nhóm}:{epochMinute}`, `lms:usage:v1:t:{tenantId}:a:{applicationId}:{yyyyMMdd}` |
| Origin trùng | `409 ORIGIN_DUPLICATED` không lộ tenant chủ |
| Platform admin | `X-Tenant-Id` chỉ có tác dụng với người gọi platform admin; token integration bỏ qua |

## 11. Security Implementation

| Biện pháp | Chi tiết |
|---|---|
| Băm secret | `lms_sk_` + base64url(32 byte CSPRNG); lưu SHA-256 và 4 ký tự cuối; so sánh `MessageDigest.isEqual`; luôn thực hiện đủ truy vấn/so sánh ở mọi nhánh để giảm chênh lệch thời gian |
| Secret hiện một lần | Chỉ có trong phản hồi tạo credential, kèm `Cache-Control: no-store`; không API nào đọc lại; lịch sử không chứa secret |
| `PermissionInterceptor` | Nhánh token integration đặt sau bước 401, trước mọi nhánh khác; endpoint không có `@IntegrationScope` thì `403 FORBIDDEN` (không nêu khóa nội bộ); thiếu scope `403 INSUFFICIENT_SCOPE` |
| `@IntegrationScope` và ArchUnit | Luật dùng chung: chỉ đặt trên method `@*Mapping` ở `infrastructure.web`; không đặt trên class; scope thuộc tập 8 giá trị hợp lệ; không đặt scope `:write` trên `@GetMapping`; catalog và sales có test riêng |
| `jti` | Mọi access token (nhân viên, storefront, `svc`, integration) có `jti` UUID |
| Khóa JWT fail-fast | Thiếu `AUTH_JWT_PRIVATE_KEY/PUBLIC_KEY` thì identity không khởi động, trừ khi `AUTH_JWT_ALLOW_EPHEMERAL_KEY=true` (demo) |
| JWKS hai khóa | `AUTH_JWT_PREVIOUS_PUBLIC_KEY`: JWKS trả khóa ký trước, khóa cũ sau; PEM sai hoặc trùng khóa ký thì identity không khởi động; quy trình ở `JWT_KEY_ROTATION.md` |
| Rate limit Redis | Cửa sổ cố định 60 giây theo (tenant, application, nhóm), Lua `INCR`+`EXPIRE`, `429 RATE_LIMITED` kèm `Retry-After`, `X-RateLimit-*`; fail-open (timeout 200 ms, tạm bỏ qua Redis 5 giây); nginx giới hạn theo IP và site key |
| Thu hồi tức thời | Tùy chọn (`INTEGRATION_REVOCATION_ENABLED`): denylist `lms:revoked:v1:jti:{jti}` và dấu `lms:revoked:v1:app:{applicationId}` (ghi khi tạm ngưng, xóa Application hoặc credential `COMPROMISED`); token bị thu hồi `401`; fail-open |
| CORS | Chỉ origin trong allowlist của chính Application nhận `Access-Control-Allow-Origin`; không bao giờ gửi `Access-Control-Allow-Credentials`; `Vary: Origin, X-Site-Key`; preflight phản chiếu origin (L5) |
| Giới hạn kích thước | Token endpoint 4 KB (nginx và ứng dụng); Integration API 64 KB; Public API v1 16 KB; `413 PAYLOAD_TOO_LARGE` |
| Log truy cập | `lms.integration.access`: `correlationId`, `jti`, `tenantId`, `applicationId`, `method`, `route` (mẫu, không id thật, không query), `status`, `durationMs`; không ghi thân, `Authorization`, secret, token; không log site key ở customer-api |
| `invalid_client` thống nhất | Một mã `401 invalid_client` cho mọi lý do thất bại (thiếu client, sai secret, thu hồi, hết hạn, Application không ACTIVE, tenant ngưng), kèm `WWW-Authenticate: Basic realm="lms-integration"`, `no-store`; lý do thật chỉ ở log identity |
| Chặn token sai kênh | integration-api từ chối token có `svc`/`admin`/`platformAdmin`, sai `aud`/`iss`; lms-api và customer-api trả 401 cho token `lms-integration` |
| Khác | Không khóa client theo số lần sai (tránh DoS bởi người biết `client_id`); query string ở token endpoint bị cấm; header an toàn (`CSP default-src 'none'`, `X-Frame-Options`); giới hạn 20 Application mỗi tenant, 2 credential dùng được cùng lúc, hạn credential mặc định 365 ngày |

## 12. Test Results

### 12.1. Đã chạy, có kết quả

| Icon | Loại | Hạng mục | Kết quả |
|---|---|---|---|
| ✅ | IT Docker | `OAuthTokenIT` | 1/1 đạt |
| ✅ | IT Docker | `ApplicationIT` (sau khi sửa `CHAR(64)`/`@JdbcTypeCode(CHAR)`, lỗi do chính IT phát hiện) | 1/1 đạt |
| ✅ | IT Docker | `SalesIntegrationTenantIsolationIT` (RLS thật, vai trò `sales_app`) | 1/1 đạt |
| ✅ | E2E dev (curl, tenant demo, jar từ HEAD) | Quản lý Application: scope ghi/`customers:*` `422 SCOPE_NOT_GRANTABLE`, scope lạ `400 INVALID_SCOPE`, secret một lần, credential thứ 3 `409`, tenant khác `404` cùng thân, phân quyền 403/401 | Đạt |
| ✅ | E2E dev | OAuth: RS256 có `kid`, `exp-iat=300`, không có `admin`/`platformAdmin`/`roles`/`permissions`/`svc`, mọi lỗi client cùng `invalid_client` 401 kèm `WWW-Authenticate`, `no-store` | Đạt |
| ✅ | E2E dev | Integration API: đọc books/inventory/orders đúng dữ liệu demo, DTO allowlist không rò `tenantId`/giá vốn/PII, tenant khác `404`/`[]`, thiếu scope `403`, `401` cho token sai chữ ký/`alg=none`/hết hạn/STAFF/platform/storefront, token integration vào lms-api và customer-api `401`, vào endpoint hạ nguồn không gắn scope `403` | Đạt |
| ✅ | E2E dev | Rate limit `429` + `Retry-After` + `X-RateLimit-*`, khóa Redis đúng mẫu, usage khớp số lần gọi, Redis dừng thì fail-open | Đạt |
| ✅ | E2E dev | Thu hồi `COMPROMISED` làm token cũ `401`; denylist `jti` hoạt động | Đạt |
| ✅ | E2E dev | Public Site Key: CORS chỉ domain đã đăng ký và không Allow-Credentials; key sai/thiếu cùng `404 SHOP_NOT_AVAILABLE`; xoay key làm key cũ `404` ngay; tạm ngưng/xóa hiệu lực sau khoảng 60 giây (cache); đường cũ `/public/t/{code}` không đổi; `403 SHOP_SESSION_MISMATCH`; đặt đơn idempotent theo `clientRequestId` | Đạt |
| ✅ | E2E dev | Hồi quy đăng nhập nhân viên và FE cổng 3000 | Đạt |
| ✅ | E2E dev | JWKS chỉ có khóa công khai; `nginx.conf` có `location ^~ /.well-known/` trả 404; `nginx -t` bằng container | Đạt |
| ✅ | FE | Typecheck, build sạch | Đạt |
| ✅ | FE | Playwright `applications.spec.ts` (trên mock) | 18/18 đạt |

ℹ️ E2E dev chạy với customer-api kèm 4 file catalog dở của phiên khác (liên quan L1).

### 12.2. Số liệu unit/ArchUnit/WebMvc do agent be-dev báo (chưa tự chạy lại toàn bộ ở HEAD)

| Icon | Module | Số test báo cáo |
|---|---|---|
| ⚠️ | security-common | 138 |
| ⚠️ | identity-service | 862 (sau khi sửa slice, 0 lỗi) |
| ⚠️ | system-service | 296 |
| ⚠️ | lms-api | 309 |
| ⚠️ | customer-api | 351 |
| ⚠️ | integration-api | 96 |
| ⚠️ | catalog, circulation, support | báo đạt, không ghi số riêng |
| ⚠️ | sales | 17 test các lớp liên quan scope và cô lập tenant đạt; toàn bộ sales chưa chạy được do thay đổi dở của phiên khác |

### 12.2b. Hồi quy toàn repo do tester chạy trên bản sạch HEAD 51ec7a1 (2026-11-19)

| Icon | Module | Chạy | Lỗi |
|---|---|---|---|
| ✅ | security-common / tenant-common / cache-common | 138 / 36 / 22 | 0 |
| ✅ | identity-service | 835 | 0 |
| ✅ | circulation-service / sales-service / support-service | 228 / 519 / 76 | 0 |
| ✅ | system-service / lms-api / integration-api | 296 / 299 / 96 | 0 |
| ⚠️ | catalog-service, customer-api | 260 / 335 chỉ khi vá cục bộ ở worktree (L1, L2: phụ thuộc file chưa commit của phiên works/series; phiên đó đã commit sau đó, chưa build lại HEAD mới để xác nhận) | 0 sau vá |
| ✅ | IT Docker: ApplicationIT, OAuthTokenIT, ContractMigrationIT, PlatformScopeLeakIT, TenantIsolationIT, StorefrontOriginMigrationIT, CatalogTenantIsolationIT, MigrationApiIT | 1/1, 1/1, 7/7, 1/1, 4/4, 4/4, 5/5, 5/5 | 0 |
| ❌ | IT: SalesIntegrationTenantIsolationIT (lỗi dựng dữ liệu, IT chưa từng xanh; bản bọc TransactionTemplate đạt 1/1, đang sửa file gốc) | | 1 |
| ⚠️ | IT: StorefrontAccessIT 2/6 lỗi, StorefrontOriginIT 2/9 lỗi: 2 ca do trường applicationId mới (SaaS P2, đang sửa test), 2 ca do ADR-0023 (tenant chỉ có LENDING nay trả 200; ngoài SaaS, chờ chủ sản phẩm quyết) | | 4 |
| ✅ | Ma trận token integration trên mọi controller thật: catalog 42 (3 có scope), sales 71 (4), identity 121, circulation 23, support 5, system 24; endpoint không gắn scope luôn 403 kể cả khi token mang admin/platformAdmin/X-Tenant-Id | 12 ca | 0 |
| ✅ | Cô lập tenant trên Postgres thật vai trò sales_app (RLS): application tenant B không thấy tồn kho/đơn của A, /contact và stock-adjustments 403 | 1 | 0 |
| ✅ | Không rò secret ở DTO Application (ApplicationViewsSecretLeakTest) | 2 | 0 |

ℹ️ Chưa có test HTTP đầu-cuối qua SecurityFilterChain thật cho ranh giới kênh token (mới kiểm ở mức decoder/filter); chưa chạy các IT identity/catalog/circulation/support còn lại và OracleIT.

### 12.3. Chưa chạy

| Icon | Hạng mục |
|---|---|
| ⚠️ | FE Playwright với BE thật (mới chạy trên mock) |
| ⚠️ | nginx đầu-cuối thật (mới `nginx -t`) |
| ⚠️ | Test tải (p95 token endpoint, tải identity khi cấp token theo site key) |
| ⚠️ | Rate limit script Lua trên Redis Cluster |
| ⚠️ | Quy trình xoay khóa JWT trên môi trường nhiều instance (mới có test đơn vị JWKS hai khóa và decoder hai khóa) |

## 13. Các vấn đề chưa xử lý

Xem bảng Known Issues ở đầu tài liệu (L1 đến L10). Việc chưa làm theo quyết định đã chốt ở nhóm Not Implemented. Thêm:

| Icon | Mục | Ghi chú |
|---|---|---|
| ❌ | Header `Idempotency-Key` ở Public API v1 | Chỉ nhận `clientRequestId` trong body |
| ❌ | `last_used_at` của site key | Migration V32 không có cột |
| ⚠️ | Xác minh email ở Public API v1 `sessions/signup` | Phụ thuộc việc dở của phiên khác; dùng đường cũ `/public/t/{code}/auth/verify-email` |
| ⚠️ | Xoay khóa JWT chưa tự động | Thao tác vận hành thủ công |
| ⚠️ | Dấu thu hồi chỉ ghi sau commit | Identity chết giữa chừng thì không có dấu, quay về TTL 5 phút |
| ⚠️ | Menu "Developer Portal" | FE đã có route, BE chưa seed menu |

## 14. Hướng dẫn cấu hình Environment

Tên biến đã đối chiếu với `application.yml` và `docker-compose.yml`.

| Biến | Áp dụng | Mặc định | Ý nghĩa |
|---|---|---|---|
| `AUTH_JWT_PRIVATE_KEY` | identity | trống | Khóa riêng RSA (PEM PKCS#8) |
| `AUTH_JWT_PUBLIC_KEY` | identity | trống | Khóa công khai (PEM X.509) |
| `AUTH_JWT_ALLOW_EPHEMERAL_KEY` | identity | `false` trong yml; compose đặt `true` | Cho phép sinh khóa tạm (chỉ demo); production phải `false` |
| `AUTH_JWT_PREVIOUS_PUBLIC_KEY` | identity | trống | Khóa công khai cũ trong cửa sổ xoay |
| `INTEGRATION_TOKEN_TTL` | identity | `PT5M` | TTL token integration |
| `INTEGRATION_REVOCATION_ENABLED` | identity và integration-api | `false` | Ghi dấu/kiểm thu hồi tức thời (cần Redis; bật ở cả hai) |
| `INTEGRATION_REVOCATION_MARKER_TTL` | identity | `PT1H` | TTL dấu thu hồi, phải lớn hơn TTL token |
| `INTEGRATION_RATE_LIMIT_ENABLED` | integration-api | yml `false`; compose `true` | Bật rate limit và bộ đếm usage |
| `INTEGRATION_RATE_LIMIT_PER_MINUTE` | integration-api | `600` | Hạn mức mặc định mỗi nhóm endpoint |
| `JWT_JWK_SET_URI` | mọi resource server | `http://localhost:8081/.well-known/jwks.json` | JWKS của identity |
| `JWT_ISSUER` | integration-api | `library-auth-service` | Phải khớp `auth.jwt.issuer` của identity |
| `IDENTITY_SERVICE_URL` | customer-api, integration-api, lms-api | `http://localhost:8081` | |
| `CATALOG_SERVICE_URL` | customer-api, integration-api | `http://localhost:8082` | |
| `SALES_SERVICE_URL` | customer-api, integration-api | `http://localhost:8087` | |
| `REDIS_HOST`, `REDIS_PORT` | identity, integration-api | `localhost`, `6379` | Compose đặt `redis`, `6379` |
| `STOREFRONT_CLIENT_SECRET` | identity và customer-api | trống trong yml; compose mặc định `dev-storefront-secret` | Bí mật chia sẻ cấp token `svc`; production phải riêng, cùng giá trị hai phía |
| `INTEGRATION_API_PROFILE` | compose, integration-api | `dev` | `prod` thì fail-fast khi `JWT_JWK_SET_URI` rỗng/localhost hoặc `JWT_ISSUER` rỗng |

Chạy local: `docker compose --profile apps --profile integration up -d --build` (integration-api không bật mặc định); chạy tay `mvn -pl integration-api -am spring-boot:run`. `scripts/dev-start.ps1` không quản lý integration-api.

Sinh khóa RSA (ví dụ, tạo ngoài repo, không commit):

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem   # PKCS#8
openssl rsa -in jwt-private.pem -pubout -out jwt-public.pem                          # X.509
```

## 15. Hướng dẫn tạo Application

Chi tiết ở `docs/api/applications.md`. Hai cách: qua Developer Portal (FE) hoặc API.

- ✅ FE Developer Portal đã làm (commit `130c781`): danh sách, chi tiết, scope, domain, credential (hiện secret một lần), lịch sử. ⚠️ Mới kiểm trên mock, chưa chạy với BE thật.
- ❌ Chưa có menu "Developer Portal" được seed ở BE; truy cập bằng route trực tiếp cho tới khi seed menu.

Qua API (tenant admin; `$TOKEN` là token nhân viên của tenant; `$ORIGIN` ví dụ `http://localhost:8080`):

```bash
# 1) Tạo Application INTEGRATION (cần hợp đồng thuê ACTIVE, nếu không 409 CONTRACT_REQUIRED)
curl -sS -X POST "$ORIGIN/api/applications" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"ERP kế toán","kind":"INTEGRATION"}'
# -> 201 { "id": "<APP_ID>", ... }   (id đồng thời là clientId)

# 2) Cấp scope (hiện chỉ cấp được books:read, inventory:read, orders:read)
curl -sS -X PUT "$ORIGIN/api/applications/$APP_ID/scopes" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"scopes":["books:read","inventory:read"]}'

# 3) Tạo credential: clientSecret CHỈ hiện một lần
curl -sS -X POST "$ORIGIN/api/applications/$APP_ID/credentials" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"expiresInDays":365}'
# -> 201 { "credentialId": "...", "clientId": "<APP_ID>", "clientSecret": "lms_sk_****************", "secretHint": "****", ... }

# 4) Website công khai: tạo PUBLIC_SITE rồi khai domain
curl -sS -X POST "$ORIGIN/api/applications" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"Website shop","kind":"PUBLIC_SITE"}'   # -> siteKey lms_pk_...
curl -sS -X POST "$ORIGIN/api/applications/$SITE_APP_ID/domains" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"origin":"https://shop.example.vn"}'
```

Xoay vòng secret: tạo credential mới (tối đa 2 dùng được cùng lúc), đổi phía client, rồi `POST .../credentials/{credentialId}/revocation` credential cũ. Nghi lộ secret: thu hồi với `{"reason":"COMPROMISED"}` (có hiệu lực tức thời nếu bật revocation). Xoay site key: `POST .../site-key/rotation` (key cũ hết hiệu lực, tối đa 60 giây do cache). Quyền: role `is_admin` đi qua sẵn; role thường cần được gán khóa `ApplicationController/*`.

## 16. Hướng dẫn tích hợp cho khách hàng

**Nguyên tắc cứng: không bao giờ đặt `client_secret` (`lms_sk_...`) ở frontend** (JavaScript, HTML, app di động). Site key `lms_pk_...` là công khai, không phải bí mật và không phải bằng chứng xác thực.

### 16.1. Backend (ERP/CRM)

```bash
# Lấy token (Basic, khuyến nghị); lưu CLIENT_ID/CLIENT_SECRET trong kho bí mật
TOKEN=$(curl -sS -X POST "$ORIGIN/api/integration/v1/oauth/token" \
  -u "$CLIENT_ID:$CLIENT_SECRET" -d grant_type=client_credentials \
  -d "scope=books:read inventory:read orders:read" | jq -r .access_token)

curl -sS -H "Authorization: Bearer $TOKEN" "$ORIGIN/api/integration/v1/books/$BOOK_ID"
curl -sS -H "Authorization: Bearer $TOKEN" "$ORIGIN/api/integration/v1/inventory?lowStockOnly=true&page=0&size=50"
curl -sS -H "Authorization: Bearer $TOKEN" "$ORIGIN/api/integration/v1/orders?status=CONFIRMED&page=0&size=20"
```

- Token sống 300 giây, không có refresh token: lưu và dùng lại đến sát hạn, hết hạn thì xin mới; không xin token cho mỗi request.
- Gặp `429`: đợi theo `Retry-After`. Mọi lỗi lấy token là `401 invalid_client`, không phân biệt lý do.
- Chỉ đọc `books`, `inventory`, `orders`; tenant luôn là tenant của Application (không gửi tenant ở đâu cả).
- Không có CORS: không gọi từ trình duyệt.

### 16.2. Website (trình duyệt)

1. Admin tạo Application `PUBLIC_SITE`, khai đúng origin của website (ví dụ `https://shop.example.vn`) để CORS hoạt động.
2. Gọi `/api/public/v1/**` kèm `X-Site-Key`, không dùng `credentials: 'include'`.
3. Đăng nhập khách bằng `POST /sessions`, lưu `accessToken`/`refreshToken` (tách riêng khỏi app nhân viên), gọi `/orders` kèm `Authorization: Bearer`.
4. Mỗi lần bấm đặt sinh một `clientRequestId` UUID (chống đặt trùng; gặp `503` thì gửi lại cùng `clientRequestId`).
5. `404 SHOP_NOT_AVAILABLE` nghĩa là key sai, đã xoay, Application ngưng, hoặc tenant không bán hàng; thay đổi cấu hình có hiệu lực sau tối đa 60 giây.

```js
const res = await fetch(`${API}/books?keyword=java&size=20`, { headers: { 'X-Site-Key': SITE_KEY } });
```

## 17. Hướng dẫn triển khai Production

| Icon | Bước | Chi tiết |
|---|---|---|
| ℹ️ | Khóa JWT cố định | Sinh RSA 2048, đặt `AUTH_JWT_PRIVATE_KEY`/`AUTH_JWT_PUBLIC_KEY` từ kho bí mật (không để vào repo); mọi instance identity dùng cùng khóa; đặt `AUTH_JWT_ALLOW_EPHEMERAL_KEY=false` (compose đang mặc định `true`) |
| ℹ️ | Secret storefront riêng | Đặt `STOREFRONT_CLIENT_SECRET` ngẫu nhiên, khác mặc định `dev-storefront-secret`, cùng giá trị ở identity và customer-api |
| ℹ️ | Profile prod | `CUSTOMER_API_PROFILE=prod` và `INTEGRATION_API_PROFILE=prod` (fail-fast khi JWKS trỏ localhost/rỗng hoặc `JWT_ISSUER` rỗng) |
| ℹ️ | Thứ tự triển khai | system-service trước (chạy migration V32 lên identity_db), rồi identity, catalog, sales, customer-api, integration-api, lms-api, cuối cùng nginx. Không build jar khi service đang chạy. Sau khi pull migration: `mvn clean` |
| ℹ️ | nginx | Location `^~ /api/public/` (thân 16 KB), `= /api/integration/v1/oauth/token` (vùng `oauth_token` 30r/m/IP, burst 10, thân 4 KB), `^~ /api/integration/` (vùng `integration_ip` 20r/s, burst 40), `^~ /.well-known/` và `^~ /internal/` trả 404; vùng `public_sitekey` 50r/s theo site key, `login`, `public_form`, `public_order` cho sessions/orders. Đặt `real-ip` đúng khi sau LB/CDN. Các số rate là đề xuất, chờ đo |
| ℹ️ | Redis | Bắt buộc cho rate limit, usage, thu hồi; đặt `INTEGRATION_RATE_LIMIT_ENABLED=true` (usage chỉ chạy khi bật); Redis lỗi thì fail-open nên cần giám sát Redis riêng |
| ℹ️ | Bật thu hồi | `INTEGRATION_REVOCATION_ENABLED=true` ở cả identity và integration-api; `INTEGRATION_REVOCATION_MARKER_TTL` lớn hơn `INTEGRATION_TOKEN_TTL` |
| ℹ️ | Xoay khóa | Theo `docs/architecture/JWT_KEY_ROTATION.md`: đặt khóa mới, `AUTH_JWT_PREVIOUS_PUBLIC_KEY` = khóa công khai cũ, chờ tối thiểu 1 giờ, rồi gỡ biến; khẩn cấp (lộ khóa) thì để trống biến cũ |
| ℹ️ | Giám sát | Log `lms.integration.access` (tỉ lệ 401/403/429/503, độ trễ); log identity `oauth2-token denied reason=...`; WARN fail-open của Redis; `application_history` (nhất là `TOKEN_DENIED_REPEATEDLY`); `platform-audit` |
| ⚠️ | Cần làm trước khi mở ra internet | (1) Chạy hồi quy toàn repo trên bản HEAD sạch (L1 phải hết); (2) kiểm nginx đầu-cuối thật; (3) đo tải token endpoint và đặt lại số rate limit; (4) người dùng xác nhận chấp nhận L5 (preflight phản chiếu origin); (5) sửa L2 hoặc chấp nhận 502; (6) quyết định về xác minh domain ownership; (7) kiểm Redis Cluster nếu dùng; (8) kiểm FE Developer Portal với BE thật và seed menu; (9) rà tài liệu lệch (L3) |
