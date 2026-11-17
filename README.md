# Library Management System

Hệ thống quản lý thư viện: Java 21, Spring Boot 3.5.x, Maven, PostgreSQL, Kafka, Redis.
Kiến trúc: Microservices + DDD + Hexagonal + Clean Architecture + RBAC.

## Cấu trúc

| Thư mục | Nội dung |
|---|---|
| `infrastructure/nginx/` | Nginx: cổng vào duy nhất (8080): CORS, header bảo mật, giới hạn tốc độ đăng nhập, chặn `/internal` |
| `lms-api/` | BFF: service duy nhất đưa API ra cho FE (cổng 8085, nội bộ), gọi các service qua API `/internal/**` |
| `customer-api/` | BFF thứ hai, công khai cho khách (cổng 8088, nội bộ, không DB): trang giới thiệu, form liên hệ, đăng nhập storefront; FE gọi `/public/**` qua nginx (ADR-0011) |
| `services/` | 4 microservice lõi và `sales-service` (module bán hàng, ADR-0010), mỗi service một database riêng, cộng `system-service` (runner Flyway tập trung, giữ mọi file SQL) |
| `shared-libraries/common-kernel` | Contract dùng chung (`DomainEvent`, `ErrorResponse`) |
| `shared-libraries/security-common` | Danh mục permission, tên claim JWT, converter claim `permissions` |
| `shared-libraries/messaging-common` | `EventEnvelope` và `KafkaEventPublisher` |
| `shared-libraries/observability-common` | `X-Correlation-Id` filter (MDC `correlationId`) |
| `shared-libraries/database-common` | Quản lý nhiều kết nối DB (PostgreSQL, Oracle, MongoDB, Redis) |
| `infrastructure/`, `deployment/`, `docs/` | Script Postgres, Kubernetes/Helm (trống), tài liệu và ADR |

## Services

Mỗi service chứa nhiều bounded context; mỗi context là một package gốc có `domain/`, `application/`,
`infrastructure/`. Context chỉ được dùng API (tầng `application`) của context khác, có ArchUnit kiểm tra.

| Service | Cổng | Database | Context | Trạng thái |
|---|---|---|---|---|
| identity-service | 8081 | identity_db | `auth`, `accesscontrol`, `user`, `department` | `auth`, `accesscontrol` và `user` (hồ sơ độc giả, UC10) đã làm; `department` là khung |
| catalog-service | 8082 | catalog_db | `catalog`, `inventory` | đã làm: đầu sách, tìm kiếm (UC02), nhập bản sao (UC11 một phần) |
| circulation-service | 8083 | circulation_db | `borrowing`, `reservation`, `fine` | `borrowing` và `reservation` (UC03, UC04) đã làm; `fine` là khung |
| support-service | 8084 | `audit_db` (PostgreSQL) + `LOG_DB` (MongoDB) | `notification`, `audit` | `audit` đã làm (dùng `database-common`); `notification` là khung |
| sales-service | 8087 | sales_db (vai trò `sales_app`, RLS) | `stock`, `ordering`, `settings` (module SALES, ADR-0009/0010) | khung dịch vụ và schema V1 (B1.1); chưa có controller nghiệp vụ |
| system-service | 8086 | `system_db` (vai trò `system_app`, RLS) cho context `menu`; ngoài ra chỉ chạy DDL lên `identity_db`, `catalog_db`, `circulation_db`, `audit_db`, `sales_db` | `migration` (runner), `menu` (đang làm, ADR-0007) | runner migration tập trung, xem bên dưới |

### system-service (migration tập trung)

Giữ toàn bộ file SQL Flyway ở `services/system-service/src/main/resources/db/migration/{identity,catalog,circulation,audit,system,sales}/`
và chạy Flyway lên từng DB lúc khởi động (cấu hình qua `IDENTITY_DB_*`, `CATALOG_DB_*`, `CIRCULATION_DB_*`, `AUDIT_DB_*`, `SYSTEM_DB_*`, `SALES_DB_*`).
`/actuator/health` chỉ UP sau khi migrate xong. Sổ cái là bảng `flyway_schema_history` sẵn có trong từng DB. `system_db` là target thứ 5 của chính runner; context `menu` kết nối DB đó bằng `spring.datasource.*` (`DB_URL`, `DB_USERNAME=system_app`, `DB_PASSWORD`) và chỉ đọc/ghi `system_db`. Migration chạy trong bean khởi tạo mà `EntityManagerFactory` phụ thuộc, nên DB trống vẫn khởi động được. API migration là `@PlatformAdminOnly` (token `platformAdmin=true`, tenant admin bị 403).
Các service khác không chạy Flyway, chỉ dùng schema đã có. API tra cứu/chạy lại (target: `identity`, `catalog`, `circulation`, `audit`, `system`, `sales`):

