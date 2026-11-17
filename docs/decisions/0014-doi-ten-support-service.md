# ADR-0014: Đổi tên `support-service` thành `audit-service` (giữ nguyên DB, cổng, khóa quyền, consumer group)

- Trạng thái: ✅ đã chọn tên `audit-service` (người dùng chốt 2026-11-17). ⏳ Chưa làm: chờ các phiên đang sửa `services/support-service`, `lms-api`, `docker-compose.yml`, `scripts` commit xong (xem mục 5 về thứ tự). Còn mở: thời hạn giữ bí danh `SUPPORT_SERVICE_URL`/profile `support`/`-WithSupport` (mục 6, câu 3), mặc định theo khuyến nghị nếu người dùng không nói khác.
- Liên quan: ADR-0002 (gộp service, đặt tên `support-service`), ADR-0003 (khóa `Controller/method`), ADR-0004 (migration tập trung), ADR-0005 (BFF, nginx), ADR-0006 (multi-tenant, vai trò `<svc>_app`), ADR-0007 (tiền lệ đổi `migration-service` → `system-service`, commit `274f885`), ADR-0011 (context `cms`, form liên hệ SaaS), ADR-0012 (consumer thông báo hợp đồng sắp ghi vào service này)
- Đề xuất liên quan: `docs/api/customer-api-contact-reader.md` Q1 (nơi lưu liên hệ đọc giả)

## 1. Bối cảnh và mục tiêu

`support-service` (ADR-0002) chỉ chứa hai context: `audit` (nhật ký kiểm toán: `audit_logs` ở `audit_db` Postgres + bản thô event ở Mongo `audit_logs`) và `notification` (thông báo trong ứng dụng: bảng `notifications`, cũng ở `audit_db`). Tên "support" khiến người đọc hiểu là nơi đặt ticket/CSKH/form hỗ trợ; hiểu nhầm đó đã lọt vào thiết kế: `customer-api-contact-reader.md` Q1 liệt kê `support-service` làm ứng viên lưu liên hệ đọc giả.

Mục tiêu: tên service nói đúng cái nó chứa, không kéo tính năng sai chỗ, với chi phí và rủi ro dữ liệu thấp nhất.

### 1.1. Hiện trạng đã đối chiếu trong code (nhánh `main`, gồm thay đổi chưa commit, 2026-11-17)

