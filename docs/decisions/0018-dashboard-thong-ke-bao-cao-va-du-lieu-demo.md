# ADR-0018: Dashboard thống kê, dữ liệu biểu đồ, báo cáo CSV và dữ liệu mẫu demo

- Trạng thái: đề xuất (chưa cài đặt). Đề bài: `docs/requirements/2026-11-17-dashboard-bao-cao-demo.md` (BA, câu hỏi mở Q1 đến Q7 ở mục 14 chưa được người dùng duyệt; ADR này dùng mặc định của BA, trừ chỗ ghi rõ "lệch đề bài").
- Liên quan: ADR-0001 (Kafka best-effort), ADR-0003 (khóa `Controller/method`), ADR-0004 (migration tập trung), ADR-0005 (BFF `lms-api`), ADR-0006 (multi-tenant, RLS fail-closed), ADR-0008 (đọc xuyên tenant, `@CrossTenantReadable`), ADR-0009 (module, `@RequiresModule`, claim `modules`), ADR-0010 (sales), ADR-0012 (hợp đồng), ADR-0016 (platform admin qua `X-Tenant-Id`).
- Số hiệu: `0017` đã dùng cho `0017-phien-da-thiet-bi.md`, nên dùng `0018`.

## 1. Bối cảnh và mục tiêu

### 1.1. Hiện trạng đã đối chiếu trong code (nhánh `main`, gồm thay đổi chưa commit, 2026-11-17)

| | Sự thật | Nguồn |
|---|---|---|
| ✅ | Chưa có endpoint thống kê nào. `lms-api` mỗi context có `client` (interface) + `client/http` + `dto` + `web`; `ServiceClients.create(baseUrl)` dùng `SimpleClientHttpRequestFactory` với timeout chung `connect 2s`/`read 5s` và `TokenRelayInterceptor`. | `lms-api/.../config/ServiceClients.java`, `application.yml` |
| ✅ | `TokenRelayInterceptor` lấy token từ `SecurityContextHolder` và `X-Tenant-Id` từ `RequestContextHolder`: cả hai là ThreadLocal, **gọi từ luồng khác thì mất token và header**. | `lms-api/.../support/TokenRelayInterceptor.java` |
| ✅ | `ApiExceptionHandler` của `lms-api`: 4xx giữ nguyên thân lỗi, 5xx thành `502 DEPENDENCY_ERROR`, không kết nối được thành `503 DEPENDENCY_UNAVAILABLE`. | `lms-api/.../web/ApiExceptionHandler.java` |
| ✅ | `GET /api/users/export` đã có (chưa commit, phiên khác): identity sinh CSV (`UsersCsv`, chống injection bằng tiền tố `'`, BOM do tầng web thêm, 5000 dòng, `byte[]`), `lms-api` chuyển `byte[]`. | `identity-service/.../user/application/{ExportUsersUseCase,UsersCsv}.java`, `lms-api/.../identity/web/UserController.java` |
| ✅ | `/api/platform/**` được `lms-api` chuyển nguyên sang identity (`IdentityGatewayController`); controller platform của identity map thẳng `/api/platform/...`. `GET /api/platform/contracts/expiring` đã có. | `IdentityGatewayController.java`, `PlatformTenantController.java`, `docs/api/platform-contracts.md` |
| ✅ | circulation: `borrowings(borrow_date DATE, due_date DATE)`, `borrowing_items(book_copy_id, return_date DATE, item_condition)`; **`borrowing_items` không có `title_id`**. Chỉ mục có `tenant_id`: `(tenant_id, user_id)`, `uq (tenant_id, book_copy_id) WHERE return_date IS NULL`, `(borrowing_id)`. Không có chỉ mục theo `borrow_date`, `due_date`, `return_date`. | migration `circulation/V1, V4, V6` |
| ✅ | circulation dùng `Clock.systemDefaultZone()` (catalog cũng vậy) để tính `borrow_date`/`due_date`; compose không đặt `TZ`, nên trong container là UTC. Identity, sales, support, system dùng `Clock.systemUTC()`. | `BorrowingBeanConfiguration`, `InventoryBeanConfiguration`, `docker-compose.yml` |
| ✅ | circulation đã gọi identity (`ReaderDirectoryPort`, từng độc giả) và catalog (`CopyInventoryPort`, từng bản sao; `CopyInfo` có `titleId`) bằng token chuyển tiếp; **không chuyển `X-Tenant-Id`** (không service nào chuyển, chỉ `PermissionInterceptor` đọc). | `borrowing/infrastructure/client/*`, `rg X-Tenant-Id` |
| ✅ | catalog: `book_copies` chỉ có chỉ mục `(title_id, status)`, không dẫn đầu `tenant_id`; `titles` có `(tenant_id, lower(name))`. Có `POST /internal/books/lookup` (tối đa 100 id, khóa `TitleController/lookup`). Không có tra lô bản sao. | migration `catalog/V1, V2`, `TitleController`, `docs/api/catalog-lookup.md` |
| ✅ | sales: `sales_orders` có `created_at`, `paid_at`, `grand_total`, `status`, `payment_status`, `channel`; `sales_order_lines.title_name` là snapshot; `refunds(status, refunded_at, amount, source)`, `sales_returns(created_at)`. Chỉ mục: `(tenant_id, status, created_at DESC)`, `(tenant_id, payment_status, created_at)`; không có theo `paid_at`, `refunded_at`, `sales_returns.created_at`. `ListStockItemsUseCase` đã tra tên đầu sách từ catalog theo lô và định nghĩa `lowStock`. | migration `sales/V1, V2`, `stock/application/*` |
| ✅ | identity không có tra lô người dùng (`users` là bảng toàn cục, lọc tenant qua `tenant_memberships`). | `user/infrastructure/web/UserController.java` |
| ✅ | ArchUnit: `noUnreviewedNativeQueries(...)` theo danh sách lớp đã rà ở từng service (circulation, sales: rỗng; catalog: `SpringDataBookCopyRepository`); circulation và sales bắt **mọi** controller có `@RequiresModule`; sales cấm entity JPA lộ ra ngoài `persistence` và web gọi thẳng persistence. | `*/architecture/*Test.java` |
| ✅ | nginx chỉ `Access-Control-Expose-Headers "X-Correlation-Id"`; `proxy_read_timeout 30s`. FE chạy khác origin (5173/3000) nên **không đọc được** `Content-Disposition` hay header tự đặt. | `infrastructure/nginx/snippets/common.conf`, `proxy.conf` |
| ✅ | Số migration kế tiếp lúc viết: identity **V25** (V23, V24 đã có, V24 của ADR-0017), catalog **V6**, circulation **V9**, sales **V5**. Phải liệt kê lại ngay trước khi tạo file. | `services/system-service/src/main/resources/db/migration/*` |

### 1.2. Mục tiêu

1. Dashboard tổng quan theo tenant, tổng quan nền tảng cho platform admin.
2. Dữ liệu chuỗi thời gian và top sách cho biểu đồ.
3. Báo cáo CSV: mượn quá hạn, tồn kho, đơn hàng (người dùng đã có).
4. Seed dữ liệu demo cho một tenant, idempotent, không bao giờ chạy ở production.

### 1.3. Ràng buộc