| API | Ý nghĩa |
|---|---|
| `GET /internal/migrations` | Trạng thái migration của mọi target |
| `GET /internal/migrations/{target}` | File nào đã chạy trên một target |
| `POST /internal/migrations/run` | Chạy migrate cho mọi target |
| `POST /internal/migrations/{target}/run` | Chạy migrate cho một target |

Quyết định và đánh đổi: `docs/decisions/0004-migration-service-tap-trung.md`.

### Phân quyền (RBAC: Role-Based Access Control, kiểm soát truy cập theo vai trò)

Chú thích thuật ngữ dùng trong mục này:

| Thuật ngữ | Tên đầy đủ | Ý nghĩa ngắn gọn |
|---|---|---|
| RBAC | Role-Based Access Control | Cấp quyền theo vai trò: người dùng có role, role chứa permission |
| Role | Vai trò | Nhóm quyền, ví dụ `SUPER_ADMIN`, `LIBRARIAN`, `MEMBER` |
| Permission | Quyền cụ thể (khóa quyền) | Mỗi endpoint có một khóa dạng `TênController/tênPhươngThức`, ví dụ `BorrowingController/borrow`; kiểm tra theo khóa này, không theo tên role |
| `isAdmin` | Cờ role quản trị | Ô tích khi tạo role: người dùng có ít nhất một role `isAdmin` được bỏ qua kiểm tra khóa quyền; nằm trong dữ liệu, không hardcode tên role |
| JWT | JSON Web Token | Token đăng nhập chứa thông tin người dùng và quyền, có chữ ký |
| Access token | Token truy cập | JWT ngắn hạn (15 phút) gửi kèm mỗi request dạng `Authorization: Bearer <token>` |
| Refresh token | Token làm mới | Token dài hạn (7 ngày) để đổi lấy access token mới, chỉ dùng được một lần |
| Claim | Trường dữ liệu trong JWT | Ví dụ `roles`, `permissions`, `departmentId` |
| RS256 | RSA Signature with SHA-256 | Thuật toán ký JWT bằng khóa bất đối xứng: auth giữ khóa riêng, các service chỉ cần khóa công khai |
| JWKS | JSON Web Key Set | Danh sách khóa công khai để xác minh chữ ký, công bố tại `/.well-known/jwks.json` |
| Resource server | Máy chủ tài nguyên | Service tự xác minh JWT và tự kiểm tra quyền, không hỏi lại identity-service |
| Authority | Quyền trong Spring Security | Mỗi permission trong claim `permissions` trở thành một authority |
| `auth` / `accesscontrol` | Ngữ cảnh xác thực / ngữ cảnh phân quyền | Hai bounded context trong `identity-service` |

- `accesscontrol` quản lý Role, Permission, gán role cho người dùng; seed sẵn 5 role (`SUPER_ADMIN` được đánh dấu `isAdmin` ở migration V9).
- **Phân quyền động theo endpoint** (`security-common`, middleware `PermissionInterceptor`): không cần khai báo hằng số hay
  `hasAuthority(...)`. Mỗi controller method tự có khóa quyền `Controller/method`. Lần đầu endpoint được gọi (sau khi đã
  đăng nhập), service báo khóa về identity-service qua API nội bộ `/internal/permissions/registrations` (khóa chia sẻ
  `INTERNAL_API_KEY`), identity lưu vào bảng `permissions` (nguồn `AUTO`, kèm service, HTTP method, đường dẫn). Khóa mới
  mặc định bị từ chối với người không phải admin cho tới khi người quản trị gán nó cho một role.
  Endpoint chỉ cần đăng nhập đánh dấu `@AuthenticatedOnly`, endpoint công khai đánh dấu `@PublicEndpoint`; `@RequirePermission("KEY")`
  chỉ dùng khi BE chủ đích đặt khóa riêng. Dev tắt bằng `lms.security.permission.enabled=false`.
