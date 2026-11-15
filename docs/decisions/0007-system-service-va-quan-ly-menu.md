# ADR-0007: Đổi `migration-service` thành `system-service` và thêm context quản lý menu (`system_db`)

- Trạng thái: đề xuất (chờ duyệt; chưa có code)
- Liên quan: ADR-0003 (phân quyền động), ADR-0004 (migration tập trung, được mở rộng bởi ADR này), ADR-0005 (BFF), ADR-0006 (multi-tenant)

## 1. Bối cảnh và mục tiêu

Yêu cầu đã chốt:

1. Đổi tên `migration-service` thành `system-service` (module, package, Docker, compose, tài liệu).
2. Quản lý menu cho FE (repo `library-management-system-fe`). Hiện FE đọc nav từ env `MFE_NAV_LINKS` (`packages/ui/src/nav-links.ts`: `[{href, label, permission?}]`, `Nav.tsx` lọc theo `session.admin || permissions.includes(permission)`). Menu mới lưu đường dẫn routing của FE, có cây cha-con, cấu hình quyền để hiển thị theo user, có cache và phá cache khi sửa.
3. Menu lưu ở DB riêng `system_db` (PostgreSQL), vai trò `system_app`, theo ADR-0006 (`tenant_id`, `@TenantId`, RLS FORCE, `afterMigrate`). Mọi user đăng nhập đọc được menu; chỉ quản trị sửa (khóa quyền động mặc định).

Hiện trạng đã đọc trong code (không suy từ tài liệu):

- `services/migration-service`: package `lms.migration.{domain,application,infrastructure,config}` (một context, không phẳng theo context như các service khác); `MigrationController` ở `/api/migrations` (chưa theo `/internal/**` của ADR-0005); `SecurityConfiguration` chỉ cho `/api/**` authenticated. Migration chạy trong `MigrationStartupRunner implements SmartInitializingSingleton` (sau khi mọi singleton được tạo). Flyway dùng DataSource tự dựng (`Flyway.configure().dataSource(url, user, pass)`), không phải bean Spring. Không có JPA, Redis, Kafka, tenant-common.
- Jar thường của `migration-service` là dependency `test` của identity, catalog, circulation, support (lấy SQL ở `classpath:db/migration/<target>`); jar thực thi có classifier `exec`.
- Không service nào dùng Redis (container `redis:7` có trong compose nhưng chưa ai kết nối). `database-common` có `RedisProvider` nhưng connection lỗi lúc khởi động bị đánh dấu `FAILED` vĩnh viễn (không tự nối lại).
- Nginx không có tham chiếu tới migration; `/api/**` đều vào `lms-api`; `lms-api` không có route migration. FE không gọi `/api/migrations`. Nghĩa là API migration hiện chỉ gọi được từ mạng nội bộ.
- Bảng `permissions` của identity là toàn cục (khóa `code` duy nhất, không theo tenant); đăng ký `ON CONFLICT (code) DO UPDATE SET service = COALESCE(permissions.service, ...)`: giữ `service`/`path_pattern` cũ.
- Mẫu "tenant mới nhận dữ liệu mặc định" đã có: circulation nghe `TenantCreated` (topic `identity-events`) và chạy `ProvisionDefaultBorrowRulesUseCase`.
- Tầng `application` được phép dùng `lms.tenant.TenantContext` (identity, circulation đang làm vậy).

Ràng buộc: không atomicity xuyên DB; domain Java thuần; application là POJO, chỉ dùng `@Transactional`; khóa quyền đổi khi đổi tên controller/method; `admin=true` bỏ qua kiểm khóa; quyền mới của role chỉ có hiệu lực ở lần đăng nhập/refresh kế tiếp.

## 2. Quyết định

### 2.1. Đổi tên và cấu trúc `system-service`

- Module `services/system-service`, artifactId `system-service`, `spring.application.name: system-service`, cổng **giữ 8086**.
- Package gốc `lms.system`, tách thành 2 context phẳng như các service khác:
  - `lms.system.migration.{domain,application,infrastructure}`: chuyển nguyên code `lms.migration.*` sang, không đổi logic.
  - `lms.system.menu.{domain,application,infrastructure}`: context mới.
  - `lms.system.config.SecurityConfiguration`, `lms.system.SystemServiceApplication`.