| | Điểm | Kết quả thật |
|---|---|---|
| ✅ | Tầng dữ liệu đã mang tên `audit`, **không** mang tên `support` | DB `audit_db`, chủ `audit`, vai trò chạy `audit_app` (không có `support_app`, không có `support_db`); Mongo `mongodb://.../audit_logs`; target migration `audit`; kết nối `AUDIT_DB`/`LOG_DB`. `services/support-service/README.md` ghi `support_db` là **sai so với code**. |
| ✅ | Bảng `notifications` nằm trong `audit_db` | `system-service/.../migration/audit/V3__create_notifications.sql`, RLS FORCE, `UNIQUE (tenant_id, event_id)`. |
| ✅ | Tên service lộ ra ở runtime | `spring.application.name: support-service` → `lms.security.permission.service-name` → cột `permissions.service` ở identity. Consumer group `spring.kafka.consumer.group-id: support-service`; `AuditEventConsumer` dùng `${...group-id:support-service}`, `ReservationReadyConsumer` dùng `${...group-id:support-service}-notification`; `auto-offset-reset: earliest`. |
| ✅ | Khóa quyền không phụ thuộc package/tên service | `PermissionKeyResolver`: `type.getSimpleName() + "/" + method.getName()`. Khóa hiện có: `AuditLogController/search`; `NotificationController` là `@AuthenticatedOnly`. |
| ✅ | `permissions.service` không tự cập nhật | `PermissionJpaRepository.registerDiscovered`: `ON CONFLICT (code) DO UPDATE SET service = COALESCE(permissions.service, EXCLUDED.service)` nên dòng cũ giữ `support-service`. V9 seed `('AUDIT_VIEW','AuditLogController/search','support-service',...)`. |
| ✅ | FE dùng cột `service` | `library-management-system-fe/apps/web/src/pages/admin/_components/RolePermissions.tsx:78` nhóm danh mục quyền theo `` `${p.service} / Controller` ``. Đổi tên mà không sửa dữ liệu thì màn gán quyền tách thành hai nhóm. |
| ✅ | FE không phụ thuộc tên service | FE gọi `/api/audit-logs`, `/api/notifications/**` qua nginx → `lms-api`; trong FE chỉ có chú thích nhắc `support-service`. Nginx không có upstream `support-service` (chỉ chú thích dòng 94). |
| ✅ | Idempotent khi đọc lại Kafka | `audit_logs`: `ON CONFLICT (event_id) DO NOTHING`; `notifications`: `ON CONFLICT (tenant_id, event_id) DO NOTHING`; Mongo `save()` theo `_id`. |
| ✅ | Cổng | 8084, không ai phụ thuộc tên ngoài compose DNS `support-service:8084` và `SUPPORT_SERVICE_URL` của `lms-api`. |
| ✅ | `lms-api` gói `lms.api.support` chứa cả lớp dùng chung | `TokenRelayInterceptor` nằm ở `lms.api.support` nhưng được `ServiceClients`, `IdentityClientConfiguration` và test của mọi client dùng; `LmsApiArchitectureTest` phải loại trừ riêng nó (chú thích dòng 36). Đây là nợ đặt sai chỗ, đổi tên sẽ phải xử lý. |
| ✅ | Module theo tenant (ADR-0009) | support là "lõi, không đánh dấu": không có mã module `SUPPORT` trong dữ liệu. |
| ⚠️ | Working tree | 14 tệp chưa commit đụng support: 7 sửa trong `services/support-service` + 1 chưa track (`AuditCrossTenantReadIT.java`), 6 sửa trong `lms-api/.../support/**`. ADR-0010 (thêm `sales.events` vào `AuditEventConsumer`) và ADR-0012 B6 (`ContractExpiringConsumer`) đang nhắm vào service này. |

### 1.2. Số liệu tác động nếu đổi tên (đếm bằng `git ls-files -co --exclude-standard`, bỏ `target/`)

Phân biệt ba loại: (S) tên service/module/package/hạ tầng `support`, phải đổi; (C) tên context `audit`/`notification`, **giữ nguyên**; (N) từ "support" nghĩa khác, **không đụng**.

