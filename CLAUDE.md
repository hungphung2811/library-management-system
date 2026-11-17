# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Monorepo Maven (Java 21, Spring Boot 3.5.6): hệ thống quản lý thư viện theo Microservices + DDD + Hexagonal + Clean Architecture + RBAC. groupId `com.company`, package Java gốc `lms`. README.md có bảng service/cổng và glossary RBAC; ADR ở `docs/decisions/`.

## Lệnh thường dùng

```bash
docker compose up -d                                        # postgres, kafka, redis, mongo
mvn test                                                    # unit + ArchUnit + WebMvc (không chạy *IT)
mvn -pl services/identity-service -am test                  # một module (luôn có -am để build shared-libraries)
mvn -pl services/identity-service -am test -Dtest=AdminRoleAndPermissionKeyTest -Dsurefire.failIfNoSpecifiedTests=false
```

Cách nhanh nhất trên máy dev (Windows): `scripts\dev-start.ps1` (Docker chỉ cho hạ tầng; mỗi service chạy bằng `java -jar` từ jar đã build, không dùng `spring-boot:run`). Cờ: `-Build` (build jar bằng `mvn -DskipTests package`, cần `mvn` trên PATH hoặc biến `MAVEN_CMD`), `-WithSupport` (bật support-service, mặc định tắt vì nặng), `-NoFe` (bỏ FE), `-Only identity,catalog` (system luôn chạy trước), `-Seq`, `-NoCds`. Log ở `.dev\*.log`, tắt bằng `scripts\dev-stop.ps1`. Script chỉ có system, identity, catalog, circulation, support, lms-api (không có sales 8087 và customer-api 8088: chạy riêng bằng `java -jar`); catalog/circulation/support chạy với `--app.security.enabled=true --lms.security.permission.enabled=true`; admin đăng nhập `admin / Admin@12345`. Lưu ý `mvn -pl <module> -am spring-boot:run` lỗi "Unable to find a suitable main class" vì chạy cả pom gốc; muốn dùng `spring-boot:run` thì `mvn -DskipTests install` trước rồi chạy `-pl <module>` không có `-am`.

Hoặc chạy từng service bằng Maven (cần `docker compose up -d` trước; mọi service mặc định profile `dev`, `lms-api` thì không có profile mặc định). Thứ tự: system-service trước, các service còn lại sau (chạy mỗi lệnh trong một terminal riêng):

```bash
mvn -pl services/system-service -am spring-boot:run       # 8086, chạy TRƯỚC: migrate 6 DB rồi mới UP; các service khác chỉ validate schema
mvn -pl services/identity-service -am spring-boot:run     # 8081, đăng nhập/phân quyền/người dùng
mvn -pl services/catalog-service -am spring-boot:run      # 8082, đầu sách/bản sao
mvn -pl services/circulation-service -am spring-boot:run  # 8083, mượn trả/đặt trước/phạt
mvn -pl services/support-service -am spring-boot:run      # 8084, audit/thông báo (cần Kafka, Mongo)
mvn -pl services/sales-service -am spring-boot:run        # 8087, bán hàng (module SALES, ADR-0010): tồn kho, đơn POS/online
mvn -pl lms-api -am spring-boot:run                       # 8085, BFF: /api/** → /internal/** của các service
mvn -pl customer-api -am spring-boot:run                  # 8088, BFF công khai: /public/** (cần identity, system-service)
```

