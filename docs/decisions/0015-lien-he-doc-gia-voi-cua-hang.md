# ADR-0015: Liên hệ của đọc giả gửi cửa hàng (context `contact` trong sales-service, gửi qua customer-api, xử lý qua lms-api)

- Trạng thái: đã cài đặt B0 đến B7 (2026-11-17), CHƯA commit; chưa chạy T8 đầu-cuối với toàn bộ compose (chỉ kiểm nginx độc lập) và chưa đo P1. Các câu Q1 đến Q5 của `docs/api/customer-api-contact-reader.md` đã được người dùng chốt theo khuyến nghị (mục 0.6). Số migration dùng: sales `V4__create_shop_contact_requests.sql`, identity `V22__sales_shop_contact_permissions.sql`. Lệch nhỏ so với thiết kế: ký tự điều khiển bị TỪ CHỐI (400, theo AC4) thay vì bị bỏ âm thầm như câu chữ R3; thêm mã `401 USER_REQUIRED` ở sales (không có người dùng trong token).
- Liên quan: ADR-0003 (khóa `Controller/method`), ADR-0005 (BFF `lms-api`), ADR-0006 (multi-tenant, RLS FORCE), ADR-0008 (`platform_read_all`), ADR-0009 (module SALES), ADR-0010 (sales-service, `SalesAuditPort`, job ẩn danh 2.8, `sales_tenants`), ADR-0011 (customer-api, nhóm cần đăng nhập 2.4, danh sách `@StorefrontAccessible` đóng 2.1), ADR-0013 (CORS customer-api), ADR-0014 (đổi tên support-service: ADR này **không** đụng service đó)
- Thay thế phần đề xuất: `docs/api/customer-api-contact-reader.md` mục 3, 4, 7 (đường dẫn nhân viên `/api/shop-contacts` và trạng thái `HANDLED`/`SPAM` ở bản nháp **không** dùng).
- Thiết kế phân quyền dữ liệu nền: `docs/architecture/customer-api-data-authorization.md` (A2 đến A5, A15, A19, 5.1, 5.6, 5.8, 5.11).

## 0. Đề bài rút gọn (thay vòng BA riêng)

### 0.1. Mục tiêu

Khách đã đăng nhập ở storefront của một cửa hàng (tenant có module SALES) gửi được yêu cầu liên hệ tới cửa hàng; nhân viên cửa hàng xem, đổi trạng thái xử lý và liên hệ lại khách **ngoài hệ thống** bằng email/điện thoại khách để lại. Dữ liệu cá nhân được tối thiểu hóa và tự ẩn danh sau thời hạn lưu giữ.

### 0.2. Phạm vi

| | Làm | Không làm (giai đoạn này) |
|---|---|---|
| Khách | Gửi yêu cầu (`POST`), bắt buộc đăng nhập bằng token storefront (`aud=lms-storefront`) đúng tenant | Xem lại yêu cầu đã gửi, sửa, hủy; gửi khi chưa đăng nhập; đính kèm tệp; gắn `orderId` |
| Nhân viên | Danh sách (lọc, phân trang, PII che), chi tiết, xem liên hệ đầy đủ (khóa riêng), đổi trạng thái, ẩn danh ngay | Trả lời trong hệ thống (hội thoại), gán người xử lý, ghi chú nội bộ, xuất file |
| Hệ thống | Job ẩn danh theo thời hạn của tenant; audit thao tác nhân viên | Thông báo cho nhân viên (Q5), gửi email, Kafka event riêng cho liên hệ |

### 0.3. Quy tắc nghiệp vụ

| # | Quy tắc |
|---|---|
| R1 | Chỉ tenant ACTIVE có bật SALES; mọi trường hợp khác `404 SHOP_NOT_AVAILABLE` (một thân, ADR-0011 2.5). |
| R2 | Người gửi = `sub` của token; không nhận `userId`/`tenantId`/`status` từ body. |
| R3 | Trường nhập: `topic` (`ORDER`, `PRODUCT`, `PAYMENT`, `OTHER`), `fullName` (1..100 ký tự), `email` (đúng định dạng, ≤ 254), `phone` (tùy chọn, ≤ 20, chỉ chữ số và `+ ( ) . -` khoảng trắng, tối thiểu 6 chữ số), `message` (1..2000 ký tự), `consent` (phải `true`). Độ dài tính theo **code point** sau chuẩn hóa (NFC, `\r\n` → `\n`, bỏ ký tự điều khiển trừ `\n` `\t`, cắt khoảng trắng đầu/cuối), như `Title` của ADR-0011 2.12d. |
| R4 | Chống trùng: cùng người, cùng `topic` và cùng `message` (sau chuẩn hóa) trong **10 phút** → coi là gửi lại, trả `201` như thường, **không** tạo dòng mới. |
| R5 | Giới hạn: tối đa **5** yêu cầu mỗi người trong **24 giờ trượt**; vượt → `429 SHOP_CONTACT_LIMIT_EXCEEDED`. R4 xét trước R5 (bấm hai lần ở yêu cầu thứ 5 vẫn `201`). |
| R6 | Trạng thái: `NEW` → `IN_PROGRESS` → `CLOSED`, cho phép `NEW` → `CLOSED` (spam, trùng, đã giải quyết ngay). Không mở lại `CLOSED`. Chỉ nhân viên có khóa đổi; khóa lạc quan bằng `version`. |
| R7 | Lưu giữ: dữ liệu cá nhân giữ theo `walkInPiiRetentionMonths` của tenant (mặc định 12, 1..120 tháng), tính từ `created_at`, **mọi trạng thái** (kể cả `NEW` chưa xử lý). Hết hạn thì ẩn danh: `contact_name`, `contact_email`, `contact_phone`, `message`, `customer_user_id` → NULL, ghi `pii_anonymized_at`; giữ dòng, `topic`, `status`, các mốc thời gian. |
| R8 | Nhân viên ẩn danh ngay một yêu cầu khi khách đòi xóa (cùng phương thức domain với job, idempotent). |
| R9 | Danh sách che PII; chi tiết có `message` nhưng liên hệ vẫn che; liên hệ đầy đủ chỉ qua khóa riêng (mẫu `SalesOrderController/getContact`). |
| R10 | PII không vào log, audit, event Kafka, thông điệp lỗi. |

### 0.4. Vai trò

| Vai trò | Có gì |
|---|---|
| Khách (role mẫu `CUSTOMER`, `signup_default`) | `ShopContactController/create` |
| Quản lý cửa hàng (`STORE_MANAGER`) | 5 khóa nhân viên (mục 2.5) |
| Thu ngân (`CASHIER`), Thủ kho (`STOCK_KEEPER`) | Không có mặc định (thống nhất với V17: Thu ngân không có `getContact` của đơn). Tenant admin gán thêm được. |
| Admin tenant (`is_admin`) | Mọi thứ (bỏ qua kiểm khóa) |
| Platform admin | Không đọc được (sales không có `@CrossTenantReadable`; bảng không có policy `platform_read_all`, 2.3) |

### 0.5. Tiêu chí nghiệm thu (kiểm được bằng test)

