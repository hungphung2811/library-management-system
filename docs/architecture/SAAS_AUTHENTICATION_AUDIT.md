# Audit: hiện trạng xác thực, phân quyền và multi-tenant trước khi tích hợp SaaS API Authentication

- Ngày: 2026-11-18. Người viết: SA. Phạm vi: Phase 1 (Audit) của yêu cầu "SaaS API Authentication" (Public API cho website khách, Integration API cho backend khách, Admin API giữ nguyên).
- Nguồn: đọc code thật trên nhánh `main` **gồm thay đổi chưa commit** của phiên khác (đặc biệt `customer-api/**`, `services/identity-service/**/auth|tenant/**`, `identity/V31__import_permissions.sql` chưa được track). Mọi nhận định có đường dẫn; điều chưa kiểm chứng ghi ⚠️.
- Không chạy test trong phiên này (cần Docker/Testcontainers). Khi viện dẫn test chỉ nêu tên lớp có thật, không khẳng định đang xanh.
- Tài liệu đi kèm: `docs/architecture/SAAS_API_AUTHENTICATION_DESIGN.md` (Phase 2), `docs/architecture/TENANT_ISOLATION.md`.

Quy ước icon: ✅ đã kiểm chứng bằng code, ⚠️ chưa kiểm chứng hoặc suy luận, ❌ thiếu hoặc lỗi, ℹ️ ghi chú.

---

## 1. Tổng quan kiến trúc hiện tại

```
Trình duyệt nhân viên ──► nginx 8080 ──/api/**──► lms-api 8085 (BFF nhân viên) ──/internal/**──► service
Trình duyệt khách      ──► nginx 8080 ──/public/**► customer-api 8088 (BFF công khai) ──/internal/**──► identity, sales, system, catalog, circulation
                                 │
                                 └─ /internal/** và /.well-known/** trả 404 (không ra ngoài)

Service theo bounded context (mỗi service một DB, Postgres + RLS):
  identity 8081 (auth, accesscontrol, user, department, tenant, contract) · catalog 8082 · circulation 8083
  support 8084 (audit, notification; Mongo + Postgres) · system 8086 (Flyway tập trung, menu, cms) · sales 8087
```

| | Nhận định | Bằng chứng |
|---|---|---|
| ✅ | Cổng vào duy nhất là nginx; `/internal/` và `/.well-known/` trả 404; mặc định `location / { return 404; }` | `infrastructure/nginx/nginx.conf` (các khối `location ^~ /internal/`, `location ^~ /.well-known/`, `location /`) |
| ✅ | Hai BFF: `lms-api` (nhân viên, `/api/**`) và `customer-api` (công khai, `/public/**`); không BFF nào có DB | `lms-api/src/main/java/lms/api/**`, `customer-api/src/main/java/lms/customer/**`, `customer-api/src/test/java/lms/customer/architecture/CustomerApiArchitectureTest.java` |
| ✅ | Service chỉ phục vụ `/internal/**` (+ identity `/api/platform/**`), còn lại `denyAll` | `services/*/src/main/java/lms/*/config/SecurityConfiguration.java` (ví dụ `lms.identity.config.SecurityConfiguration#securityFilterChain`, `lms.sales.config.SecurityConfiguration#securityFilterChain`) |
| ✅ | Hexagonal theo context: `domain` (Java thuần), `application` (port, use case POJO), `infrastructure/*`; bean dựng ở `*BeanConfiguration` | ví dụ `services/identity-service/src/main/java/lms/identity/auth/infrastructure/config/AuthBeanConfiguration.java` |
| ✅ | Migration tập trung ở system-service theo target | `services/system-service/src/main/resources/db/migration/{identity,catalog,circulation,audit,system,sales}/` |

## 2. Technology stack (phiên bản thật)

| | Thành phần | Phiên bản | Bằng chứng |
|---|---|---|---|
| ✅ | Java | 21 | `pom.xml` `<java.version>21</java.version>` |
| ✅ | Spring Boot (parent) | 3.5.6 | `pom.xml` `spring-boot-starter-parent` `3.5.6` |
| ✅ | Spring Cloud BOM | 2025.0.0 | `pom.xml` `<spring-cloud.version>` |
| ✅ | springdoc-openapi | 2.8.13 | `pom.xml` `<springdoc.version>` |
| ✅ | ArchUnit | 1.5.1 | `pom.xml` `<archunit.version>` |
| ✅ | commonmark | 0.27.1 | `pom.xml` |
| ✅ | Apache POI | 5.4.1 | `pom.xml` `<poi.version>` |
| ✅ | Spring Security OAuth2 Resource Server + Nimbus JOSE (ký/kiểm JWT) | theo BOM Boot 3.5.6 | `JwtKeyConfiguration` dùng `NimbusJwtEncoder`/`NimbusJwtDecoder`, `RSAKey` |
| ✅ | Caffeine (cache trong bộ nhớ ở customer-api) | theo BOM | `customer-api/.../config/PublicCorsFilter.java` import `com.github.benmanes.caffeine` |
| ✅ | Redis (cache-common, import-common) | theo BOM | `shared-libraries/cache-common/src/main/java/lms/cache/RedisRequestCache.java` |
| ✅ | nginx | `nginx:1.27-alpine` (theo ADR-0013 1.1) | ⚠️ chỉ đọc qua ADR, chưa mở `docker-compose.yml` dòng image |
| ❌ | Spring Authorization Server | **không có** trong bất kỳ `pom.xml` nào | grep `authorization-server` toàn repo (bỏ `target/`) không có kết quả |
| ❌ | Thư viện rate limit (Bucket4j, Resilience4j RateLimiter) | **không có** | grep `bucket4j|RateLimiter|resilience4j` không có kết quả; `customer-api` có bulkhead tự viết (`app.resilience.max-concurrent-calls`) |

## 3. Authentication hiện tại

### 3.1. Phát hành và ký token (identity-service)