- Chỉ quản trị viên mới tạo được role `isAdmin` hoặc gán/thu hồi role đó (chống người chỉ có quyền gán role tự nâng quyền).
- `auth` đăng nhập, phát hành access token RS256 chứa `roles`, `permissions`, `departmentId`; refresh token
  xoay vòng (dùng lại token cũ thì thu hồi mọi phiên); khóa tài khoản sau 5 lần sai; JWKS tại
  `/.well-known/jwks.json`.
- Các service khác là resource server: đọc claim `permissions` thành authority (`security-common`) và
  kiểm tra khóa của endpoint có trong claim hay không, không gọi lại identity-service mỗi request. Quyền mới có hiệu lực ở lần
  đăng nhập hoặc làm mới token kế tiếp (access token 15 phút).
- Khi role bị thu hồi, `RoleRevokedEvent` làm `auth` thu hồi refresh token của người dùng đó.
- Chạy local (profile `dev`): tài khoản `admin` / `Admin@12345` (role SUPER_ADMIN). Chỉ dùng ở dev.

### Multi-tenant (ADR-0006)

Nhiều thư viện (tenant) dùng chung DB: bảng nghiệp vụ có `tenant_id`, entity dùng `@TenantId`, PostgreSQL bật Row-Level Security (fail-closed). Quyết định và đánh đổi: `docs/decisions/0006-multi-tenant.md`.

- Token (JWT) theo tenant: claim `tenantId`; `permissions`/`admin` tính theo role của tenant đó. Thiếu `tenantId` thì 403 `TENANT_REQUIRED` (trừ endpoint `@AuthenticatedOnly` như `switch-tenant`, `/api/me/tenants`).
- Đổi tenant: `POST /api/auth/switch-tenant` body `{tenantId, refreshToken}` (bắt buộc có `refreshToken`).
- Service chạy bằng vai trò DB `<svc>_app` (không phải chủ bảng, chịu RLS); `system-service` chạy bằng chủ bảng. Vai trò tạo trong `infrastructure/postgres/init-databases.sql`: volume Postgres cũ cần `docker compose down -v` (hoặc chạy tay SQL đó) rồi mới `up`; sau khi pull thay đổi migration cần `mvn clean`.
- Chạy local dùng tenant mặc định `00000000-0000-0000-0000-0000000000d1`. Profile dev tắt security thì không có JWT nên không có tenant; ghi vào bảng tenant sẽ bị chặn (fail-closed), cần Bearer token từ identity.
- Dữ liệu cũ ngoài Postgres: `infrastructure/mongo/backfill-tenant-id.js` (Mongo), `infrastructure/redis/README.md` (Redis).
- Giới hạn đã biết và kiểm chứng còn nợ (chưa chạy test theo R18/R21): xem mục "Kiểm chứng còn nợ" trong ADR-0006.

### Nhiều database trong một service

`support-service` (ngữ cảnh `audit`) là service mẫu dùng `database-common`: `AUDIT_DB` (PostgreSQL, critical) cho
audit log và `LOG_DB` (MongoDB, không critical) lưu bản thô của event. Mỗi use case chỉ ghi vào một DB; việc lưu
Mongo là best-effort và không có atomicity xuyên DB. Chi tiết và kết quả kiểm thử: `docs/architecture/multi-database-report.md`.

## Chạy nhanh toàn bộ hệ thống (Docker)

Cách đơn giản nhất, không cần cài Maven: Docker build và chạy cả hạ tầng lẫn các service.

```bash
# 1. BE: postgres, kafka, redis, mongo, system-service, các service (trừ support-service), lms-api, nginx (cổng vào 8080)
#    Cần đặt mật khẩu admin khởi tạo, nếu không identity không tạo tài khoản admin (đăng nhập sẽ 401)
export AUTH_BOOTSTRAP_ADMIN_PASSWORD='Admin@12345'          # PowerShell: $env:AUTH_BOOTSTRAP_ADMIN_PASSWORD='Admin@12345'
docker compose --profile apps up -d --build
docker compose --profile apps ps            # chờ system-service healthy, các service còn lại sẽ tự lên sau

# 2. FE (repo library-management-system-fe): một app React + Vite (apps/web) ở cổng 3000, proxy /api -> lms-api 8085 (đổi bằng VITE_API_TARGET)
cd ../library-management-system-fe
npm install
npm run dev
```