- Không đọc chéo DB; mỗi use case chỉ ghi một DB; Kafka best-effort.
- RLS fail-closed: mọi đọc/ghi bảng tenant phải có `TenantContext`; service chạy bằng `<svc>_app` (NOBYPASSRLS).
- Khóa quyền `Controller/method` ổn định; `lms-api` không phân quyền.
- Múi giờ nghiệp vụ `Asia/Ho_Chi_Minh`, dữ liệu lưu UTC (`timestamptz`) hoặc `DATE`.
- Không thêm hạ tầng mới khi chưa đo thấy cần (không Redis, không bảng tổng hợp).

## 2. Quyết định

### 2.1. (a) Số liệu nằm ở đâu: service sở hữu tự tính, `lms-api` gộp

**Chọn:** mỗi service tính phần của mình qua endpoint `/internal/**` mới, đặt trong context mới `reporting` của từng service (catalog, circulation, sales); identity thêm tổng quan nền tảng ở context `contract`. `lms-api` có nhóm mới `dashboard` và `reports` để gộp, bổ sung tên, chuyển CSV.

| Phương án | Đánh giá |
|---|---|
| **A. Service tự tính + BFF gộp (chọn)** | Đúng ranh giới DB (ADR-0002, 0006); RLS và `@RequiresModule` của service vẫn là lớp chặn; khóa quyền theo từng phần nên người chỉ có quyền mượn trả thấy đúng phần mượn trả; không hạ tầng mới. Đánh đổi: nhiều endpoint nhỏ, BFF phải xử lý suy giảm từng phần. |
| B. Service/bảng tổng hợp riêng nuôi bằng event Kafka | Kafka best-effort, chưa có Outbox (ADR-0001): mất event là số liệu lệch vĩnh viễn, đúng chỗ người xem đối chiếu với danh sách. Thêm service, DB, consumer, xử lý dữ liệu cũ. Quá mức cho nhu cầu trình diễn. |
| C. Bảng rollup chạy nền trong từng service | Tối ưu khi dữ liệu lớn; hiện chưa đo thấy chậm, thêm job và độ trễ "số liệu hôm nay". Giữ làm hướng mở rộng (mục 2.2). |
| D. Đọc chéo DB (một truy vấn join nhiều DB, hoặc system-service đọc giùm) | Bị cấm (CLAUDE.md, ADR-0002). system-service chỉ có ngoại lệ chạy DDL. |

Vì sao context `reporting` riêng, không nhét vào `borrowing`/`ordering`: số liệu đọc ngang nhiều aggregate (borrowing + fine; order + refund + return + stock), là read model chỉ đọc; tách ra để repository của aggregate không bị phình và luật ArchUnit hiện có không phải nới. Adapter đọc của `reporting` dùng truy vấn native riêng (không dùng entity JPA của context khác, vì sales cấm entity lộ ra ngoài `persistence` của chính context đó). Chỗ cần đúng định nghĩa nghiệp vụ sẵn có thì `reporting.application` gọi `application` của context sở hữu (ví dụ `stock.application.ListStockItemsUseCase` cho tồn thấp), không chép logic.

**Suy giảm một phần** (đúng đề bài mục 6.1, AC6, AC7, AC19, AC22): `GET /api/dashboard/summary` gọi song song 3 nguồn `catalog`, `circulation` (trả cả `lending` và `fines`), `sales`. Quy tắc gộp, theo thứ tự:

1. Module tắt: BFF không gọi nguồn của module tắt, phần đó `null`, tên module vào `disabledModules`. Nguồn module lấy như sau: token có `tenantId` thì đọc claim `modules`; token platform admin không `tenantId` có `X-Tenant-Id` thì gọi `GET /api/platform/tenants/{id}/modules` của identity (vì ADR-0016 bỏ qua kiểm module cho platform admin, service sẽ không tự chặn). Đây là quyết định **hiển thị**, không phải phân quyền: service vẫn chặn bằng `@RequiresModule`, nên nếu claim cũ mà service trả `403 MODULE_NOT_ENABLED` thì cũng xếp vào `disabledModules`.
2. Nguồn trả `403 FORBIDDEN`: bỏ phần đó, tên phần vào `forbiddenSections`.
3. Nguồn trả 5xx, hết hạn chờ, không kết nối được: phần đó `null`, tên vào `unavailableSections`.
4. Lỗi ở mức người gọi (`401`, `403 TENANT_REQUIRED`, `400 INVALID_TENANT_ID`): mọi nguồn đều trả cùng một lỗi, nên BFF trả nguyên lỗi đó (không trả 200 rỗng).
5. Mọi nguồn đã gọi đều thuộc 2: trả `403 FORBIDDEN`. Mọi nguồn đã gọi đều thuộc 3: trả `503 SERVICE_UNAVAILABLE`. Còn lại: `200`.

Platform admin xem dashboard của một tenant: **chỉ** bằng header `X-Tenant-Id` (ADR-0016). Không gắn `@CrossTenantReadable` cho endpoint thống kê (không có số liệu nghiệp vụ "mọi tenant cộng dồn", đề bài A3/Q1): truy vấn tổng hợp dưới phạm vi `ALL` chưa kiểm chứng (`isRoot` của Hibernate, ADR-0008) và cộng doanh thu giữa các tenant không có ý nghĩa nghiệp vụ.

Tổng quan nền tảng: identity `GET /api/platform/dashboard/overview` (`PlatformDashboardController/overview`, `@PlatformAdminOnly`), trong context `contract` (được dùng `tenant.application`, đã có logic hết hạn/ân hạn). Đặt ở `/api/platform/**` để `lms-api` chuyển nguyên, **không sửa `lms-api`**. Lệch đề bài: BA ghi đường nội bộ `/internal/platform/dashboard/overview`; mẫu hiện có của mọi API platform ở identity là `/api/platform/**`, nên theo mẫu hiện có.

### 2.2. (b) Hiệu năng: truy vấn trực tiếp có chỉ mục, không cache, chặn thời gian

**Truy vấn.** Adapter đọc của `reporting` dùng `EntityManager.createNativeQuery` (cần `AT TIME ZONE`, `GROUP BY` theo ngày, đếm có điều kiện; HQL không có chuyển múi giờ). Quy tắc cho mọi truy vấn:

- Có điều kiện tường minh `tenant_id = :tenantId` (lấy từ `TenantContext.require()`) **cộng** RLS: điều kiện tường minh bảo đảm planner chọn chỉ mục dẫn đầu `tenant_id` và là lớp lọc thứ hai vì native bỏ qua `@TenantId`. RLS vẫn áp (vai trò app, FORCE RLS).
- Lọc khoảng thời gian trên **cột gốc** (`paid_at >= :fromInstant AND paid_at < :toExclusive`, mốc tính trong Java từ ngày giờ VN) để dùng chỉ mục; chỉ biểu thức nhóm mới dùng `(paid_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date`. Cột `DATE` của circulation so trực tiếp với `LocalDate`.
- Điền đủ mốc 0 (ngày, tuần ISO bắt đầu thứ Hai) ở `reporting.domain` (Java thuần), không bằng `generate_series`, để test đơn vị kiểm được biên múi giờ.
- Lớp adapter đọc được thêm **có chủ đích** vào danh sách `noUnreviewedNativeQueries` của service đó (`CatalogStatsQueryAdapter`, `LendingStatsQueryAdapter`, `SalesStatsQueryAdapter`; tên do `be-dev` chốt). Không dùng `JdbcTemplate` để lách luật.
- Tham số luôn là bind parameter; `limit`, khoảng ngày (tối đa 366 ngày) bị chặn ở `reporting.domain`.

