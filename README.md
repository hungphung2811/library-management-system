# Library Management System

Hệ thống quản lý thư viện: Java 21, Spring Boot 3.5.x, Maven, PostgreSQL, Kafka, Redis.
Kiến trúc: Microservices + DDD + Hexagonal + Clean Architecture + RBAC.

## Cấu trúc

| Thư mục | Nội dung |
|---|---|
| `infrastructure/nginx/` | Nginx: cổng vào duy nhất (8080): CORS, header bảo mật, giới hạn tốc độ đăng nhập, chặn `/internal` |
| `lms-api/` | BFF: service duy nhất đưa API ra cho FE (cổng 8085, nội bộ), gọi các service qua API `/internal/**` |
| `services/` | 4 microservice, mỗi service một database riêng |
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

### Nhiều database trong một service

`support-service` (ngữ cảnh `audit`) là service mẫu dùng `database-common`: `AUDIT_DB` (PostgreSQL, critical) cho
audit log và `LOG_DB` (MongoDB, không critical) lưu bản thô của event. Mỗi use case chỉ ghi vào một DB; việc lưu
Mongo là best-effort và không có atomicity xuyên DB. Chi tiết và kết quả kiểm thử: `docs/architecture/multi-database-report.md`.

## Chạy local

```bash
docker compose up -d                       # postgres, kafka, redis, mongo
mvn -pl services/identity-service -am spring-boot:run      # profile dev
mvn -pl services/catalog-service -am spring-boot:run       # profile dev, security tắt
mvn -pl services/circulation-service -am spring-boot:run   # profile dev, security tắt (đặt trước vẫn cần Bearer token để biết người gọi)
mvn test                                    # unit + ArchUnit + WebMvc test
```

Integration test (cần Docker), ví dụ: `mvn test -Dtest=AuthPersistenceAdapterIT -Dsurefire.failIfNoSpecifiedTests=false`.
Surefire mặc định không chạy `*IT`.

## Quy tắc

- Domain không phụ thuộc Spring/JPA; application không phụ thuộc infrastructure (ArchUnit kiểm tra).
- Không service nào truy cập database của service khác; tham chiếu chéo bằng ID hoặc event.
- Event có `eventId`, `eventType`, `aggregateId`, `occurredAt`, `version`; consumer phải idempotent.
- Production cần Transactional Outbox thay cho publish trực tiếp (xem ADR-0001).
- Gộp từ 11 service nhỏ xuống 4 service theo bounded context, xem ADR-0002.
