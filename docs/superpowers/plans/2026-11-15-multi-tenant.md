# Multi-tenant Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cho nhiều thư viện (tenant) dùng chung một hệ thống với dữ liệu cách ly, một tài khoản dùng ở nhiều tenant, và super admin quản lý tenant.

**Architecture:** Dùng chung database; mọi bảng thuộc tenant có `tenant_id`, lọc bằng Hibernate `@TenantId` và chặn lần hai bằng PostgreSQL Row-Level Security (fail-closed). Tenant lấy từ claim JWT `tenantId`, đặt vào `TenantContext` (thư viện mới `tenant-common`). Identity-service thêm bounded context `tenant`, cấp token theo từng tenant.

**Tech Stack:** Java 21, Spring Boot 3.5.6 (Hibernate 6), PostgreSQL 16, Flyway (chạy bởi `migration-service`), Kafka, MongoDB, Redis, Testcontainers, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-11-14-multi-tenant-design.md` (đọc kèm; số mục `§` trong plan trỏ tới spec).

## Global Constraints

- Làm trong worktree `.worktrees/multiple-tenant`, nhánh `multiple-tenant`. Máy không có `mvn` toàn cục: dùng Maven ở `C:\Users\kunph\AppData\Local\Temp\claude\faa311fcaffe5525\33856112-c206-4074-abda-da6fa7b6351f\scratchpad\maven-dl\apache-maven-3.9.9\bin\mvn` (gọi là `$MVN` dưới đây). Luôn `-pl <module> -am`.
- Test IT cần Docker: `-Dtest=<Tên> -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44`.
- Commit: message tiếng Việt có dấu, **không** có `Co-Authored-By` hay dòng attribution nào.
- Migration chỉ thêm file mới vào `services/migration-service/src/main/resources/db/migration/<target>/`, không sửa file cũ (đổi checksum). Số kế tiếp: identity `V11`, catalog `V2`, circulation `V6`, audit `V2` (kiểm tra lại thư mục trước khi đặt).
- Kiến trúc Hexagonal giữ nguyên: domain không Spring/JPA, application là POJO, ArchUnit phải qua; context chỉ dùng tầng `application` của context khác.
- Fail-closed: không có tenant trong ngữ cảnh thì không thấy dòng nào và không ghi được.
- Không cứng tên role hay id tenant trong code. Id tenant mặc định chỉ xuất hiện trong migration: `00000000-0000-0000-0000-0000000000d1`, mã `default`.
- Khóa permission `Controller/method` (ADR-0003) và danh mục `permissions` giữ **toàn cục**.
- Token cũ không có `tenantId` bị từ chối; không cấp token cho tenant SUSPENDED hoặc membership không ACTIVE.
- Nhiều agent chạy song song được ở các nhóm đánh dấu dưới đây; mỗi agent một phạm vi thư mục riêng, `shared-libraries/**` làm trước, không chạy Maven đồng thời trên cùng module.

## Review Focus

1. SQL native dưới RLS (upsert permission, truy vấn audit, join chi tiết bản sao): không trả dòng tenant khác; không có tenant thì trả rỗng, không lỗi nuốt. Test ở Task 6, 7, 8.
2. Việc nền và consumer Kafka không có request (`ExpireReservationsJob`, `InventoryEventConsumer`, `ReservationEventConsumer`, `AuditEventConsumer`, `AccessEventConsumer`): phải đặt tenant theo từng event hoặc từng tenant; thiếu tenant thì bỏ qua và log, không đọc/ghi toàn bộ. Test ở Task 9.
3. Membership bị tắt hoặc tenant bị tạm ngưng: refresh token và `switch-tenant` bị từ chối; token đang sống hết hạn tự nhiên. Test ở Task 4, 6.
4. Quản trị viên tenant tạo người dùng trùng username/email ở tenant khác: lỗi giống hệt trùng trong tenant, không lộ tài khoản thuộc tenant nào. Test ở Task 5.
5. Tài khoản thuộc hai tenant: quản trị viên một tenant không sửa họ tên/email và không đặt lại mật khẩu được. Test ở Task 5.
6. Gọi service-to-service (circulation → catalog/identity) bằng token tenant A chỉ nhận dữ liệu tenant A. Test ở Task 8.

---

## Nhóm và thứ tự

`T0 → T1 → T2 → T10` (tuần tự, nền dùng chung; Task 10 nằm ở đây vì consumer của T8, T9 cần `EventEnvelope.tenantId`) → `T3 → T4 → T5 → T6` (identity, tuần tự) song song với `T7`, `T8`, `T9` (catalog, circulation, support; chỉ cần T1, T2, T10) → `T11 → T12`. Ngoại lệ: phần consumer `TenantCreatedEvent` trong T8 làm sau khi T6 xong.

### Task 0: Khôi phục baseline xanh

**Files:** Modify `services/catalog-service/src/test/java/lms/catalog/catalog/unit/SearchTitlesUseCaseTest.java:36-45` (stub `BookCopyRepository`).

**Interfaces:** Produces: build `mvn test-compile` toàn dự án qua.

- [ ] **Step 1:** Chạy `$MVN -B -q test-compile -DskipTests`; Expected: FAIL `does not override abstract method findDetailsByBarcode(String)`.
- [ ] **Step 2:** Thêm `public Optional<CopyDetails> findDetailsByBarcode(String barcode) { return Optional.empty(); }` vào stub `BookCopyRepository` (kiểm chữ ký trong `BookCopyRepository.java`; nếu `main` có thêm phương thức trừu tượng khác thì thêm tương tự).
- [ ] **Step 3:** `$MVN -B test '-Dtest=*Test' -Dsurefire.failIfNoSpecifiedTests=false`; Expected: BUILD SUCCESS.
- [ ] **Step 4:** Commit `Sửa test catalog theo BookCopyRepository mới`.

### Task 1: Thư viện `tenant-common` và claim tenant

**Files:**
- Create: `shared-libraries/tenant-common/pom.xml` (thêm module vào pom gốc, dependencyManagement), package `lms.tenant`: `TenantContext.java`, `TenantRequiredException.java`, `TenantContextFilter.java`, `TenantIdentifierResolver.java`, `TenantAutoConfiguration.java` + `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; tests `TenantContextTest`, `TenantContextFilterTest`.
- Modify: `shared-libraries/security-common/.../JwtClaims.java` (thêm `TENANT_ID = "tenantId"`, `PLATFORM_ADMIN = "platformAdmin"`), `CurrentUser.java` (thêm `UUID tenantId`), `permission/PermissionInterceptor.java`, tạo `permission/PlatformAdminOnly.java` (annotation).

**Interfaces:**
- Produces: `TenantContext.current(): Optional<UUID>`; `TenantContext.require(): UUID` (ném `TenantRequiredException`); `TenantContext.runAs(UUID tenantId, Runnable r): void`; `TenantContext.callAs(UUID tenantId, Supplier<T> s): T`; `TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID>` (không có tenant thì trả UUID nil `00000000-0000-0000-0000-000000000000`, khớp 0 dòng); `@PlatformAdminOnly` (method/class). Thuộc tính `lms.tenant.enabled` (mặc định true; dev có thể tắt).

- [ ] **Step 1: Tests** — `TenantContextTest`: `runAs` đặt rồi khôi phục giá trị cũ kể cả khi ném lỗi; `require()` ném `TenantRequiredException` khi trống; context không rò sang thread khác. `TenantContextFilterTest`: JWT có `tenantId` → context được đặt trong request và xóa sau đó; JWT không có `tenantId` trên endpoint thường → 403 JSON `{"code":"TENANT_REQUIRED"}`; endpoint `@PublicEndpoint`/`@AuthenticatedOnly`/`@PlatformAdminOnly` không bị chặn vì thiếu tenant. Mở rộng `PermissionInterceptorTest`: `@PlatformAdminOnly` + claim `platformAdmin=true` qua, thiếu claim → 403 `{"code":"FORBIDDEN"}`; claim `platformAdmin` **không** thay thế quyền endpoint thường.
- [ ] **Step 2:** `$MVN -pl shared-libraries/tenant-common,shared-libraries/security-common -am test`; Expected: FAIL (lớp chưa có).
- [ ] **Step 3:** Cài đặt theo Interfaces. Filter chạy sau xác thực JWT; `TenantIdentifierResolver` đăng ký vào Hibernate qua `HibernatePropertiesCustomizer` (`hibernate.tenant_identifier_resolver`).
- [ ] **Step 4:** Chạy lại; Expected: PASS.
- [ ] **Step 5:** Commit `Thêm tenant-common: TenantContext, filter và claim tenantId`.

### Task 2: Vai trò DB và nền Row-Level Security

**Files:**
- Modify: `infrastructure/postgres/init-databases.sql` (tạo vai trò `<svc>_app` NOSUPERUSER NOBYPASSRLS, không phải chủ bảng), `docker-compose.yml` (service dùng `*_app`, `migration-service` dùng chủ), `application*.yml` của 4 service (dùng thông tin `*_app`), `services/migration-service/.../db/migration/<target>/` (script cấp quyền).
- Create: `shared-libraries/tenant-common/.../TenantRlsConnectionInitializer.java`, `TenantRlsIT.java`.

**Interfaces:**
- Produces: quy ước RLS cho mọi bảng thuộc tenant: `ALTER TABLE t ENABLE ROW LEVEL SECURITY; ALTER TABLE t FORCE ROW LEVEL SECURITY; CREATE POLICY tenant_isolation ON t USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid) WITH CHECK (same)`. `TenantRlsConnectionInitializer`: mỗi transaction đặt `app.tenant_id` bằng `TenantContext.current()` (trống nếu không có) trước câu lệnh đầu tiên. Cách gắn vào kết nối do người xây dựng chọn (`StatementInspector`, `ConnectionPreparer` hoặc `DataSource` wrapper).

- [ ] **Step 1: Test** `TenantRlsIT` (Testcontainers Postgres): bảng mẫu có RLS và hai dòng thuộc tenant A, B; kết nối bằng vai trò `app` (không chủ): (a) `runAs(A)` chỉ thấy dòng A; (b) không có tenant → 0 dòng; (c) `INSERT` với `tenant_id=B` khi context là A bị từ chối; (d) SQL native `select *` cũng chỉ thấy dòng A; (e) vai trò chủ bảng vẫn bị RLS vì `FORCE`.
- [ ] **Step 2:** `$MVN -pl shared-libraries/tenant-common -am test -Dtest=TenantRlsIT ...`; Expected: FAIL.
- [ ] **Step 3:** Cài `TenantRlsConnectionInitializer`, auto-config; cập nhật init SQL, compose, yml: chỉ `migration-service` giữ thông tin chủ; service chạy bằng `*_app` (script migration cấp `SELECT/INSERT/UPDATE/DELETE` trên bảng cho vai trò app).
- [ ] **Step 4:** Chạy lại; Expected: PASS. Chạy thêm `MigrationApiIT` để chắc script cấp quyền không phá migration.
- [ ] **Step 5:** Commit `Thêm vai trò DB ứng dụng và nền Row-Level Security`.

### Task 3: Mô hình tenant ở identity (dữ liệu, domain, tầng lưu)

**Files:**
- Create migrations identity: `V11__create_tenants_and_memberships.sql` (`organizations`, `tenants`, `tenant_memberships(user_id, tenant_id, department_id, status)`, `role_templates` + `role_template_permissions`, `credentials.platform_admin boolean default false`, `users.last_tenant_id`; tenant mặc định `00000000-0000-0000-0000-0000000000d1`/`default`; membership ACTIVE cho mọi user hiện có; `role_templates` khởi tạo từ 5 role seed), `V12__tenant_id_cho_bang_identity.sql` (`tenant_id` cho `roles`, `departments`, `reader_profiles`, `refresh_tokens`: thêm null → gán tenant mặc định → NOT NULL; chuyển `users.department_id` sang `tenant_memberships.department_id`; unique theo tenant: `roles(tenant_id, name)`, `departments(tenant_id, code)`, `reader_profiles(tenant_id, card_no)`, PK `reader_profiles(tenant_id, user_id)`; RLS theo quy ước Task 2 cho các bảng này và `tenant_memberships`).
- Create package `lms.identity.tenant`: `domain/{Tenant, TenantStatus, Organization, Membership, MembershipStatus, TenantRepository, MembershipRepository, OrganizationRepository, TenantNotFoundException, DuplicateTenantException}.java`, `infrastructure/persistence/*JpaEntity + adapters`, `infrastructure/config/TenantBeanConfiguration`.
- Test: `identity/tenant/unit/TenantTest`, `identity/tenant/architecture/TenantArchitectureTest`, `identity/integration/TenantMigrationIT`.

**Interfaces:**
- Produces: `Tenant.create(String code, String name, UUID organizationId): Tenant`; `Tenant.suspend()/activate()`; `MembershipRepository.findActiveByUser(UUID userId): List<Membership>`; `MembershipRepository.find(UUID userId, UUID tenantId): Optional<Membership>`; `TenantRepository.findByCode(String): Optional<Tenant>`; `UserRepository`/`users.last_tenant_id` đọc-ghi qua `MembershipRepository.saveLastTenant(UUID userId, UUID tenantId)` và `findLastTenant(UUID userId): Optional<UUID>`.

- [ ] **Step 1: Tests** — `TenantTest`: code hợp lệ `^[a-z0-9-]{2,50}$`, tạo mới `ACTIVE`, `suspend()` đổi trạng thái, code rỗng/ký tự lạ ném `InvalidTenantException`. `TenantMigrationIT` (Testcontainers, chạy V1–V10 rồi V11–V12 trên dữ liệu có sẵn user, role, phòng ban, hồ sơ độc giả): mọi dòng có `tenant_id = ...d1`, mọi user có membership ACTIVE, `roles` trùng tên ở hai tenant khác nhau được phép, `card_no` trùng giữa hai tenant được phép nhưng trùng trong một tenant bị chặn, `role_templates` có 5 mẫu.
- [ ] **Step 2:** `$MVN -pl services/identity-service -am test -Dtest=TenantTest,TenantMigrationIT ...`; Expected: FAIL.
- [ ] **Step 3:** Viết migration, domain, entity, adapter theo Interfaces. Entity JPA thuộc tenant gắn `@TenantId`; `tenant_memberships` và bảng toàn cục (`credentials`, `users`, `external_logins`, `permissions`) **không** gắn.
- [ ] **Step 4:** Chạy lại cùng với `IdentityArchitectureTest`; Expected: PASS.
- [ ] **Step 5:** Commit `Thêm mô hình tenant và nâng cấp dữ liệu identity`.

### Task 4: Token theo tenant, đăng nhập và đổi tenant

**Files:**
- Modify: `auth/application/{TokenService, UserAccessPort, TokenIssuerPort, AuthDtos, LoginUseCase, RefreshTokenUseCase}.java`, `auth/infrastructure/security/JwtTokenIssuer.java`, `auth/infrastructure/client/InProcessUserAccessAdapter.java`, `auth/infrastructure/web/AuthController.java`, `RefreshTokenJpaEntity`.
- Create: `tenant/application/{ResolveLoginTenantUseCase, SwitchTenantUseCase, ListMyTenantsUseCase}.java`, `tenant/infrastructure/web/MeTenantController.java`.
- Test: `identity/auth/unit/TokenServiceTenantTest`, `identity/tenant/unit/LoginTenantResolutionTest`, mở rộng `AuthSecurityTest`, `IdentityServiceIT`.

**Interfaces:**
- Produces: `ResolveLoginTenantUseCase.execute(UUID userId, String tenantCodeOrNull): TenantChoice` (`record TenantChoice(UUID tenantId, List<TenantSummary> available)`; ném `NoTenantAccessException`); `record TenantSummary(UUID id, String code, String name)`; `TokenIssuerPort.Claims` thêm `UUID tenantId, boolean platformAdmin`; `AuthDtos.LoginRequest(String username, String password, String tenantCode)`; `AuthDtos.TokenResponse` thêm `UUID tenantId, List<TenantSummary> availableTenants`; `POST /api/auth/switch-tenant` body `{tenantId}` → `TokenResponse`; `GET /api/me/tenants` → `List<TenantSummary>`. Claim `permissions` và `admin` chỉ của tenant đã chọn; claim `platformAdmin=true` khi credential có cờ. Refresh token gắn `tenant_id`.

- [ ] **Step 1: Tests** — `LoginTenantResolutionTest`: có `tenantCode` hợp lệ + membership ACTIVE → tenant đó; `tenantCode` sai hoặc không có membership → `NoTenantAccessException` **cùng thông điệp** (không lộ lý do); không có mã → `last_tenant_id` nếu còn ACTIVE, nếu không thì tenant ACTIVE có membership tham gia sớm nhất; tenant SUSPENDED bị bỏ qua; không còn tenant nào và không platform admin → `NoTenantAccessException`; platform admin không có membership → token có `platformAdmin`, không có `tenantId`. `TokenServiceTenantTest`: token chứa `tenantId`, `permissions` chỉ của role trong tenant đó, `admin` chỉ khi có role `isAdmin` ở tenant đó. `IdentityServiceIT`: login → đổi tenant lưu `last_tenant_id` và lần login sau vào đúng tenant; `switch-tenant` sang tenant không có membership → 403; refresh sau khi tenant bị `SUSPENDED` hoặc membership bị tắt → 401/403; token cũ không có `tenantId` bị từ chối ở endpoint thường.
- [ ] **Step 2:** Chạy; Expected: FAIL.
- [ ] **Step 3:** Cài đặt theo Interfaces; `JwtTokenIssuer` ghi claim mới; `SwitchTenantUseCase` kiểm membership + tenant ACTIVE, lưu `last_tenant_id`, cấp cặp token mới và thu hồi refresh token hiện tại.
- [ ] **Step 4:** Chạy lại (unit + `AuthSecurityTest` + `IdentityServiceIT`); Expected: PASS.
- [ ] **Step 5:** Commit `Cấp token theo tenant, đăng nhập theo mã tenant và đổi tenant`.

### Task 5: Cách ly accesscontrol, departments, users, reader profile ở identity

**Files:** Modify theo context: `accesscontrol/**` (role thuộc tenant; `RoleJpaEntity`, `UserRoleJpaEntity`, `GetUserAccessUseCase` lọc theo tenant), `department/**`, `user/**` (`UserController`, `CreateUserUseCase`, `UpdateUserUseCase`, `SearchUsersUseCase`, `IssueReaderCardUseCase`), entity JPA gắn `@TenantId`. Create `user/application/ResetUserPasswordUseCase.java`.
- Test: mở rộng `UserManagementTest`, `UserRoleUseCaseTest`, `DepartmentUseCasesTest`, `AccessControlPersistenceAdapterIT`, `IdentityServiceIT`; thêm `identity/integration/TenantIsolationIT`.

**Interfaces:**
- Consumes: `TenantContext.require()` (Task 1). Produces: `UserRepository.countMembershipsOf(UUID userId): int`; quy tắc §5.6: `UpdateUserUseCase`/`ResetUserPasswordUseCase` chỉ cho quản trị viên tenant khi `countMembershipsOf(userId) == 1`, người đó thuộc tenant của người gọi, và không phải platform admin; ngược lại ném `UserOperationNotAllowedException` (403). `CreateUserUseCase` tạo credential + user + membership trong tenant hiện tại; username/email đã tồn tại ở **bất kỳ đâu** ném `DuplicateUserException` với thông điệp chung. `IssueReaderCardUseCase` tạo tài khoản online cho độc giả (mật khẩu tạm, `mustChangePassword`) cùng hồ sơ trong tenant hiện tại.

- [ ] **Step 1: Tests** — `TenantIsolationIT` (hai tenant A, B): quản trị viên A `GET /api/users`, tìm kiếm, `GET /api/users/{id}` của người chỉ ở B → danh sách không có người B, `{id}` → 404; role, phòng ban, hồ sơ độc giả của B không thấy từ A, kể cả truy vấn native; role cùng tên tạo được ở cả hai tenant. `UserManagementTest`: (Review Focus 4) tạo user trùng username ở tenant khác → cùng lỗi với trùng trong tenant; (Review Focus 5) người thuộc hai tenant: sửa họ tên và đặt lại mật khẩu bởi quản trị viên A bị `UserOperationNotAllowedException`, người chỉ thuộc A thì được; quản trị viên không gán được role ngoài tenant mình; chỉ quản trị viên mới gán role `isAdmin` (hành vi hiện có, phạm vi tenant).
- [ ] **Step 2:** Chạy; Expected: FAIL.
- [ ] **Step 3:** Cài đặt; bỏ cột `department_id` khỏi `UserJpaEntity`/`User`, lấy phòng ban từ membership. `TokenService` đọc phòng ban từ membership của tenant được chọn.
- [ ] **Step 4:** Chạy toàn bộ test identity (`$MVN -pl services/identity-service -am test`) và các IT; Expected: PASS.
- [ ] **Step 5:** Commit `Cách ly dữ liệu identity theo tenant và quy tắc quản lý người dùng`.

### Task 6: Quản lý tenant cho super admin

**Files:**
- Create: `lms.identity.tenant.application/{CreateTenantUseCase, UpdateTenantUseCase, SetTenantStatusUseCase, SearchTenantsUseCase, GetTenantUseCase, ManageOrganizationUseCase, AddTenantAdminUseCase, RemoveTenantAdminUseCase, AttachUserToTenantUseCase}.java`, `tenant/infrastructure/web/{PlatformTenantController, PlatformOrganizationController, TenantExceptionHandler}.java` (đều `@PlatformAdminOnly`, `/api/platform/**`), tạo bản ghi audit thao tác nền tảng qua cổng `PublishPlatformEventPort`.
- Modify: `config/SecurityConfiguration` (`/api/platform/**` authenticated), bootstrap platform admin trong `BootstrapAdminConfiguration` (thuộc tính `auth.bootstrap.platform-admin-username/password`, bỏ trống thì không tạo).
- Test: `identity/tenant/unit/PlatformTenantUseCasesTest`, `identity/tenant/integration/PlatformTenantSecurityTest`, mở rộng `IdentityServiceIT`.

**Interfaces:**
- Produces (REST, §7): `POST /api/platform/tenants` body `CreateTenantRequest(String code, String name, UUID organizationId, NewAdmin newAdmin, UUID existingUserId)` → `TenantResponse(id, code, name, organizationId, status, memberCount)`; `GET /api/platform/tenants?keyword&status&organizationId&page&size` (record `TenantSearchRequest`) → `PageResponse<TenantResponse>`; `GET /api/platform/tenants/{id}`; `PUT /api/platform/tenants/{id}` (đổi tên, trường; **không** đổi `code`); `POST /api/platform/tenants/{id}/suspend|activate`; `/api/platform/organizations` CRUD cơ bản; `POST|DELETE /api/platform/tenants/{id}/admins`; `POST /api/platform/tenants/{id}/members` (gắn tài khoản có sẵn). Tạo tenant, sao chép `role_templates` thành role thuộc tenant mới (role mẫu có cờ admin thành role `isAdmin`), tạo dữ liệu khởi tạo (phát `TenantCreatedEvent(UUID tenantId)` lên topic `identity-events` để circulation tạo quy tắc mượn mặc định ở Task 8) và quản trị viên đầu tiên trong **một transaction**.

- [ ] **Step 1: Tests** — `PlatformTenantUseCasesTest`: tạo tenant tạo đủ 5 role từ mẫu, quản trị viên đầu tiên có role admin, `code` trùng → `DuplicateTenantException`, `suspend()` rồi `activate()`; mọi thao tác phát một sự kiện audit. `PlatformTenantSecurityTest` (WebMvc): token không có `platformAdmin` (quản trị viên tenant, người dùng thường) → 403 cho mọi endpoint `/api/platform/**`; có `platformAdmin` → 200/201. `IdentityServiceIT` (Review Focus 3): suspend tenant → login vào tenant đó bị từ chối, refresh bị từ chối, activate → vào lại được; super admin **không** đọc được dữ liệu nghiệp vụ trong tenant (gọi `/api/users` bằng token chỉ có `platformAdmin` → 403 `TENANT_REQUIRED`).
- [ ] **Step 2:** Chạy; Expected: FAIL.
- [ ] **Step 3:** Cài đặt theo Interfaces.
- [ ] **Step 4:** Chạy lại; Expected: PASS.
- [ ] **Step 5:** Commit `Thêm API quản lý tenant cho super admin`.

### Task 7: catalog-service theo tenant (song song với T3–T6)

**Files:** Create `migration-service/.../catalog/V2__tenant_id.sql` (thêm `tenant_id` cho `titles`, `book_copies`, gán tenant mặc định, NOT NULL, `uq_titles_isbn` thành `(tenant_id, isbn) WHERE isbn IS NOT NULL`, `barcode` unique thành `(tenant_id, barcode)`, RLS). Modify `TitleJpaEntity`, `BookCopyJpaEntity` (`@TenantId`), `SpringDataBookCopyRepository` (rà SQL native), `catalog/config/SecurityConfiguration` nếu cần; `catalog-service/pom.xml` (phụ thuộc `tenant-common`). Test: mở rộng `CatalogApiIT`, thêm `catalog/integration/CatalogTenantIsolationIT`.

**Interfaces:** Consumes `TenantContext` (Task 1), quy ước RLS (Task 2).

- [ ] **Step 1: Test** `CatalogTenantIsolationIT` (token tenant A, tenant B): cùng ISBN và cùng mã vạch tạo được ở hai tenant, trùng trong một tenant bị `DuplicateIsbnException`/`DuplicateBarcodeException`; `GET /api/titles/search` và `GET /api/copies/{id}` của dữ liệu tenant B từ token A → rỗng/404; truy vấn native `findDetailsByBarcode` không trả bản sao tenant khác (Review Focus 1); gọi không có `tenantId` → 403 `TENANT_REQUIRED`.
- [ ] **Step 2:** Chạy; Expected: FAIL.
- [ ] **Step 3:** Cài đặt migration + entity; rà mọi `nativeQuery` để đúng dưới RLS.
- [ ] **Step 4:** `$MVN -pl services/catalog-service -am test` và `CatalogApiIT,CatalogTenantIsolationIT`; Expected: PASS.
- [ ] **Step 5:** Commit `Cách ly dữ liệu catalog theo tenant`.

### Task 8: circulation-service theo tenant (song song)

**Files:** Create `migration-service/.../circulation/V6__tenant_id.sql` (`tenant_id` cho `borrowings`, `borrowing_items`, `reservations`, `fines`, `borrow_rules`; `borrow_rules` PK `(tenant_id, reader_type)`; chỉ mục unique một phần có `tenant_id`; tạo `borrow_rules` mặc định cho tenant mặc định; RLS). Modify các `*JpaEntity` (`@TenantId`), `SpringDataBorrowingRepository`, `SpringDataReservationRepository`, `circulation/reservation/infrastructure/client/{CatalogRestClientAdapter, IdentityRestClientAdapter}` (chuyển tiếp Bearer token như hiện nay, đủ để tenant đi theo token), cơ chế tạo `borrow_rules` cho tenant mới (consumer sự kiện `TenantCreatedEvent` của Task 6, đặt `TenantContext.runAs(event.tenantId())`). Test: mở rộng `BorrowingPersistenceAdapterIT`, `ReservationUseCasesTest`; thêm `circulation/integration/CirculationTenantIsolationIT`.

**Interfaces:** Consumes `TenantContext` và `TenantCreatedEvent(UUID tenantId)` do Task 6 phát lên topic `identity-events` (eventType `TenantCreated`, envelope mang `tenantId`).

- [ ] **Step 1: Test** `CirculationTenantIsolationIT`: mượn/đặt trước/phạt của tenant B không thấy từ token A; hàng đợi đặt trước và `uq_reservations_active` tính riêng từng tenant; quy tắc mượn đọc theo tenant; (Review Focus 6) circulation gọi catalog bằng token A chỉ nhận dữ liệu tenant A (dùng `MockWebServer` hoặc hai service thật trong IT); tạo tenant mới → có `borrow_rules` mặc định cho tenant đó.
- [ ] **Step 2:** Chạy; Expected: FAIL.
- [ ] **Step 3:** Cài đặt theo Files.
- [ ] **Step 4:** `$MVN -pl services/circulation-service -am test` + IT; Expected: PASS.
- [ ] **Step 5:** Commit `Cách ly dữ liệu circulation theo tenant`.

### Task 9: support-service (audit Postgres, Mongo, Redis) theo tenant (song song)

**Files:** Create `migration-service/.../audit/V2__tenant_id.sql` (`tenant_id` cho `audit_logs`, backfill tenant mặc định, RLS). Modify `AuditLogJpaEntity` (`@TenantId`), `SpringDataAuditLogRepository` (SQL native), `MongoRawEventArchive` (thêm trường `tenantId`, mọi truy vấn lọc theo `TenantContext`), `AuditEventConsumer`/`AuditEventParser` (đặt tenant từ event; thiếu tenant thì bỏ qua và log, Review Focus 2), khóa Redis nếu có (tiền tố `tenant:{id}:`). Test: mở rộng `SupportServiceIT`, `AuditUseCasesTest`; thêm `support/integration/AuditTenantIsolationIT`.

- [ ] **Step 1: Test** `AuditTenantIsolationIT`: audit event của tenant B không thấy từ token A (cả Postgres lẫn Mongo); event không có `tenantId` không ghi gì và không làm consumer lỗi; truy vấn native lọc đúng.
- [ ] **Step 2:** Chạy; Expected: FAIL. **Step 3:** Cài đặt. **Step 4:** `$MVN -pl services/support-service -am test` + `SupportServiceIT,AuditTenantIsolationIT`; Expected: PASS.
- [ ] **Step 5:** Commit `Cách ly dữ liệu audit theo tenant`.

### Task 10: Messaging mang tenant

**Files:** Modify `shared-libraries/messaging-common/.../EventEnvelope.java` (thêm `UUID tenantId`, constructor cũ giữ tương thích, `tenantId` lấy từ `TenantContext` khi publish), `KafkaEventPublisher.java`; consumers: `ReservationEventConsumer`, `InventoryEventConsumer`, `AccessEventConsumer`, `AuditEventConsumer`, job `ExpireReservationsJob` (lặp từng tenant ACTIVE qua `TenantContext.runAs`). Test: `KafkaEventPublisherTest`, `ExpireReservationsJobTenantTest`, `ConsumerTenantContextTest`.

**Interfaces:** `EventEnvelope` thêm thành phần `UUID tenantId` (nullable cho event hệ thống cũ). Consumer luôn bọc xử lý bằng `TenantContext.runAs(envelope.tenantId(), ...)`; `tenantId == null` → bỏ qua và log cảnh báo.

- [ ] **Step 1: Test** `KafkaEventPublisherTest`: envelope có `tenantId` bằng tenant đang chạy. `ConsumerTenantContextTest`: consumer đặt đúng tenant khi xử lý và xóa sau đó; event thiếu `tenantId` không gọi use case. `ExpireReservationsJobTenantTest`: job chỉ hết hạn đặt trước trong từng tenant, chạy lần lượt, thiếu tenant thì không đọc toàn bảng (Review Focus 2).
- [ ] **Step 2:** Chạy; Expected: FAIL. **Step 3:** Cài đặt. **Step 4:** Chạy `$MVN -pl shared-libraries/messaging-common,services/catalog-service,services/circulation-service,services/support-service -am test`; Expected: PASS.
- [ ] **Step 5:** Commit `Truyền tenantId qua event Kafka và việc nền`.

### Task 11: Thông báo đặt trước sẵn sàng nhận, lms-api và nginx

**Files:** Create trong `support-service` ngữ cảnh `notification`: `domain/Notification.java`, `application/{CreateNotificationUseCase, ListMyNotificationsUseCase}.java`, `infrastructure/{persistence, web/NotificationController, messaging/ReservationReadyConsumer}` (lưu thông báo trong DB, endpoint `GET /api/notifications/me`), migration `audit/` hoặc target mới theo DB mà support dùng cho `notifications(id, tenant_id, user_id, type, message, created_at, read_at)` kèm RLS. Modify `lms-api` (route `/api/platform/**`, `/api/me/tenants`, `/api/notifications/**` tới service đúng) và `infrastructure/nginx/nginx.conf` (`/api/platform/` chuyển tới identity; `/t/<code>` chỉ là đường dẫn FE, không cần xử lý ở nginx ngoài việc cho phép). Test: `NotificationUseCasesTest`, `ReservationReadyConsumerTest`, `LmsApiRoutingTest`.

- [ ] **Step 1: Test** — `ReservationReadyConsumerTest`: event `ReservationReadyEvent` của tenant A tạo đúng một thông báo cho độc giả trong tenant A (idempotent theo `eventId`), không hiện cho tenant B; `LmsApiRoutingTest`: `/api/platform/tenants` được chuyển tới identity với header `Authorization` nguyên vẹn.
- [ ] **Step 2:** Chạy; Expected: FAIL. **Step 3:** Cài đặt. **Step 4:** Chạy `$MVN -pl lms-api,services/support-service -am test`; Expected: PASS.
- [ ] **Step 5:** Commit `Thêm thông báo đặt trước sẵn sàng và route nền tảng ở lms-api`.

### Task 12: Dữ liệu ngoài Postgres, kiến trúc, tài liệu, kiểm chứng cuối

**Files:** Create `docs/decisions/0006-multi-tenant.md`, script một lần `infrastructure/mongo/backfill-tenant-id.js` (gán `tenantId` tenant mặc định cho bản ghi audit cũ) và `infrastructure/redis/README.md` (khóa cũ không tiền tố tự hết hạn theo TTL hoặc xóa); `shared-libraries/tenant-common` thêm `TenantArchitectureRules` dùng trong ArchUnit của từng service (entity thuộc tenant bắt buộc có `@TenantId`; không gọi `EntityManager.createNativeQuery`/`@Query(nativeQuery = true)` ngoài danh sách đã rà). Modify `README.md`, `CLAUDE.md` (mục phân quyền và migration: thêm tenant, token theo tenant, vai trò DB, cách chạy local với tenant mặc định), `docker-compose.yml` nếu còn thiếu. Test: `TenantArchitectureRulesTest` trong từng service.

- [ ] **Step 1: Test** — ArchUnit: mọi `*JpaEntity` có cột `tenant_id` phải có `@TenantId`, ngoại lệ có danh sách rõ ràng (`credentials`, `users`, `external_logins`, `permissions`, `tenants`, `organizations`); không có truy vấn native mới không nằm trong danh sách đã rà.
- [ ] **Step 2:** Chạy; Expected: FAIL nếu còn entity sót. **Step 3:** Hoàn thiện, viết tài liệu, chạy thử script Mongo trên bản sao dữ liệu cũ.
- [ ] **Step 4:** Chạy kiểm chứng toàn bộ: `$MVN -B test '-Dtest=*Test,*IT' -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44`; Expected: BUILD SUCCESS cả 13 module (đọc `Tests run` thật, không chỉ mã thoát).
- [ ] **Step 5:** Commit `Hoàn thiện multi-tenant: kiểm tra kiến trúc, script dữ liệu và tài liệu`.