**Chỉ mục cần thêm** (migration chỉ DDL, không ghi dữ liệu nên không cần `set_config`; `CREATE INDEX` thường, bảng nhỏ ở môi trường hiện tại):

| Target | File (kiểm số lại) | Nội dung | Phục vụ |
|---|---|---|---|
| circulation | `V9__reporting_indexes_va_title_id.sql` | `borrowings (tenant_id, borrow_date)`; `borrowings (tenant_id, due_date)`; `borrowing_items (tenant_id, return_date) WHERE return_date IS NOT NULL`; cột mới `borrowing_items.title_id UUID NULL` | mượn theo ngày, quá hạn, trả theo ngày, top sách mượn |
| catalog | `V6__reporting_indexes.sql` | `book_copies (tenant_id, status)` | đếm bản sao theo trạng thái |
| sales | `V5__reporting_indexes.sql` | `sales_orders (tenant_id, paid_at) WHERE paid_at IS NOT NULL`; `sales_orders (tenant_id, created_at)`; `refunds (tenant_id, refunded_at) WHERE status = 'REFUNDED'`; `sales_returns (tenant_id, created_at)` | doanh thu, CSV đơn theo ngày tạo, hoàn tiền, top bán trừ trả hàng |
| identity | không cần chỉ mục | (`tenants`, `tenant_modules`, hợp đồng là bảng nhỏ toàn cục) | |

Không thêm chỉ mục cho phạt chưa thu (`idx_fines_reader_unpaid (tenant_id, reader_id) WHERE status = 'UNPAID'` đã dẫn đầu `tenant_id`) và `titles` (`(tenant_id, lower(name))` đã dùng được để đếm). Thêm nữa chỉ khi `perf-tester` đo thấy chậm.

**`borrowing_items.title_id` (quyết định đi kèm).** Top sách mượn phải nhóm theo đầu sách, mà circulation chỉ giữ `book_copy_id`. Phương án:

| Phương án | Đánh giá |
|---|---|
| **Snapshot `title_id` vào `borrowing_items` lúc mượn (chọn)** | `BorrowBookUseCase` đã có `CopyInfo.titleId` từ catalog, ghi thêm một cột; nhóm theo đầu sách bằng một truy vấn. Cùng mẫu với `sales_order_lines.title_id`. Đánh đổi: dòng cũ `NULL`, không vào top (ghi rõ trong tài liệu API); seed demo điền đủ. |
| Circulation trả top theo bản sao, BFF đổi bản sao sang đầu sách | Xếp hạng theo bản sao khác xếp hạng theo đầu sách (một đầu sách nhiều bản sao): phải trả **mọi** bản sao trong khoảng rồi gộp ở BFF, cần thêm tra lô bản sao, sai khi cắt `limit` sớm. |
| Backfill `title_id` cho dòng cũ | Cần đọc catalog từ circulation hàng loạt; không có lợi cho trình diễn. Bỏ qua (⏭️), làm sau nếu cần. |

**Cache: không làm ở đợt này** (đề bài A9). Lý do: (1) dữ liệu demo nhỏ, truy vấn có chỉ mục dự kiến vài mili giây; (2) khi trình diễn, người xem mượn một cuốn rồi mở dashboard, cache 30-60 giây làm số "không đổi" trông như lỗi; (3) cache ở BFF theo tenant làm rò phần người dùng không có khóa (kết quả gộp phụ thuộc quyền từng người). Điều kiện bật: `perf-tester` đo p95 `summary` vượt 1 giây hoặc một biểu đồ vượt 500 ms với dữ liệu seed nhân 10. Khi đó: Caffeine trong **từng service**, đặt ở use case `reporting` (sau khi interceptor đã kiểm quyền), khóa `(tenantId, tham số)`, TTL 30 giây, tối đa 1000 mục; không cache khi phạm vi `ALL`; không cache ở `lms-api`; không Redis (chỉ system-service có Redis, không đưa vào đường đi bắt buộc của service khác, đúng lý do đã loại ở ADR-0009 mục 2.2).

**Thời gian chờ:**

- Mỗi truy vấn thống kê có hint `jakarta.persistence.query.timeout = 2000` ms; truy vấn báo cáo 20000 ms. ⚠️ Chưa kiểm chứng Hibernate 6.6 + PgJDBC hủy truy vấn native theo hint (cần IT với `pg_sleep`).
- `lms-api`: RestClient riêng cho dashboard (connect 1s, read 3s) và cho báo cáo (connect 2s, read 30s, khớp `proxy_read_timeout 30s` của nginx). Không sửa `ServiceClients` dùng chung: lớp cấu hình mới tự dựng `SimpleClientHttpRequestFactory` với `TokenRelayInterceptor` (lớp public).
- Gộp song song bằng `CompletableFuture` trên executor riêng của `lms-api` (luồng ảo Java 21 có giới hạn đồng thời, hoặc pool cố định; `be-dev` chọn), hạn chót chung 3 giây cho cả `summary`; quá hạn thì nguồn đó vào `unavailableSections`. Executor **bắt buộc** có `TaskDecorator` chép `SecurityContext`, `RequestAttributes` và MDC (`correlationId`) sang luồng con và xóa khi xong, nếu không `TokenRelayInterceptor` mất token và `X-Tenant-Id` (mục 1.1). Mọi future được chờ xong (hoặc hủy) trước khi trả response, để `RequestAttributes` không bị đọc sau khi request kết thúc.
- Mốc "hôm nay": `lms-api` tính `LocalDate.now(Asia/Ho_Chi_Minh)` một lần mỗi request `summary` và truyền xuống mọi nguồn bằng query `asOf=yyyy-MM-dd`. Service nhận `asOf` tùy chọn (mặc định hôm nay giờ VN, từ `Clock` của service). FE không truyền được `asOf` (BFF không chuyển tham số này từ FE).

### 2.3. (c) CSV: service sở hữu sinh và stream, `lms-api` chuyển luồng byte

**Chọn:** CSV do service sở hữu dữ liệu sinh ra, **ghi thẳng vào `HttpServletResponse.getOutputStream()` trên luồng request** (đồng bộ), theo lô 500 dòng; `lms-api` chuyển luồng byte sang client (không giữ cả file trong bộ nhớ). Tên đầu sách/độc giả do chính service tra qua port sẵn có của nó, theo lô.

| Phương án | Đánh giá |
|---|---|
| **R1. Service sinh CSV đồng bộ, BFF chuyển luồng (chọn)** | Một khuôn cho mọi báo cáo, giống `users/export`; service biết định nghĩa, log `report-export` có `tenantId`/`actorId` thật; sales đã có tra tên lô (`ListStockItemsUseCase`); BFF không phải hiểu CSV. Đánh đổi: circulation phải gọi identity và catalog theo lô (thêm 2 endpoint tra lô). |
| R2. Service trả JSON, BFF bổ sung tên rồi sinh CSV | BFF thành nơi chứa định dạng báo cáo và phải giữ đủ 10000 dòng JSON; hai khuôn (users do identity sinh, phần còn lại BFF sinh). |
| `StreamingResponseBody` / phản hồi bất đồng bộ ở service | Bị loại vì **sai với multi-tenant**: thân phản hồi ghi trên luồng khác sau khi controller trả về, lúc đó `TenantContextFilter` đã `clear()` `TenantContext` (ThreadLocal) nên RLS trả rỗng hoặc lỗi, transaction cũng đã đóng. |
| `byte[]` như `users/export` | Đủ cho 5000 dòng, nhưng đề bài yêu cầu streaming với 10000 dòng; giữ nguyên `users/export` (không sửa), báo cáo mới dùng stream. |