- Thư mục SQL **giữ nguyên đường dẫn** trong jar: `db/migration/{identity,catalog,circulation,audit}` và thêm `db/migration/system`. IT của các service khác không phải đổi location.
- API migration chuyển sang `/internal/migrations/**` (đúng ADR-0005; `SecurityConfiguration` chỉ còn `/internal/**` authenticated, còn lại denyAll). **Giữ nguyên tên lớp `MigrationController` và tên method** để khóa quyền (`MigrationController/statusAll`, `.../statusOf`, `.../runAll`, `.../runTarget`) không đổi, role đã gán không mất quyền.
- Cập nhật ADR-0004 bằng một dòng trỏ sang ADR này (không viết lại ADR cũ): ADR-0004 vẫn đúng về bản chất "runner Flyway tập trung"; ADR-0007 ghi phần thay đổi (tên, DB riêng, context nghiệp vụ).

Phương án đã loại:

| Phương án | Lý do loại |
|---|---|
| Tách menu thành service mới (`menu-service`, cổng mới), giữ `migration-service` | Thêm một container, một Dockerfile, một cấu hình bảo mật cho vài bảng; người dùng đã chốt gộp vào `system-service`. |
| Đổi cổng sang số mới | Không có lợi ích; chỉ làm hỏng script/hướng dẫn hiện có. |
| Giữ `/api/migrations` | Lệch ADR-0005; không ai ngoài mạng nội bộ gọi được nên đổi đường dẫn không phá FE. |
| Tách SQL ra module chỉ chứa tài nguyên (`db-scripts`) | Đúng về lâu dài nhưng thêm module và đổi 4 pom; giải quyết rủi ro classpath bằng exclusion (mục 3.6) là đủ. |

### 2.2. "Không có DB riêng" không còn đúng: system-service có `system_db`

- `system-service` giữ **hai vai trò tách biệt** trong một tiến trình:
  1. Runner migration: credential chủ bảng (`identity`, `catalog`, `circulation`, `audit`, và mới là `system`) cho 5 target, chỉ chạy DDL (ngoại lệ có chủ đích của ADR-0004 giữ nguyên).
  2. Context `menu`: DataSource Spring Boot chuẩn (`spring.datasource.*`) bằng vai trò `system_app` (NOSUPERUSER NOBYPASSRLS), chỉ trên `system_db`. Context `menu` **không bao giờ** đọc/ghi DB khác.
- `system_db` là target thứ 5 của chính runner (`migration.targets.system`), nên service "tự migrate DB của mình", cùng cơ chế với 4 DB còn lại, ghi sổ cái ở `system_db.flyway_schema_history`.
- **Bắt buộc đổi thứ tự khởi động**: hiện migration chạy ở `SmartInitializingSingleton`, tức là *sau* khi `EntityManagerFactory` được tạo. Với JPA `ddl-auto: validate`, DB trống sẽ làm khởi động hỏng trước khi kịp migrate. Quyết định: chuyển việc chạy migration lúc khởi động sang một bean khởi tạo (ví dụ `MigrationInitializer implements InitializingBean`) và khai báo `EntityManagerFactory` phụ thuộc bean này (cách Spring Boot làm với Flyway của nó: `EntityManagerFactoryDependsOnPostProcessor`). Health vẫn chỉ UP sau khi migrate xong.
  - Đã loại: đặt `ddl-auto: none` cho context menu (mất kiểm schema, lỗi lệch entity chỉ lộ ra lúc chạy).

### 2.3. Mô hình dữ liệu menu

Bảng `menus` trong `system_db` (một bảng, danh sách kề cha-con):

| Cột | Kiểu | Ghi chú |
|---|---|---|
| `id` | UUID PK | |
| `tenant_id` | UUID NOT NULL | `@TenantId`, RLS |
| `parent_id` | UUID NULL | gốc khi NULL; FK kép `(parent_id, tenant_id) → menus(id, tenant_id)` `ON DELETE RESTRICT` (cùng mẫu `borrowing_items` của circulation V6) |
| `code` | VARCHAR(100) NOT NULL | mã ổn định cho FE/seed/test; `UNIQUE (tenant_id, code)` |
| `label` | VARCHAR(200) NOT NULL | nhãn hiển thị |
| `path` | VARCHAR(500) NULL | route FE (ví dụ `/catalog`); NULL = nút nhóm (chỉ chứa con) |
| `icon` | VARCHAR(100) NULL | tên icon antd, tùy chọn |
| `sort_order` | INT NOT NULL DEFAULT 0 | thứ tự trong cùng cha; bằng nhau thì xếp theo `label` |
| `enabled` | BOOLEAN NOT NULL DEFAULT TRUE | tắt nút thì ẩn cả cây con |
| `permission_key` | VARCHAR(255) NULL | khóa `Controller/method` (ADR-0003); NULL = mọi người đăng nhập đều thấy |
| `version` | BIGINT NOT NULL DEFAULT 0 | `@Version`, khóa lạc quan khi hai quản trị viên cùng sửa |
| `created_at`, `updated_at` | TIMESTAMPTZ NOT NULL | |