| # | Tiêu chí |
|---|---|
| AC1 | Khách đăng nhập đúng tenant gửi body hợp lệ → `201`, không thân, `Cache-Control: no-store`; DB có một dòng `NEW` đúng `tenant_id`, `customer_user_id = sub`. |
| AC2 | Không token → `401 UNAUTHORIZED`; token `lms-api` (không `aud=lms-storefront`) → `401`; token `svc` → `401`; token tenant khác `{code}` → `403 SHOP_SESSION_MISMATCH`; `{code}` không khả dụng → `404 SHOP_NOT_AVAILABLE` **trước** mọi kiểm phiên. |
| AC3 | Role khách thiếu khóa `ShopContactController/create` → `403 FORBIDDEN` (thông điệp không chứa tên khóa). Tenant tắt SALES → `404 SHOP_NOT_AVAILABLE`. |
| AC4 | Thiếu/sai trường, `topic` lạ, email sai, quá dài (2001 code point), ký tự điều khiển → `400 VALIDATION_FAILED`; `consent` false/thiếu → `400 CONSENT_REQUIRED`; 2000 code point có emoji/tiếng Việt tổ hợp → `201`. |
| AC5 | Mass assignment: body thêm `status`, `customerUserId`, `tenantId`, `id`, `createdAt`, `orderId`, `version` → kết quả giống khi không gửi (dòng `NEW`, chủ là `sub`). |
| AC6 | Gửi trùng trong 10 phút → `201`, vẫn một dòng. Sau 10 phút → dòng mới. |
| AC7 | Yêu cầu thứ 6 trong 24 giờ của cùng người → `429 SHOP_CONTACT_LIMIT_EXCEEDED`; người khác cùng tenant không bị ảnh hưởng. |
| AC8 | Nhân viên có khóa `search` thấy danh sách tenant mình, mới nhất trước, lọc `status`, `topic`, `from`, `to`; tên/email/điện thoại che, **không** có `message`. Không thấy dòng của tenant khác. |
| AC9 | `get` trả `message` + liên hệ che; `getContact` trả liên hệ đầy đủ; id tenant khác hoặc không tồn tại → `404 SHOP_CONTACT_NOT_FOUND` cùng thân. |
| AC10 | `updateStatus` đúng chuyển trạng thái → `200`, `closedAt` đặt khi `CLOSED`; chuyển sai hoặc `version` lệch → `409 SHOP_CONTACT_STATE_CONFLICT`; có một dòng audit `SHOP_CONTACT_STATUS_CHANGED` không PII. |
| AC11 | Job (đồng hồ giả) chỉ ẩn danh dòng có `created_at` trước mốc của **từng tenant** (hai tenant khác thời hạn); chạy lại không đổi gì; dòng ẩn danh trả trường liên hệ `null` và `piiAnonymizedAt`; trạng thái vẫn đổi được. |
| AC12 | Tenant chỉ có liên hệ, chưa có đơn nào vẫn được job xử lý (`sales_tenants` có tenant sau lần gửi đầu). |
| AC13 | Log (bắt bằng appender) của customer-api và sales trong mọi nhánh (thành công, validate lỗi, 429, 409, ngoại lệ) không chứa email, điện thoại, tên, nội dung; `sales_audit_log.data` và payload `sales.events` không chứa các trường đó. |
| AC14 | Token khách gọi endpoint nhân viên → `403`; token `svc` gọi bất kỳ endpoint liên hệ nào → `403` (không `@StorefrontAccessible`). |
| AC15 | Bảng `shop_contact_requests`: RLS ENABLE + FORCE, đúng một policy `tenant_isolation`; `sales_app` không đọc được khi chưa đặt `app.tenant_id`. |

### 0.6. Đã chốt bởi người dùng (2026-11-17)

- ✅ Q1: lưu ở `sales-service`, bảng có `tenant_id` + RLS FORCE + `@TenantId`.
- ✅ Q2: bắt buộc đăng nhập bằng token người dùng (`aud=lms-storefront`) của tenant; không dùng token `svc`.
- ✅ Q3: chỉ ghi nhận; nhân viên liên hệ ngoài hệ thống; trạng thái `NEW` → `IN_PROGRESS` → `CLOSED` do nhân viên đổi.
- ✅ Q4: giữ dữ liệu cá nhân 12 tháng (cấu hình theo tenant) rồi ẩn danh, giữ bản ghi và trạng thái.
- ✅ Q5: chưa thông báo nhân viên; không động support-service.
- ✅ Liên kết `orderId`: để giai đoạn sau. Nhân viên xử lý qua `/api/sales/**` (`lms-api`, package `lms.api.sales`).

## 1. Bối cảnh và hiện trạng đã đối chiếu trong code (2026-11-17, gồm thay đổi chưa commit)

| | Điểm | Kết quả thật |
|---|---|---|
| ✅ | Migration sales | `sales/V1__create_sales_tables.sql`, `V2__create_returns_refunds.sql`, `V3__create_sales_tenants.sql`, `afterMigrate.sql`. Kế tiếp **`V4`**. `afterMigrate.sql` có `ALTER DEFAULT PRIVILEGES ... TO sales_app`, bảng mới tự được cấp quyền. |
| ✅ | Mẫu RLS sales | V1/V2: mọi bảng nghiệp vụ `ENABLE` + `FORCE`, hai policy `tenant_isolation` và `platform_read_all` (`FOR SELECT` khi `app.tenant_scope='all'`). `SalesSchemaIT.moiBangCoRlsEnableForceVaHaiPolicy` kiểm theo danh sách cố định `BANG_NGHIEP_VU`. |
| ✅ | Migration identity | Mới nhất `V21__tenant_storefront_origins.sql`. Kế tiếp **`V22`**. ⚠️ ADR-0014 cũng cần một migration identity (đổi `permissions.service`), chưa tạo: ai tạo sau lấy `V23`. |
| ✅ | Seed khóa sales | `V17__sales_permissions_and_role_templates.sql` (mẫu `c1` CASHIER, `c2` STOCK_KEEPER, `c3` STORE_MANAGER), `V20__sales_phase2_permissions_and_customer_role.sql` (mẫu `c4` CUSTOMER `signup_default`, backfill theo kiểu "role còn nguyên mẫu mới thêm khóa", cập nhật mẫu **sau** backfill). |
| ✅ | Test đối chiếu seed | `SalesPermissionSeedMatchesCodeTest` (sales-service) đọc `classpath*:db/migration/identity/V*__sales_*.sql`, **khẳng định đúng 2 file**, liệt kê controller trong `@WebMvcTest`, regex mẫu `c1..c4`. Thêm file seed thứ ba phải sửa test. |
| ✅ | Job ẩn danh | `AnonymizeWalkInJob` (`ordering.infrastructure.config`) duyệt `TenantDirectoryPort.listAll()` (bảng `sales_tenants`), mỗi tenant `TenantContext.callAs` → `AnonymizeWalkInContactsUseCase` → lô 500 qua `AnonymizeWalkInBatchUseCase` (`@Transactional`, audit `WALK_IN_PII_ANONYMIZED`, actor null). Cờ `lms.sales.anonymize.enabled` (mặc định true), lịch `lms.sales.anonymize.cron` (mặc định `0 30 2 * * *`). |
| ✅ | `sales_tenants` | Chỉ được ghi (`TenantDirectoryPort.touch`, `Propagation.MANDATORY`) trong transaction tạo **đơn**. Tenant chỉ có liên hệ sẽ **không** có trong danh bạ → job bỏ sót nếu không sửa (AC12). |
| ✅ | Thời hạn theo tenant | `sales_settings.walk_in_pii_retention_months` (CHECK 1..120, mặc định 12); `UpdateSettingsRequest` bắt buộc đủ mọi trường (`@NotNull`), tức thêm trường cấu hình mới là đổi hợp đồng `PUT /api/sales/settings`. |
| ✅ | Che PII | `PersonalDataMasker` (`ordering.domain`): `maskName`, `maskPhone`, `maskAddress`; **chưa có** `maskEmail`. `RetentionPolicy.cutoff` cũng ở `ordering.domain`. |
| ✅ | Audit | `SalesAuditPort.record` (MANDATORY transaction) ghi `sales_audit_log` **và** đăng ký phát `sales.events` sau commit (`SalesAuditAdapter`): dữ liệu audit đi ra Kafka, nên càng phải không có PII. |
| ✅ | ArchUnit sales | `SalesArchitectureTest`: `ordering` không phụ thuộc `stock`/`settings` `domain`/`infrastructure`; `stock`, `settings` không biết `ordering`. Context mới phải thêm luật. |
| ✅ | customer-api | `SecurityConfiguration.AUTHENTICATED_API` chỉ có 4 mẫu `/public/t/*/orders...`; `/public/t/*/contact-requests` hiện rơi vào `denyAll`. `OrderController` là khuôn: `storefronts.resolve(code)` → `ShopSessionGuard.requireSession` (kiểm `svc`, `aud=lms-storefront`, tenant) → chuyển tiếp token khách. `PublicExceptionHandler.FORWARDED_CODES` đã có `CONSENT_REQUIRED`, `VALIDATION_FAILED`; alias `INVALID_REQUEST` → `VALIDATION_FAILED`. |
| ❌ | nginx | **Chưa có** location cho `/public/t/*/contact-requests` (chỉ `/public/site/contact-requests`, signup, login). Đường dẫn mới hiện chỉ chịu vùng `public_read` 20r/s. Thông tin "vùng `public_form` đã có cho đường dẫn này" **không đúng với code**; phải thêm location (B6). |
| ✅ | lms-api | `lms.api.sales.web.SalesOrderController` có `GET /api/sales/orders/{id}/contact` → khuôn cho `getContact`. `ChannelAudienceFilter` từ chối token `aud=lms-storefront` ở lms-api. |
| ✅ | Tên lớp | Không có lớp `ShopContactController`, `SalesContactController` nào trong repo (grep). system-service có `ContactRequestAdminController` (form SaaS), khác tên. |
| ✅ | Thêm khóa vào role `signup_default` | Domain identity chỉ cho **admin** thêm khóa vào role đăng ký mặc định (`SignupRoleTest`); migration là đường duy nhất gán sẵn cho tenant hiện có. |