Quy tắc chung (khớp đề bài 5.5):

- Bộ ghi CSV dùng chung `lms.common.csv.CsvWriter` ở `common-kernel` (Java thuần, nhận `java.io.Writer`): RFC 4180, phân tách `,`, xuống dòng `CRLF`, ô có `,` `"` `CR` `LF` thì bọc `"` và nhân đôi `"`. Chống CSV injection: ô **văn bản** bắt đầu bằng `=` `+` `-` `@` `TAB` `CR` thêm `'` phía trước; ô **số/ngày/boolean** ghi bằng phương thức kiểu riêng, không qua bộ chống (nếu không số âm `-5` thành `'-5`). Hằng `BOM = EF BB BF`. `UsersCsv` của identity giữ nguyên (⏭️ chuyển sang `CsvWriter` là việc sau, không làm trong đợt này vì file đang được phiên khác sửa).
- Use case báo cáo ở `reporting.application` nhận một cổng ghi dòng (`ReportSink` hoặc `Writer`), không phụ thuộc servlet. Controller đặt header rồi gọi use case với `OutputStreamWriter(UTF-8)` bọc output stream của response.
- Trần dòng: cấu hình `lms.reports.max-rows` (mặc định 10000). Vì header phải gửi trước thân, use case **đếm trước** (`COUNT` cùng bộ lọc), nếu vượt trần thì controller đặt `X-Report-Truncated: true` rồi ghi đúng `max-rows` dòng. Dữ liệu đổi giữa lúc đếm và lúc đọc chỉ làm header sai trong trường hợp biên (⚠️ chấp nhận).
- Lỗi phát hiện **trước** khi ghi byte đầu (quyền, module, tham số) trả JSON lỗi bình thường. Lỗi **sau** khi đã ghi: chỉ có thể cắt kết nối và log ERROR; FE nhận file thiếu. ⚠️ chấp nhận cho trình diễn.
- Header: `Content-Type: text/csv; charset=UTF-8`, `Content-Disposition: attachment; filename="<tên>-<yyyyMMdd>.csv"` (ngày giờ VN), `Cache-Control: no-store`.
- `lms-api` chuyển luồng: dùng `RestClient.get()...exchange(...)`, nếu mã 2xx thì chép `Content-Type`, `Content-Disposition`, `X-Report-Truncated` rồi `transferTo` output stream của response; mã 4xx/5xx thì ném ngoại lệ để `ApiExceptionHandler` xử lý như hiện nay. Controller `lms-api` trả `void` và nhận `HttpServletResponse` (đồng bộ, cùng luồng nên `TokenRelayInterceptor` vẫn có token).
- nginx: thêm `Content-Disposition, X-Report-Truncated` vào `Access-Control-Expose-Headers` (snippet `common.conf`), nếu không FE khác origin không đọc được tên file và cờ cắt.
- Log INFO có cấu trúc ở service: `report-export report=<tên> actorId=<sub> tenantId=<uuid> rows=<n> truncated=<bool>`, không log giá trị ô.

Báo cáo và nơi sinh:

| Báo cáo | Service | Tra tên | Ghi chú |
|---|---|---|---|
| Mượn quá hạn | circulation | `readerName` qua identity `POST /internal/users/lookup` (mới); `titleName`, `barcode` qua catalog `POST /internal/book-copies/lookup` (mới); theo lô 100 | Một dòng mỗi bản sao chưa trả của phiếu quá hạn; tra lỗi thì để trống, vẫn xuất đủ dòng |
| Tồn kho | sales | `titleName`, `isbn` qua `CatalogLookupPort` sẵn có (theo trang 100 dòng) | Dùng lại `lowStock` của `stock.application` |
| Đơn hàng | sales | không cần (không có cột tên đầu sách; không xuất tên/điện thoại/địa chỉ khách) | lọc theo `created_at` giờ VN |
| Người dùng | identity | đã có | không đổi |

Hai endpoint tra lô mới phải lọc theo tenant: `POST /internal/book-copies/lookup` qua `@TenantId`/RLS như `books/lookup`; `POST /internal/users/lookup` **phải** lọc qua `tenant_memberships` của tenant hiện tại (bảng `users` toàn cục, không RLS), id ngoài tenant không có trong kết quả, không báo lỗi.

### 2.4. (d) Seed: runner một lần theo profile trong từng service sở hữu, điều phối bằng script

**Chọn:** mỗi service sở hữu dữ liệu (identity, catalog, circulation, sales) có bước seed chạy trong một tiến trình **một lần** của chính jar service đó, bật bằng profile `demo-seed`, không web, rồi thoát. Script `scripts/seed-demo.ps1` chạy lần lượt các jar. Kế hoạch dữ liệu (id, số lượng, quan hệ, độ lệch ngày) nằm ở **một** nơi dùng chung để các service khớp nhau.

| Phương án | Đánh giá |
|---|---|
| **S1. Runner `@Profile("demo-seed")` một lần trong từng service + kế hoạch dùng chung (chọn)** | Mỗi service ghi DB của mình bằng vai trò `<svc>_app` dưới RLS (`TenantContext.callAs`), dùng use case khi có thể, persistence của chính context khi cần ngày quá khứ; schema lệch thì lỗi biên dịch/khởi động; không có đường HTTP; không chạy khi khởi động bình thường. Đánh đổi: bốn phần cài đặt (làm song song được) và một module dùng chung. |
| S2. system-service chạy SQL seed lên 6 DB (endpoint `@PlatformAdminOnly` hoặc khi khởi động) | Một chỗ, nhưng ghi bằng chủ bảng (lách vai trò app, đề bài 8.2 cấm), mở rộng ngoại lệ "chỉ DDL" của ADR-0004 sang DML vào DB của service khác, bỏ qua bất biến miền (chép role mẫu, sổ cái tồn, hợp đồng), có đường HTTP (đề bài cấm). |
| S3. Migration Flyway (versioned hoặc repeatable) | Chạy ở mọi môi trường kể cả production (đề bài cấm); ngày cố định lúc migrate, không "tươi"; làm bẩn `flyway_schema_history`. |
| S4. Script gọi API công khai `/api/**` | Đi qua nghiệp vụ đầy đủ, nhưng không tạo được lịch sử (ngày mượn, ngày thanh toán = hiện tại), nên biểu đồ và quá hạn không có dữ liệu. Chỉ dùng được cho tenant/người dùng. |
| S5. `ApplicationRunner` chạy mỗi lần service khởi động khi bật profile | Như S1 nhưng gắn vào tiến trình phục vụ: phải khởi động lại service để seed, thứ tự khởi động giữa các service quyết định tính đúng. Bị S1 thay thế (cùng mã, khác cách chạy). |

Thiết kế S1:

- **Module mới `shared-libraries/demo-seed`** (artifact `demo-seed`), hai phần:
  - `lms.demo.plan` (Java thuần): `DemoIds` sinh UUID tất định `UUID.nameUUIDFromBytes("lms-demo:<loại>:<n>")`; `DemoPlan.generate(LocalDate today, long seed)` dùng `java.util.Random(seed)` cố định, trả danh sách đầu sách, bản sao (kèm trạng thái), độc giả, phiếu mượn (bản sao, độc giả, độ lệch ngày mượn/hạn/trả), phạt, mặt hàng tồn, đơn, trả hàng, hoàn tiền. Cấu trúc (id, ai mượn bản sao nào, bản sao nào `LOANED`) **không phụ thuộc** `today`, chỉ ngày tuyệt đối dịch theo `today`; nên chạy các service vào hai ngày khác nhau vẫn khớp trạng thái bản sao với phiếu đang mở. Các con số "đúng 6 quá hạn, đúng 4 phạt chưa thu, đúng 5 sắp hết tồn" là bất biến của kế hoạch, có test đơn vị.
  - `lms.demo.runner` (Spring, autoconfiguration có điều kiện `@Profile("demo-seed")` **và** `@ConditionalOnProperty("lms.demo-seed.enabled", havingValue = "true")`): `DemoSeedRunner` thu thập các bean `DemoSeedStep` của service (`order()`, `run(DemoSeedContext)` trả số tạo/bỏ qua), kiểm guard, chạy từng bước trong `TenantContext.callAs(demoTenantId)`, in tóm tắt, rồi `SpringApplication.exit`.
- **Guard (từ chối, không ghi gì, thoát mã khác 0):** profile `prod` đang bật; thiếu `lms.demo-seed.enabled=true`; thiếu `lms.demo-seed.tenant-id` ở các service ngoài identity; ở identity, tenant mã `demo` đã tồn tại nhưng không do seed tạo (không có cờ/nhận diện demo, `be-dev` chọn cách nhận diện, ví dụ tên tổ chức cố định). Mật khẩu demo lấy từ `lms.demo-seed.password` (không có mặc định trong mã; script truyền giá trị mặc định dev, ghi trong README).
- **Đặt bước ở đâu:** mỗi context sở hữu bảng có một `DemoSeedStep` trong `infrastructure/demo` của chính nó (ví dụ `borrowing.infrastructure.demo`, `fine.infrastructure.demo`), dùng repository/adapter persistence của chính context đó hoặc use case của nó. Không có context nào ghi bảng của context khác; không đặt bean seed ở `domain`/`application`.
- **Cách ghi theo service:**

  | Service | Thứ tự | Cách ghi | Ghi chú |
  |---|---|---|---|
  | identity | 1 | Tenant `demo` (LENDING + SALES) qua `CreateTenantUseCase` (chép role mẫu, module); hợp đồng HIỆU LỰC kết thúc sau khoảng 20 ngày qua use case của context `contract`; người dùng với id `DemoIds` qua persistence của context `user` (mật khẩu qua `PasswordHasher`), gán role theo tên role mẫu, hồ sơ độc giả | In `DEMO_TENANT_ID=<uuid>` để script truyền cho bước sau. Event Kafka do use case tái dùng phát ra (TenantCreated...) được **giữ** vì là sự kiện thật (lệch đề bài 8.2 "không gửi event": bỏ chúng phải sửa use case; consumer đều idempotent). ⚠️ `be-dev` xác nhận quy tắc kích hoạt hợp đồng (ADR-0012) cho phép tạo hợp đồng HIỆU LỰC trong một bước. |
  | catalog | 2 | Đầu sách và bản sao với id `DemoIds`, trạng thái theo kế hoạch, qua persistence của `catalog`/`inventory` | |
  | circulation | 3 (song song với sales) | `known_tenants` (ACTIVE), phiếu mượn có ngày quá khứ, `title_id`, phạt, đặt trước qua persistence của `borrowing`, `fine`, `reservation` | Không gọi catalog/identity; mọi id lấy từ kế hoạch |
  | sales | 3 (song song với circulation) | Danh bạ tenant (`sales_tenants`), cấu hình (ngưỡng chung), nhà cung cấp, phiếu nhập đã xác nhận, mặt hàng tồn **kèm dòng sổ cái** (tổng `delta_on_hand` = `on_hand`), đơn (`order_no` tiền tố `DEMO-`), dòng đơn, thanh toán, trả hàng, hoàn tiền | Giữ bất biến `ck_*` và sổ cái; số đơn là khóa tự nhiên |

- **Idempotent:** mọi bản ghi có khóa tất định (`DemoIds` hoặc khóa tự nhiên: mã tenant, username, ISBN, `order_no`, barcode); bước chỉ chèn phần chưa có, không cập nhật phần đã có. Chạy lần hai không đổi số bản ghi (AC26). Lần trước dừng giữa chừng thì lần sau bổ sung phần thiếu (mỗi bước ghi theo lô, mỗi lô một transaction).
- **Chạy một lần:** `java -jar <svc>.jar --spring.profiles.active=dev,demo-seed --spring.main.web-application-type=none --lms.demo-seed.enabled=true ...`. Profile `demo-seed` (file `application-demo-seed.yml` ở từng service) tắt `spring.kafka.listener.auto-startup` và các job `@Scheduled` (theo thuộc tính bật/tắt sẵn có của từng job, ⚠️ `be-dev` rà từng service), để tiến trình một lần không tranh partition hay chạy job. Không xung đột cổng với service đang chạy vì không có web. Thứ tự trong script: system-service đã migrate xong, rồi identity, catalog, rồi circulation và sales.
- **Làm mới dữ liệu (Q5):** **không làm** ở đợt này. Lý do: xóa dữ liệu của tenant đụng sổ cái chỉ-chèn, chuỗi FK đơn/trả/hoàn, ràng buộc phiếu; cách làm mới đơn giản và an toàn là `docker compose down -v` rồi seed lại. Lệch mặc định của đề bài (BA đề xuất có nhưng tắt mặc định), cần người dùng xác nhận.
- Hệ quả thời gian: dữ liệu tính tương đối theo ngày seed; sau vài ngày, phiếu `ACTIVE` chưa quá hạn sẽ dần quá hạn nên các con số "đúng 6" chỉ đúng vào ngày seed (AC1 phải chạy ngay sau seed).

### 2.5. (e) Phân quyền

- Endpoint `/internal/**` mới mang khóa động `Controller/method` (ADR-0003), không `@RequirePermission`. Tên dưới đây là **tên chốt**, đổi tên là đổi khóa:

  | Khóa | Service, đường dẫn nội bộ | Module |
  |---|---|---|
  | `CatalogStatsController/summary` | catalog `GET /internal/reporting/catalog/summary` | lõi (không `@RequiresModule`) |
  | `BookCopyController/lookup` | catalog `POST /internal/book-copies/lookup` | `LENDING` (kế thừa từ lớp `BookCopyController`) |
  | `LendingStatsController/summary` | circulation `GET /internal/reporting/lending/summary?asOf=` | `LENDING` |
  | `LendingStatsController/borrowingTrend` | circulation `GET /internal/reporting/lending/borrowing-trend?from=&to=&granularity=` | `LENDING` |
  | `LendingStatsController/topBorrowedTitles` | circulation `GET /internal/reporting/lending/top-titles?from=&to=&limit=` | `LENDING` |
  | `LendingReportController/overdueBorrowingsCsv` | circulation `GET /internal/reporting/lending/overdue-borrowings.csv` | `LENDING` |
  | `SalesStatsController/summary` | sales `GET /internal/sales/reporting/summary?asOf=` | `SALES` |
  | `SalesStatsController/revenueTrend` | sales `GET /internal/sales/reporting/revenue-trend?from=&to=` | `SALES` |
  | `SalesStatsController/topSoldTitles` | sales `GET /internal/sales/reporting/top-titles?from=&to=&limit=` | `SALES` |
  | `SalesReportController/stockCsv` | sales `GET /internal/sales/reporting/stock.csv?lowStockOnly=` | `SALES` |
  | `SalesReportController/ordersCsv` | sales `GET /internal/sales/reporting/orders.csv?from=&to=&channel=&status=` | `SALES` |
  | `UserController/lookup` | identity `POST /internal/users/lookup` | lõi |
  | (không khóa) `@PlatformAdminOnly` | identity `GET /api/platform/dashboard/overview?withinDays=` | không |

  Sales dùng tiền tố `/internal/sales/**` như các controller hiện có của sales (`HttpSalesClient.BASE`). Tham số từ 2 trở lên gom vào `record` (quy ước CLAUDE.md).