Chỉ mục: `(tenant_id, parent_id, sort_order)`. RLS: `ENABLE` + `FORCE` + policy `tenant_isolation` đúng mẫu `TenantRlsConnectionInitializer` (bật RLS **sau** khi seed, như circulation V6).

Quy tắc nghiệp vụ (đặt ở `menu.domain`, không ở controller):

- `path` khi có phải bắt đầu bằng `/`, không chứa `//` ở đầu, không có scheme (`http:`, `javascript:`): chỉ route nội bộ của FE. Chặn link ngoài và chèn script qua dữ liệu menu.
- Cây không có chu trình: cha mới không được là chính nó hoặc con cháu của nó. Độ sâu tối đa 3 cấp.
- Nút nhóm (`path` NULL) phải có `label`; nút lá phải có `path`.
- Xóa nút còn con: từ chối (`MENU_HAS_CHILDREN`), không xóa dây chuyền ngầm.
- Kiểm bất biến cây cần cả cây của tenant: use case nạp toàn bộ mục của tenant (dự kiến vài chục, giới hạn 200 mục/tenant) vào aggregate `MenuTree` rồi thao tác.

Quy tắc hiển thị (`MenuTree.visibleFor(viewer)`, `viewer = {admin, permissions}` lấy từ JWT):

1. Mục bị loại nếu `enabled = false` hoặc cha của nó bị loại.
2. Mục có `permission_key` chỉ hiện khi `admin = true` hoặc khóa có trong claim `permissions`.
3. Nút nhóm (`path` NULL) chỉ hiện khi còn ít nhất một con hiện.
4. Kết quả sắp theo `sort_order`, rồi `label`.

Ẩn menu chỉ là trải nghiệm người dùng, **không phải** kiểm soát truy cập: quyền thật vẫn do `PermissionInterceptor` của từng service kiểm.

#### Gắn quyền: theo `permissionKey` hay theo role

| Tiêu chí | A. Theo `permissionKey` (chọn) | B. Theo role (bảng `menu_roles`) |
|---|---|---|
| Khớp mô hình hiện có | Đúng ADR-0003 và đúng cách FE đang lọc (`NavLink.permission`) | Thêm một ma trận phân quyền thứ hai song song với role→permission |
| Nhất quán "thấy menu thì gọi được API" | Có: cùng khóa mà service kiểm | Không: role thấy menu nhưng thiếu khóa thì bị 403, hoặc ngược lại |
| Dữ liệu chéo DB | Không cần: khóa là chuỗi, so với claim | Role sống ở `identity_db` (theo tenant): lưu id thì mồ côi khi xóa role, lưu mã thì hỏng khi đổi mã; không có FK xuyên DB |
| Admin (`admin=true`) | Bỏ qua tự nhiên, giống interceptor | Phải xử lý riêng |
| Đổi role/permission | Có hiệu lực ở token kế tiếp, không cần phá cache menu | Phải đồng bộ hoặc phá cache khi role đổi |
| Điểm yếu | Khóa đổi khi đổi tên controller/method (menu trỏ khóa cũ thì chỉ admin thấy); khóa chỉ có trong danh mục sau khi endpoint được gọi lần đầu | Trực quan hơn với người quản trị ("menu này cho Thủ thư") |

Chọn **A**, mỗi mục **một** `permission_key` (NULL = ai đăng nhập cũng thấy). Đã loại thêm: C. kết hợp cả hai (OR/AND) vì tăng độ phức tạp mà chưa có nhu cầu; nhiều khóa mỗi mục vì FE hiện chỉ có một khóa/link.

Không kiểm `permission_key` có tồn tại trong identity khi lưu (tránh phụ thuộc đồng bộ sang identity và khóa chưa được đăng ký vẫn hợp lệ); chỉ kiểm định dạng `^[A-Za-z0-9_]+/[A-Za-z0-9_]+$` hoặc khóa tùy chỉnh. FE quản trị nên cho chọn từ danh mục permission sẵn có (`lms-api` đã có API danh sách permission của identity).

### 2.4. API (qua `lms-api` theo ADR-0005)

```
FE ──/api/**──> nginx(8080) ──> lms-api(8085) ──/internal/**, Bearer chuyển tiếp──> system-service(8086)
```

`system-service` (nội bộ):