- Mở `http://localhost:3000`, đăng nhập `admin` / mật khẩu đã đặt ở `AUTH_BOOTSTRAP_ADMIN_PASSWORD` (ví dụ `Admin@12345`, chỉ dùng ở dev; profile `prod` trong compose mặc định bắt đổi mật khẩu lần đầu). API chỉ đi qua nginx `http://localhost:8080`.
- Lần đầu build mất vài phút; identity-service mất 1-2 phút để khởi động, trong lúc đó đăng nhập qua nginx trả 502/503.
- Xem log: `docker compose --profile apps logs -f identity-service`. Dừng: `docker compose --profile apps down` (thêm `-v` để xoá dữ liệu).
- Volume Postgres cũ chưa có vai trò `<svc>_app`: chạy `docker compose down -v` rồi `up` lại.
- Nếu `up --build` báo `additional privileges requested: pass "--allow=fs.read=..."` (Docker Compose dùng bake): đặt `COMPOSE_BAKE=false` hoặc `BUILDX_BAKE_ENTITLEMENTS_FS=0` trước lệnh.
- `support-service` (audit, thông báo) **mặc định không bật** để tiết kiệm tài nguyên dev. Thiếu nó thì chuông thông báo và màn Nhật ký hoạt động báo lỗi hoặc trống, các chức năng khác không ảnh hưởng. Bật bằng `docker compose --profile apps --profile support up -d --build` (hoặc `docker compose --profile apps up -d --build support-service`). Khi chạy bằng script: `scripts/dev-start.ps1 -WithSupport`.
- Chỉ chạy hạ tầng (service chạy bằng IDE/Maven): bỏ `--profile apps`, xem mục dưới.

## Chạy từng service bằng Docker

Mọi service nằm trong profile `apps` của `docker-compose.yml`. Gọi tên một service thì Compose tự bật thêm các service nó
phụ thuộc (`depends_on`) và chờ `system-service` healthy trước khi khởi động service nghiệp vụ. Không cần bật cả hệ thống.

| Service | Cổng (trong mạng compose) | Compose tự bật kèm | Lệnh |
|---|---|---|---|
| system-service | 8086 | postgres, redis | `docker compose --profile apps up -d --build system-service` |
| identity-service | 8081 | postgres, system-service, kafka | `docker compose --profile apps up -d --build identity-service` |
| catalog-service | 8082 | postgres, system-service | `docker compose --profile apps up -d --build catalog-service` |
| circulation-service | 8083 | postgres, system-service, kafka | `docker compose --profile apps up -d --build circulation-service` |
| support-service | 8084 | postgres, system-service, mongo, kafka | `docker compose --profile apps up -d --build support-service` |
| sales-service | 8087 | postgres, system-service, kafka | `docker compose --profile apps up -d --build sales-service` |
| lms-api (BFF) | 8085 | identity-service, catalog-service (và các thứ chúng cần) | `docker compose --profile apps up -d --build lms-api` |
| customer-api (BFF công khai) | 8088 | identity-service, system-service (và các thứ chúng cần) | `docker compose --profile apps up -d --build customer-api` |
| nginx | 8080 (publish ra host) | lms-api, customer-api, identity, circulation, support | `docker compose --profile apps up -d --build nginx` |

Thao tác thường dùng (thay `catalog-service` bằng service cần thao tác):

```bash
# Build lại và chạy lại CHỈ một service sau khi sửa code (các service phụ thuộc phải đã chạy)
docker compose --profile apps up -d --build --no-deps catalog-service

docker compose --profile apps logs -f catalog-service      # xem log
docker compose --profile apps ps                           # trạng thái và health
docker compose --profile apps stop catalog-service         # dừng một service (giữ container)
docker compose --profile apps start catalog-service        # chạy lại, không build
docker compose --profile apps rm -sf catalog-service       # dừng và xoá container của service đó
```