- Profile dev tắt kiểm xác thực/phân quyền ở các service, nên không có JWT/tenant: ghi bảng tenant bị chặn (xem mục Multi-tenant), cần Bearer token. Muốn chạy kiểu production thêm `-Dspring-boot.run.profiles=prod`.
- Chạy cả hệ thống bằng Docker: `docker compose --profile apps up -d --build` (thêm nginx 8080 làm cổng vào, lms-api, system-service, sales-service và 4 service). Đặt `AUTH_BOOTSTRAP_ADMIN_PASSWORD` trước, nếu không identity không tạo tài khoản admin. Volume Postgres cũ chưa có `system_db`/vai trò `<svc>_app`: `docker compose down -v`.
- Chạy tập con để đỡ RAM: `docker compose --profile core up -d --build` (system, identity, lms-api, nginx), thêm `--profile lending|catalog|circulation|support|sales|bookstore` tùy nhu cầu; `apps` vẫn là đủ. Compose đã đặt sẵn heap Kafka/Mongo/Redis/Postgres, cờ JVM, pool Hikari, Tomcat threads và `mem_limit`, đều ghi đè được bằng biến môi trường (`JVM_XMX`, `KAFKA_HEAP_OPTS`, `DB_POOL_MAX`, `JVM_MEM_LIMIT`...): bảng đầy đủ ở README mục "Giảm RAM/CPU khi chạy Docker". Không bật `spring.main.lazy-initialization` (làm `@KafkaListener`/`@Scheduled` không chạy).
- Integration test (`*IT`, Testcontainers, cần Docker) mặc định bị Surefire bỏ qua; chạy bằng `-Dtest=<TênIT>`. Với Docker 29 thêm `-DargLine=-Dapi.version=1.44`, nếu không Testcontainers không tìm thấy Docker.
- `OracleIT` chỉ chạy khi có `ORACLE_TEST_URL/USERNAME/PASSWORD` (dùng container `oracle-free` có sẵn, không tạo DB Oracle riêng).
- Máy này không có `mvn` toàn cục; dùng Maven trong scratchpad nếu cần (thay `mvn` bằng đường dẫn Maven đó và đặt `JAVA_HOME`).
- Cổng: identity 8081, catalog 8082, circulation 8083, support 8084, system 8086, sales 8087, customer-api 8088. FE chỉ gọi qua nginx (8080) → `lms-api` (BFF, 8085); BFF gọi service qua `/internal/**`. FE storefront/khách chưa đăng nhập gọi `/public/**` → `customer-api` (xem mục dưới).

## Kiến trúc

4 service theo bounded context, mỗi service một database riêng (không truy cập DB của nhau, tham chiếu chéo bằng ID hoặc event Kafka):
identity (`auth`, `accesscontrol`, `user`, `department`), catalog (`catalog`, `inventory`), circulation (`borrowing`, `reservation`, `fine`), support (`audit`, `notification`), sales (`stock`, `ordering`, `settings`; module bán hàng ADR-0010, DB `sales_db`, vai trò `sales_app`, mọi controller `@RequiresModule(SALES)`).
Thêm `system-service` (ADR-0004): runner Flyway tập trung, giữ mọi file SQL và chạy lên identity_db/catalog_db/circulation_db/audit_db/system_db/sales_db lúc khởi động (ngoại lệ có chủ đích với luật "không truy cập DB của nhau", chỉ chạy DDL). `system_db` (vai trò `system_app`, RLS, ADR-0007) là DB riêng của context `menu` trong chính system-service: datasource Spring Boot chuẩn `spring.datasource.*`, và migration chạy trong bean khởi tạo mà `EntityManagerFactory` phụ thuộc (không dùng Flyway autoconfig, `spring.flyway.enabled=false`). API migration là `@PlatformAdminOnly`. Sổ cái là `flyway_schema_history` trong từng DB; API `GET /internal/migrations[/{target}]`, `POST /internal/migrations/run`, `POST /internal/migrations/{target}/run` (target: identity, catalog, circulation, audit, system, sales). Các service không chạy Flyway, chỉ validate schema.

**Thêm migration mới** = thêm file `V<n>__*.sql` vào `services/system-service/src/main/resources/db/migration/<target>/` (không còn nằm trong từng service). Kiểm tra số `n` kế tiếp vì nhiều người cùng sửa; không sửa file đã chạy (đổi checksum). IT của service bật Flyway ở test scope (jar thường của system-service) với location `classpath:db/migration/<target>`.

Mỗi context là một package gốc phẳng `domain/`, `application/`, `infrastructure/{web,persistence,messaging,client,security,config}`:
- `domain`: Java thuần, không Spring/jakarta/jackson.
- `application`: port + use case là POJO, bean tạo trong `*BeanConfiguration`; chỉ dùng `@Transactional` của Spring.
- Context chỉ được dùng tầng `application` của context khác (ví dụ `auth` chỉ dùng `accesscontrol.application`). ArchUnit kiểm tra các luật này, test kiến trúc sẽ fail nếu vi phạm.

`shared-libraries/`: `common-kernel` (DomainEvent, ErrorResponse), `security-common` (claim JWT, middleware phân quyền), `messaging-common` (EventEnvelope, `KafkaEventPublisher` best-effort: lỗi Kafka chỉ log, không làm hỏng request; production cần Outbox, ADR-0001), `observability-common` (`X-Correlation-Id`), `database-common` (nhiều kết nối DB).