| | Nhận định | Bằng chứng |
|---|---|---|
| ✅ | JWT ký **RS256** bằng **một** khóa RSA; `kid` = thumbprint | `lms.identity.auth.infrastructure.config.JwtKeyConfiguration#rsaKey` (`keyIDFromThumbprint()`), `JwtTokenIssuer#issue` (`JwsHeader.with(SignatureAlgorithm.RS256)`) |
| ✅ | JWKS công khai ở `GET /.well-known/jwks.json` (một khóa) | `lms.identity.auth.infrastructure.web.JwksController#jwks` (`new JWKSet(rsaKey.toPublicJWK())`) |
| ⚠️ | Không có cơ chế xoay khóa: JWKS chỉ chứa đúng một khóa, đổi PEM là mọi token đang sống mất hiệu lực | `JwksController`, `JwtKeyConfiguration` (một bean `RSAKey`) |
| ❌ | Khóa PEM bỏ trống thì **sinh khóa tạm và chỉ log WARN**, kể cả profile `prod`; nhiều instance identity sẽ ký bằng khóa khác nhau | `JwtKeyConfiguration#rsaKey` (`log.warn("Chưa cấu hình auth.jwt.*-key-pem ...")`), `services/identity-service/src/main/resources/application-prod.yml` không bắt buộc `AUTH_JWT_PRIVATE_KEY`, `docker-compose.yml` `AUTH_JWT_PRIVATE_KEY: ${AUTH_JWT_PRIVATE_KEY:-}` |
| ✅ | Claim access token người dùng: `iss`, `sub` (userId UUID), `iat`, `exp`, `username`, `roles`, `permissions`, `tenantId`, `modules`, `sid`, `departmentId`, `admin`/`platformAdmin` (chỉ khi true), `mustChangePassword`, `aud` (theo kênh) | `JwtTokenIssuer#issue`, `shared-libraries/security-common/src/main/java/lms/security/JwtClaims.java` |
| ❌ | **Không có `jti`** trong mọi loại token | `JwtTokenIssuer#issue`, `JwtTokenIssuer#issueService` (không gọi `.id(...)`) |
| ✅ | `iss` = `library-auth-service`, TTL access `PT15M`, refresh `P7D` | `services/identity-service/src/main/resources/application.yml` (`auth.jwt.issuer`, `access-token-ttl`, `refresh-token-ttl`) |
| ✅ | Token dịch vụ (`svc`): `sub` cố định `...5f01`, `svc=customer-api`, `tenantId`, `modules`, `permissions=[]`, TTL `PT5M` | `JwtTokenIssuer#issueService`, `lms.identity.auth.application.IssueStorefrontTokenUseCase` (`STOREFRONT_SUBJECT`), `application.yml` `lms.identity.storefront.token-ttl` |

### 3.2. Luồng đăng nhập, làm mới, kênh token

| | Nhận định | Bằng chứng |
|---|---|---|
| ✅ | Đăng nhập mật khẩu: `POST /internal/auth/login` (`username`, `password`, `tenantCode?`, `channel?`); thứ tự kiểm: tài khoản → khóa/vô hiệu → mật khẩu → tenant | `lms.identity.auth.application.LoginUseCase`, `docs/api/token-audience.md` |
| ✅ | Kênh `STAFF` / `STOREFRONT`: STOREFRONT ghi `aud=lms-storefront`, không bao giờ có `admin`/`platformAdmin`, `permissions` lọc theo allowlist `lms.identity.token.storefront-permissions` | `TokenService#issue`, `TokenService#permissionsFor`, `TokenService.DEFAULT_STOREFRONT_PERMISSIONS`, `JwtTokenIssuer#issue` (hàng rào thứ hai) |
| ✅ | Token STAFF **không có `aud`** trong giai đoạn chuyển tiếp (cờ `lms.identity.token.staff-audience`, mặc định tắt) | `JwtTokenIssuer` constructor tham số `staffAudience` |
| ✅ | Refresh token: 48 byte `SecureRandom`, base64url, lưu **SHA-256**; xoay vòng mỗi lần dùng; họ phiên `session_id`; dùng lại token đã xoay ngoài cửa sổ ân hạn thì thu hồi phiên | `SecureRefreshTokenCodec#generate`, `#hash`; `TokenService#issue` (`refreshTokens.rotate`); `application.yml` `auth.refresh-reuse-grace: PT30S`; migration `identity/V24__refresh_tokens_session_family.sql` |
| ✅ | Refresh token giữ kênh; lệch kênh → 401 | `TokenService#issueForTenant` (lấy `replacedOrNull.channel()`), `identity/V18__signup_default_channel_va_khoa_getany.sql` (cột `channel`) |
| ✅ | Chống dò mật khẩu: `credentials.failed_attempts`, `locked_until` + nginx vùng `login` 10r/m/IP | `identity/V1__create_auth_tables.sql`, `nginx.conf` `limit_req_zone ... zone=login` |
| ✅ | customer-api gộp `UNAUTHORIZED`, `NO_TENANT_ACCESS`, `ACCOUNT_LOCKED`, `ACCOUNT_DISABLED` thành `401 LOGIN_FAILED` (đã đóng cửa dò trạng thái khóa nêu ở ADR-0011 2.13) | `customer-api/.../auth/client/http/HttpAuthClient.java` (`LOGIN_FAILURE_CODES`) |
| ⚠️ | `lms-api /api/auth/login` vẫn trả mã riêng cho khóa/vô hiệu (ADR-0011 Q4 còn mở) | `docs/decisions/0011-customer-api-cong-cong-khai.md` 2.13; chưa đọc lại `lms-api` `AuthController` |
| ✅ | Đăng nhập ngoài OIDC (Google), PKCE, state cookie ký | `lms.identity.auth.infrastructure.security.OidcClient`, `Pkce`, `StateCookieCodec`, `ExternalAuthController` |
| ✅ | Access token không tra lại kho: thu hồi phiên chỉ chặn refresh, access còn sống tối đa 15 phút | `docs/decisions/0017-phien-da-thiet-bi.md` (H9, R3) |

### 3.3. Token dịch vụ storefront (`svc`) và bí mật chia sẻ