| Loại | Nhóm | Số lượng | Đường dẫn |
|---|---|---|---|
| S | Mã nguồn service (package `lms.support`) | 76 tệp (59 main + 17 test) | `services/support-service/src/**` (đổi `package`/`import`, đường dẫn thư mục, `SupportServiceApplication`, `SupportServiceIT`, `@AnalyzeClasses(packages = "lms.support")`) |
| S | Tệp build của service | 3 | `services/support-service/{pom.xml,Dockerfile,README.md}` |
| S | Maven gốc | 1 | `pom.xml` (`<module>services/support-service</module>`) |
| S | Cấu hình service | 3 | `application.yml` (`name`, `group-id`, `entity-packages lms.support...`), `application-dev.yml` (`logging.level.lms.support`), `application-prod.yml` (không có chữ support, kiểm lại) |
| S | `lms-api` gói `lms.api.support` | 14 tệp (11 main + 3 test) | `lms-api/src/main/java/lms/api/support/**`, `lms-api/src/test/java/lms/api/support/**` |
| S | `lms-api` tệp ngoài gói nhưng import/tham chiếu | 12 tệp + `application.yml` | `config/ServiceClients.java`, `config/ServicesProperties.java` (`supportUrl`), `identity/IdentityClientConfiguration.java`, test: `LmsApiArchitectureTest`, `LmsApiIdentityArchitectureTest`, `LmsApiRoutingTest`, `CrossTenantReadClientTest`, `CrossTenantReadControllerTest`, `catalog/HttpCatalogClientTest`, `circulation/HttpCirculationClientTest`, `identity/HttpIdentityClientTest`, `sales/HttpSalesClientTest`, `system/HttpCmsClientTest`; `application.yml` (`support-url`, `SUPPORT_SERVICE_URL`) |
| S | ArchUnit ở module khác | 1 | `customer-api/src/test/java/lms/customer/architecture/CustomerApiArchitectureTest.java` (`"lms.support.."`) |
| S | Docker Compose | 1 tệp, 6 chỗ | `docker-compose.yml`: khối `support-service` (tên, `dockerfile`, profile `support`), `SUPPORT_SERVICE_URL` của `lms-api`, `depends_on` của nginx, 2 chú thích |
| S | Script | 1 | `scripts/dev-start.ps1` (`-WithSupport`, khóa `support`, đường dẫn jar); thư mục cache `.dev/cds/support`, log `.dev/support*.log` (không track, tự sinh lại) |
| S | SQL chỉ là chú thích | 2 | `infrastructure/postgres/init-databases.sql` (chú thích dòng 18), `system-service/.../migration/audit/afterMigrate.sql` (chú thích; callback không ghi `flyway_schema_history` nên sửa không đổi checksum) |
| S | SQL **không được sửa** | 2 | `migration/identity/V4__add_audit_view_permission.sql` (chú thích), `V9__...sql` (dữ liệu `'support-service'`). Đã chạy: sửa là đổi checksum, Flyway validate lỗi. Sửa dữ liệu bằng migration mới. |
| S | Dữ liệu runtime | 1 cột | `identity_db.permissions.service = 'support-service'` (dòng seed V9 + dòng đăng ký tự động) |
| S | Kafka | 2 consumer group | `support-service`, `support-service-notification` (đề xuất **giữ nguyên**, mục 2.3) |
| S | Tài liệu gốc ngoài `docs/` | 4 | `README.md` (9 chỗ: bảng service, profile, biến môi trường), `CLAUDE.md` (2 chỗ), `.claude/agents/sa.md`, `.claude/agents/be-dev.md` |
| S | Tài liệu trong `docs/` | 19 tệp, ~97 dòng | nhiều nhất: `architecture/multi-database-report.md` (32), `decisions/0012` (10), `decisions/0008` (7), `superpowers/plans/2026-11-15-multi-tenant.md` (7), `decisions/0007` (6), `decisions/0010` (5); còn lại `0002`, `0004`, `0005`, `0006`, `0009`, `api/notifications.md`, `api/customer-api.md`, `api/customer-api-contact-reader.md`, `architecture/sales-module-design.md`, `architecture/legacy-lm-migration.md`, `requirements/2026-11-17-hop-dong-thue-dich-vu.md` |
| S | Chú thích ở service khác | 3 | `identity/.../LoginUseCase.java`, `ManageOrganizationUseCase.java` ("audit của support"), FE `AdminHome.tsx` (repo FE) |
| C | Context `audit`, `notification`; DB `audit_db`, vai trò `audit`/`audit_app`, target `audit`, Mongo `audit_logs`; tên controller `AuditLogController`, `NotificationController`; đường dẫn `/internal/audit-logs`, `/internal/notifications/**`, `/api/audit-logs`, `/api/notifications/**` | 0 thay đổi | giữ nguyên |
| N | "support" nghĩa khác | ~45 tệp, không đụng | `org.springframework.*.support.*`, `UnsupportedOperationException`, `UNSUPPORTED_MEDIA_TYPE`, `HttpRequestMethodNotSupportedException`, `RelationalProviderSupport`, `lms.database.testsupport`, `lms.sales.support` (test harness), `RequestSupport` (sales), `SQLFeatureNotSupportedException`... |

Tổng phải sửa: khoảng **120 tệp code/cấu hình** (76 + 3 + 1 + 3 + 14 + 13 + 1 + 1 + 1 + 2), **1 migration mới**, **~25 tệp tài liệu**; **0** thay đổi DB schema, vai trò DB, volume, cổng, đường dẫn API, khóa quyền.

## 2. Quyết định