### Phân quyền động (ADR-0003)

Không có hằng số permission và không dùng `hasAuthority`. `PermissionInterceptor` (security-common) lấy khóa `TênController/tênMethod` của từng endpoint, tự đăng ký vào bảng `permissions` của identity lần đầu endpoint được gọi (identity ghi trực tiếp; service khác gọi `POST /internal/permissions/registrations` với header `X-Internal-Api-Key` = `INTERNAL_API_KEY`), rồi kiểm khóa trong claim JWT `permissions`.
- Khóa chưa gán cho role nào thì người không phải admin bị 403 (khóa vẫn được đăng ký).
- Endpoint không cần quyền: `@AuthenticatedOnly` (chỉ đăng nhập) hoặc `@PublicEndpoint`. Không dùng `@RequirePermission("KEY")` (còn trong code nhưng chủ đích không dùng); mọi khóa là `Controller/method`.
- Đổi tên controller/method đổi khóa, role đã gán mất quyền; quyền mới của role chỉ có hiệu lực khi đăng nhập hoặc refresh token kế tiếp.
- Quản trị viên là dữ liệu: role có cờ `is_admin` → JWT có claim `admin=true` và bỏ qua kiểm khóa. Không hardcode tên role. Chỉ admin được tạo/gán/thu hồi role `isAdmin`. Token của tài khoản `mustChangePassword` không mang `admin`/`permissions`.
- `SecurityConfiguration` của mỗi service chỉ xác thực (`/internal/**` authenticated, còn lại denyAll; `/api/**` chỉ có ở lms-api; identity đã chuyển sang `/internal/**`, chỉ công khai các đường dẫn đăng nhập/làm mới/đăng xuất/đăng nhập ngoài, JWKS và `POST /internal/permissions/registrations`). Profile dev tắt kiểm khóa bằng `lms.security.permission.enabled=false`; test WebMvc phải bật lại (`@TestPropertySource`) và `@MockitoBean PermissionKeyRegistrar`.
- Migration identity có đánh số liên tục (V9 đổi mã seed cũ sang khóa endpoint); thêm migration mới phải kiểm tra số kế tiếp vì nhiều người cùng sửa (file nằm ở `system-service`, thư mục `identity`).

### Multi-tenant (ADR-0006)

- Dùng chung DB: bảng nghiệp vụ có `tenant_id`, entity `@TenantId` (Hibernate), PostgreSQL `ENABLE`+`FORCE ROW LEVEL SECURITY` theo biến phiên `app.tenant_id` (fail-closed). `TenantContext` (tenant-common) đặt từ claim JWT `tenantId`; không có tenant thì 403 `TENANT_REQUIRED` (trừ `@AuthenticatedOnly`).
- Token theo tenant: `permissions`/`admin` tính theo role của tenant trong token. `POST /api/auth/switch-tenant` body `{tenantId, refreshToken}` (bắt buộc có `refreshToken`, token cũ bị thu hồi). Platform admin: token không có `tenantId`.
- Service chạy bằng vai trò DB `<svc>_app` (NOSUPERUSER NOBYPASSRLS), tạo trong `infrastructure/postgres/init-databases.sql`; system-service dùng chủ bảng và cấp quyền qua callback `afterMigrate.sql`. Volume Postgres cũ: `docker compose down -v` (hoặc chạy tay SQL). Sau khi pull thay đổi migration: `mvn clean`.
- Chạy local dùng tenant mặc định `00000000-0000-0000-0000-0000000000d1`. Profile dev tắt security nên không có JWT và không có tenant: ghi bảng tenant bị chặn, cần Bearer token.
- **Migration mới trên bảng có RLS**: FORCE RLS áp cả cho chủ bảng nên migration ghi dữ liệu vào bảng tenant phải đặt `app.tenant_id` (`set_config('app.tenant_id', '<uuid>', true)`) hoặc chạy bằng superuser; bảng mới có `tenant_id` phải bật RLS + FORCE, thêm `@TenantId` vào entity (ArchUnit `TenantArchitectureRulesTest` bắt thiếu). Bảng không `@TenantId` phải nằm trong `TenantArchitectureRules.TENANT_EXEMPT_TABLES`; không thêm native query ngoài danh sách đã rà.
- IT chạy bằng vai trò app: Flyway chạy bằng chủ bảng/superuser trong container, sau đó tạo `<svc>_app` và nối DataSource của service bằng vai trò này (xem `AppRoleDatabase` trong IT của support/identity); đặt `TenantContext` hoặc JWT có `tenantId` cho mỗi lần gọi.
- Giới hạn đã biết và lệnh chạy test bù: mục "Kiểm chứng còn nợ" trong `docs/decisions/0006-multi-tenant.md`.