## 2. Quyết định

### 2.1. Nơi lưu: context mới `contact` trong sales-service (đã chốt Q1)

| Tiêu chí | **A. sales-service, context `lms.sales.contact` (chọn)** | B. support-service (`audit-service`) | C. system-service `cms` | D. Service mới |
|---|---|---|---|---|
| Đúng bounded context | Quan hệ khách với cửa hàng, cùng module SALES, sau này gắn `orderId` trong cùng DB | Luồng sinh từ event, không có dữ liệu người dùng nhập (ADR-0014 mục 7) | Bảng `cms_*` cố ý không có tenant (ADR-0011 O3) | Đúng nhưng thừa |
| Tenant + RLS + module | Có sẵn (`@RequiresModule(SALES)`, `sales_app`, RLS) | Có RLS nhưng không có module SALES | Phải thêm tenant vào context nền tảng | Phải dựng lại toàn bộ |
| Chi phí | Một bảng, một context | Đổi tên đang chờ (ADR-0014), thêm API ghi | Trái quyết định O3 | JVM, DB, compose, nginx mới |

Chọn **A**. Context riêng (không nhét vào `ordering`) vì vòng đời, quyền và chính sách lưu giữ khác đơn; ArchUnit giữ `ordering` không biết liên hệ. Giai đoạn gắn `orderId` sau này: `contact.application` gọi `ordering.application` (kiểm sở hữu đơn theo `customer_user_id`, customer-api-data-authorization 5.2); chiều phụ thuộc chỉ `contact → ordering.application`.

### 2.2. Xác thực khách: token người dùng `aud=lms-storefront`, không token `svc` (đã chốt Q2)

- Endpoint nhận ở sales **không** gắn `@StorefrontAccessible` (giữ danh sách đóng ADR-0011 2.1); token `svc` lọt qua BFF vẫn `403` ở interceptor.
- customer-api: thêm `POST /public/t/*/contact-requests` vào `AUTHENTICATED_API`; controller đi đúng thứ tự "phân giải `{code}` → `ShopSessionGuard` → chuyển tiếp token khách" (customer-api-data-authorization 5.8). Phân quyền theo khóa do sales kiểm (ADR-0011 2.4), customer-api không kiểm khóa.
- Đã loại: cho khách vãng lai gửi (cần honeypot/captcha, người gửi không định danh nên không giới hạn theo người, và đây sẽ là đường **ghi** dữ liệu tenant duy nhất của token `svc`, phá bất biến "vãng lai không có đường ghi nào" của ADR-0011 2.1); token `svc` + `userId` trong body (BFF tự khai người gửi, service phải tin BFF).

### 2.3. Mô hình dữ liệu: bảng `shop_contact_requests` (sales_db)

Migration `services/system-service/src/main/resources/db/migration/sales/V4__create_shop_contact_requests.sql` (⚠️ kiểm lại số ngay trước khi tạo). `be-dev` chốt kiểu cuối cùng; cột khái niệm:

| Cột | Kiểu gợi ý | Ràng buộc / ghi chú |
|---|---|---|
| `id` | `UUID` PK | sinh ở ứng dụng |
| `tenant_id` | `UUID NOT NULL` | `@TenantId` |
| `customer_user_id` | `UUID` NULL | NOT NULL khi chưa ẩn danh (CHECK dưới); về NULL khi ẩn danh |
| `topic` | `VARCHAR(20) NOT NULL` | CHECK `IN ('ORDER','PRODUCT','PAYMENT','OTHER')` |
| `message` | `VARCHAR(2000)` NULL | nội dung đã chuẩn hóa; NULL sau ẩn danh |
| `contact_name` | `VARCHAR(100)` NULL | |
| `contact_email` | `VARCHAR(254)` NULL | |
| `contact_phone` | `VARCHAR(20)` NULL | tùy chọn ngay cả khi chưa ẩn danh |
| `consent_at` | `TIMESTAMPTZ NOT NULL` | thời điểm khách đồng ý |
| `status` | `VARCHAR(20) NOT NULL DEFAULT 'NEW'` | CHECK `IN ('NEW','IN_PROGRESS','CLOSED')` |
| `version` | `BIGINT NOT NULL DEFAULT 0` | `@Version` |
| `created_at` | `TIMESTAMPTZ NOT NULL` | mốc tính lưu giữ |
| `closed_at` | `TIMESTAMPTZ` NULL | CHECK `(status = 'CLOSED') = (closed_at IS NOT NULL)` |
| `pii_anonymized_at` | `TIMESTAMPTZ` NULL | |

- CHECK nhất quán ẩn danh: `pii_anonymized_at IS NULL` ⇒ `customer_user_id`, `message`, `contact_name`, `contact_email` đều NOT NULL; `pii_anonymized_at IS NOT NULL` ⇒ cả năm cột PII (`customer_user_id`, `message`, `contact_name`, `contact_email`, `contact_phone`) đều NULL. Lớp chặn cuối nếu code ẩn danh sót cột.
- CHECK `btrim(message) <> ''`, `btrim(contact_name) <> ''` khi khác NULL.
- Chỉ mục: `(tenant_id, status, created_at DESC)` (danh sách nhân viên); `(tenant_id, customer_user_id, created_at DESC) WHERE customer_user_id IS NOT NULL` (R4, R5); `(tenant_id, created_at) WHERE pii_anonymized_at IS NULL` (job).
- RLS: `ENABLE` + `FORCE` + **chỉ** policy `tenant_isolation` (cùng biểu thức V1). **Không** tạo `platform_read_all`: bảng chứa văn bản tự do của khách, nền tảng không có nhu cầu đọc, sales không có `@CrossTenantReadable`; bỏ policy là phòng thủ thêm nếu sau này ai bật đọc xuyên tenant cho sales. Hệ quả: `SalesSchemaIT` kiểm bảng này riêng (đúng một policy). Migration chỉ DDL, không ghi dữ liệu nên không cần `app.tenant_id`.
- Không có cột `order_id` lúc này (giai đoạn sau thêm `order_id UUID NULL` bằng `ALTER TABLE ... ADD COLUMN`, rẻ, không phá dữ liệu). Không có `subject` tự do: `topic` là enum để giảm văn bản tự do chứa PII.
- Không lưu băm nội dung cho R4: số dòng của một người trong 24 giờ ≤ 5, use case đọc các dòng đó (của chính người gửi) và so trong bộ nhớ; thêm cột băm vừa thừa vừa là dữ liệu suy ra từ nội dung phải ẩn danh.