### 2.1. Các phương án

| Tiêu chí | A. Giữ `support-service` + ghi chú phạm vi | B. `notification-service` | C. Tách `audit-service` + `notification-service` | **D. Đổi thành `audit-service` (đề xuất thêm)** |
|---|---|---|---|---|
| Đúng ngữ nghĩa | ❌ vẫn gợi ticket/CSKH; chỉ chữa bằng tài liệu | ❌ giấu context lớn hơn (audit, 2 kho Postgres + Mongo, 5 topic) | ✅ đúng tuyệt đối | ⚠️ đúng với audit và toàn bộ tầng dữ liệu; notification là "khách" ghi trong README |
| Khớp tên dữ liệu sẵn có | ❌ lệch (`support` ↔ `audit_db`) | ❌ lệch (`notification` ↔ `audit_db`, `audit_app`) | ⚠️ notification phải có DB mới | ✅ trùng `audit_db`, `audit_app`, target `audit`, Mongo `audit_logs` |
| Thay đổi DB/volume | không | không | ❌ DB `notification_db` mới, vai trò `notification`/`notification_app`, target migration mới, chuyển dữ liệu `notifications` (RLS FORCE: phải đặt `app.tenant_id` hoặc chạy superuser), xóa bảng cũ ở `audit_db` | không |
| Kafka | không | đổi/giữ group | ❌ thêm 1 JVM tiêu thụ `reservation.events`, `identity-events`; tách group | giữ group (2.3) |
| Vận hành | không | như D | ❌ thêm 1 JVM (giới hạn 768m/container, README đang tối ưu RAM 4-5 GiB), thêm Dockerfile, compose, `lms-api` thêm client + URL | như hiện tại |
| ArchUnit, RLS | không | như D | ❌ thêm `TenantArchitectureRulesTest`, `ArchitectureTest`, `AppRoleDatabase` cho service mới | đổi package trong test |
| Chi phí code | ~0 (vài dòng tài liệu) | ~120 tệp | ~120 tệp + service mới + migration dữ liệu | ~120 tệp (cơ học) |
| Tiến hóa | — | tách audit ra sau thì tên sai hẳn | — | tách notification ra sau: tên `audit-service` tự đúng, không đổi lần hai |

Phương án ngoài phạm vi so sánh:

- ❌ `platform-service`: **loại**. Trùng ngữ nghĩa với `system-service` (runner Flyway, `menu`, `cms`, tức phần "nền tảng" của hệ thống) và với từ "platform" đã dùng cho cấp nền tảng: `@PlatformAdminOnly`, `/api/platform/**`, claim `platformAdmin`, `PlatformOperationEvent`. Đặt tên này sẽ tạo đúng loại hiểu nhầm đang muốn sửa, chỉ là theo hướng khác.
- ❌ `activity-service` (khớp nhãn FE "Nhật ký hoạt động"): loại vì thêm thuật ngữ thứ ba không có trong DB/code; nếu sau này tách notification thì tên mơ hồ.
- ❌ `audit-notification-service`: đúng nhưng package `lms.auditnotification` khó đọc và tên sai ngay khi tách.

### 2.2. Chọn D: đổi `support-service` → `audit-service`

Lý do:

1. Tên service khớp toàn bộ tầng dữ liệu đã có (`audit_db`, `audit_app`, target `audit`, `audit_logs`), nên **không đụng DB, volume, vai trò, Flyway**; rủi ro dữ liệu gần như bằng không (chỉ một cột hiển thị `permissions.service`).
2. "audit" không kéo tính năng sai chỗ như "support". Thiếu sót còn lại (notification nằm trong `audit-service`) là thiếu mô tả, không phải mô tả sai; chữa bằng README và bảng service.
3. Không thêm JVM/DB như C; ADR-0002 vẫn đúng: context đã cô lập bằng ArchUnit nên tách notification ra service riêng làm được về sau mà **không phải đổi tên lần hai**.
4. Package `lms.audit.audit.*` có tiền lệ `lms.catalog.catalog.*` (catalog-service), giữ quy ước "package gốc = tên service ngắn, context bên dưới".