| | Nhận định | Bằng chứng |
|---|---|---|
| ✅ | `POST /internal/storefront/access-tokens` (permitAll đúng một đường dẫn), xác thực bằng header `X-Storefront-Client-Secret` so thời gian hằng với `STOREFRONT_CLIENT_SECRET`; rỗng → từ chối | `lms.identity.auth.infrastructure.web.StorefrontAccessController#issueAccessToken`, `lms.identity.config.SecurityConfiguration` |
| ✅ | Phân giải `{code}` → tenant: tồn tại, `ACTIVE`, bật **SALES hoặc LENDING** (ADR-0023 đã nới từ "chỉ SALES"); trả kèm `allowedOrigins`, `modules`; mọi nhánh cùng số truy vấn (chống đo thời gian) | `lms.identity.tenant.application.ResolveStorefrontTenantUseCase#execute` |
| ✅ | `INTERNAL_API_KEY` chỉ dùng cho đăng ký khóa quyền, là **một giá trị chung** cho mọi service; compose có giá trị mặc định `dev-internal-key` | `InternalPermissionController#register`, `docker-compose.yml` (`INTERNAL_API_KEY: ${INTERNAL_API_KEY:-dev-internal-key}` ở nhiều service) |
| ✅ | customer-api từ chối khởi động ở profile khác dev nếu bí mật storefront là giá trị dev | `customer-api/.../config/StartupConfigurationCheck.java` (`DEV_DEFAULT_SECRET`) |
| ❌ | identity/các service **không** có kiểm tra tương tự cho `INTERNAL_API_KEY=dev-internal-key` hoặc khóa JWT trống ở prod | `application-prod.yml` của identity chỉ đặt `app.internal-api-key: ${INTERNAL_API_KEY}`; không thấy lớp kiểm khởi động tương ứng ở identity |

### 3.4. Filter, entry point, CORS, CSRF

| | Thành phần | Hiện trạng | Bằng chứng |
|---|---|---|---|
| ✅ | Resource server ở service | `oauth2ResourceServer().jwt()` với `PermissionJwtConverter` (claim `permissions` → authority không prefix); JWKS từ `JWT_JWK_SET_URI` | `services/*/config/SecurityConfiguration.java`, `security-common/.../PermissionJwtConverter.java`, `services/*/src/main/resources/application-prod.yml` (`jwk-set-uri: ${JWT_JWK_SET_URI}`) |
| ⚠️ | Kiểm `iss`/`aud` ở service | Chỉ cấu hình `jwk-set-uri`, không `issuer-uri`, không `audiences`: theo hành vi autoconfig Boot thì chỉ kiểm chữ ký + thời hạn. Hệ quả: service nhận **mọi** token do identity ký (STAFF, STOREFRONT, `svc`); phân biệt hoàn toàn dựa vào `PermissionInterceptor` | grep `audiences|issuer-uri` trong `services/*/src/main` không có kết quả; ⚠️ hành vi validator mặc định của Boot chưa thử chạy |
| ✅ | lms-api | Chỉ xác thực; bỏ qua `Authorization` ở các đường dẫn đăng nhập/làm mới; `ChannelAudienceFilter` **chỉ** từ chối token có `aud` chứa `lms-storefront`; token không `aud` (gồm `svc`) qua được lớp xác thực | `lms-api/.../config/SecurityConfiguration.java`, `lms-api/.../config/ChannelAudienceFilter.java#acceptable` |
| ✅ | customer-api | Danh sách đóng `PUBLIC_API` (permitAll, bỏ qua `Authorization`), `AUTHENTICATED_API` (cần JWT), còn lại `denyAll`; `JwtDecoder` riêng bắt buộc `aud` chứa `lms-storefront`; `ServiceTokenRejectionFilter` chặn `svc`; `ShopSessionGuard` so tenant | `customer-api/.../config/SecurityConfiguration.java`, `StorefrontJwtConfiguration#jwtDecoder`, `ServiceTokenRejectionFilter`, `storefront/ShopSessionGuard#requireSession` |
| ✅ | Entry point | customer-api: 401 ở `/public/**`, 403 cho đường dẫn khác; thân JSON cố định | `customer-api/.../config/SecurityConfiguration#securityFilterChain` (`entryPoint`), `JsonErrorWriter` |
| ✅ | CORS `/api/**` | nginx `map $http_origin $cors_origin` (chỉ `localhost:3000`, `localhost:5173`), `snippets/common.conf` (có `Allow-Credentials true`) | `nginx.conf`, `infrastructure/nginx/snippets/common.conf` (⚠️ đọc qua ADR-0013 1.1, chưa mở file) |
| ✅ | CORS `/public/**` **đã cài đặt** (ADR-0013 ghi "đề xuất" nhưng code đã có): `PublicCorsFilter` servlet filter `HIGHEST_PRECEDENCE + 5`, chế độ `OFF/REPORT/ENFORCE`, mặc định `ENFORCE`, so khớp chính xác origin, **không bao giờ gửi `Allow-Credentials`**, preflight luôn 204 | `customer-api/.../config/PublicCorsFilter.java`, `PublicCorsConfiguration#publicCorsFilter`, `customer-api/src/main/resources/application.yml` (`app.public-cors`), nginx location `/public/` chỉ include `security-headers.conf` |
| ✅ | Danh sách origin theo tenant | Bảng `tenant_storefront_origins` (UNIQUE `origin` toàn hệ thống, tối đa 10/tenant), API platform + tenant admin chỉ xem | `identity/V21__tenant_storefront_origins.sql`, `PlatformStorefrontOriginController`, `TenantStorefrontOriginController`, `tenant/domain/StorefrontOrigin.java` |
| ✅ | CSRF | Tắt ở mọi chain, `SessionCreationPolicy.STATELESS`, bearer token trong header (không cookie phiên) | các `SecurityConfiguration` nêu trên |
| ✅ | Giới hạn kích thước thân | nginx `client_max_body_size 1m` (mặc định), `16k` cho `/public/`, `6m` cho import; customer-api `RequestSizeLimitFilter` 16 KB + giới hạn Jackson | `nginx.conf`, `customer-api/.../config/RequestSizeLimitFilter.java`, `JacksonLimitsConfiguration.java` |

## 4. Authorization hiện tại