Đã loại: giữ `customer_user_id` sau ẩn danh (chỉ là giả danh hóa: nối lại được với tài khoản identity có tên/email; R5 chỉ cần 24 giờ gần nhất); xóa hẳn dòng (mất thống kê số yêu cầu theo chủ đề/trạng thái, trái chốt Q4).

### 2.4. Thời hạn lưu giữ: dùng lại `walkInPiiRetentionMonths`

| Tiêu chí | **A. Dùng lại `walkInPiiRetentionMonths` (chọn)** | B. Cột mới `contact_pii_retention_months` | C. Cấu hình nền tảng (yaml) |
|---|---|---|---|
| Theo tenant (đã chốt) | Có | Có | **Không** |
| Thay đổi hợp đồng | Không: `PUT /api/sales/settings`, domain `SalesSettings`, FE giữ nguyên | Thêm trường bắt buộc vào `UpdateSettingsRequest` (hiện mọi trường `@NotNull`) → FE cũ gãy, hoặc phải thêm ngữ nghĩa "null = giữ nguyên" chỉ cho một trường | Không |
| Ngữ nghĩa | Một chính sách "dữ liệu cá nhân khách do cửa hàng thu" cho tenant; tên trường lệch nghĩa (walk-in) | Rõ ràng | |

Chọn **A**. Lý do: người dùng chốt cùng con số 12 tháng cho cả hai loại, không có nhu cầu khác nhau; chi phí B là đổi hợp đồng settings mà chưa có giá trị. Ghi rõ trong `docs/api/sales.md`: `walkInPiiRetentionMonths` áp cho cả liên hệ đọc giả. Khi cần tách: thêm cột `contact_pii_retention_months` NULL (NULL = theo `walkInPiiRetentionMonths`) và trường **tùy chọn** ở API, không phá hợp đồng.

Mốc tính là `created_at` (khác đơn: đơn tính từ khi kết thúc vì cần đối soát tiền; liên hệ không có nghĩa vụ giữ, và áp cho cả `NEW` là tối thiểu hóa dữ liệu, cùng tiền lệ `cms_contact_requests` ADR-0011 2.10).

### 2.5. API và khóa quyền (CHỐT, dùng cho seed identity)

Khóa sinh từ `TênController/tênMethod` ở **sales-service** (ADR-0003). Tên dưới đây **chốt**; đổi tên là đổi khóa, role đã gán mất quyền, phải sửa ADR + seed + test cùng lúc.

**Khách**: `lms.sales.contact.infrastructure.web.ShopContactController`, `@RequestMapping("/internal/sales/shop/contact-requests")`, `@RequiresModule(SALES)` mức lớp.

| Khóa | Service nhận | customer-api (công khai) | Vào | Ra |
|---|---|---|---|---|
| `ShopContactController/create` | `POST /internal/sales/shop/contact-requests` | `POST /public/t/{code}/contact-requests` | `{topic, fullName, email, phone?, message, consent}` | `201` không thân (kể cả khi trùng R4) |

**Nhân viên**: `lms.sales.contact.infrastructure.web.SalesContactController`, `@RequestMapping("/internal/sales/contact-requests")`, `@RequiresModule(SALES)` mức lớp. lms-api ánh xạ 1-1 ở `/api/sales/contact-requests` (lms-api chỉ xác thực, ADR-0005).

| Khóa | Service nhận | lms-api | Vào | Ra |
|---|---|---|---|---|
| `SalesContactController/search` | `GET /internal/sales/contact-requests` | `GET /api/sales/contact-requests` | query record `{status?, topic?, from?, to?, page?, size?}` (`from` bao gồm, `to` loại trừ, theo `created_at`; `size` ≤ 100) | trang `ShopContactSummaryView`, mới nhất trước |
| `SalesContactController/get` | `GET /internal/sales/contact-requests/{id}` | `GET /api/sales/contact-requests/{id}` | | `ShopContactDetailView`; `404 SHOP_CONTACT_NOT_FOUND` |
| `SalesContactController/getContact` | `GET /internal/sales/contact-requests/{id}/contact` | `GET /api/sales/contact-requests/{id}/contact` | | `{fullName, email, phone, piiAnonymizedAt}` đầy đủ (đã ẩn danh: các trường `null`) |
| `SalesContactController/updateStatus` | `PUT /internal/sales/contact-requests/{id}/status` | `PUT /api/sales/contact-requests/{id}/status` | `{status, version}` (cả hai bắt buộc) | `ShopContactDetailView`; `409 SHOP_CONTACT_STATE_CONFLICT` |
| `SalesContactController/anonymize` | `POST /internal/sales/contact-requests/{id}/anonymization` | `POST /api/sales/contact-requests/{id}/anonymization` | không thân | `ShopContactDetailView` (đã ẩn danh); gọi lại là no-op `200` |

DTO (allowlist, dựng tường minh, không trả entity; test so **tập tên trường**):

| DTO | Trường | Không bao giờ có |
|---|---|---|
| `ShopContactSummaryView` | `id, topic, status, createdAt, closedAt, contact{name, email, phone} (che), piiAnonymizedAt, version` | `message`, `customerUserId`, `tenantId`, liên hệ đầy đủ |
| `ShopContactDetailView` | như trên + `message`, `customerUserId` | `tenantId`, liên hệ đầy đủ |
| `ShopContactContactView` (đặt tên tùy `be-dev`) | `fullName, email, phone, piiAnonymizedAt` | mọi trường khác |

Che: tên `maskName` (ký tự đầu mỗi từ), điện thoại `maskPhone` (3 số cuối), email `maskEmail` mới (ký tự đầu phần trước `@` + `***` + `@` + tên miền, ví dụ `b***@example.vn`).

Đã loại:

| Phương án | Lý do loại |
|---|---|
| Một controller chung cho khách và nhân viên (`ShopContactController/create|search|...`) | Màn gán quyền nhóm theo controller: khóa của khách lẫn với khóa nhân viên, dễ gán nhầm `search` cho role Khách hàng. Tách theo đúng khuôn `ShopController` (khách) / `SalesOrderController` (nhân viên). |
| Thêm method vào `ShopController` có sẵn (`ShopController/createContactRequest`) | `ShopController` thuộc `ordering`; kéo liên hệ vào context đơn hàng, trái 2.1. |
| `GET /public/t/{code}/contact-requests/mine` | Không có nhu cầu (Q3: không trả lời trong hệ thống, trạng thái là việc nội bộ); thêm DTO công khai, khóa, bề mặt IDOR. Khi cần: `ShopContactController/mine`, lọc `customer_user_id = sub` trong truy vấn, migration seed mới. ⏭️ |
| Trả `id` khi tạo | Khách không có endpoint dùng `id`; không trả để không phân biệt "tạo mới" với "trùng" (R4). |
| Gộp `getContact` vào `get` | Mất khả năng giao danh sách/chi tiết cho người không được xem liên hệ, khác khuôn đơn hàng đã chốt. |

### 2.6. Gán khóa cho role mẫu (migration identity `V22`)

| Khóa | `CUSTOMER` (`c4`) | `STORE_MANAGER` (`c3`) | `CASHIER` (`c1`) | `STOCK_KEEPER` (`c2`) |
|---|---|---|---|---|
| `ShopContactController/create` | ✅ | | | |
| `SalesContactController/search` | | ✅ | | |
| `SalesContactController/get` | | ✅ | | |
| `SalesContactController/getContact` | | ✅ | | |
| `SalesContactController/updateStatus` | | ✅ | | |
| `SalesContactController/anonymize` | | ✅ | | |

File `services/system-service/src/main/resources/db/migration/identity/V22__sales_shop_contact_permissions.sql` (tên **phải** khớp `V*__sales_*.sql` để `SalesPermissionSeedMatchesCodeTest` đọc; ⚠️ kiểm lại số ngay trước khi tạo, và phối hợp với migration của ADR-0014). Cấu trúc theo đúng V20:

1. `INSERT INTO permissions (...) VALUES (6 khóa, 'sales-service', method, path) ON CONFLICT (code) DO NOTHING`. Đường dẫn: khóa khách ghi đường dẫn service `/internal/sales/shop/contact-requests` (như `ShopController/*` ở V20); khóa nhân viên ghi đường dẫn BFF `/api/sales/contact-requests...`.
2. Backfill tenant đang bật SALES, trong `set_config('app.tenant_id', ..., true)` từng tenant (bảng `roles`/`role_permissions` RLS FORCE): role `CUSTOMER` và `STORE_MANAGER` **còn nguyên mẫu** (tập khóa trùng đúng mẫu hiện tại sau V20) thì thêm khóa mới; role đã bị chỉnh, không còn, hoặc `is_admin` thì không đụng, `RAISE WARNING` để tenant admin gán tay. Backfill chạy **trước** bước 3 (so với mẫu cũ).
3. Thêm khóa vào `role_template_permissions` của `c4`, `c3` (tenant bật SALES sau này nhận mẫu mới).

Khóa của khách thêm vào allowlist mẫu Khách hàng (customer-api-data-authorization 5.11): allowlist mới = `ShopController/*`, `TitleController/lookup`, `ShopContactController/create`. Role khách đã bị tenant chỉnh không tự có khóa; chỉ admin tenant thêm được (luật domain identity cho role `signup_default`).

### 2.7. Chống spam: ba lớp, quy tắc ở domain/application của sales

| Lớp | Cơ chế | Chặn gì |
|---|---|---|
| nginx | Location regex mới `"^/public/t/[^/]+/contact-requests(?:[/;].*)?$"`, `limit_req zone=public_form burst=3 nodelay` + `public_tenant`, `client_max_body_size 16k` (mẫu signup) | Dội từ một IP, kể cả nhiều tài khoản |
| sales, theo người (R5) | Đếm dòng của `(tenant, customer_user_id)` có `created_at > now − 24h` trong transaction tạo; ≥ giới hạn → `429` | Một tài khoản spam chậm (né được nginx) |
| sales, trùng nội dung (R4) | Trong các dòng trên, có dòng cùng `topic` + `message` và `created_at > now − 10 phút` → no-op, `201` | Bấm hai lần, client tự thử lại khi hết thời gian chờ |

- Quy tắc nằm ở domain `contact.domain.ContactSubmissionPolicy` (Java thuần: nhận danh sách gửi gần đây của chính người đó, bản mới, `now`, giới hạn, cửa sổ → `ACCEPT`/`DUPLICATE`/`LIMIT_EXCEEDED`); use case `SubmitShopContactUseCase` (application, `@Transactional`) đọc qua port rồi gọi. Không ở controller.
- Cấu hình nền tảng (không theo tenant, chưa có nhu cầu), thêm vào khối `lms:` **đã có** trong `services/sales-service/src/main/resources/application.yml` (dòng ~51), không tạo khối thứ hai: `lms.sales.contact.max-per-user-per-day: 5`, `lms.sales.contact.duplicate-window: PT10M`.
- Đã loại: `clientRequestId` cho liên hệ (thêm cột, ràng buộc duy nhất và mã lỗi, trong khi R4 đủ cho bấm hai lần); `409 SHOP_CONTACT_DUPLICATE` (khách thấy lỗi dù yêu cầu đã được ghi nhận); captcha/honeypot (đã đăng nhập); khóa phân tán hoặc `SELECT ... FOR UPDATE` để đếm chính xác tuyệt đối (xem rủi ro, chấp nhận vượt nhẹ khi gửi song song); giới hạn theo ngày lịch giờ VN (24 giờ trượt không cần múi giờ, cùng tác dụng).

### 2.8. Job ẩn danh: job riêng trong context `contact`, dùng chung cờ và lịch

| Tiêu chí | **A. `ShopContactAnonymizationJob` trong `contact.infrastructure`, dùng `TenantDirectoryPort` và cùng `lms.sales.anonymize.enabled/cron` (chọn)** | B. Thêm bước vào `AnonymizeWalkInJob` hiện có | C. Job ở system-service hoặc service khác |
|---|---|---|---|
| Phụ thuộc context | `contact → shared.application, settings.application` | `ordering → contact.application`: context đơn biết liên hệ | Truy cập DB của sales, trái luật |
| Lỗi một bên | Độc lập | Phải bọc try/catch riêng từng bước | |
| Vận hành | Một cờ, một lịch cho "ẩn danh dữ liệu cá nhân sales" | Như A | |

Chọn **A**. Cách làm như `AnonymizeWalkInJob`: duyệt `sales_tenants`, mỗi tenant `TenantContext.callAs` → `AnonymizeExpiredShopContactsUseCase` (đọc `walkInPiiRetentionMonths` qua `GetSalesSettingsUseCase`, mốc = `now − tháng` theo UTC) → lô 500 dòng/transaction, JPQL bulk update có điều kiện `tenant_id` tường minh + `pii_anonymized_at IS NULL` + `created_at < mốc` (không native query); mỗi lô có thay đổi ghi **một** audit `SHOP_CONTACT_PII_ANONYMIZED` `{requests, cutoff}`, actor null. Lỗi một tenant chỉ log mã tenant + tên lớp ngoại lệ, sang tenant kế. Idempotent.

Bắt buộc kèm theo (AC12): `SubmitShopContactUseCase` gọi `TenantDirectoryPort.touch(tenantId)` trong transaction tạo liên hệ (port đã có, `MANDATORY`). Thiếu bước này thì tenant chỉ có liên hệ không bao giờ được ẩn danh.

Ẩn danh ngay (R8, `SalesContactController/anonymize`) và job dùng cùng phương thức domain `ShopContactRequest.anonymize(now)`; bản thủ công ghi audit `SHOP_CONTACT_ANONYMIZED` với actor là nhân viên.

Tái cấu trúc nhỏ trước: chuyển `PersonalDataMasker` và `RetentionPolicy` từ `lms.sales.ordering.domain` sang `lms.sales.shared.domain` (thêm `maskEmail`), để `contact` dùng mà không phụ thuộc `ordering.domain`. Đã loại: chép hai lớp sang `contact.domain` (hai bản quy tắc che PII lệch nhau theo thời gian).

### 2.9. Audit, quyền riêng tư, log

- Audit qua `SalesAuditPort` trong cùng transaction (ghi `sales_audit_log` và phát `sales.events` sau commit). Thêm vào `SalesAuditPort.Actions`:

  | Hành động | Khi | `aggregateType`/`aggregateId` | `actorId` | `data` (không PII) |
  |---|---|---|---|---|
  | `SHOP_CONTACT_CREATED` | tạo mới (không ghi khi trùng R4) | `ShopContactRequest`/id | **null** (cố ý: audit giữ vĩnh viễn, ghi `userId` ở đây sẽ nối lại người gửi sau khi ẩn danh) | `{topic}` |
  | `SHOP_CONTACT_STATUS_CHANGED` | `updateStatus` | như trên | nhân viên | `{from, to}` |
  | `SHOP_CONTACT_ANONYMIZED` | `anonymize` thủ công (khi có đổi) | như trên | nhân viên | `{}` |
  | `SHOP_CONTACT_PII_ANONYMIZED` | mỗi lô của job | `ShopContactRequest`/null | null | `{requests, cutoff}` |

- `getContact` **không** ghi audit, thống nhất với `SalesOrderController/getContact` hiện có (⚠️ rủi ro: không truy được ai đã xem liên hệ đầy đủ; nếu cần thì thêm cho cả đơn lẫn liên hệ trong một quyết định riêng).
- Mọi record request/response/command/domain chứa tên, email, điện thoại, nội dung ghi đè `toString` (`[***]`), ở customer-api, sales và lms-api. Không log thân request (customer-api đã vậy; sales không thêm log thân). Thông điệp `SalesException` không chứa giá trị người dùng nhập. Log job chỉ có mã tenant và số đếm.
- Không có event Kafka riêng cho liên hệ (Q5). Bản audit đi ra `sales.events` chỉ có các trường ở bảng trên.

