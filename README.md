# Library Management System

Hệ thống quản lý thư viện: Java 21, Spring Boot 3.5.x, Maven, PostgreSQL, Kafka, Redis.
Kiến trúc: Microservices + DDD + Hexagonal + Clean Architecture + RBAC.

## Cấu trúc

| Thư mục | Nội dung |
|---|---|
| `api-gateway/` | Spring Cloud Gateway, route tới các service (cổng 8080) |
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
| identity-service | 8081 | identity_db | `auth`, `accesscontrol`, `user`, `department` | `auth` và `accesscontrol` đã làm; `user`, `department` là khung |
| catalog-service | 8082 | catalog_db | `catalog`, `inventory` | khung |
| circulation-service | 8083 | circulation_db | `borrowing`, `reservation`, `fine` | `borrowing` đã làm; còn lại là khung |
| support-service | 8084 | `audit_db` (PostgreSQL) + `LOG_DB` (MongoDB) | `notification`, `audit` | `audit` đã làm (dùng `database-common`); `notification` là khung |

### Phân quyền (RBAC)

- `accesscontrol` quản lý Role, Permission, gán role cho người dùng; seed sẵn 5 role và 19 permission (gồm `AUDIT_VIEW` cho nhật ký audit, V4).
- `auth` đăng nhập, phát hành access token RS256 chứa `roles`, `permissions`, `departmentId`; refresh token
  xoay vòng (dùng lại token cũ thì thu hồi mọi phiên); khóa tài khoản sau 5 lần sai; JWKS tại
  `/.well-known/jwks.json`.
- Các service khác là resource server: đọc claim `permissions` thành authority (`security-common`) và
  kiểm tra bằng `hasAuthority(...)`, không gọi lại identity-service mỗi request.
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
mvn -pl services/circulation-service -am spring-boot:run   # profile dev, security tắt
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