| | Nhận định | Bằng chứng |
|---|---|---|
| ✅ | Phân quyền động theo khóa `TênController/tênMethod`, tự đăng ký vào `permissions` của identity, kiểm claim `permissions`; `admin=true` bỏ qua kiểm khóa | `shared-libraries/security-common/.../permission/PermissionInterceptor#preHandle`, `PermissionKeyResolver`, `CachingPermissionKeyRegistrar`, `RemotePermissionKeyRegistrar` |
| ✅ | Thứ tự trong interceptor: `PUBLIC` → chưa xác thực 401 → **token `svc`** (chỉ qua `@StorefrontAccessible`) → `AUTHENTICATED` không module → `PLATFORM_ADMIN` → đọc xuyên tenant → act-as-tenant → `TENANT_REQUIRED` → đăng ký khóa → module → khóa | `PermissionInterceptor#preHandle`, `#handleServiceToken` |
| ⚠️ | Một token **không có `svc`**, không `permissions`, có `tenantId` vẫn qua mọi endpoint `@AuthenticatedOnly` (ví dụ đổi mật khẩu, `menus/me`, thông báo, `users` một số method). Hiện chỉ có token người dùng ở nhánh này nên an toàn; **một loại token mới (integration) sẽ lọt vào đây** nếu không thêm nhánh riêng | `PermissionInterceptor#preHandle` (khối `Mode.AUTHENTICATED && module == null`), danh sách controller `@AuthenticatedOnly` ở mục 12 |
| ✅ | Module theo tenant (`@RequiresModule(LENDING|SALES)`), admin tenant cũng bị chặn | `PermissionInterceptor#hasModule`, `security-common/.../permission/TenantModule.java`, `identity/V16__tenant_modules.sql` |
| ✅ | Platform admin: `@PlatformAdminOnly`; `@CrossTenantReadable` (GET, `tenantId` query); `X-Tenant-Id` act-as-tenant cho mọi method, bỏ qua khóa/module, chỉ log `platform-audit` | `PermissionInterceptor#isCrossTenantRead`, `#handleActAsTenant` |
| ⚠️ | Thông điệp 403 chứa tên khóa nội bộ (`"Thiếu quyền: " + key`); customer-api tự viết lại thông điệp, lms-api chuyển nguyên 4xx | `PermissionInterceptor#preHandle` dòng cuối, `customer-api/.../web/PublicExceptionHandler.java`, ADR-0011 1.1 |
| ✅ | BFF không phân quyền: lms-api và customer-api chỉ xác thực; service tự kiểm | `lms-api/.../config/SecurityConfiguration.java` (javadoc), ADR-0011 2.4 |
| ✅ | Phòng ban: `departmentId` theo membership của tenant đã chọn | `TokenService#issue` (`choice.departmentId()`) |
| ❌ | Không có khái niệm **scope** OAuth2, không có quyền gắn với ứng dụng (application) | grep `scope` chỉ ra `app.tenant_scope` (RLS) và OIDC `scopes`; không có claim `scope` trong `JwtClaims` |

## 5. Tenant architecture hiện tại

Chi tiết và khoảng trống: `docs/architecture/TENANT_ISOLATION.md`. Tóm tắt:

| | Nhận định | Bằng chứng |
|---|---|---|
| ✅ | Chung DB, cột `tenant_id`, `@TenantId` Hibernate + RLS `ENABLE`/`FORCE`, policy theo `current_setting('app.tenant_id')` | `tenant-common/.../TenantRlsConnectionInitializer.java` (javadoc quy ước), `identity/V13__tenant_isolation.sql` |
| ✅ | `TenantContext` thread-local (CURRENT + ALL), đặt bởi `TenantContextFilter` **chỉ từ claim `tenantId` của JWT**, xóa ở `finally` | `tenant-common/.../TenantContext.java`, `TenantContextFilter#doFilterInternal` |
| ✅ | Biến phiên đặt bằng `set_config(..., false)` (cấp **phiên**, không phải cấp transaction) lúc mượn kết nối, đặt lại rỗng khi trả kết nối | `TenantRlsConnectionInitializer.SET_SQL`, `#apply`, `#reset`, `ResettingHandler#invoke` |
| ✅ | Tenant được xác định ở customer-api theo `{code}` trong đường dẫn → token `svc` mang `tenantId` → service đặt `TenantContext` như thường | `customer-api/.../storefront/StorefrontAccessCache.java`, `IssueStorefrontTokenUseCase#execute` |
| ✅ | Hợp đồng thuê và module theo tenant (`tenant_contracts`, `tenant_contract_modules`, `tenant_modules`) | `identity/V19__tenant_contracts.sql`, `identity/V16__tenant_modules.sql` |
| ❌ | Không có khái niệm **gói thuê/plan** (tier, hạn mức) để áp rate limit theo gói; hợp đồng chỉ mang danh sách module và tiền | `identity/V19__tenant_contracts.sql` (không cột plan/quota) |

## 6. Database schema liên quan (identity_db)