### 2.10. Thông báo nhân viên (Q5): không làm, ghi hướng sau

Không động support-service (đang chờ đổi tên ADR-0014). Nhân viên xem `search?status=NEW`. Khi cần: sales phát event riêng sau commit (`ShopContactSubmitted {contactId, topic}`, không PII) và consumer ở `notification` của `audit-service` theo tiền lệ `ContractExpiringConsumer` (ADR-0012 2.4.3); cần quyết định "người nhận là ai" (role nào). Đã loại cho lúc đó: gọi HTTP đồng bộ sang support (ADR-0012 đã loại cùng lý do).

### 2.11. Mã lỗi

| Nơi | Status | `code` | Khi |
|---|---|---|---|
| sales (mới trong `ErrorCode`) | 404 | `SHOP_CONTACT_NOT_FOUND` | id không có hoặc thuộc tenant khác (RLS) |
| sales (mới) | 409 | `SHOP_CONTACT_STATE_CONFLICT` | chuyển trạng thái không cho phép, `version` lệch |
| sales (mới) | 429 | `SHOP_CONTACT_LIMIT_EXCEEDED` | R5 |
| sales (mới) | 400 | `CONSENT_REQUIRED` | `consent` khác `true` (sales tự kiểm, không tin BFF) |
| sales (có) | 400 | `INVALID_REQUEST` | độ dài, định dạng email/điện thoại, `topic` lạ, ký tự điều khiển |
| interceptor (có) | 403 | `FORBIDDEN`, `MODULE_NOT_ENABLED`, `TENANT_REQUIRED` | thiếu khóa, tắt SALES, không tenant |
| customer-api | 201 | | thành công hoặc trùng R4 |
| customer-api | 400 | `VALIDATION_FAILED` | validate ở BFF, hoặc alias của `INVALID_REQUEST`/`VALIDATION_ERROR` |
| customer-api | 400 | `CONSENT_REQUIRED` | đã có trong allowlist |
| customer-api | 401 | `UNAUTHORIZED` | không token, sai `aud`, token `svc` |
| customer-api | 403 | `SHOP_SESSION_MISMATCH`, `FORBIDDEN` | sai tenant; role thiếu khóa |
| customer-api | 404 | `SHOP_NOT_AVAILABLE` | tenant không khả dụng hoặc tắt SALES |
| customer-api | 429 | `SHOP_CONTACT_LIMIT_EXCEEDED` | **thêm vào `FORWARDED_CODES`** (thông điệp do customer-api viết, ví dụ "Bạn đã gửi quá nhiều yêu cầu, vui lòng thử lại sau") |
| nginx | 429 | (thân của nginx) | vượt `public_form`; FE phân biệt với 429 của sales bằng việc có `code` hay không |
| customer-api | 503 | `SERVICE_UNAVAILABLE` | sales lỗi/chết |

lms-api chuyển nguyên 4xx của sales (khuôn hiện có), 5xx thành 502.

## 3. Thiết kế

### 3.1. Thành phần

```
Trình duyệt (storefront, token aud=lms-storefront)
  └─POST /public/t/{code}/contact-requests─> nginx 8080 (public_form 5r/m/IP, 16k) ─> customer-api 8088
        customer-api: StorefrontAccessCache.resolve(code) → ShopSessionGuard → record allowlist → dựng body
          └─POST /internal/sales/shop/contact-requests (Bearer token KHÁCH)─> sales-service 8087
                PermissionInterceptor: khóa ShopContactController/create, module SALES, tenant từ claim
                SubmitShopContactUseCase (1 transaction sales_db): đọc gần đây của chính người gửi → ContactSubmissionPolicy
                  → insert shop_contact_requests (RLS) + TenantDirectoryPort.touch + audit SHOP_CONTACT_CREATED
Nhân viên (token lms-api)
  └─/api/sales/contact-requests/**─> nginx ─> lms-api 8085 (lms.api.sales) ─/internal/sales/contact-requests/**─> sales
        SalesContactController: search | get | getContact | updateStatus | anonymize
sales-service ShopContactAnonymizationJob (lms.sales.anonymize.cron) ─ sales_tenants ─ callAs(tenant) ─ lô 500
```

Gói trong sales-service:

```
lms.sales.contact
  domain/          ShopContactRequest (aggregate: submit, changeStatus, anonymize, chuẩn hóa + độ dài),
                   ContactTopic, ContactStatus, ContactSubmissionPolicy
  application/     SubmitShopContactUseCase, ShopContactQueryUseCases (search, get, contact),
                   ChangeShopContactStatusUseCase, AnonymizeShopContactUseCase,
                   AnonymizeExpiredShopContactsUseCase (+ batch @Transactional), ShopContactRepository (port)
  infrastructure/
    persistence/   ShopContactRequestJpaEntity (@TenantId, @Version), adapter, Spring Data repo (JPQL)
    web/           ShopContactController, SalesContactController, view records
    config/        ContactBeanConfiguration, ShopContactAnonymizationJob + scheduling config
lms.sales.shared.domain   PersonalDataMasker (+ maskEmail), RetentionPolicy (chuyển từ ordering.domain)
```

### 3.2. Luồng gửi

```mermaid
sequenceDiagram
    participant B as Trình duyệt
    participant N as nginx
    participant C as customer-api
    participant S as sales
    B->>N: POST /public/t/{code}/contact-requests (Bearer khách)
    N->>N: limit_req public_form (5r/m, burst 3) theo IP
    N->>C: chuyển tiếp
    C->>C: resolve(code) → 404 SHOP_NOT_AVAILABLE nếu không khả dụng
    C->>C: ShopSessionGuard: svc → 401, thiếu aud → 401, tenant lệch → 403
    C->>C: validate record allowlist (thô), dựng body {topic, fullName, email, phone, message, consent}
    C->>S: POST /internal/sales/shop/contact-requests (token khách)
    S->>S: interceptor: khóa + SALES; TenantContext từ claim
    S->>S: tx: chuẩn hóa + kiểm (domain) → đọc dòng 24h của sub → policy
    alt DUPLICATE
        S-->>C: 201 (không ghi)
    else LIMIT_EXCEEDED
        S-->>C: 429 SHOP_CONTACT_LIMIT_EXCEEDED
    else ACCEPT
        S->>S: insert + touch sales_tenants + audit (cùng tx)
        S-->>C: 201
    end
    C-->>B: 201 no-store | lỗi đã viết lại thông điệp
```

### 3.3. Hợp đồng customer-api

- `customer-api/src/main/java/lms/customer/shop/`: `web/ContactRequestController` (tên lớp ở BFF không sinh khóa, `be-dev` chọn), `dto/ShopDtos.ContactRequestBody` (record allowlist: `topic` enum, `fullName` `@NotBlank @Size(max=400) @NoControlChars`, `email` `@NotBlank @Size(max=254)`, `phone` `@Size(max=20) @NoControlChars`, `message` `@NotBlank @Size(max=8000) @NoControlChars(allowLineBreaksAndTabs=true)`, `consent` `@NotNull Boolean` dùng `StrictBooleanDeserializer` nếu nhất quán với form SaaS; giới hạn chính xác theo code point ở domain sales, giới hạn ở BFF chỉ chặn thô vì `@Size` đếm UTF-16), `toString` che; `client/ShopClient.submitContactRequest(token, body)`. Không trường `orderId` (bỏ qua nếu FE gửi).
- `SecurityConfiguration.AUTHENTICATED_API` thêm `POST /public/t/*/contact-requests`; mọi method khác trên đường dẫn này vẫn `denyAll`.
- `PublicExceptionHandler.FORWARDED_CODES` thêm `SHOP_CONTACT_LIMIT_EXCEEDED`. Không chuyển `SHOP_CONTACT_NOT_FOUND`/`STATE_CONFLICT` (khách không gọi được endpoint nhân viên).
- Phản hồi `201`, `Cache-Control: no-store`, không `Location`.

## 4. Phân việc

### 4.1. Thứ tự và song song