- **Thứ tự và health:** `system-service` chạy Flyway lên 6 DB (identity, catalog, circulation, audit, system, sales) rồi mới `UP`; service
  khác chờ nó healthy nên lần đầu có thể mất 1-2 phút. Kiểm tra: `docker compose --profile apps ps system-service`.
- **identity-service:** cần `AUTH_BOOTSTRAP_ADMIN_PASSWORD` (xem mục trên), nếu không không tạo tài khoản admin và đăng nhập trả 401.
  Đặt biến trước lệnh `up`, hoặc trong file `.env` cạnh `docker-compose.yml`.
- **Gọi API của từng service:** chỉ `nginx` publish cổng ra host (8080). Các service khác chỉ `expose` trong mạng compose nên
  `localhost:8082` từ máy host không vào được. Hai cách thử một service riêng lẻ:
  - qua nginx: `http://localhost:8080/api/...` (cần bật `nginx` và `lms-api`);
  - vào trong container: `docker compose --profile apps exec catalog-service curl -s localhost:8082/actuator/health`.
- **lms-api** chỉ khai `depends_on` identity và catalog; circulation và support được gọi lúc chạy, nên muốn dùng các màn hình mượn
  trả hoặc audit thì bật thêm hai service đó.
- **customer-api** (cổng công khai `/public/**`, ADR-0011) chỉ `expose` 8088 trong mạng compose, ra ngoài qua nginx. Dùng bí mật riêng
  `STOREFRONT_CLIENT_SECRET` (mặc định dev `dev-storefront-secret`; production bắt buộc đặt giá trị riêng ở cả identity và customer-api).
  Trang giới thiệu và form liên hệ cần context `cms` của system-service; tra tenant và đăng nhập khách cần endpoint cấp token storefront của identity.
- **Không chạy song song** bản Docker và bản Maven/IDE của cùng một service (trùng cổng nội bộ và cùng database).
- **Volume Postgres cũ** (tạo trước khi có `system_db`, `system_app` hoặc các vai trò `<svc>_app`) thiếu database/vai trò mới vì
  `infrastructure/postgres/init-databases.sql` chỉ chạy khi volume còn trống. Hoặc `docker compose down -v` (xoá dữ liệu), hoặc
  chạy tay các lệnh `CREATE USER`/`CREATE DATABASE` còn thiếu bằng `docker compose exec postgres psql -U postgres`; callback
  `afterMigrate.sql` tự cấp quyền sau khi `system-service` migrate.
- **RAM/CPU:** xem mục "Giảm RAM/CPU khi chạy Docker" ngay dưới. Compose có thể báo
  `Recreate` cho container postgres khi cấu hình đổi; dữ liệu trong volume `pgdata` vẫn được giữ.

## Giảm RAM/CPU khi chạy Docker

Máy/Docker VM ít RAM (khoảng 4-5 GiB) thì chạy tập con thay vì `--profile apps` (đủ mọi service). Các profile có thể kết hợp:

| Profile | Service được bật (thêm hạ tầng postgres, kafka, redis, mongo; luôn kèm system + identity) |
|---|---|
| `core` | system-service, identity-service, lms-api, nginx |
| `catalog` | catalog-service |
| `circulation` | circulation-service, catalog-service (circulation gọi catalog) |
| `support` | support-service |
| `sales` | sales-service, catalog-service |
| `lending` | catalog + circulation + support |
| `bookstore` | catalog + sales + customer-api |
| `apps` | tất cả (như cũ) |

```bash
docker compose --profile core up -d --build                      # tối thiểu: đăng nhập/phân quyền/menu
docker compose --profile core --profile lending up -d --build    # thêm mượn trả
docker compose --profile core --profile bookstore up -d --build  # thêm bán hàng/storefront
```

`lms-api` và `nginx` chỉ nằm trong `core`/`apps`; profile khác không có chúng nên luôn kèm `--profile core` khi cần FE. Với `core`,
các API của service vắng mặt (catalog, circulation, support, sales) trả lỗi 502/503 từ BFF; không ảnh hưởng phần còn lại.

Biến điều chỉnh (đặt trong `.env` hoặc trước lệnh `up`; mặc định trong ngoặc):