| Method | Đường dẫn | Controller/method (khóa) | Quyền |
|---|---|---|---|
| GET | `/internal/menus/me` | `MenuController/myMenu` | `@AuthenticatedOnly` |
| GET | `/internal/menus` | `MenuAdminController/listMenus` | khóa động |
| POST | `/internal/menus` | `MenuAdminController/createMenu` | khóa động |
| PUT | `/internal/menus/{id}` | `MenuAdminController/updateMenu` | khóa động |
| DELETE | `/internal/menus/{id}` | `MenuAdminController/deleteMenu` | khóa động |

`lms-api` (cho FE): `GET /api/menus/me`, `GET|POST /api/admin/menus`, `PUT|DELETE /api/admin/menus/{id}`, chuyển tiếp 1-1 qua `SystemClient` (interface + `HttpSystemClient`), DTO riêng cho FE. Phân quyền không kiểm ở `lms-api` (ADR-0005).

- Tên lớp `MenuController`/`MenuAdminController` phải duy nhất toàn hệ thống vì bảng `permissions` toàn cục (đã kiểm: chưa có lớp trùng tên). Đặt tên xong thì không đổi (đổi là mất quyền đã gán).
- Hai controller tách riêng để đọc (`@AuthenticatedOnly`) và quản trị (khóa động) không lẫn nhau. Quản trị viên (`admin=true`) dùng được ngay; người khác cần được gán các khóa `MenuAdminController/*`.

Hợp đồng chính (be-dev chốt chi tiết):

- `GET /api/menus/me` → `200 [MenuNode]`, `MenuNode = {id, code, label, path|null, icon|null, children: [MenuNode]}`. Không trả `permissionKey`, `enabled` (không lộ cấu hình). Token không có `tenantId` (platform admin) → `200 []`.
- `GET /api/admin/menus` → `200 [MenuAdminNode]`, thêm `parentId, sortOrder, enabled, permissionKey, version, updatedAt`, gồm cả mục đang tắt.
- `POST /api/admin/menus` body `{code, label, path?, icon?, parentId?, sortOrder?, enabled?, permissionKey?}` → `201 MenuAdminNode`.
- `PUT /api/admin/menus/{id}` body `{label, path?, icon?, parentId?, sortOrder, enabled, permissionKey?, version}` → `200 MenuAdminNode` (`code` không đổi được; đổi cha = di chuyển).
- `DELETE /api/admin/menus/{id}` → `204`.
- Lỗi (định dạng `ErrorResponse` hiện có): `404 MENU_NOT_FOUND`, `404 MENU_PARENT_NOT_FOUND`, `409 MENU_CODE_DUPLICATED`, `409 MENU_HAS_CHILDREN`, `409 MENU_VERSION_CONFLICT`, `400 MENU_CYCLE`, `400 MENU_TOO_DEEP`, `400 MENU_PATH_INVALID`, `400 MENU_PERMISSION_KEY_INVALID`, `400 MENU_LIMIT_EXCEEDED`, `400` lỗi validate; `403 TENANT_REQUIRED` cho API quản trị khi token không có tenant.

### 2.5. Cache

Chọn **Redis, cache-aside, khóa theo tenant, cache cây chưa lọc; lọc theo user ở mỗi request**.

- Khóa: `tenant:{tenantId}:system:menu:tree` (đúng tiền tố của `infrastructure/redis/README.md`). Giá trị: JSON danh sách mọi mục của tenant (kể cả mục tắt). TTL **10 phút** (lưới an toàn).
- Không cache theo user/role: tổ hợp `permissions` gần như mỗi người một khác, phá cache theo user rất khó; lọc vài chục mục trong bộ nhớ rẻ hơn một lần gọi Redis. Thêm nữa, đổi quyền của role không cần phá cache menu.
- Đọc: GET Redis → trúng thì dùng; trượt thì đọc DB (trong transaction chỉ đọc) rồi SET kèm TTL. Lỗi Redis bất kỳ (timeout ngắn, ví dụ 200 ms) → log WARN, đọc DB; **không** làm hỏng request.
- Phá cache: sau khi transaction ghi **commit** thì `DEL` khóa của tenant (adapter đăng ký `afterCommit` qua `TransactionSynchronizationManager` nếu có transaction; tầng application chỉ gọi port `evict(tenantId)`). Rollback thì không xóa. DEL lỗi → log WARN, TTL giới hạn thời gian cũ.
- Cấu trúc theo Hexagonal: port `MenuCachePort` (application, Java thuần: `get/put/evict` theo `UUID tenantId` và danh sách `MenuItem`); adapter `RedisMenuCacheAdapter` ở `menu.infrastructure.cache` (Jackson + `StringRedisTemplate` chỉ ở đây); `NoopMenuCacheAdapter` khi `lms.system.menu.cache.enabled=false` (dev/test). `domain` không biết cache; ArchUnit cấm `org.springframework..`, `com.fasterxml..` trong `..domain..`.
- Kết nối: `spring-boot-starter-data-redis` (Lettuce, autoconfig `spring.data.redis.*`), tắt health Redis (`management.health.redis.enabled: false`) để Redis chết không làm `system-service` DOWN (health của nó đang là cổng `service_healthy` cho mọi service khác).