Đánh đổi chấp nhận: ~120 tệp đổi cơ học, phải chốt thời điểm không có phiên khác sửa support (mục 5); lịch sử `git log` theo đường dẫn cần `--follow`.

Điều kiện chuyển sang C (tách) sau này, không làm bây giờ: notification có kênh ngoài (email/SMS/push) cần thư viện, nhịp triển khai hoặc tải khác hẳn audit.

Nếu người dùng không muốn tốn công đổi ngay: A kèm ghi chú là phương án dự phòng chấp nhận được, nhưng **bắt buộc** ghi rõ "service này không nhận ticket/liên hệ" vào README và đóng Q1 của liên hệ đọc giả (mục 7) để hiểu nhầm không lan tiếp.

### 2.3. Quy tắc giữ tương thích khi đổi

| | Điểm | Quyết định |
|---|---|---|
| ✅ | Khóa quyền | **Không** đổi tên lớp `AuditLogController`, `NotificationController` và tên method (ở service và ở `lms-api`). Khóa `AuditLogController/search` giữ nguyên; role đã gán không mất quyền. Package đổi không ảnh hưởng khóa (`getSimpleName`). |
| ✅ | Đường dẫn API | Giữ `/internal/audit-logs`, `/internal/notifications/**`, `/api/audit-logs`, `/api/notifications/**`. FE không phải sửa. Cổng giữ 8084. |
| ✅ | DB | Giữ `audit_db`, `audit`, `audit_app`, `AUDIT_DB_*`, `LOG_DB_URI`, Mongo `audit_logs`. Không cần `docker compose down -v`. |
| ✅ | Consumer group | Giữ **cố định** `support-service` và `support-service-notification`: viết thẳng `group-id: support-service` trong `application.yml` kèm chú thích "tên lịch sử, đổi là mất offset", giữ fallback `:support-service` trong hai `@KafkaListener`. Không gắn group id vào `spring.application.name`. |
| ✅ | `permissions.service` | Migration identity mới: `UPDATE permissions SET service = 'audit-service' WHERE service = 'support-service';` (bảng toàn cục, không RLS nên không cần `app.tenant_id`; idempotent). Đi cùng bản phát hành đổi tên: system-service chạy trước nên khi `audit-service` lên, dòng cũ đã đổi, khóa mới đăng ký với `audit-service`. Không sửa V4, V9. |
| ✅ | URL của BFF | `app.services.audit-url: ${AUDIT_SERVICE_URL:${SUPPORT_SERVICE_URL:http://localhost:8084}}` trong một chu kỳ, để `.env`/môi trường cũ còn chạy; bỏ fallback ở lần dọn sau. |
| ✅ | Compose profile | Service `audit-service` mang profile `[apps, audit, support, lending]`: giữ `support` làm bí danh một chu kỳ. `dev-start.ps1`: thêm `-WithAudit`, giữ `-WithSupport` làm bí danh. |
| ✅ | `TokenRelayInterceptor` | Chuyển sang `lms.api.config` (cạnh `ServiceClients`), không để trong `lms.api.audit`; bỏ ngoại lệ tương ứng ở `LmsApiArchitectureTest`. |
| ✅ | Tên lớp `lms-api` | `SupportClient` → `AuditClient`, `HttpSupportClient` → `HttpAuditClient`, `SupportClientConfiguration` → `AuditClientConfiguration`, `ServicesProperties.supportUrl` → `auditUrl`. `lms-api` không có `PermissionInterceptor` nên không sinh khóa. |
| ✅ | Mã lỗi | Giữ `INVALID_AUDIT_QUERY` (tên lịch sử, `docs/api/notifications.md`), không đổi hợp đồng lỗi. |
| ✅ | ADR cũ | Không viết lại nội dung ADR-0002..0013; thêm một dòng ℹ️ "đổi tên thành `audit-service` theo ADR-0014" ở đầu các ADR nhắc tên. Tài liệu đang dùng (README, CLAUDE.md, `docs/api/**`, `multi-database-report.md`) thì sửa hẳn. |