| | Bảng | Cột chính | RLS | Bằng chứng |
|---|---|---|---|---|
| ✅ | `tenants` | `id`, `code` UNIQUE, `name`, `organization_id`, `status` (`ACTIVE`/`SUSPENDED`), `suspension_cause`, `suspended_by_contract_id` | Không (toàn cục) | `identity/V11__create_tenants_and_memberships.sql`, `V19` |
| ✅ | `organizations` | `id`, `name` | Không | `V11` |
| ✅ | `tenant_memberships` | PK (`user_id`, `tenant_id`), `department_id`, `status` | Không | `V11` |
| ✅ | `credentials` | `user_id` PK, `username` UNIQUE, `password_hash`, `active`, `failed_attempts`, `locked_until`, `platform_admin`, `last_tenant_id`, `must_change_password` | Không | `V1`, `V7`, `V11` |
| ✅ | `users`, `reader_profiles` | `users` toàn cục; `reader_profiles` PK (`tenant_id`, `user_id`) | `users` không; `reader_profiles` có | `V10`, `V13` |
| ✅ | `roles`, `user_roles`, `role_permissions` | `roles.tenant_id`, `is_admin`, UNIQUE (`tenant_id`, `name`) | Có (+ `platform_read_all` FOR SELECT, chặn ghi ở scope `all` với `role_permissions`) | `V2`, `V9`, `V13`, `V15__platform_read_all.sql` |
| ✅ | `permissions` | `code` PK (khóa `Controller/method`), `description`, ... | Không | `V2`, `V9` |
| ✅ | `role_templates`, `role_template_permissions` | mẫu role theo module | Không (⚠️ không thấy trong `TENANT_EXEMPT_TABLES` vì không có cột `tenant_id`) | `V11`, `V16` |
| ✅ | `refresh_tokens` | `token_hash` UNIQUE (SHA-256), `tenant_id`, `platform_session`, `channel`, `session_id`, `device_summary`, `client_ip`, `revoked_at`, `revoked_reason` | Không | `V1`, `V12`, `V18`, `V24` |
| ✅ | `tenant_modules` | PK (`tenant_id`, `module`), `enabled`, `updated_by` | Không | `V16` |
| ✅ | `tenant_contracts` (+ `_modules`, `_payments`, `_history`, `_reminders`) | hợp đồng, thanh toán chỉ thêm | Không; `REVOKE UPDATE, DELETE` cho bảng chỉ thêm | `V19`, `identity/afterMigrate.sql` |
| ✅ | `tenant_storefront_origins`, `tenant_storefront_origin_history` | `origin` UNIQUE toàn cục, `enabled`; lịch sử chỉ thêm (trigger + REVOKE) | Không | `V21`, `afterMigrate.sql` |
| ✅ | `external_logins`, `password_reset_tokens` | | Không | `V8`, `V23` |
| ✅ | Migration identity mới nhất trên đĩa: **`V31__import_permissions.sql` (chưa được git track, của phiên khác)** → số kế tiếp dự kiến `V32` ⚠️ | `ls services/system-service/src/main/resources/db/migration/identity/`, `git status` |
| ✅ | `support`: `audit_logs` (event Kafka, `payload TEXT`, `tenant_id` từ `V2`) | `audit/V1__create_audit_logs.sql`, `audit/V2__tenant_id.sql` |
| ✅ | `sales`: `sales_orders.client_request_id` UNIQUE (`tenant_id`, `client_request_id`), `goods_receipts` tương tự (chống trùng/idempotency) | `sales/V1__create_sales_tables.sql` (`uq_sales_orders_tenant_request`, `uq_goods_receipts_tenant_request`) |

## 7. Thành phần tái sử dụng được

| | Thành phần | Dùng cho | Bằng chứng |
|---|---|---|---|
| ✅ | Khóa RS256 + `JwtTokenIssuer` + JWKS | Ký access token client_credentials (thêm một phương thức phát hành, không thêm máy chủ ủy quyền) | `JwtTokenIssuer#issueService` là tiền lệ token không phải người dùng |
| ✅ | Mẫu "endpoint permitAll đúng một đường dẫn + bí mật so thời gian hằng" | Token endpoint `client_credentials` | `StorefrontAccessController`, `InternalPermissionController` |
| ✅ | `SecureRefreshTokenCodec` (CSPRNG 48 byte + SHA-256) | Sinh/băm client secret | `auth/infrastructure/security/SecureRefreshTokenCodec.java` |
| ✅ | Nhánh token đặc biệt trong `PermissionInterceptor` (`svc` + `@StorefrontAccessible`) | Nhánh token integration + annotation scope | `PermissionInterceptor#handleServiceToken`, `PermissionKeyResolver#storefrontAccessible` |
| ✅ | `aud` theo kênh + `ChannelAudienceFilter` + `StorefrontAudienceValidator` | Kênh `lms-integration` | `JwtClaims.AUDIENCE_*`, `lms-api/.../ChannelAudienceFilter.java`, `customer-api/.../StorefrontJwtConfiguration.java` |
| ✅ | `tenant_storefront_origins` + CORS động `PublicCorsFilter` + `StorefrontAccessCache` | Domain allowlist cho Application kiểu website | `V21`, `PublicCorsFilter`, `StorefrontAccessCache` |
| ✅ | customer-api: danh sách đóng đường dẫn, `PublicExceptionHandler` allowlist mã lỗi, `RequestSizeLimitFilter`, bulkhead, không log thân request | Public API v1 và khung cho integration BFF | `customer-api/.../config/*`, `web/PublicExceptionHandler.java` |
| ✅ | Mẫu bảng toàn cục do nền tảng sở hữu + lịch sử chỉ thêm (trigger + REVOKE) + `PlatformOperationEvent` | `applications`, lịch sử credential | ADR-0013 2.5, 2.9; `V21`; `tenant/application/PublishPlatformEventPort.java` |
| ✅ | `IdentityGatewayController` chuyển nguyên `/api/platform/**` | API platform quản lý Application không cần sửa lms-api | `lms-api/.../identity/web/IdentityGatewayController.java` |
| ✅ | Idempotency theo `client_request_id` ở sales | `Idempotency-Key` của Integration API ánh xạ vào đây | `sales/V1`, `ShopController` (`clientRequestId`), `sales/shared/domain/DuplicateClientRequestException.java` |
| ✅ | Redis + mẫu fail-open của `cache-common` | Bộ đếm rate limit theo application | `RedisRequestCache` (fail-open, tạm bỏ qua Redis 5 giây) |
| ✅ | nginx `limit_req` theo IP và theo `{code}` | Mở rộng theo `X-Site-Key`, token endpoint | `nginx.conf` (`public_read`, `public_tenant`, `login`) |
| ✅ | Phiên khách STOREFRONT (refresh token kênh STOREFRONT) | "Public Session Token" cho giỏ hàng/đặt đơn | `TokenService`, `customer-api/.../auth/web/AuthController.java` |

## 8. Thành phần còn thiếu so với yêu cầu