Phương án đã loại:

| Phương án | Lý do loại |
|---|---|
| Cache trong bộ nhớ (Spring Cache + Caffeine/ConcurrentMap) | Đơn giản nhất nhưng nhiều instance thì chỉ instance nhận lệnh sửa được phá cache; các instance khác cũ tới hết TTL. Muốn đúng phải thêm broadcast (Kafka/pub-sub), rốt cuộc phức tạp hơn Redis. |
| Hai tầng (bộ nhớ + Redis + pub/sub phá cache) | Vượt nhu cầu với tải đọc menu. |
| `@Cacheable`/`@CacheEvict` của Spring Cache | Annotation trên use case vi phạm luật application chỉ dùng `@Transactional`; khó làm "evict sau commit" và "lỗi Redis thì rơi về DB" một cách tường minh. Port tự viết dễ test hơn. |
| `RedisProvider` của `database-common` | Connection lỗi lúc khởi động bị đánh dấu FAILED vĩnh viễn (không nối lại) nên Redis khởi động muộn hơn service là mất cache tới khi restart; dùng `database-common` còn buộc tắt autoconfig DataSource/JPA như support-service. Lettuce của Spring Boot tự nối lại, đủ cho một cache phụ. |
| Cache theo `(tenant, user)` hoặc `(tenant, tập role)` | Bùng nổ khóa, phá cache khi đổi role cần nghe event từ identity. |

Race đã biết: người đọc trượt cache đọc DB cũ, người sửa commit + DEL, rồi người đọc SET bản cũ → cũ tối đa 10 phút. Chấp nhận vì sửa menu hiếm. Nếu gặp thực tế thì nâng lên khóa có phiên bản (`...:menu:ver` + `INCR` sau commit), không làm ngay.

### 2.6. Multi-tenant và dữ liệu khởi tạo

- `system_db`, vai trò `system` (chủ DB, chỉ runner dùng) và `system_app` (NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE) thêm vào `infrastructure/postgres/init-databases.sql`. `db/migration/system/afterMigrate.sql` cấp quyền cho `system_app` đúng mẫu `audit/afterMigrate.sql` (kể cả `REVOKE ALL ON flyway_schema_history`).
- Entity `MenuJpaEntity` có `@TenantId`; `TenantArchitectureRulesTest` cho system-service; không dùng native query (danh sách đã rà để trống).
- `V1__create_menus.sql`: tạo bảng, seed menu mặc định cho tenant `00000000-0000-0000-0000-0000000000d1` tương ứng `DEFAULT_NAV_LINKS` của FE (Trang chủ `/`, Sách `/catalog`, Mượn trả `/circulation`, Quản trị `/admin` với khóa `AccessControlController/listRoles`), rồi mới bật RLS FORCE.
- Cấu hình `lms.security.permission.require-tenant: true` và `lms.tenant.enabled: true` cho system-service.
- Tenant mới tạo sau này: chưa có menu. Phase 1 chấp nhận (FE rơi về `MFE_NAV_LINKS` khi API trả rỗng hoặc lỗi). Đề xuất phase 2: nghe `TenantCreated` như circulation (`ProvisionDefaultMenusUseCase`, idempotent nhờ `UNIQUE (tenant_id, code)`, menu mặc định là hằng ở tầng application, khớp seed V1). Xem câu hỏi mở Q2.

## 3. Thiết kế

### 3.1. Thành phần

```
system-service (8086)
├── lms.system.SystemServiceApplication
├── lms.system.config.SecurityConfiguration      (/internal/** authenticated, còn lại denyAll)
├── lms.system.migration                          (chuyển từ lms.migration, logic giữ nguyên)
│   ├── domain / application (RunMigrationsUseCase, GetMigrationStatusUseCase, ports)
│   └── infrastructure/{flyway, config (MigrationInitializer + EMF depends-on), web (MigrationController: /internal/migrations)}
└── lms.system.menu
    ├── domain        MenuItem, MenuTree (bất biến cây, visibleFor), MenuViewer, MenuPath, lỗi nghiệp vụ
    ├── application   GetMyMenuUseCase, ListMenusUseCase, Create/Update/DeleteMenuUseCase,
    │                 MenuRepositoryPort, MenuCachePort
    └── infrastructure
        ├── persistence  MenuJpaEntity(@TenantId, @Version), SpringDataMenuRepository, MenuPersistenceAdapter
        ├── cache        RedisMenuCacheAdapter, NoopMenuCacheAdapter
        ├── web          MenuController, MenuAdminController, MenuExceptionHandler, DTO
        └── config       MenuBeanConfiguration
```