- `lms-api` không có khóa riêng: dashboard gộp được phép khi ít nhất một nguồn cho phép (mục 2.1), báo cáo chuyển nguyên mã lỗi của service.
- Bổ sung tên trong top sách mượn: BFF gọi `POST /internal/books/lookup` (`TitleController/lookup`) bằng token người dùng; thiếu khóa thì `titleName = null` (không làm hỏng biểu đồ). Top bán dùng snapshot `sales_order_lines.title_name` của sales, BFF không tra.
- **Migration identity** `V25__reporting_permissions.sql` (⚠️ kiểm số: ADR-0017 đã lấy V24): chèn các khóa trên vào `permissions` (FK của `role_permissions`), thêm vào `role_template_permissions` và vào role hiện có của tenant được chép từ mẫu tương ứng, theo mẫu V17/V20/V22 (ghi `role_permissions` có RLS: đặt `set_config('app.tenant_id', ...)` theo từng tenant hoặc chạy bằng chủ bảng như các file đó đã làm). Phân bổ mặc định (đề bài 6.2, Q3 mặc định):
  - Mẫu thủ thư/quản lý thư viện: `CatalogStatsController/summary`, `LendingStatsController/*`, `BookCopyController/lookup`, `UserController/lookup`, `TitleController/lookup`.
  - Mẫu quản lý cửa hàng: `CatalogStatsController/summary`, `SalesStatsController/*`.
  - Khóa xuất (`LendingReportController/overdueBorrowingsCsv`, `SalesReportController/*Csv`): **không** gán sẵn (Q3 mặc định: chỉ tenant admin `is_admin` và role được gán tay).
  - ⚠️ Tên mẫu role thật (`a2..a5` và mẫu SALES) `be-dev` đọc từ V3/V17/V20, không đoán.
- Platform admin: tổng quan nền tảng bằng `@PlatformAdminOnly`; dashboard/biểu đồ/báo cáo của một tenant bằng `X-Tenant-Id` (ADR-0016, bỏ qua kiểm khóa và module, log `platform-audit operation=act-as-tenant`). Không dùng query `tenantId`/`@CrossTenantReadable` của ADR-0008 cho các endpoint này.
- **Sửa kèm (bắt buộc để platform admin dùng được báo cáo):** lời gọi service-sang-service (circulation → identity/catalog, sales → catalog) phải chuyển tiếp `X-Tenant-Id` của request gốc giống `TokenRelayInterceptor` của `lms-api`; nếu không, phía nhận trả `403 TENANT_REQUIRED` và cột tên trống khi platform admin xuất báo cáo. Người gọi không phải platform admin không bị ảnh hưởng (header bị bỏ qua, ADR-0016 mục 4).

### 2.6. Định nghĩa còn chỉnh so với đề bài

- **Doanh thu (Q2, cần người dùng chốt).** Đề bài định nghĩa (A) loại đơn `CANCELLED` nhưng (B) trừ **mọi** khoản hoàn `REFUNDED`. Một đơn đã thanh toán rồi hủy có khoản hoàn `source = CANCELLATION`: (A) không tính nó nhưng (B) vẫn trừ, nên `net` bị trừ hai lần và có thể âm. Khuyến nghị: tính theo dòng tiền, (A) gồm mọi đơn có `paid_at` trong khoảng (kể cả sau đó hủy), (B) mọi khoản hoàn `REFUNDED` theo `refunded_at`; `net = A − B` là tiền thực thu. `orders` = số đơn trong (A). ADR dùng định nghĩa khuyến nghị này cho tới khi người dùng chốt khác.
- **Mốc thanh toán:** cột `sales_orders.paid_at` (đã có, `SalesOrder` đặt khi thanh toán). ⚠️ Chưa kiểm chứng đơn POS luôn có `paid_at` lúc chốt; nếu có đơn đã `PAID` mà `paid_at` NULL thì `be-dev` báo lại, không tự lấy `created_at`.
- **Mốc trả bản sao:** `borrowing_items.return_date` (`DATE`). **Mốc mượn:** `borrowings.borrow_date`. Hai cột này do circulation tính bằng `Clock.systemDefaultZone()`, trong container là UTC: phiếu lập 00:00-07:00 giờ VN mang ngày hôm trước. Khuyến nghị đổi `Clock` của circulation (và catalog) sang `Asia/Ho_Chi_Minh` (câu hỏi Q-SA2); cho tới khi đổi, AC5 với `borrowedToday`/`returnedToday` không đạt trong khung giờ đó.

## 3. Thiết kế

### 3.1. Thành phần

```
FE ──/api/dashboard/**, /api/reports/**──> nginx:8080 ──> lms-api:8085
  lms-api (mới)
    dashboard/  DashboardController (summary, charts, top-titles)
                DashboardAggregator: song song + TaskDecorator, hạn chót 3s, quy tắc gộp 2.1
                client/ StatsClient (catalog, circulation, sales), ModuleLookupClient (identity platform)
    reports/    ReportController (void + HttpServletResponse), ReportStreamClient (exchange -> transferTo)
  ──/internal/**──>
    catalog      reporting/  CatalogStatsController ─ application ─ CatalogStatsQueryAdapter (native)
                 inventory/  BookCopyController/lookup (mới)
    circulation  reporting/  LendingStatsController, LendingReportController
                             application: ports đọc + port tra tên (identity users/lookup, catalog book-copies/lookup)
                             infrastructure: LendingStatsQueryAdapter (native), client adapters (chuyển X-Tenant-Id)
                 borrowing/  BorrowBookUseCase ghi title_id
    sales        reporting/  SalesStatsController, SalesReportController
                             application: dùng stock.application (tồn thấp, tra tên), SalesStatsQueryAdapter (native)
    identity     user/       UserController/lookup (mới, lọc tenant_memberships)
                 contract/   PlatformDashboardController/overview (/api/platform/dashboard/overview)
shared-libraries
    common-kernel  lms.common.csv.CsvWriter (mới)
    demo-seed      lms.demo.plan (Java thuần) + lms.demo.runner (autoconfig, profile demo-seed) (module mới)
system-service     migration: circulation V9, catalog V6, sales V5, identity V25
infrastructure     nginx snippets/common.conf: Expose-Headers thêm Content-Disposition, X-Report-Truncated
```

### 3.2. Luồng `summary`