| | Yêu cầu | Hiện trạng |
|---|---|---|
| ❌ | Khái niệm **Application** (một tenant nhiều ứng dụng) | Không có bảng/entity; grep `application_id`, `applications` không có trong migration |
| ❌ | **Public Site Key** | Không có; tenant nhận diện bằng `{code}` trong đường dẫn |
| ❌ | **OAuth 2.0 Client Credentials** (token endpoint, client ID/secret, `grant_type`) | Không có; `grant_type` chỉ xuất hiện trong `OidcClient` (identity là client của Google) |
| ❌ | **Scope** (`books:read`...) | Không có claim, không có annotation |
| ❌ | **Xoay vòng/thu hồi credential** ứng dụng (nhiều key cùng hoạt động) | Không có; bí mật duy nhất `STOREFRONT_CLIENT_SECRET` là cấu hình môi trường |
| ❌ | `jti`, thu hồi access token | Không có `jti`; access token không tra lại kho (ADR-0017 R3) |
| ❌ | Xoay khóa ký JWT (nhiều khóa trong JWKS) | Một khóa |
| ❌ | Rate limit theo **tenant/application/gói thuê** ở tầng ứng dụng | Chỉ nginx theo IP và theo `{code}`; không có Redis counter |
| ❌ | **Audit API** theo request (ai gọi, ứng dụng nào) | `audit_logs` chỉ ghi event nghiệp vụ qua Kafka (best-effort, ADR-0001); log `platform-audit` chỉ là dòng log |
| ❌ | Integration API `/api/integration/v1/**` | Không có controller/route; nginx chỉ có `/api/` → lms-api và `/public/` → customer-api |
| ❌ | Public API `/api/public/v1/**` | Không có; Public hiện là `/public/**` (khác tiền tố). `/api/public/**` nếu tạo sẽ rơi vào `location /api/` → lms-api |
| ❌ | Danh mục công khai `categories`, giỏ hàng server | customer-api không có categories; sales không có giỏ hàng (grep `cart` chỉ thấy trong `RequestSupport`); đơn online gửi danh sách dòng trực tiếp |
| ❌ | Danh sách đầu sách có phân trang, mốc `updated_at` cho đồng bộ ERP | `TitleController#search` trả `List` không phân trang; `titles` không có `updated_at` (`catalog/V1`) |
| ❌ | Developer Portal | Không có màn/API quản lý ứng dụng, thống kê sử dụng |
| ⚠️ | Idempotency key chuẩn (`Idempotency-Key` header) | Có `clientRequestId` trong body ở sales (đơn, phiếu nhập), chưa có cho endpoint khác |

## 9. Rủi ro bảo mật (xếp mức)

| Mức | | Rủi ro | Bằng chứng | Ghi chú |
|---|---|---|---|---|
| Cao | ❌ | Khóa ký JWT sinh tạm khi PEM trống, **kể cả prod**; compose mặc định trống. Nhiều instance identity → token của instance này bị instance khác từ chối; khởi động lại → mọi token mất hiệu lực; với client_credentials sẽ làm mọi tích hợp hỏng đồng loạt | `JwtKeyConfiguration#rsaKey`, `docker-compose.yml` `AUTH_JWT_PRIVATE_KEY: ${AUTH_JWT_PRIVATE_KEY:-}` | Phải fail-fast ở prod trước khi mở API cho bên ngoài |
| Cao | ⚠️ | Thêm loại token mới mà không sửa đủ ba chỗ sẽ mở quyền ngoài ý muốn: (1) `PermissionInterceptor` cho token không `svc` qua mọi `@AuthenticatedOnly`; (2) `lms-api` `ChannelAudienceFilter` chỉ chặn `lms-storefront`; (3) service không kiểm `aud`/`iss` | `PermissionInterceptor#preHandle`, `ChannelAudienceFilter#acceptable`, cấu hình `jwk-set-uri` các service | Rủi ro thiết kế, chưa phải lỗ hổng hiện hành |
| Vừa | ❌ | `INTERNAL_API_KEY` chung cho mọi service, compose có mặc định `dev-internal-key`, không có kiểm khởi động ở prod | `docker-compose.yml`, `InternalPermissionController` | Lộ khóa này chỉ cho phép đăng ký khóa quyền (không cấp quyền), nhưng làm bẩn bảng `permissions` |
| Vừa | ⚠️ | Platform admin `X-Tenant-Id` bỏ qua khóa và module cho **mọi method**, dấu vết chỉ là dòng log (không DB) | `PermissionInterceptor#handleActAsTenant` | Có chủ đích (ADR-0016); với Integration API phải bảo đảm token integration không bao giờ có `platformAdmin` |
| Vừa | ⚠️ | Không xoay được khóa ký; không `jti` nên không thể thu hồi một access token cụ thể; thu hồi phiên trễ tối đa 15 phút | `JwksController`, `JwtTokenIssuer`, ADR-0017 R3 | Với client_credentials cần TTL ngắn |
| Vừa | ⚠️ | Thông điệp 403 của interceptor lộ tên lớp/method nội bộ; lms-api chuyển nguyên 4xx | `PermissionInterceptor#preHandle`, ADR-0011 1.1 | Integration BFF phải lọc như customer-api |
| Vừa | ⚠️ | Vùng `public_preview` (30r/m) của ADR-0011 2.7 **chưa có** trong nginx; preview chỉ chịu `public_read` 20r/s | `nginx.conf` (không có `zone=public_preview`) | Chống cào preview yếu hơn thiết kế |
| Vừa | ⚠️ | 19 bảng toàn cục (`TENANT_EXEMPT_TABLES`) chỉ được bảo vệ bằng lọc tường minh trong use case, không RLS | `tenant-common/.../architecture/TenantArchitectureRules.java` | Thêm bảng Application vào đây tăng bề mặt IDOR |
| Thấp | ✅ | CORS `/public/**` không gửi `Allow-Credentials`, origin so khớp chính xác, `null`/nhiều header bị từ chối | `PublicCorsFilter#allowedOrigin` | Đạt yêu cầu "không wildcard với credentialed" |
| Thấp | ⚠️ | CORS `/api/**` ở nginx có `Allow-Credentials: true` với map tĩnh localhost; production phải thêm origin bằng tay | `nginx.conf` `map $http_origin $cors_origin`, ADR-0013 1.1 | ⚠️ chưa mở `snippets/common.conf` |
| Thấp | ⚠️ | nginx tính giới hạn theo IP biên; sau LB/CDN mà không cấu hình `real-ip.conf` thì mọi khách chung một IP | `nginx.conf` (`include .../real-ip.conf`), ADR-0013 2.11 | ⚠️ module realip của image chưa xác minh |
| Thấp | ✅ | Webhook thanh toán giả lập công khai ở lớp xác thực, bảo vệ bằng HMAC, 404 khi tắt sandbox; nginx chặn `/internal/**` | `lms.sales.config.SecurityConfiguration` (`/internal/payments/webhook/simulated`) | Ngoài phạm vi, ghi để đủ danh sách |