Luật ArchUnit cho system-service: luật cũ của migration áp cho `lms.system.migration`; `menu` và `migration` không phụ thuộc nhau; `domain` không Spring/jakarta/jackson; `application` không phụ thuộc `infrastructure`; Flyway chỉ ở `..migration.infrastructure.flyway..`; Redis/Jackson chỉ ở `..menu.infrastructure..`; `TenantArchitectureRules`.

### 3.2. Luồng đọc menu

```mermaid
sequenceDiagram
    participant FE
    participant API as lms-api
    participant SYS as system-service
    participant R as Redis
    participant DB as system_db (system_app, RLS)
    FE->>API: GET /api/menus/me (Bearer)
    API->>SYS: GET /internal/menus/me (Bearer chuyển tiếp)
    SYS->>SYS: TenantContext từ claim tenantId; viewer = {admin, permissions}
    SYS->>R: GET tenant:{t}:system:menu:tree
    alt trúng
        R-->>SYS: JSON
    else trượt hoặc Redis lỗi
        SYS->>DB: SELECT menus (RLS lọc tenant)
        SYS->>R: SET ... EX 600 (best-effort)
    end
    SYS->>SYS: MenuTree.visibleFor(viewer)
    SYS-->>API: [MenuNode]
    API-->>FE: [MenuNode]
```

### 3.3. Luồng sửa menu

`MenuAdminController` → use case (`@Transactional`, `TenantContext.require()`) nạp `MenuTree` của tenant → domain kiểm bất biến → lưu (`@Version`) → `MenuCachePort.evict(tenant)` (adapter hoãn tới `afterCommit`) → trả DTO.

### 3.4. Cấu hình và triển khai

- `application.yml` của system-service: thêm `migration.targets.system` (`SYSTEM_DB_URL/USERNAME/PASSWORD`, mặc định `system`/`system`, `classpath:db/migration/system`, khai báo **cuối** danh sách); `spring.datasource` (`DB_URL`, `DB_USERNAME=system_app`, `DB_PASSWORD`), `spring.jpa.hibernate.ddl-auto: validate`, `spring.flyway.enabled: false` (Flyway autoconfig của Boot không được chạy, runner tự lo); `spring.data.redis.host/port` (`REDIS_HOST`, `REDIS_PORT`), `management.health.redis.enabled: false`; khối `lms:` thêm `tenant` và `require-tenant` (sửa trong khối `lms:` sẵn có, không tạo khối thứ hai).
- `docker-compose.yml`: đổi service `migration-service` → `system-service` (dockerfile, healthcheck 8086 giữ), thêm `SYSTEM_DB_*`, `DB_*` (system_app), `REDIS_HOST: redis`, `depends_on: redis` (`service_started`); mọi `depends_on: migration-service` của identity/catalog/circulation/support đổi tên; `lms-api` thêm `SYSTEM_SERVICE_URL: http://system-service:8086` và `depends_on`. Nginx không đổi.
- `lms-api`: `ServicesProperties` thêm `systemUrl` (`app.services.system-url: ${SYSTEM_SERVICE_URL:http://localhost:8086}`), `SystemClientConfiguration`.
- Volume Postgres cũ không có `system_db`: `docker compose down -v` hoặc chạy tay phần `system` của `init-databases.sql`.

### 3.5. Danh sách điểm phải đổi khi đổi tên (đã grep, bỏ qua `target/` và `.worktrees/`)