```mermaid
sequenceDiagram
    participant FE
    participant BFF as lms-api
    participant ID as identity
    participant CAT as catalog
    participant CIR as circulation
    participant SAL as sales
    FE->>BFF: GET /api/dashboard/summary (Bearer, [X-Tenant-Id])
    BFF->>BFF: asOf = hôm nay giờ VN; modules = claim, hoặc
    opt platform admin có X-Tenant-Id
        BFF->>ID: GET /api/platform/tenants/{id}/modules
    end
    par hạn chót chung 3s
        BFF->>CAT: GET /internal/reporting/catalog/summary
    and nếu LENDING bật
        BFF->>CIR: GET /internal/reporting/lending/summary?asOf=
    and nếu SALES bật
        BFF->>SAL: GET /internal/sales/reporting/summary?asOf=
    end
    BFF->>BFF: gộp theo quy tắc 2.1 (disabled/forbidden/unavailable)
    opt sales.lowStockItems có titleId
        BFF->>CAT: POST /internal/books/lookup (bổ sung tên, lỗi thì null)
    end
    BFF-->>FE: 200 {generatedAt, timezone, tenantId?, modules, catalog, lending, fines, sales, disabledModules, forbiddenSections, unavailableSections}
```

Ghi chú: sales `summary` có thể tự trả tên mặt hàng tồn thấp (đã có tra lô trong `ListStockItemsUseCase`); khi đó BFF bỏ bước tra cuối. `be-dev` sales chọn, ghi vào hợp đồng.

### 3.3. Hợp đồng ở mức ranh giới (`be-dev` chốt chi tiết trong `docs/api/dashboard-reports.md`)

| FE gọi | BFF gọi | Dữ liệu chính | Lỗi nghiệp vụ |
|---|---|---|---|
| `GET /api/dashboard/summary` | 3 nguồn + tra module/tên | như đề bài mục 7; thêm `tenantId` khi platform admin dùng `X-Tenant-Id` | `403 FORBIDDEN` (mọi nguồn cấm), `503 SERVICE_UNAVAILABLE` (mọi nguồn hỏng), lỗi mức người gọi trả nguyên |
| `GET /api/dashboard/charts/borrowings` | circulation `borrowing-trend` | `{from,to,granularity,points[{bucket,borrowed,returned}]}` | `400 INVALID_RANGE`, `RANGE_TOO_LARGE`, `INVALID_GRANULARITY`; `403 MODULE_NOT_ENABLED` |
| `GET /api/dashboard/charts/revenue` | sales `revenue-trend` | `{from,to,totals{gross,refunds,net,orders},points[...]}` | như trên |
| `GET /api/dashboard/top-titles/borrowed` | circulation `top-titles` + catalog `books/lookup` | `{items[{titleId,titleName|null,count}]}` | `400` khi `limit` ngoài 1..50 |
| `GET /api/dashboard/top-titles/sold` | sales `top-titles` | `{items[{titleId,titleName,quantity,revenue}]}` | như trên |
| `GET /api/reports/overdue-borrowings` | circulation `overdue-borrowings.csv` | `text/csv` stream | lỗi trước byte đầu là JSON |
| `GET /api/reports/stock?lowStockOnly=` | sales `stock.csv` | `text/csv` stream | |
| `GET /api/reports/orders?from=&to=&channel=&status=` | sales `orders.csv` | `text/csv` stream | `400 INVALID_RANGE`, `RANGE_TOO_LARGE` |
| `GET /api/users/export` | identity (đã có) | không đổi | |
| `GET /api/platform/dashboard/overview?withinDays=` | identity (chuyển nguyên `/api/platform/**`) | như đề bài 5.4 | `403 FORBIDDEN` khi không phải platform admin |

Biểu đồ và top là endpoint một nguồn: BFF chỉ chuyển (dùng client dashboard, read 3s), lỗi đi theo `ApiExceptionHandler` hiện có. Mã lỗi khoảng ngày (`INVALID_RANGE`, `RANGE_TOO_LARGE`, `INVALID_GRANULARITY`) do `reporting.domain` của service ném và `ExceptionHandler` của context `reporting` ánh xạ; không kiểm ở controller.

## 4. Phân việc

Mỗi dòng là một phạm vi file riêng để nhiều `be-dev` làm song song. Hợp đồng `/internal/**` ở mục 2.5 và 3.3 là điểm hẹn: bên gọi viết theo hợp đồng với client giả, không chờ bên kia xong.

| # | Ai | Việc | Phạm vi file | Phụ thuộc | Xong khi |
|---|---|---|---|---|---|
| ⏳ 0a | be-dev (shared) | `CsvWriter` (RFC 4180, chống injection cho ô văn bản, ô kiểu không chặn, BOM) | `shared-libraries/common-kernel` | không | test đơn vị AC14 qua |
| ⏳ 0b | be-dev (shared) | Module `demo-seed`: `DemoIds`, `DemoPlan` (bất biến 6/4/5, cấu trúc không phụ thuộc ngày), `DemoSeedStep`, `DemoSeedRunner` + guard + autoconfig; thêm vào `pom.xml` gốc và `shared-libraries/pom.xml` | `shared-libraries/demo-seed`, hai pom | không | test đơn vị plan và guard (`prod` bị từ chối) qua |
| ⏳ 1 | be-dev (system) | 4 migration: circulation V9, catalog V6, sales V5, identity V25 (kiểm số ngay trước khi tạo) | `services/system-service/src/main/resources/db/migration/*` | không (identity V25 cần tên mẫu role) | `MigrationApiIT`/IT của service chạy xanh |
| ⏳ 2a | be-dev catalog | `reporting` (CatalogStatsController/summary, adapter native, đưa vào danh sách rà), `BookCopyController/lookup` | `services/catalog-service` | 1 (chỉ mục, không chặn code) | WebMvc ma trận quyền, IT `AppRoleDatabase` cô lập 2 tenant |
| ⏳ 2b | be-dev circulation | `title_id` trong `BorrowingItem`/entity/`BorrowBookUseCase`; `reporting` (summary, borrowingTrend, topBorrowedTitles, overdueBorrowingsCsv stream), port tra lô identity/catalog; chuyển `X-Tenant-Id` ở các adapter client | `services/circulation-service` | 0a, 1; hợp đồng của 2a, 2d | test biên múi giờ (AC5), AC2, AC8-9, AC12, WebMvc ma trận, IT |
| ⏳ 2c | be-dev sales | `reporting` (summary, revenueTrend, topSoldTitles, stockCsv, ordersCsv) dùng `stock.application`; chuyển `X-Tenant-Id` ở `HttpCatalogLookupAdapter` | `services/sales-service` | 0a, 1 | AC3, AC4 (định nghĩa 2.6), AC10, AC13, WebMvc, IT |
| ⏳ 2d | be-dev identity | `UserController/lookup` (lọc membership); `PlatformDashboardController/overview` trong `contract` | `services/identity-service` (⚠️ `UserController` đang có thay đổi chưa commit của phiên khác: Edit có mục tiêu, không ghi đè) | không | AC23, test lookup không trả user tenant khác |
| ⏳ 3 | be-dev lms-api | Nhóm `dashboard` (aggregator song song + TaskDecorator, client riêng timeout 1s/3s, tra module platform), `reports` (chuyển luồng); không sửa client/DTO hiện có (nhiều file đang chưa commit) | `lms-api/src/main/java/lms/api/{dashboard,reports}` + một lớp cấu hình mới | hợp đồng 2a-2d (client giả) | AC6, AC7, AC19, AC22 bằng test với client giả; test TaskDecorator có token ở luồng con |
| ⏳ 4 | be-dev (infra) | nginx `Access-Control-Expose-Headers` thêm `Content-Disposition, X-Report-Truncated` | `infrastructure/nginx/snippets/common.conf` | không | `nginx -t`; FE đọc được tên file |
| ⏳ 5 | be-dev từng service (2a-2d) | `DemoSeedStep` của service mình + `application-demo-seed.yml` | `infrastructure/demo` của từng context | 0b; 2b (cột `title_id`) | AC26 (chạy hai lần), AC28, AC29 |
| ⏳ 6 | be-dev (scripts) | `scripts/seed-demo.ps1` (chạy jar một lần theo thứ tự, truyền `DEMO_TENANT_ID`, in tóm tắt) + mục README | `scripts/`, `README.md` | 5 | AC25 trên máy sạch |
| ⏳ 7 | be-dev lms-api | `docs/api/dashboard-reports.md` + dòng mục lục `docs/api/README.md` | `docs/api` | 3 | AC31 |
| ⏳ 8 | tester | WebMvc ma trận AC18/AC20 từng service; IT cô lập AC21; đầu-cuối qua nginx AC1, AC12-13, AC22-23 sau seed | test của từng module | 2a-2d, 3, 6 | bảng kết quả AC |
| ⏳ 9 | perf-tester | p95 `summary` < 1s, biểu đồ < 500ms, CSV 10000 dòng < 5s, với dữ liệu seed và seed nhân 10; quyết định có bật cache (mục 2.2) | không sửa code | 6 | báo cáo số đo, khuyến nghị bật/không bật cache |