```
B0 (refactor sales shared.domain) ─┐
B1 (sales/V4)  ────────────────────┼─> B2 (sales context contact) ─> T2, T3, T6
B3 (identity/V22 + sửa test seed) ─┘        │
B4 (customer-api) ‖ B5 (lms-api) ‖ B6 (nginx) ‖ B7 (tài liệu)   (theo hợp đồng mục 2.5, 3.3; client giả)
                                    T4, T5, T7 sau B4/B5/B6; T8 đầu-cuối sau tất cả
```

Tên khóa, đường dẫn và DTO đã chốt ở ADR nên B3, B4, B5, B6 bắt đầu ngay, song song với B2 (thư mục không chồng nhau). B0 và B2 cùng module sales: **cùng một người, tuần tự**.

| # | Ai | Việc | Phạm vi file | Phụ thuộc | Song song với |
|---|---|---|---|---|---|
| ✅ B0 | be-dev #1 | Chuyển `PersonalDataMasker`, `RetentionPolicy` sang `lms.sales.shared.domain`, thêm `maskEmail` + test; sửa import ở `ordering` và test hiện có. Commit `refactor(sales): ...` | `services/sales-service/src/{main,test}/java/lms/sales/{ordering,shared}/**` | không | B1, B3..B7 |
| ✅ B1 | be-dev #2 | `sales/V4__create_shop_contact_requests.sql` (mục 2.3; ⚠️ kiểm số ngay trước khi tạo) | `services/system-service/src/main/resources/db/migration/sales/` (chỉ file mới) | không | B0, B3..B7 |
| ✅ B2 | be-dev #1 | Context `lms.sales.contact` (mục 3.1): domain + test đơn vị, application, persistence (`@TenantId`, `@Version`, JPQL), hai controller đúng tên/method mục 2.5, view allowlist, `ErrorCode` mới + ánh xạ trong `SalesExceptionHandler` (429), `SalesAuditPort.Actions` mới, `touch` trong transaction tạo, job + scheduling dùng `lms.sales.anonymize.*`, cấu hình `lms.sales.contact.*` trong khối `lms:` có sẵn; `SalesArchitectureTest` thêm luật: `contact` chỉ dùng `settings.application`, `shared`; `ordering`, `stock`, `settings` không phụ thuộc `contact`; `TenantArchitectureRulesTest` xanh. Commit `feat(sales): ...` | `services/sales-service/**` | B0 (tuần tự), B1 (để chạy IT) | B3..B7 |
| ✅ B3 | be-dev #2 | `identity/V22__sales_shop_contact_permissions.sql` (mục 2.6; ⚠️ kiểm số, phối hợp ADR-0014) và sửa `SalesPermissionSeedMatchesCodeTest` (`hasSize(3)`, thêm `ShopContactController`, `SalesContactController` vào `@WebMvcTest`). Commit migration `feat(identity): ...`, test đi cùng commit B2 hoặc sau B2 (test cần lớp controller thật) | `services/system-service/src/main/resources/db/migration/identity/` (chỉ file mới), `services/sales-service/src/test/java/lms/sales/web/SalesPermissionSeedMatchesCodeTest.java` | tên khóa (đã chốt); phần test chờ B2 | B1, B4..B7 |
| ✅ B4 | be-dev #3 | customer-api mục 3.3: controller, DTO, client + http, `AUTHENTICATED_API`, `FORWARDED_CODES`, test WebMvc/client (client giả). Commit `feat(customer-api): ...` | `customer-api/src/{main,test}/java/lms/customer/{shop,config,web}/**` | hợp đồng mục 2.5, 3.3 | B2, B5, B6 |
| ✅ B5 | be-dev #4 | lms-api: `SalesClient`/`HttpSalesClient` thêm 5 phương thức, DTO trong `SalesDtos` (`toString` che với view liên hệ đầy đủ), controller `/api/sales/contact-requests/**` (query record cho search), test client/controller/routing. Commit `feat(lms-api): ...` | `lms-api/src/{main,test}/java/lms/api/sales/**` (⚠️ có thay đổi chưa commit của người khác trong `lms-api`: sửa có mục tiêu) | hợp đồng mục 2.5 | B2, B4, B6 |
| ✅ B6 | be-dev #3 hoặc #4 | nginx: location regex `"^/public/t/[^/]+/contact-requests(?:[/;].*)?$"` theo mẫu signup (public_form + public_tenant, 16k, security-headers, proxy tới customer-api). Commit `build(nginx): ...` | `infrastructure/nginx/nginx.conf` (⚠️ đang có thay đổi chưa commit) | không | B2..B5 |
| ✅ B7 | be-dev hoặc sa | Tài liệu: `docs/api/customer-api.md` (endpoint mới, bảng giới hạn, danh sách cần đăng nhập), `docs/api/sales.md` (API nhân viên, `walkInPiiRetentionMonths` áp cả liên hệ), `docs/api/customer-api-contact-reader.md` (đánh dấu đã chốt theo ADR-0015, sửa đường dẫn/trạng thái/mã lỗi), `customer-api-data-authorization.md` 5.11 (allowlist mẫu Khách hàng), ADR-0006 nếu có danh sách bảng RLS. Commit `docs: ...` | `docs/**` | B2..B6 xong (đúng code) | |
| ⏭️ FE | ngoài repo | Form ở storefront `/t/{code}/...`, ẩn khi chưa đăng nhập, điền sẵn tên/email, khóa nút khi gửi, xử lý 201/400/401/403/404/429 (hai loại)/503; màn quản trị liên hệ trong khu bán hàng (danh sách che, chi tiết, nút "Xem liên hệ" theo khóa `getContact`, đổi trạng thái với `version`, ẩn danh). Không log PII ở FE. | repo FE | B4, B5 | |

### 4.2. Kiểm thử

| # | Ai | Nội dung | Phụ thuộc |
|---|---|---|---|
| ✅ T1 | be-dev (cùng B0, B2) | Unit domain: chuẩn hóa, độ dài code point (2000 có emoji/tổ hợp qua, 2001 lỗi), email/điện thoại, `consent`; máy trạng thái (mọi cặp chuyển, không mở lại `CLOSED`); `ContactSubmissionPolicy` (trùng trong/ngoài 10 phút, khác `topic`, giới hạn 5 trong 24 giờ, trùng xét trước giới hạn); `anonymize` xóa đủ 5 cột, idempotent; `maskEmail` | B0, B2 |
| ✅ T2 | tester | WebMvc sales (bật kiểm khóa, `@MockitoBean PermissionKeyRegistrar`): ma trận khóa × 6 endpoint (khách có `create` → 201; khách gọi endpoint nhân viên → 403; nhân viên thiếu khóa → 403; token `svc` mọi endpoint → 403; SALES tắt → `MODULE_NOT_ENABLED`); tập tên trường JSON của 3 view đúng mục 2.5; mass assignment AC5; `customer_user_id` lấy từ JWT | B2 |
| ✅ T3 | tester | IT sales (Testcontainers, vai trò `sales_app`, `AppRoleDatabase`): cách ly tenant (nhân viên A `get`/`getContact`/`updateStatus`/`anonymize` id của B → 404 cùng thân; `search` không lẫn); RLS ENABLE+FORCE, đúng một policy `tenant_isolation` (AC15), đọc khi chưa đặt `app.tenant_id` → 0 dòng/lỗi; các CHECK (chèn thẳng dòng ẩn danh thiếu cột NULL bị chặn); `touch` khi tạo (AC12); job: hai tenant thời hạn khác nhau, đồng hồ giả, chỉ dòng quá hạn, chạy lại 0 dòng, tenant lỗi không chặn tenant khác; `version` lệch → 409; gửi song song ghi nhận số vượt thực tế (đo, không bắt buộc chặn) | B1, B2 |
| ✅ T4 | tester | WebMvc customer-api (client giả): AC2 đủ tổ hợp và thứ tự (404 trước 401/403); body gửi xuống chỉ có 6 trường allowlist (bắt request ở client giả); 201 không thân + `no-store`; `VALIDATION_FAILED`, `CONSENT_REQUIRED`; 429 của sales chuyển với `code`, thông điệp do BFF viết; `FORBIDDEN` không lộ tên khóa; 5xx/mất kết nối → 503; GET/PUT trên đường dẫn → không vào controller | B4 |
| ✅ T5 | tester | lms-api: routing 5 endpoint, token `aud=lms-storefront` → 401, 4xx chuyển nguyên, `toString` của view liên hệ đầy đủ không lộ | B5 |
| ✅ T6 | tester | PII không vào log/audit (AC13): appender bắt log ở sales và customer-api cho mọi nhánh (thành công, 400, 409, 429, ngoại lệ bất ngờ, job); `sales_audit_log.data` chỉ có khóa ở mục 2.9, `actor_id` null với `SHOP_CONTACT_CREATED`; payload `sales.events` (bắt ở `SalesEventPublisher` giả) không chứa email/điện thoại/tên/nội dung | B2, B4 |
| ✅ T7 | tester | Seed: `SalesPermissionSeedMatchesCodeTest` xanh; IT migration identity (mẫu `MigrationApiIT`): `V22` chạy trên DB có tenant bật SALES với role `CUSTOMER`/`STORE_MANAGER` nguyên mẫu (nhận khóa) và đã chỉnh (không nhận, có WARNING); mẫu `c3`, `c4` có khóa mới; khóa không vào `c1`, `c2` | B3 |
| ⚠️ T8 | tester | nginx (compose): POST vượt `public_form` → 429, kể cả `/contact-requests/`, `;x=1`, `%63ontact-requests`; đầu-cuối: signup → login storefront → gửi → nhân viên thấy che → `getContact` → `IN_PROGRESS` → `CLOSED` → `anonymize` → `getContact` trả null | B2..B6 |
| ⏭️ P1 | perf-tester | Không bắt buộc: đường gửi là một truy vấn chỉ mục + một insert, lưu lượng thấp. Tùy chọn: job ẩn danh 100 000 dòng/tenant (thời gian, khóa dòng ảnh hưởng `updateStatus` đồng thời), `search` p95 với 100 000 dòng/tenant. ⚠️ chưa có số nền | B2 |