| Nhóm | File/điểm |
|---|---|
| Maven | `pom.xml` gốc (`<module>services/migration-service</module>`); `services/migration-service/pom.xml` (artifactId, name); dependency `migration-service` (test) trong pom của identity, catalog, circulation, support |
| Mã nguồn | Toàn bộ `lms.migration.*` (main + test: `MigrationArchitectureTest`, `MigrationUseCasesTest`, `MigrationSecurityTest`, `MigrationApiIT`) → `lms.system.migration.*`; `MigrationServiceApplication` → `SystemServiceApplication`; `@AnalyzeClasses(packages = "lms.migration")` |
| Cấu hình | `application.yml` (`spring.application.name`), `application-dev.yml` (`logging.level.lms.migration`) |
| Docker | `services/system-service/Dockerfile` (`-pl services/system-service`, tên jar `system-service-*-exec.jar`), `docker-compose.yml` (service, comment, 4 `depends_on`) |
| SQL | 4 `afterMigrate.sql` và `init-databases.sql`: chỉ comment nhắc "migration-service". Sửa comment trong `afterMigrate.sql` được vì callback không ghi vào `flyway_schema_history` (không có checksum); file `V<n>` đã chạy thì không sửa |
| Comment ở service khác | `application.yml` của identity, catalog, circulation, support; `support-service/pom.xml`; `SupportServiceIT`; `AuditBeanConfiguration` |
| Tài liệu | `README.md`, `CLAUDE.md`, ADR-0004 (dòng trỏ), ADR-0006 (nhắc migration-service, cập nhật khi code xong), `docs/architecture/multi-database-report.md`, `.claude/agents/be-dev.md`, `.claude/agents/sa.md`, `.claude/skills/team-workflow/SKILL.md`; `docs/superpowers/**` là lịch sử, không sửa |
| Không đụng | `.worktrees/` (gitignore), `target/` (chạy `mvn clean` sau khi đổi) |

### 3.6. Rủi ro classpath khi system-service có thêm JPA/Redis/tenant-common

Jar `system-service` là dependency `test` của 4 service. Thêm `spring-boot-starter-data-redis` vào system-service sẽ kéo Redis autoconfig (và health Redis) vào IT của identity/catalog/circulation/support. Quyết định: ở 4 pom đó khai báo dependency `system-service` kèm exclusion wildcard (`<exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion>`): IT chỉ cần file SQL. Lớp `lms.system.*` có trên classpath nhưng không bị quét (base package của từng service khác nhau).

## 4. Phân việc

Thứ tự phụ thuộc: B1 → (B2 ‖ B3) → B4 → B5; T và P chạy theo.

| # | Ai | Việc | Phụ thuộc |
|---|---|---|---|
| ⏳ B1 | be-dev | Đổi tên thuần (mục 3.5), chuyển `lms.migration` → `lms.system.migration`, `/api/migrations` → `/internal/migrations`, SecurityConfiguration `/internal/**`, exclusion wildcard ở 4 pom, compose/Dockerfile, README/CLAUDE.md/ADR-0004. Không đổi hành vi. Commit riêng (`refactor(system)!:` vì đổi đường dẫn API nội bộ) | không |
| ⏳ B2 | be-dev | Hạ tầng DB: `init-databases.sql` (`system`, `system_db`, `system_app`), target `system`, `db/migration/system/V1__create_menus.sql` + `afterMigrate.sql`, `MigrationInitializer` + EMF depends-on, `spring.datasource`/JPA/tenant-common | B1 |
| ⏳ B3 | be-dev (song song B2, khác file) | `menu.domain` + `menu.application` + unit test (`MenuTree`: chu trình, độ sâu, ẩn cha khi con ẩn, path không hợp lệ, admin bỏ qua khóa) | B1 |
| ⏳ B4 | be-dev | `menu.infrastructure` (persistence, cache Redis + Noop, web, exception handler), ArchUnit, compose Redis env | B2, B3 |
| ⏳ B5 | be-dev (có thể giao agent riêng, chỉ `lms-api/`) | `SystemClient`, `MenuController`/`MenuAdminController` ở lms-api, DTO, `ServicesProperties.systemUrl` | hợp đồng mục 2.4 (bắt đầu song song B4 được) |
| ⏳ T1 | tester | Sau B1: toàn bộ `mvn test` + `MigrationApiIT` + IT của 4 service (xác nhận exclusion không làm thiếu SQL) | B1 |
| ⏳ T2 | tester | Sau B4: WebMvc (bật `lms.security.permission.enabled`, `@MockitoBean PermissionKeyRegistrar`): user thường đọc `/me` 200, sửa 403; admin sửa 200. IT Testcontainers Postgres + Redis: cách ly 2 tenant (RLS bằng `system_app`), phá cache sau commit, không phá khi rollback, Redis tắt thì vẫn 200 từ DB, `@Version` xung đột 409, khởi động trên DB trống (thứ tự migrate trước EMF) | B4 |
| ⏳ T3 | tester | Đầu-cuối qua nginx: `GET /api/menus/me` với token tenant mặc định | B5 |
| ⏳ P1 | perf-tester | `GET /api/menus/me` khi cache trúng/trượt, có/không Redis; mục tiêu tham khảo p95 < 50 ms ở system-service khi trúng cache (⚠️ chưa có số đo nền) | B5 |
| ⏳ F | (repo FE, ngoài phạm vi BE) | `Nav` gọi `GET /api/menus/me`, rơi về `MFE_NAV_LINKS` khi lỗi/rỗng; màn quản trị menu | B5 |