Song song được: 0a, 0b, 1, 2d, 4 ngay từ đầu; 2a, 2b, 2c, 3 song song sau khi chốt hợp đồng (không cần chờ code của nhau); 5 song song theo service sau 0b; 6 sau 5; 8, 9 cuối. Đường găng: 0a → 2b (CSV + tra lô) → 6 → 8.

## 5. Rủi ro và việc chưa kiểm chứng

- ⚠️ **Mất token ở luồng con của `lms-api`**: thiếu `TaskDecorator` thì mọi nguồn trả 401 và dashboard thành 503; header `X-Tenant-Id` cũng mất nên platform admin nhận 403 `TENANT_REQUIRED`. Cần test riêng cho decorator.
- ⚠️ **Native query bỏ qua `@TenantId`**: chỉ còn RLS và điều kiện `tenant_id` tường minh. Phải có IT chạy bằng `<svc>_app` với hai tenant (AC21), và review danh sách lớp được phép.
- ⚠️ **Hint timeout truy vấn** với Hibernate 6.6 + PgJDBC cho native query: chưa chạy.
- ⚠️ **Clock circulation/catalog theo múi giờ máy** (UTC trong container): `borrow_date`/`return_date` lệch ngày trong 00:00-07:00 giờ VN (mục 2.6). Chưa kiểm `TZ` thực tế của máy dev Windows (có thể là giờ VN nên test tay không thấy lỗi).
- ⚠️ **Định nghĩa doanh thu** của đề bài trừ hoàn tiền của đơn hủy hai lần (mục 2.6); chờ người dùng chốt.
- ⚠️ **`paid_at` của đơn POS**: chưa kiểm luôn được đặt.
- ⚠️ **Dòng mượn cũ không có `title_id`**: top sách mượn bỏ qua chúng; với dữ liệu thật trước migration, top có thể thiếu.
- ⚠️ **CSV lỗi giữa chừng**: không trả được mã lỗi sau khi đã ghi byte; FE nhận file thiếu. Header `X-Report-Truncated` dựa trên `COUNT` trước, có thể lệch trong trường hợp biên.
- ⚠️ **Platform admin bỏ qua kiểm module** (ADR-0016): nếu BFF không tra module của tenant đích thì phần sales của tenant không bật SALES trả số 0 thay vì ẩn. Thiết kế đã tra qua identity; cần AC22.
- ⚠️ **Gọi service-sang-service chưa chuyển `X-Tenant-Id`**: sửa ở 2b, 2c; các luồng khác của platform admin (ví dụ lập phiếu mượn qua `X-Tenant-Id`) đang bị cùng lỗi, ngoài phạm vi ADR này nhưng sửa adapter sẽ chữa luôn.
- ⚠️ **Seed qua use case identity phát event Kafka**: lệch đề bài 8.2; consumer của support/circulation/sales được giả định idempotent, chưa rà từng consumer.
- ⚠️ **Tiến trình seed một lần trong Docker**: thiết kế nhắm máy dev (`scripts\dev-start.ps1`, jar trên host). Chạy trong compose (`docker compose run` với entrypoint của image) chưa kiểm.
- ⚠️ **Job `@Scheduled` và listener Kafka trong tiến trình seed**: phải tắt bằng profile `demo-seed`; chưa rà đủ thuộc tính bật/tắt của từng job.
- ⚠️ **Xung đột file**: `lms-api` có nhiều file client/DTO và identity `UserController` đang thay đổi chưa commit; thiết kế đặt code mới vào package mới để tránh, nhưng 2d vẫn phải sửa `UserController` của identity.
- ⚠️ **Khóa mới và token cũ**: quyền gán bằng migration chỉ có hiệu lực ở lần đăng nhập/làm mới kế tiếp (ADR-0003).
- ℹ️ **Số liệu của tenant `SUSPENDED`** (AC24): theo hành vi hiện có của đăng nhập/token, không thêm cơ chế riêng.
- ℹ️ **Mở rộng sau**: cache Caffeine theo điều kiện ở mục 2.2; bảng rollup (phương án C) khi dữ liệu lớn; backfill `title_id`; chuyển `UsersCsv` sang `CsvWriter`.

## 6. Câu hỏi mở (điều phối viên hỏi người dùng)

Ngoài Q1 đến Q7 của đề bài (ADR dùng mặc định của BA, trừ Q5):

1. ⏳ **Q-SA1 (doanh thu, gắn Q2 của BA):** chấp nhận định nghĩa theo dòng tiền ở mục 2.6 (đơn đã thanh toán rồi hủy vẫn vào `gross`, khoản hoàn hủy trừ ở `refunds`) không? Khuyến nghị: có, vì `net` luôn bằng tiền thực thu và không bao giờ trừ hai lần.
2. ⏳ **Q-SA2 (múi giờ circulation):** đổi `Clock` của circulation và catalog sang `Asia/Ho_Chi_Minh` không? Khuyến nghị: có, vì đây là cách duy nhất để `borrow_date`, `due_date`, `return_date` đúng ngày giờ VN. Đánh đổi: hạn trả của phiếu lập trong 00:00-07:00 sẽ muộn hơn một ngày so với hiện nay; không cần migration.
3. ⏳ **Q-SA3 (Q5 của BA, làm mới seed):** đồng ý **không** làm chế độ làm mới ở đợt này (làm mới bằng `docker compose down -v` rồi seed lại) không? Khuyến nghị: đồng ý, vì xóa dữ liệu tenant đụng sổ cái chỉ-chèn và chuỗi FK đơn/trả/hoàn.
4. ⏳ **Q-SA4 (event khi seed):** chấp nhận để use case identity tái dùng phát event Kafka thật (TenantCreated...) khi seed không? Khuyến nghị: chấp nhận; nếu không thì phải thêm cờ tắt publisher trong use case, phức tạp hơn mà không thêm an toàn.