## 10. Vấn đề cần xử lý trước khi expose API cho bên thứ ba

| | Việc | Lý do |
|---|---|---|
| ⏳ | Bắt buộc khóa ký PEM ở profile prod (fail-fast), và thiết kế JWKS nhiều khóa (khóa hiện hành + khóa cũ còn hiệu lực) | Rủi ro cao mục 9; client_credentials cần khóa ổn định |
| ⏳ | Thêm `jti` vào mọi token mới (không phá token cũ) | Truy vết audit, chuẩn RFC 9068 |
| ⏳ | Nhánh token integration trong `PermissionInterceptor` trước nhánh `AUTHENTICATED`; `ChannelAudienceFilter` của lms-api từ chối `aud=lms-integration`; customer-api đã từ chối (bắt buộc `lms-storefront`) | Rủi ro cao mục 9 |
| ⏳ | Kiểm khởi động prod cho `INTERNAL_API_KEY` mặc định ở identity và service (như `StartupConfigurationCheck` của customer-api) | Rủi ro vừa |
| ⏳ | Che thông điệp 403 nội bộ ở BFF integration (allowlist mã lỗi như `PublicExceptionHandler`) | Lộ tên khóa nội bộ |
| ⏳ | Xác nhận `real-ip.conf` cho môi trường có LB/CDN | Rate limit theo IP sai |
| ⏳ | Bổ sung vùng `public_preview` theo ADR-0011 2.7 | Thiết kế đã chốt, chưa làm |
| ⏳ | Chạy bù test cô lập tenant còn nợ (ADR-0006 "Kiểm chứng còn nợ") trước khi thêm bảng toàn cục mới | Chưa có bằng chứng xanh trong phiên này |

## 11. Đề xuất kiến trúc tích hợp (tóm tắt; chi tiết ở Design)

1. **Admin API (C)**: giữ nguyên `lms-api` + JWT STAFF + khóa động. Chỉ thêm API quản lý Application cho platform admin (`/api/platform/tenants/{id}/applications/**`, đi qua `IdentityGatewayController` sẵn có) và tenant admin (`/api/applications/**`, khóa động).
2. **Public API (A)**: ở lại `customer-api`. Site key **bổ sung** cho `{code}` (không thay ngay): site key xác định Application kiểu website → tenant + domain allowlist; customer-api vẫn xin token `svc` từ identity (mở rộng phản hồi cấp token với `applicationId`). Phiên khách tái dùng phiên STOREFRONT hiện có.
3. **Integration API (B)**: identity phát token `client_credentials` bằng khóa RS256 hiện có (không dùng Spring Authorization Server); `aud=lms-integration`, `scope`, `applicationId`, `jti`. Một BFF mới mỏng `integration-api` (khuôn customer-api) phục vụ `/api/integration/v1/**`; service kiểm scope bằng annotation mới song song `@StorefrontAccessible`.
4. **Dữ liệu**: bảng mới ở `identity_db` toàn cục (không RLS, vào `TENANT_EXEMPT_TABLES`) theo tiền lệ ADR-0013: `applications`, `application_credentials`, `application_scopes`, `application_history`; domain dùng lại `tenant_storefront_origins` (thêm `application_id` tùy chọn). Không tạo `tenants`, `api_sessions`; không tạo `api_audit_logs` theo request ở giai đoạn đầu.
5. **Rate limit**: nginx cho IP/mã/site key; Redis (fail-open) trong `integration-api` cho hạn mức theo application.

## 12. Phân loại API hiện có

Quy ước: **Public** = gọi từ internet không cần tài khoản nhân viên; **Integration** = backend bên ngoài; **Admin** = người dùng nội bộ qua `lms-api`; **Internal** = chỉ trong mạng (`/internal/**`, nginx trả 404). Annotation lấy bằng grep trên file controller (có thể ở cấp method).

### 12.1. customer-api (`/public/**`, cổng 8088)

| | Controller | Đường dẫn | Loại | Xác thực |
|---|---|---|---|---|
| ✅ | `site/web/SiteController` | `GET /public/site/pages[/{slug}]`, `POST /public/site/contact-requests` | Public | Không (CMS qua `@PublicEndpoint` của system) |
| ✅ | `storefront/web/ShopController` | `GET /public/t/{code}` | Public | Không; phía sau token `svc` |
| ✅ | `catalog/web/BookController` | `GET /public/t/{code}/books`, `/suggestions`, `/{bookRef}`, `/{bookRef}/preview` | Public | Không; token `svc` |
| ✅ | `banner/web/BannerController` | `GET /public/t/{code}/banners` | Public | Không; token `svc` |
| ✅ | `auth/web/AuthController` | `POST /public/t/{code}/auth/{signup,login,refresh,logout}`, `POST /public/auth/{refresh,logout}` (cũ) | Public | Không (tạo phiên STOREFRONT) |
| ✅ | `shop/web/OrderController` | `POST|GET /public/t/{code}/orders`, `GET /orders/{id}`, `POST /orders/{id}/cancellation` | Public (cần phiên) | JWT `aud=lms-storefront` + `ShopSessionGuard` |
| ✅ | `shop/web/ContactRequestController` | `POST /public/t/{code}/contact-requests` | Public (cần phiên) | như trên |
| ✅ | `reader/web/ReaderPortalController` (chưa track) | `GET /public/t/{code}/me/{borrowings,fines,reservations,reader-profile}`, `POST /me/reservations/{id}/cancellation` | Public (cần phiên) | như trên |

### 12.2. lms-api (`/api/**`, cổng 8085)