## 3. Thiết kế

```
Trước                                         Sau
nginx :8080 ─> lms-api :8085                  nginx :8080 ─> lms-api :8085
  lms.api.support.{client,dto,web}              lms.api.audit.{client,dto,web}
  lms.api.support.TokenRelayInterceptor         lms.api.config.TokenRelayInterceptor
  SUPPORT_SERVICE_URL ─> support-service:8084   AUDIT_SERVICE_URL (fallback SUPPORT_SERVICE_URL) ─> audit-service:8084
services/support-service  (lms.support.*)     services/audit-service  (lms.audit.*)
  audit        ─> audit_db (audit_app), Mongo audit_logs      (không đổi)
  notification ─> audit_db.notifications                      (không đổi)
  Kafka group support-service[-notification]                  (giữ nguyên)
  spring.application.name support-service   ─> audit-service
identity_db.permissions.service 'support-service' ─> 'audit-service' (migration identity mới)
```

Không có endpoint, lỗi nghiệp vụ hay quy tắc domain mới.

## 4. Phân việc

### 4.1. Điều kiện tiên quyết (điều phối viên)

| | Việc | Ghi chú |
|---|---|---|
| 🚫 | Người dùng chốt tên (D hay A) và thời điểm | mục 6 |
| ⏳ | Chốt "cửa sổ đổi tên": mọi thay đổi chưa commit trong `services/support-service/**`, `lms-api/src/**/lms/api/support/**`, `docker-compose.yml`, `scripts/dev-start.ps1` đã được chủ của chúng commit | `git status --short -- services/support-service lms-api docker-compose.yml scripts` phải rỗng ở các đường dẫn đó |
| ⏳ | Không giao việc mới vào support trong cửa sổ (ADR-0010 thêm `sales.events`, ADR-0012 B6 `ContractExpiringConsumer`) | Làm trước cửa sổ rồi commit, hoặc làm sau trên đường dẫn mới |
| ⏳ | Lấy số migration identity kế tiếp **ngay lúc làm** | Trên đĩa mới nhất `V16`; ⚠️ `V17`, `V18` đã có thiết kế nhắm (sales B1.6/B2.3, ADR-0012, ADR-0013) |

### 4.2. Các bước