| Biến | Tác dụng |
|---|---|
| `JVM_XMX` (256m), `JVM_MAX_METASPACE` (192m), `JVM_CODE_CACHE` (64m), `JVM_EXTRA_OPTS` (rỗng, ví dụ `-Xss512k`) | cờ JVM từng service; `JAVA_TOOL_OPTIONS` ghi đè cả chuỗi |
| `JVM_MEM_LIMIT` (768m) | trần RAM mỗi container service Java |
| `TOMCAT_THREADS_MAX` (20), `DB_POOL_MAX` (5), `DB_POOL_MIN_IDLE` (1) | số thread Tomcat, pool Hikari (service dùng datasource Boot; support-service dùng pool riêng của database-common nên không đổi) |
| `KAFKA_HEAP_OPTS` (`-Xms128m -Xmx256m`), `KAFKA_MEM_LIMIT` (768m) | heap/trần RAM Kafka (mặc định của Kafka là heap 1G) |
| `MONGO_CACHE_GB` (0.25), `MONGO_MEM_LIMIT` (512m) | cache WiredTiger/trần RAM Mongo |
| `REDIS_MAXMEMORY` (64mb), `REDIS_MEM_LIMIT` (128m) | Redis (chính sách `allkeys-lru`; chỉ là cache menu) |
| `PG_SHARED_BUFFERS` (64MB), `PG_MAX_CONNECTIONS` (100), `PG_WORK_MEM` (4MB), `PG_MAINTENANCE_WORK_MEM` (32MB), `PG_MEM_LIMIT` (512m) | Postgres |
| `NGINX_MEM_LIMIT` (64m) | nginx |

Đánh đổi: heap Kafka 256m và `TOMCAT_THREADS_MAX=20` có thể chậm/nghẽn khi tải lớn (nâng lại khi chạy test hiệu năng); trần `mem_limit` quá thấp
làm container bị OOM-kill (exit 137). Không bật `spring.main.lazy-initialization`: bean `@KafkaListener`/`@Scheduled` sẽ không khởi tạo nên consumer và job không chạy.
Container Kafka/Postgres/Mongo/Redis đang chạy chỉ nhận cấu hình mới sau `docker compose up -d` (tạo lại container; volume `pgdata`/`mongodata` giữ nguyên, Kafka không có volume nên mất topic).

## Chạy local
docker compose up -d                       # postgres, kafka, redis, mongo
# Chạy system-service TRƯỚC các service khác (các service chỉ validate schema, không tự migrate)
mvn -pl services/system-service -am spring-boot:run     # cổng 8086, migrate cả 6 DB rồi mới UP
mvn -pl services/identity-service -am spring-boot:run      # profile dev
mvn -pl services/catalog-service -am spring-boot:run       # profile dev, security tắt
mvn -pl services/circulation-service -am spring-boot:run   # profile dev, security tắt (đặt trước vẫn cần Bearer token để biết người gọi)
mvn -pl services/sales-service -am spring-boot:run         # cổng 8087, profile dev, security tắt (module bán hàng, cần system-service chạy trước)
mvn -pl customer-api -am spring-boot:run                   # cổng 8088, BFF công khai /public/** (cần identity, system-service)
mvn test                                    # unit + ArchUnit + WebMvc test
```

Integration test (cần Docker), ví dụ: `mvn test -Dtest=AuthPersistenceAdapterIT -Dsurefire.failIfNoSpecifiedTests=false`.
Surefire mặc định không chạy `*IT`. IT có RLS tạo vai trò `<svc>_app` trong container và chạy service bằng vai trò đó (không dùng superuser).
Với Docker 29 thêm `-DargLine=-Dapi.version=1.44`.

## Quy tắc

- Domain không phụ thuộc Spring/JPA; application không phụ thuộc infrastructure (ArchUnit kiểm tra).
- Không service nào truy cập database của service khác; tham chiếu chéo bằng ID hoặc event. Ngoại lệ có chủ đích: `system-service` chỉ chạy DDL (Flyway) lên các DB, không đọc/ghi dữ liệu nghiệp vụ (ADR-0004).
- Event có `eventId`, `eventType`, `aggregateId`, `occurredAt`, `version`; consumer phải idempotent.
- Production cần Transactional Outbox thay cho publish trực tiếp (xem ADR-0001).
- Gộp từ 11 service nhỏ xuống 4 service theo bounded context, xem ADR-0002.