Commit tách theo nhóm, không `Co-Authored-By`: `refactor(sales):` (B0), `feat(sales):` (B1 + B2, migration sales có thể commit riêng `feat(system):`), `feat(identity):` (B3 migration), `feat(customer-api):`, `feat(lms-api):`, `build(nginx):`, `docs:`.

Triển khai: system-service (migrate `sales/V4`, `identity/V22`) trước sales-service mới (sales `validate` schema sẽ không lên nếu entity có bảng mà DB chưa có). Quyền mới của role chỉ hiệu lực ở lần đăng nhập/refresh kế tiếp (ADR-0003).

## 5. Rủi ro và việc chưa kiểm chứng

| | Rủi ro | Xử lý |
|---|---|---|
| ✅ | nginx: đã thêm location `/public/t/*/contact-requests` (B6): `nginx -t` đạt; probe nginx độc lập (upstream giả) cho `429` từ yêu cầu thứ 5 ở `/contact-requests`, `/contact-requests/`, `;x=1`, `%63ontact-requests`, GET không bị giới hạn form, thân 20 KB bị `413`, không có header CORS | Còn T8 đầu-cuối với compose đầy đủ |
| ⚠️ | Giới hạn R5 vượt nhẹ khi một người gửi song song (hai transaction cùng đếm 4) | Chấp nhận: nginx giới hạn 5r/m/IP burst 3, vượt tối đa vài dòng; T3 đo. Nếu thành vấn đề: khóa dòng `sales_tenants` hoặc bảng đếm theo người (ADR sau) |
| ⚠️ | Né R5 bằng nhiều tài khoản tự đăng ký (signup chưa xác thực email, ADR-0010) | nginx theo IP là lớp chặn chính; nhân viên đóng `CLOSED` hàng loạt. Chưa có giới hạn theo tenant/ngày |
| ⚠️ | Số migration `sales/V4`, `identity/V22` trùng với phiên khác (ADR-0014 cũng cần migration identity) | Kiểm lại ngay trước khi tạo và trước commit; không sửa file đã chạy |
| ⚠️ | `SalesPermissionSeedMatchesCodeTest` cứng `hasSize(2)`: thêm `V22__sales_*` mà quên sửa test thì đỏ; đặt tên không theo `V*__sales_*` thì test không thấy khóa mới | B3 sửa test cùng lúc; tên file theo mục 2.6 |
| ⚠️ | Role `CUSTOMER`/`STORE_MANAGER` của tenant đã chỉnh không nhận khóa mới; khách không gửi được (403) cho tới khi admin tenant gán | WARNING trong migration; ghi vào tài liệu vận hành; FE hiển thị 403 thân thiện |
| ⚠️ | Dùng chung `walkInPiiRetentionMonths`: tenant giảm thời hạn cho khách vãng lai cũng giảm cho liên hệ | Chấp nhận (một chính sách); tách được không phá hợp đồng (2.4) |
| ⚠️ | Bỏ `platform_read_all` cho bảng mới lệch mẫu V1/V2; công cụ/test nào giả định mọi bảng sales có hai policy sẽ đỏ | `SalesSchemaIT` kiểm riêng bảng này; `PlatformReadAllIT` (system) hiện chỉ liệt kê bảng cụ thể (đã đọc), ⚠️ chưa chạy |
| ⚠️ | `getContact` không có audit (theo khuôn đơn hàng) | Ghi nhận; quyết định chung cho đơn và liên hệ nếu cần |
| ⚠️ | Nội dung tự do có thể chứa PII khác (số tài khoản, địa chỉ) ngoài trường liên hệ; `get` hiển thị đầy đủ cho ai có khóa `get` | Mặc định chỉ `STORE_MANAGER` có khóa; ẩn danh xóa cả `message` |
| ⚠️ | JPQL bulk update với `@TenantId` (Hibernate 6.6): giống rủi ro ADR-0010, thêm `tenant_id` tường minh; RLS chặn ở DB | T3 |
| ⚠️ | Profile dev tắt xác thực: không có JWT nên không có `sub`; `create` phải từ chối khi không có người dùng (401), không ghi dòng `customer_user_id` NULL | B2 kiểm `RequestSupport.user(jwt)` null; CHECK DB chặn thêm |
| ⚠️ | Job ẩn danh và job đơn hàng chạy cùng lịch, cùng duyệt `sales_tenants`: tải cộng dồn lúc 02:30 | Lưu lượng thấp; P1 tùy chọn |
| ⚠️ | 429 có hai nguồn (nginx thân HTML/rỗng, sales thân JSON có `code`) | Ghi trong `docs/api/customer-api.md`; FE xử lý cả hai |
| ✅ | Đã có code và test (unit, WebMvc, IT Testcontainers sales_app, IT migration identity V22, customer-api, lms-api); xem báo cáo thực hiện | |
| ℹ️ | Không đụng `services/support-service` (ADR-0014); Q5 làm sau theo 2.10 | |
| ℹ️ | `orderId` để giai đoạn sau: cột NULL mới + kiểm sở hữu qua `ordering.application` (customer-api-data-authorization 5.2), khách chỉ gắn đơn của mình, sai → `400 VALIDATION_FAILED` | |

## 6. Câu hỏi mở

Không có câu hỏi chặn thiết kế. Hai mặc định cần người dùng biết (đổi được bằng dữ liệu, không đổi thiết kế):

- ℹ️ Mặc định chỉ `STORE_MANAGER` xử lý liên hệ; Thu ngân không có khóa nào (thống nhất với việc Thu ngân không xem liên hệ đầy đủ của đơn). Tenant admin gán thêm được.
- ℹ️ Giới hạn 5 yêu cầu/người/24 giờ và cửa sổ trùng 10 phút là cấu hình nền tảng (`lms.sales.contact.*`), không theo tenant.