| | Bước | Ai | Phạm vi file | Phụ thuộc | Song song |
|---|---|---|---|---|---|
| ⏳ | R1. Đổi module service | be-dev #1 | `git mv services/support-service services/audit-service`; `git mv .../java/lms/support .../java/lms/audit` (main và test); sửa `package`/`import` (`lms.support.` → `lms.audit.`); `SupportServiceApplication` → `AuditServiceApplication`; `SupportServiceIT` → `AuditServiceIT`; `pom.xml` của service (artifactId, name), `pom.xml` gốc (`<module>`); `Dockerfile` (3 đường dẫn); `application.yml` (`name: audit-service`, `group-id: support-service` cố định + chú thích, `entity-packages lms.audit...`); `application-dev.yml` (`logging.level.lms.audit`); `README.md` của service (sửa luôn `support_db` sai); `@AnalyzeClasses(packages = "lms.audit")` | cửa sổ 4.1 | song song R2, R3 |
| ⏳ | R2. Đổi `lms-api` | be-dev #2 | `git mv lms-api/src/{main,test}/java/lms/api/support → .../audit`; `TokenRelayInterceptor` → `lms.api.config`; đổi tên lớp client/config (2.3); `ServicesProperties.auditUrl`; `application.yml` `audit-url` có fallback; mọi import ở 12 tệp liệt kê trong 1.2; `LmsApiArchitectureTest`, `LmsApiIdentityArchitectureTest` (`lms.api.audit..`, bỏ ngoại lệ interceptor) | cửa sổ 4.1 | song song R1, R3 |
| ⏳ | R3. Hạ tầng, script, migration | be-dev #3 | `docker-compose.yml` (khối service, profile bí danh, `AUDIT_SERVICE_URL`, `depends_on` của nginx, chú thích); `scripts/dev-start.ps1` (khóa `audit`, jar `services\audit-service\target\audit-service-0.1.0-SNAPSHOT.jar`, `-WithAudit` + bí danh); chú thích `init-databases.sql`, `migration/audit/afterMigrate.sql`; migration mới `system-service/.../migration/identity/V<n>__doi_ten_service_support_thanh_audit.sql` (UPDATE ở 2.3); `customer-api/.../CustomerApiArchitectureTest.java` (`lms.audit..`); chú thích `LoginUseCase`, `ManageOrganizationUseCase` | cửa sổ 4.1 | song song R1, R2 |
| ⏳ | R4. Tài liệu | be-dev #3 hoặc sa | `README.md`, `CLAUDE.md` (lệnh `mvn -pl services/audit-service`, bảng, profile, biến), `.claude/agents/{sa,be-dev}.md`, `docs/api/notifications.md`, `docs/api/customer-api.md`, `docs/architecture/multi-database-report.md`, `docs/architecture/sales-module-design.md`; dòng ℹ️ ở ADR-0002, 0004..0012; ADR-0014 → "chấp nhận" | R1-R3 xong (để đường dẫn trong tài liệu đúng) | sau R1-R3 |
| ⏳ | R5. Commit | điều phối | Một commit cho R1+R2+R3 (build không gãy giữa chừng): `refactor(audit)!: đổi tên support-service thành audit-service, giữ DB, cổng, khóa quyền và consumer group`; commit riêng `docs: ...` cho R4 | R1-R4 | — |
| ⏳ | T1. Kiểm tĩnh và unit | tester | `mvn test` toàn repo (ArchUnit 3 nơi: audit-service, lms-api, customer-api; `TenantArchitectureRulesTest`); grep `support-service\|lms\.support\|lms\.api\.support\|SupportClient` chỉ còn trong: V4, V9, `group-id`/fallback Kafka, fallback `SUPPORT_SERVICE_URL`, profile bí danh, dòng ℹ️ ADR | R1-R3 | song song T2 |
| ⏳ | T2. IT | tester | `-Dtest=AuditServiceIT,AuditTenantIsolationIT,AuditCrossTenantReadIT` (thêm `-DargLine=-Dapi.version=1.44` với Docker 29), `NotificationControllerSecurityTest`, `AuditLogSecurityTest`; IT migration của system-service (`MigrationApiIT`) để chắc migration identity mới chạy trên schema thật | R1-R3 | song song T1 |
| ⏳ | T3. Chạy thật trên **volume cũ** (không `down -v`) | tester | `docker compose --profile apps up -d --build --remove-orphans`; kiểm: (a) container `support-service` cũ đã bị gỡ; (b) `audit-service` kết nối bằng `audit_app`; (c) `kafka-consumer-groups --describe --group support-service` và `support-service-notification`: lag về 0, không reset offset; (d) `SELECT DISTINCT service FROM permissions` không còn `support-service`; (e) FE: chuông thông báo, màn Nhật ký hoạt động, màn gán quyền (nhóm `audit-service / AuditLogController`), role đã có quyền xem audit vẫn xem được; (f) `dev-start.ps1 -WithSupport` và `-WithAudit` đều bật được | T1, T2 | — |
| ⏭️ | perf-tester | — | Không cần: không đổi luồng, truy vấn hay tài nguyên | — | — |

## 5. Rủi ro và việc chưa kiểm chứng