| | Controller | Tiền tố | Loại |
|---|---|---|---|
| ✅ | `identity/web/AuthController`, `ExternalAuthController`, `SessionController` | `/api/auth`, `/api/auth/sessions` | Admin (đăng nhập công khai, còn lại cần JWT) |
| ✅ | `identity/web/IdentityGatewayController` | `/api/platform/**` (chuyển nguyên), `/api/me/tenants`, `/api/storefront-origins` | Admin (platform) |
| ✅ | `identity/web/AccessController`, `UserController`, `DepartmentController`; `identity/imports/UserImportController` | `/api`, `/api/users`, `/api/departments`, `/api/identity/imports/users` | Admin |
| ✅ | `catalog/web/CatalogController`, `ReferenceCatalogController`; `catalog/imports/CatalogImportController` | `/api`, `/api/catalog/imports/{type}` | Admin |
| ✅ | `circulation/web/BorrowingController`, `ReservationController`, `FineController`, `CirculationPolicyController` | `/api/borrowings`, `/api/reservations`, `/api/fines`, `/api/circulation/policy` | Admin |
| ✅ | `sales/web/SalesOrderController`, `SalesPurchasingController`, `SalesReturnController`, `SalesStockController`, `SalesPaymentController`, `SalesBannerController`, `SalesContactRequestController`; `sales/imports/SalesImportController` | `/api/sales/**` | Admin |
| ✅ | `dashboard/web/DashboardController`, `reports/web/ReportController` | `/api/dashboard`, `/api/reports` | Admin |
| ✅ | `support/web/AuditLogController`, `NotificationController` | `/api/audit-logs`, `/api/notifications` | Admin |
| ✅ | `system/web/MenuController`, `MenuAdminController`, `CmsPageAdminController`, `CmsContactRequestAdminController` | `/api/menus`, `/api/admin/menus`, `/api/cms/**` | Admin |
| ❌ | (không có) | `/api/integration/**`, `/api/public/**` | Integration / Public v1: chưa có |

### 12.3. Service (`/internal/**`; identity thêm `/api/platform/**`)

| | Service | Controller (annotation đáng chú ý) | Loại |
|---|---|---|---|
| ✅ | identity | `AuthController` (`@PublicEndpoint` + `@AuthenticatedOnly` theo method), `ExternalAuthController`, `SessionController` (`@AuthenticatedOnly`), `JwksController` (`@PublicEndpoint`), `StorefrontAccessController` (`@PublicEndpoint`, bí mật riêng), `InternalPermissionController` (`@PublicEndpoint`, `X-Internal-Api-Key`) | Internal (đăng nhập/JWKS/cấp token là "công khai trong mạng") |
| ✅ | identity | `AccessControlController` (`@CrossTenantReadable`), `UserController` (`@AuthenticatedOnly`, `@CrossTenantReadable`), `ReaderProfileController` (`@AuthenticatedOnly`), `DepartmentController`, `UserImportController`, `MeTenantController` (`@AuthenticatedOnly`), `TenantStorefrontOriginController`, `TenantContractController` | Internal (đích của Admin qua lms-api) |
| ✅ | identity | `PlatformTenantController`, `PlatformTenantModuleController`, `PlatformOrganizationController`, `PlatformStorefrontOriginController`, `PlatformContractController`, `PlatformContractPaymentController`, `PlatformDashboardController` (đều `@PlatformAdminOnly`) | Admin (platform), map thẳng `/api/platform/**` |
| ✅ | catalog | `TitleController` (`@StorefrontAccessible` ở `lookup`, `lookupByKeyword`, `preview`; `@CrossTenantReadable` ở `search`), `AuthorController`, `CategoryController`, `PublisherController`, `ShelfLocationController` (`@RequiresModule(LENDING)`), `*ImportController`, `BookCopyController` (`LENDING`, `@CrossTenantReadable`), `CatalogStatsController` | Internal |
| ✅ | circulation | `BorrowingController`, `FineController`, `ReservationController`, `CirculationPolicyController`, `LendingReportController`, `LendingStatsController` (đều `LENDING`) | Internal |
| ✅ | sales | `StorefrontController`, `StorefrontBannerController` (`@StorefrontAccessible`), `ShopController`, `ShopContactController` (phiên khách), `SalesOrderController`, `PosOrderController`, `RefundController`, `SalesReturnController`, `StockItemController`, `StockAdjustmentController`, `GoodsReceiptController`, `SupplierController`, `SalesSettingsController`, `SalesBannerController`, `SalesContactController`, `SalesAuditController`, `SalesReportController`, `SalesStatsController`, `PaymentIntentController`, `*ImportController` (đều `SALES`); `PaymentWebhookController` (`@PublicEndpoint`, HMAC) | Internal |
| ✅ | support | `AuditLogController` (`@CrossTenantReadable`), `NotificationController` (`@AuthenticatedOnly`) | Internal |
| ✅ | system | `MenuController` (`@AuthenticatedOnly`), `MenuAdminController`, `CmsPublicController` (`@PublicEndpoint`), `CmsPageAdminController`, `ContactRequestAdminController`, `MigrationController` (`@PlatformAdminOnly`) | Internal |
| ❌ | mọi service | Không có endpoint nào đánh dấu cho token integration/scope | Integration: chưa có |

## 13. Điểm chưa kiểm chứng trong Audit

- ⚠️ Chưa chạy bất kỳ test nào (unit, WebMvc, IT) trong phiên này.
- ⚠️ Hành vi validator JWT mặc định của Spring Boot 3.5.6 khi chỉ có `jwk-set-uri` (kết luận "không kiểm `iss`/`aud`" dựa trên cấu hình, chưa thử token sai `iss`).
- ⚠️ `snippets/common.conf`, `security-headers.conf`, `real-ip.conf` và dòng image nginx trong `docker-compose.yml` chưa mở trực tiếp (dựa trên ADR-0013 1.1 và chú thích trong `nginx.conf`).
- ⚠️ `lms-api` `AuthController` có gộp mã lỗi khóa tài khoản hay không chưa đọc lại.
- ⚠️ Nhiều file nêu ở đây đang là thay đổi chưa commit của phiên khác; nếu phiên đó revert, một số nhận định (CORS động, kênh token, cổng độc giả) có thể không còn đúng.