### Nhiều database (`database-common`)

Kết nối khai báo ở `database.connections.*` (PostgreSQL, Oracle, MongoDB, Redis; không có MySQL/SQL Server). Mỗi kết nối JPA có TransactionManager tên `<name>TransactionManager`. Không có atomicity xuyên DB: mỗi use case chỉ ghi một DB, bản Mongo là best-effort. Mẫu tham khảo: `support-service` (context `audit`, `AUDIT_DB` Postgres + `LOG_DB` Mongo), xem `docs/architecture/multi-database-report.md`.

### customer-api (BFF công khai, ADR-0011)

Module `customer-api/` (cổng 8088, package `lms.customer`, không DB/JPA/Kafka, không phụ thuộc `security-common`/`tenant-common`): cổng công khai cho khách qua nginx `/public/**` (vùng `limit_req` `public_read` 20r/s, `public_form` 5r/m). Cấu trúc như `lms-api` theo nhóm `site`, `storefront`, `auth` (`client` interface + `http`, `dto`, `web`). Hiện có C0: `GET /public/site/pages[/{slug}]`, `POST /public/site/contact-requests` (honeypot `website`), `GET /public/t/{code}`, `POST /public/t/{code}/auth/login`, `POST /public/auth/{refresh,logout}`; sách và đặt hàng thuộc C1/C2.
- Gọi nội bộ thay khách chưa đăng nhập bằng token dịch vụ (claim `svc`, TTL 5 phút) xin từ identity bằng `STOREFRONT_CLIENT_SECRET` (bí mật riêng, không dùng `INTERNAL_API_KEY`); tra tenant theo `{code}` đi qua `StorefrontAccessCache` (Caffeine, kết quả không khả dụng giữ 60 giây). Mọi lý do storefront không khả dụng cùng một `404 SHOP_NOT_AVAILABLE`.
- Chỉ xác thực JWT (phân quyền do sales kiểm), `permitAll` theo danh sách, còn lại `denyAll`; token `svc` bị 401; token khách khác tenant của `{code}` bị `403 SHOP_SESSION_MISMATCH` (`ShopSessionGuard`). Lỗi phía sau: chỉ chuyển mã trong danh sách cho phép (`PublicExceptionHandler`), thông điệp do customer-api viết, 5xx và mất kết nối thành `503 SERVICE_UNAVAILABLE` chung. Không log thân request.

## Quy ước code

- Controller: endpoint có từ 2 tham số query trở lên thì gom vào một `record` (Spring tự bind, không cần annotation), không dùng nhiều `@RequestParam` rời. Body dùng `@Valid @RequestBody` với record; id trong đường dẫn dùng `@PathVariable`.
- Cấu hình YAML: nhiều người cùng sửa `application.yml`; khi thêm khối cấp cao (ví dụ `lms:`) đặt ở cuối file và chắc chắn chỉ có một khối cùng tên (chèn vào giữa `app:` từng làm lồng nhầm cấu hình khác).
- Working tree thường có thay đổi chưa commit của phiên/người khác: sửa có mục tiêu bằng Edit, đọc lại file trước khi sửa, không ghi đè cả file và không revert.
- Song song hóa: khi công việc chia được thành các phần độc lập (ví dụ sửa/test nhiều service hoặc module không phụ thuộc nhau, rà nhiều controller), chạy sub agent song song để giảm thời gian chờ. Giao mỗi agent một phạm vi file riêng để không ghi đè nhau; việc phụ thuộc nhau (shared-libraries trước, service sau) thì chạy tuần tự.
- Commit: message tiếng Việt có dấu, không thêm `Co-Authored-By` hay dòng attribution nào. Dòng đầu có prefix Conventional Commits, chỉ dùng 7 loại `feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `build`; scope tuỳ chọn theo service/module (`fix(identity):`, `feat(lms-api):`), thay đổi phá vỡ API thêm `!` (`feat(api)!:`). Quy ước đầy đủ ở `~/.claude/CLAUDE.md`.