| | Rủi ro | Xử lý |
|---|---|---|
| ⚠️ | Phiên khác đang sửa đường dẫn cũ (14 tệp chưa commit, ADR-0010/0012 nhắm vào support): đổi tên giữa chừng làm tệp của họ rơi vào đường dẫn cũ, tạo lại `services/support-service` mồ côi hoặc xung đột merge khó | Bắt buộc cửa sổ 4.1; R1-R3 làm và commit trong một lượt ngắn; báo các phiên sau dùng đường dẫn mới. Không đổi theo nhiều commit rời làm build gãy giữa chừng. |
| ⚠️ | Đổi consumer group nhầm (ví dụ để group id theo `spring.application.name`) | Group mới với `earliest` đọc lại toàn bộ event còn giữ (idempotent nên không nhân bản, nhưng tốn thời gian, log nhiều); với `latest` mất event trong khoảng chuyển. Giữ group cố định (2.3); T3 (c) kiểm. |
| ⚠️ | Container `support-service` cũ còn chạy cùng `audit-service` (cùng group) | Chia partition giữa hai tiến trình, ghi cùng DB (idempotent nên không hỏng dữ liệu) nhưng khó gỡ lỗi. Dùng `--remove-orphans` hoặc `docker rm -f` container cũ. |
| ⚠️ | Quên migration `permissions.service` | Không mất quyền (khóa giữ nguyên) nhưng màn gán quyền tách hai nhóm. T3 (d), (e). |
| ⚠️ | Số migration identity trùng với phiên khác | Lấy số ngay lúc làm, kiểm lại trước commit; không sửa file đã chạy. |
| ⚠️ | Môi trường ngoài repo (`.env` riêng, k8s/helm sau này) đặt `SUPPORT_SERVICE_URL` hoặc hostname `support-service` | Fallback một chu kỳ; `deployment/helm`, `deployment/kubernetes` hiện rỗng (đã kiểm). ⚠️ chưa kiểm chứng `.env` trên máy người khác. |
| ⚠️ | Cache CDS `.dev/cds/support` của `dev-start.ps1` | Tự sinh lại theo khóa mới; ⚠️ chưa kiểm chứng script không lỗi khi thư mục cũ còn. |
| ⚠️ | `mvn` còn jar cũ `support-service` trong `~/.m2` hoặc `target/` | Vô hại (không ai phụ thuộc artifact này); `mvn clean` sau khi pull. |
| ℹ️ | Lịch sử git | `git log --follow` cho tệp; scope commit sau này là `audit` thay cho `support`. |
| ⚠️ | Chưa chạy bất kỳ build/test nào cho ADR này | Mọi số liệu ở 1.2 lấy từ grep trên working tree ngày 2026-11-17; phải đếm lại ngay trước khi làm vì working tree đang đổi. |

## 6. Câu hỏi mở cho người dùng

1. 🚫 Chọn D (`audit-service`, khuyến nghị) hay A (giữ tên, chỉ ghi chú)? Nếu muốn tên khác `audit-service`, tên đó không được trùng nghĩa với `system-service` hay "platform".
2. 🚫 Thời điểm: đổi ngay sau khi các phiên đang sửa support commit xong (khuyến nghị), hay đợi xong ADR-0012 B6 và phần audit của ADR-0010?
3. 🚫 Giữ bí danh (`SUPPORT_SERVICE_URL`, profile `support`, `-WithSupport`) bao lâu? Khuyến nghị: tới lần dọn kế tiếp rồi bỏ trong một commit `refactor` riêng.

## 7. Tác động tới nơi lưu liên hệ đọc giả (`customer-api-contact-reader.md` Q1)

- ℹ️ Dù chọn D hay A, liên hệ đọc giả **không** đặt ở service này: audit/notification là luồng sinh từ event, không có dữ liệu nghiệp vụ do người dùng nhập, không có khái niệm hội thoại/trạng thái xử lý. Đặt vào đây chỉ vì cái tên "support" chính là hiểu nhầm ADR này sửa.
- ℹ️ Ứng viên còn lại: `sales-service` (gắn đơn hàng, module SALES, storefront đã ở đó) là khuyến nghị; nếu sau này cần liên hệ dùng chung nhiều loại thì xét context riêng, không xét lại service này. Q1 nên sửa thành "`sales-service` hay context mới".
- ℹ️ Q5 (thông báo cho nhân viên khi có liên hệ mới) vẫn dùng `notification` của service này qua event Kafka, giống tiền lệ `ContractExpiringConsumer` (ADR-0012); không đổi theo tên.