## 5. Rủi ro và việc chưa kiểm chứng

- ⚠️ Thứ tự khởi động: chưa kiểm chứng việc cho `EntityManagerFactory` phụ thuộc bean migration trong khi Flyway autoconfig của Boot bị tắt; cần IT khởi động trên `system_db` trống.
- ⚠️ `CREATE USER system`: `SYSTEM` là từ khóa không dành riêng của PostgreSQL nên dự kiến dùng được làm tên vai trò; chưa chạy thử. Nếu lỗi, đổi tên chủ DB thành `system_owner` (chỉ ảnh hưởng init SQL và `SYSTEM_DB_USERNAME`).
- ⚠️ Exclusion wildcard ở dependency test: chưa chạy IT của 4 service sau khi đổi.
- ⚠️ Hibernate `@TenantId` + `@Version` + FK tự tham chiếu kép `(parent_id, tenant_id)`: chưa kiểm chứng; ADR-0006 đã ghi các giả định Hibernate tương tự còn nợ IT.
- ⚠️ Bảng `permissions` giữ `service = 'migration-service'` và `path_pattern = /api/migrations/...` cũ cho các khóa `MigrationController/*` (đăng ký dùng `COALESCE` giữ giá trị cũ). Chỉ là siêu dữ liệu sai, không ảnh hưởng quyền; nếu cần đúng thì một migration identity mới cập nhật 4 dòng đó (kiểm số `V<n>` kế tiếp).
- ⚠️ Tenant admin (`admin=true`) hiện chạy được `POST /internal/migrations/run` lên **mọi** DB (khóa thường, admin bỏ qua). Đây là quyền cấp nền tảng; xem Q1.
- ⚠️ `require-tenant: true` làm API migration (nếu vẫn dùng khóa thường) đòi token có `tenantId`: platform admin (không tenant) sẽ bị 403. Chỉ ổn nếu Q1 chọn `@PlatformAdminOnly`.
- ⚠️ Profile dev tắt security → không có JWT, không có tenant → `GET /internal/menus/me` trả rỗng và ghi menu bị chặn (fail-closed, như ADR-0006). Dev cần Bearer token.
- ⚠️ Race cache (mục 2.5): dữ liệu cũ tối đa bằng TTL 10 phút trong trường hợp hiếm.
- ⚠️ `permission_key` trỏ khóa đã bị đổi tên (đổi tên controller/method) → chỉ admin thấy mục đó; không có cảnh báo tự động. Người quản trị phải sửa menu cùng lúc với khi gán lại quyền.
- ⚠️ Health: thêm DataSource `system_app` làm `/actuator/health` phụ thuộc `system_db`; Redis đã được loại khỏi health. Toàn bộ stack vẫn chờ `system-service` healthy (như trước).
- ⚠️ Không atomicity xuyên DB vẫn giữ: migrate 5 target là 5 lần chạy độc lập.
- ℹ️ Nhiều instance `system-service` cùng chạy migrate lúc khởi động: Flyway khóa bằng advisory lock của PostgreSQL nên an toàn; trước đây ít khi scale runner, nay đọc menu có thể cần scale.
- ℹ️ Menu ẩn không thay thế phân quyền API.

## 6. Câu hỏi mở (cần người dùng quyết)

- Q1. API migration nên chuyển sang `@PlatformAdminOnly` (chỉ quản trị nền tảng) thay vì khóa động? Khuyến nghị: **có**, làm trong commit riêng sau B1, vì migrate chạm mọi DB của mọi tenant. Hệ quả: khóa `MigrationController/*` không còn dùng.
- Q2. Tenant mới có cần tự nhận menu mặc định (nghe `TenantCreated`, thêm Kafka vào system-service) ngay đợt này? Khuyến nghị: **phase 2**; phase 1 FE rơi về `MFE_NAV_LINKS` khi menu rỗng.
- Q3. Có cần link ngoài (URL tuyệt đối) trong menu không? Khuyến nghị: **không** (chỉ route nội bộ bắt đầu bằng `/`), thêm sau nếu có nhu cầu kèm danh sách domain cho phép.
- Q4. Nhãn menu có cần đa ngôn ngữ? Khuyến nghị: **không** ở đợt này; `code` ổn định để FE tự dịch nếu cần.
