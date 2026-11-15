# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Monorepo Maven (Java 21, Spring Boot 3.5.6): hệ thống quản lý thư viện theo Microservices + DDD + Hexagonal + Clean Architecture + RBAC. groupId `com.company`, package Java gốc `lms`. README.md có bảng service/cổng và glossary RBAC; ADR ở `docs/decisions/`.

## Lệnh thường dùng

```bash
docker compose up -d                                        # postgres, kafka, redis, mongo
mvn test                                                    # unit + ArchUnit + WebMvc (không chạy *IT)
mvn -pl services/identity-service -am test                  # một module (luôn có -am để build shared-libraries)
mvn -pl services/identity-service -am test -Dtest=AdminRoleAndPermissionKeyTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl services/migration-service -am spring-boot:run      # chạy TRƯỚC: migrate 4 DB (cổng 8086); các service chỉ validate schema
mvn -pl services/identity-service -am spring-boot:run       # profile dev
```

- Integration test (`*IT`, Testcontainers, cần Docker) mặc định bị Surefire bỏ qua; chạy bằng `-Dtest=<TênIT>`. Với Docker 29 thêm `-DargLine=-Dapi.version=1.44`, nếu không Testcontainers không tìm thấy Docker.
- `OracleIT` chỉ chạy khi có `ORACLE_TEST_URL/USERNAME/PASSWORD` (dùng container `oracle-free` có sẵn, không tạo DB Oracle riêng).
- Máy này không có `mvn` toàn cục; dùng Maven trong scratchpad nếu cần.
- Cổng: identity 8081, catalog 8082, circulation 8083, support 8084, migration 8086. FE chỉ gọi qua nginx (8080) → `lms-api` (BFF, 8085); BFF gọi service qua `/internal/**`.

## Kiến trúc

4 service theo bounded context, mỗi service một database riêng (không truy cập DB của nhau, tham chiếu chéo bằng ID hoặc event Kafka):
identity (`auth`, `accesscontrol`, `user`, `department`), catalog (`catalog`, `inventory`), circulation (`borrowing`, `reservation`, `fine`), support (`audit`, `notification`).
Thêm `migration-service` (ADR-0004): runner Flyway tập trung, giữ mọi file SQL và chạy lên identity_db/catalog_db/circulation_db/audit_db lúc khởi động (ngoại lệ có chủ đích với luật "không truy cập DB của nhau", chỉ chạy DDL). Sổ cái là `flyway_schema_history` trong từng DB; API `GET /api/migrations[/{target}]`, `POST /api/migrations/run`, `POST /api/migrations/{target}/run` (target: identity, catalog, circulation, audit). Các service không chạy Flyway, chỉ validate schema.

**Thêm migration mới** = thêm file `V<n>__*.sql` vào `services/migration-service/src/main/resources/db/migration/<target>/` (không còn nằm trong từng service). Kiểm tra số `n` kế tiếp vì nhiều người cùng sửa; không sửa file đã chạy (đổi checksum). IT của service bật Flyway ở test scope (jar thường của migration-service) với location `classpath:db/migration/<target>`.

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
- Migration identity có đánh số liên tục (V9 đổi mã seed cũ sang khóa endpoint); thêm migration mới phải kiểm tra số kế tiếp vì nhiều người cùng sửa (file nằm ở `migration-service`, thư mục `identity`).

### Nhiều database (`database-common`)

Kết nối khai báo ở `database.connections.*` (PostgreSQL, Oracle, MongoDB, Redis; không có MySQL/SQL Server). Mỗi kết nối JPA có TransactionManager tên `<name>TransactionManager`. Không có atomicity xuyên DB: mỗi use case chỉ ghi một DB, bản Mongo là best-effort. Mẫu tham khảo: `support-service` (context `audit`, `AUDIT_DB` Postgres + `LOG_DB` Mongo), xem `docs/architecture/multi-database-report.md`.

## Quy ước code

- Controller: endpoint có từ 2 tham số query trở lên thì gom vào một `record` (Spring tự bind, không cần annotation), không dùng nhiều `@RequestParam` rời. Body dùng `@Valid @RequestBody` với record; id trong đường dẫn dùng `@PathVariable`.
- Cấu hình YAML: nhiều người cùng sửa `application.yml`; khi thêm khối cấp cao (ví dụ `lms:`) đặt ở cuối file và chắc chắn chỉ có một khối cùng tên (chèn vào giữa `app:` từng làm lồng nhầm cấu hình khác).
- Working tree thường có thay đổi chưa commit của phiên/người khác: sửa có mục tiêu bằng Edit, đọc lại file trước khi sửa, không ghi đè cả file và không revert.
- Song song hóa: khi công việc chia được thành các phần độc lập (ví dụ sửa/test nhiều service hoặc module không phụ thuộc nhau, rà nhiều controller), chạy sub agent song song để giảm thời gian chờ. Giao mỗi agent một phạm vi file riêng để không ghi đè nhau; việc phụ thuộc nhau (shared-libraries trước, service sau) thì chạy tuần tự.
- Commit: message tiếng Việt có dấu, không thêm `Co-Authored-By` hay dòng attribution nào. Dòng đầu có prefix Conventional Commits, chỉ dùng 7 loại `feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `build`; scope tuỳ chọn theo service/module (`fix(identity):`, `feat(lms-api):`), thay đổi phá vỡ API thêm `!` (`feat(api)!:`). Quy ước đầy đủ ở `~/.claude/CLAUDE.md`.
