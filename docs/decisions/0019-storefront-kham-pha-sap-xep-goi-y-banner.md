# ADR-0019: Storefront Gói A "khám phá": sắp xếp/lọc, sách mới về, gợi ý tìm kiếm và banner theo tenant

- Trạng thái: đề xuất (chưa cài đặt). Đề bài: `docs/requirements/2026-11-17-customer-api-goi-a-kham-pha.md` (BA). Người dùng đã chốt Q1 (banner hướng A), Q2 (mới về theo ngày đưa vào bán), Q4 (autocomplete dùng chung `public_read`), Q5 (chấp nhận giới hạn 200 đầu sách), ngoài phạm vi: ảnh bìa, nổi bật/bán chạy. Q3 chưa có câu trả lời riêng: ADR dùng khuyến nghị (a) của BA (mục 2.2), cần người dùng xác nhận (mục 6).
- Liên quan: ADR-0003 (khóa `Controller/method`), ADR-0004 (migration tập trung), ADR-0005 (BFF `lms-api`), ADR-0006 (multi-tenant, RLS fail-closed), ADR-0010 (sales, giá `sellingPrice ?? coverPrice`, "không bản sao đầu sách"), ADR-0011 (customer-api, token dịch vụ, danh sách đóng `@StorefrontAccessible`), ADR-0013 (CORS theo tenant), ADR-0015 (mẫu context `contact` trong sales).
- Số hiệu: `0014` đến `0018` đã dùng (`0014-doi-ten-support-service.md` ... `0018-dashboard-...`), nên dùng `0019`.
- Hợp đồng cho FE: `docs/api/storefront-kham-pha.md`.

## 1. Bối cảnh và mục tiêu

### 1.1. Hiện trạng đã đối chiếu trong code (nhánh `main`, gồm thay đổi chưa commit, 2026-11-17)

| | Sự thật | Nguồn |
|---|---|---|
| ✅ | `StorefrontQueryUseCases.list`: không từ khóa thì `stock.listActive` (JPQL `order by s.createdAt desc, s.id`, đếm `count` riêng), rồi **một** `catalog.lookup` lô cho trang; có từ khóa thì `catalog.lookupByKeyword` (tối đa 200), giao với `findByTitleIds`, sắp theo tên trong bộ nhớ, phân trang trong bộ nhớ. | `sales/.../stock/application/StorefrontQueryUseCases.java` |
| ✅ | Mặt hàng có giá hiệu lực `null` (không `sellingPrice` và catalog không trả đầu sách) bị **lọc sau khi phân trang**: trang có thể ít hơn `size` trong khi `total` vẫn đếm chúng (lỗi nhỏ sẵn có). | như trên, dòng `if (t != null && priceOf(i, t) != null)` |
| ✅ | Use case `list` có `@Transactional(readOnly = true)` bao cả lời gọi HTTP sang catalog, trái chú thích của `CatalogLookupPort` ("Gọi TRƯỚC khi mở transaction"). | `StorefrontQueryUseCases`, `CatalogLookupPort` |
| ✅ | `stock_items`: `selling_price BIGINT NULL`, `active`, `created_at`, `on_hand`, `reserved`; chỉ mục duy nhất `(tenant_id, title_id)`, `(id, tenant_id)`; **không** có chỉ mục theo `created_at` hay giá. `StockItem` (domain) không mang `createdAt`. | `sales/V1__create_sales_tables.sql`, `StockItem.java`, `StockItemJpaEntity.java` |
| ✅ | `coverPrice` chỉ ở catalog (`NUMERIC`, bắt buộc, `>= 0`), đổi được qua `PUT /internal/books/{id}`; catalog **không** phát event nào (`rg TitleUpdated` rỗng). Giá đặt hàng dùng `coverPrice` sống lúc đặt. | `catalog-books.md`, `HttpCatalogLookupAdapter.java` |
| ✅ | `lookupByKeyword` của catalog: JPQL `lower(t.name) like lower(concat('%', :keyword, '%')) or lower(t.author) like ...` `order by t.name`, tối đa 200; **không escape** `%`/`_`; chỉ mục `(tenant_id, lower(name))` không dùng được cho `%...%`. | `SpringDataTitleRepository.java`, `LookupTitlesByKeywordUseCase.java`, `catalog/V1, V2` |
| ✅ | `StorefrontController` có `@StorefrontAccessible` ở mức lớp: mọi method mới thêm vào lớp này tự mở cho token `svc`. Khóa `StorefrontController/list|get|preview` đã seed cho mẫu Quản lý cửa hàng (c3) ở identity `V20`. | `StorefrontController.java`, `identity/V20` |
| ✅ | customer-api: `SecurityConfiguration.PUBLIC_API` có `GET|HEAD /public/t/*/books/*`, nên `/public/t/{code}/books/suggestions` **đã** được `permitAll`; `/public/t/*/banners` **chưa** có. `BookQuery(keyword, page, size)`; cache chỉ là `Cache-Control` (`PublicCache.publicFor`), không đệm danh mục ở server. `PublicExceptionHandler` đã đổi `INVALID_REQUEST` của sales thành `VALIDATION_FAILED`. | `customer-api/.../config/SecurityConfiguration.java`, `BookController.java`, `PublicExceptionHandler.java` |
| ✅ | nginx: `location /public/` áp `public_read` (20r/s, burst 40, theo IP) và `public_tenant` (50r/s, burst 100, theo `{code}`); location regex riêng chỉ cho contact, signup, contact-requests, login. `/books/suggestions` và `/banners` rơi vào `location /public/`, **không cần sửa nginx**. ℹ️ Vùng `public_preview` mà ADR-0011 2.7 mô tả không có trong `nginx.conf` hiện tại (ngoài phạm vi ADR này). | `infrastructure/nginx/nginx.conf` |
| ✅ | lms-api: nhóm `lms.api.sales` (`SalesClient` + `HttpSalesClient`, `BASE = /internal/sales`, các controller `/api/sales/**` chuyển nguyên, 4xx giữ thân lỗi). Không có `PermissionInterceptor` (khóa do sales kiểm). | `lms-api/.../sales/**` |
| ✅ | Mẫu context mới trong sales: `lms.sales.contact` (ADR-0015) với `SalesContactController` (nhân viên) + `ShopContactController` (khách), version lạc quan, migration sales `V4`, seed khóa identity `V22` (đăng ký khóa, gán mẫu c3, backfill role "còn nguyên mẫu"). `SalesArchitectureTest` có luật riêng cho từng context. | `sales/.../contact/**`, `identity/V22`, `SalesArchitectureTest.java` |
| ✅ | `TenantArchitectureRules` bắt entity có cột `tenant_id` phải có `@TenantId`, trừ bảng trong `TENANT_EXEMPT_TABLES`; sales không có native query nào được rà. | `tenant-common/.../TenantArchitectureRules.java`, `sales/.../TenantArchitectureRulesTest.java` |
| ✅ | Số migration lúc viết: sales mới nhất **V5** (`V5__reporting_indexes.sql`, ⚠️ **chưa commit**, của ADR-0018) → kế tiếp **V6, V7**; identity mới nhất **V25** (V23 đến V25 chưa commit) → kế tiếp **V26**. Postgres 16 (compose). | `services/system-service/src/main/resources/db/migration/{sales,identity}/`, `docker-compose.yml` |

### 1.2. Mục tiêu và ràng buộc

- F1 sắp xếp/lọc danh sách, F2 rail "mới về", F3 gợi ý tìm kiếm, F4 banner theo tenant (admin tenant tự quản).
- Không lộ tồn chính xác (chỉ `inStock`), không thêm trường vào `BookSummaryView` (6 trường, có test so tập tên).
- Không atomicity xuyên DB; không bản sao tên/tác giả của catalog trong sales (ADR-0010, ADR-0011 2.6); giá tính ở sales.
- Phân trang danh sách không từ khóa phải ở DB; có từ khóa giữ nguồn 200 đầu sách (Q5 đã chấp nhận).
- Khóa quyền là `Controller/method`; endpoint cho token `svc` phải nằm trong danh sách đóng của ADR-0011 2.1.

## 2. Quyết định

### 2.1. Sắp theo giá khi mặt hàng chỉ có giá bìa ở catalog: bản sao giá bìa trong `stock_items`, chỉ dùng để sắp

| Tiêu chí | **A. Cột `cover_price_snapshot` + cột sinh `effective_price` (chọn)** | B. Sắp trong bộ nhớ toàn bộ mặt hàng active | C. Sắp DB chỉ theo `selling_price`, NULL cuối | D. Đổi luật: lúc đưa vào bán chép `coverPrice` thành `sellingPrice` |
|---|---|---|---|---|
| Đúng thứ tự | Đúng, trừ khi giá bìa đổi ở catalog sau lần đồng bộ cuối (tự sửa ở lần xem kế tiếp, xem dưới) | Đúng tuyệt đối | Sai thấy rõ: mặt hàng theo giá bìa dồn cuối, lệch với giá hiển thị | Đúng |
| Chi phí mỗi request | Một truy vấn trang có chỉ mục + một lô catalog (như hiện nay) | Quét mọi mặt hàng active + `ceil(n/100)` lời gọi catalog mỗi request: tenant 5 000 mặt hàng là 50 lời gọi; khuếch đại tải từ một GET công khai | Như A | Như A |
| Nguồn sự thật của giá | Giá **hiển thị và tính tiền** vẫn là `sellingPrice ?? coverPrice` sống từ catalog; snapshot chỉ là khóa sắp | Giữ nguyên | Giữ nguyên | Đổi ngữ nghĩa ADR-0010 ("NULL = theo giá bìa"), API `PUT .../price` với `null`, dữ liệu cũ |
| Migration | Một file sales DDL | Không | Một chỉ mục | DDL + backfill cần catalog (không làm được bằng SQL xuyên DB) |

Chọn **A**. Lý do: giữ phân trang ở DB (quy tắc 6.5 của đề bài), không đổi luật giá của ADR-0010, giá tiền khách thấy và trả vẫn lấy sống từ catalog. Bản sao ở đây khác loại bản sao ADR-0011 2.6 đã loại (tên/tác giả để tìm kiếm): nó **không bao giờ được hiển thị hay dùng tính tiền**, sai lệch chỉ làm thứ tự sắp lệch tạm thời, không làm sai số tiền.

Thiết kế:

- Migration `sales/V6__storefront_sort_cover_price.sql` (chỉ DDL, không ghi dữ liệu nên không cần `app.tenant_id`; RLS, policy, `@TenantId` của `stock_items` giữ nguyên):
  - `cover_price_snapshot BIGINT NULL CHECK (cover_price_snapshot >= 0)`: giá bìa đã làm tròn đồng (`Money.fromCoverPrice`, `HALF_UP`), `NULL` = chưa biết.
  - `effective_price BIGINT GENERATED ALWAYS AS (COALESCE(selling_price, cover_price_snapshot)) STORED`. Đổi `sellingPrice` qua API thì cột tự cập nhật, không cần mã đồng bộ. Entity ánh xạ chỉ đọc (`insertable = false, updatable = false`).
  - Chỉ mục: `idx_stock_items_storefront_newest ON stock_items (tenant_id, created_at DESC, id) WHERE active` và `idx_stock_items_storefront_price ON stock_items (tenant_id, effective_price, id) WHERE active AND effective_price IS NOT NULL` (sắp giảm dần dùng quét ngược cùng chỉ mục).
  - ⚠️ Thêm cột sinh `STORED` viết lại bảng và khóa `ACCESS EXCLUSIVE` trong lúc chạy: chấp nhận với cỡ bảng hiện tại; ghi chú trong file.
- Ghi snapshot (mọi lần ghi đều chỉ khi giá trị khác, `UPDATE ... WHERE tenant_id = :t AND title_id = :id AND (cover_price_snapshot IS NULL OR cover_price_snapshot <> :v)`, JPQL bulk như `tryApply`, có `tenant_id` tường minh):
  1. `RegisterStockItemUseCase`: đã tra catalog, ghi luôn lúc chèn.
  2. Đồng bộ lười trước truy vấn sắp theo giá: lấy tối đa **500** `title_id` active có `cover_price_snapshot IS NULL AND selling_price IS NULL`, tra catalog theo lô (≤ 5 lời gọi 100 id), ghi trong transaction ghi riêng. Sau lần đầu của mỗi tenant, truy vấn này trả rỗng (một truy vấn chỉ mục rẻ).
  3. Tự sửa lệch: sau khi danh sách/chi tiết storefront tra catalog cho trang, so `coverPrice` sống (đã làm tròn) với snapshot; khác thì ghi lại (tối đa 50 dòng, thường 0). **Best-effort**: lỗi chỉ log, không làm hỏng GET công khai.
- Lọc mặt hàng không có giá: truy vấn sắp theo giá có `effective_price IS NOT NULL` ở DB, nên `total` và trang khớp nhau. Sắp `newest` giữ lọc sau như hiện có (snapshot `NULL` không có nghĩa là không có giá).
- Đã loại thêm: chỉ mục biểu thức `COALESCE(...)` không có cột sinh (planner chỉ dùng khi biểu thức Hibernate sinh ra khớp từng ký tự, khó kiểm); đồng bộ qua event `TitleUpdated` từ catalog (catalog chưa phát event, Kafka best-effort theo ADR-0001, quá tầm Gói A); job định kỳ (job không có token để gọi catalog).

### 2.2. Bộ sắp xếp/lọc và cách đưa vào `StockPort`

Theo khuyến nghị (a) của BA (Q3, chờ người dùng xác nhận): `sort ∈ {newest, price_asc, price_desc}` + `inStock=true`. Không `name_asc` khi không có từ khóa, không khoảng giá.

| `keyword` | `sort` vắng | `newest` | `price_asc` / `price_desc` | `inStock=true` |
|---|---|---|---|---|
| không có | = `newest` (hành vi hiện tại) | DB: `created_at DESC, id` | DB: `effective_price ASC, id ASC` / `DESC, id DESC`, sau đồng bộ lười (2.1) | DB: `(on_hand - reserved) > 0` |
| có | **giữ hành vi hiện tại: theo tên** (hồi quy AC2) | bộ nhớ, theo `created_at` | bộ nhớ, theo giá **sống** (chính xác) | bộ nhớ, `available > 0` |

- Ánh xạ tham số sang mệnh đề cố định bằng enum ở tầng application: `StorefrontSort { NEWEST, PRICE_ASC, PRICE_DESC }` (`StorefrontSort.parse(String)`: `null` → `null` nghĩa là "mặc định theo ngữ cảnh"; giá trị lạ → `SalesException(INVALID_REQUEST)`, không chạm DB). Adapter chọn chuỗi `order by` bằng `switch` trên enum; **không nối giá trị từ request vào JPQL**.
- Thay đổi `StockPort` (cộng thêm, `be-dev` chốt tên):
  - `record StorefrontRow(UUID titleId, Money sellingPrice, Long coverPriceSnapshot, boolean inStock, Instant listedAt)` ở `stock.application`: hình chiếu chỉ đọc cho storefront; **không** thêm `createdAt`/snapshot vào domain `StockItem` (là dữ liệu đọc, không phải bất biến nghiệp vụ).
  - `PageResult<StorefrontRow> listStorefront(UUID tenantId, StorefrontSort sort, boolean inStockOnly, int page, int size)` thay `listActive` (caller duy nhất là `StorefrontQueryUseCases`, đã kiểm bằng `rg listActive`).
  - `Map<UUID, StorefrontRow> findStorefrontRows(UUID tenantId, Collection<UUID> titleIds)`: chỉ dòng `active`, dùng cho nhánh có từ khóa và gợi ý.
  - `List<UUID> titleIdsMissingCoverPrice(UUID tenantId, int limit)` và `void refreshCoverPrices(UUID tenantId, Map<UUID, Long> prices)` (`@Transactional` ghi riêng).
- Use case: bỏ `@Transactional(readOnly = true)` ở mức use case `list` để lời gọi catalog không giữ kết nối DB (mỗi phương thức adapter đã có transaction riêng); giữ thứ tự "đọc DB → gọi catalog → ghi snapshot (best-effort)".
- Controller sales: `StorefrontQuery(String keyword, String sort, Boolean inStock, Integer page, Integer size)`; `inStock = true` lọc, vắng/`false` không lọc (customer-api là cổng kiểm chặt, 2.6).

### 2.3. Sách mới về: không thêm endpoint

`GET /public/t/{code}/books?sort=newest&size=10` (FE có thể thêm `inStock=true`). "Mới" = `stock_items.created_at` (ngày đưa vào bán, Q2 đã chốt). Không lộ `createdAt`, không DTO mới. `Cache-Control` như danh sách: **30 giây** (đề bài 6.7 ghi 60 giây cho sách mới; vì dùng chung endpoint nên lấy 30, AC8 đã sửa theo). Đã loại `GET /books/new`: thêm đường dẫn, matcher, test mà không thêm gì (cùng dữ liệu, cùng vùng nginx). ℹ️ Tạm ngưng rồi bật lại một mặt hàng (`active`) không đổi `created_at`, nên không "mới" lại.

### 2.4. Gợi ý tìm kiếm: sales ghép từ `lookupByKeyword` của catalog, không thêm endpoint catalog

| Tiêu chí | **A. sales gọi `lookupByKeyword` (≤ 200), giao với mặt hàng active, cắt `limit` (chọn)** | B. Endpoint catalog mới theo tiền tố có `limit` | C. Bảng/chỉ mục tìm kiếm trong sales |
|---|---|---|---|
| Đúng "chỉ sách đang bán" | Có (sales lọc) | catalog không biết "đang bán": cắt `limit` trước khi giao có thể ra 0 kết quả dù có sách đang bán | Có |
| Thay đổi | sales + customer-api; catalog không đổi, không thêm `@StorefrontAccessible` ở catalog | Thêm endpoint catalog, khóa mới, mục mới trong danh sách đóng ADR-0011 | Bản sao tên/tác giả (ADR-0011 2.6 đã loại) |
| Tải | Một truy vấn `%q%` ở catalog (không dùng chỉ mục) + một `IN` ≤ 200 id ở sales mỗi lần gõ | Nhẹ hơn nếu có chỉ mục tiền tố | Nhẹ nhất |

Chọn **A**: đúng nghiệp vụ, không đụng catalog. "Đủ nhẹ" là ⚠️ **chưa kiểm chứng**: catalog quét các đầu sách của tenant với `LIKE '%q%'`; với vài chục nghìn đầu sách mỗi tenant dự kiến vài ms đến vài chục ms, nhưng phải đo (P2, mục 4). Ngưỡng hành động: nếu p95 của `GET /public/t/{code}/books/suggestions` > 150 ms ở 20 r/s, thêm bộ đệm Caffeine ở customer-api (khóa `(tenantId, q đã chuẩn hóa, limit)`, TTL 15 giây) hoặc chỉ mục `pg_trgm` ở catalog; không làm trước khi đo.

- sales: `GET /internal/sales/storefront/suggestions?q&limit` trong `StorefrontController` (khóa `StorefrontController/suggestions`, tự có `@StorefrontAccessible` của lớp). Use case: `q` trim, 2..50 code point (sales kiểm lại, lỗi `INVALID_REQUEST`), `limit` 1..8 (mặc định 5, lớn hơn thì cắt) → `catalog.lookupByKeyword(q)` → `findStorefrontRows` → chỉ giữ dòng active **và có giá hiệu lực** (cùng điều kiện hiển thị với danh sách) → xếp: tên bắt đầu bằng `q` (không phân biệt hoa thường, `Locale.ROOT`) trước, rồi theo tên → `limit` mục `{titleId, name, author}`. Không giá, không `inStock`.
- ⚠️ Không bỏ dấu tiếng Việt: "nha gia kim" không khớp "Nhà giả kim" (giới hạn sẵn có của `lower(...) like`). `%`/`_` trong `q` là ký tự đại diện của LIKE ở catalog (chưa escape): chỉ làm khớp rộng hơn trong giới hạn 200, không lộ gì thêm; khuyến nghị sửa riêng ở catalog bằng `escapeLike` có sẵn trong `ReferencePersistenceAdapter` (việc nhỏ, ngoài phạm vi bắt buộc).

### 2.5. Banner theo tenant: context mới `lms.sales.banner`, bảng `storefront_banners`

Hướng A đã chốt. Đặt thành context riêng trong sales (như `contact`, ADR-0015), chỉ phụ thuộc `shared`; không chung package với `stock`.

**Dữ liệu** (`sales/V7__create_storefront_banners.sql`):

| Cột | Kiểu | Ràng buộc |
|---|---|---|
| `id` | `UUID` | PK |
| `tenant_id` | `UUID NOT NULL` | RLS |
| `title` | `VARCHAR(100) NOT NULL` | `btrim(title) <> ''` |
| `subtitle` | `VARCHAR(200)` | `NULL` hoặc `btrim <> ''` |
| `link_path` | `VARCHAR(200)` | `NULL` (không bấm được) hoặc `link_path ~ '^/([^/\\]|$)'` và `link_path !~ '[[:space:][:cntrl:]\\]'` (lớp chặn cuối; luật đầy đủ ở domain) |
| `sort_order` | `INT NOT NULL DEFAULT 0` | `BETWEEN 0 AND 999` |
| `active` | `BOOLEAN NOT NULL DEFAULT TRUE` | |
| `starts_at`, `ends_at` | `TIMESTAMPTZ` | tùy chọn; `starts_at IS NULL OR ends_at IS NULL OR starts_at < ends_at` |
| `version` | `BIGINT NOT NULL DEFAULT 0` | khóa lạc quan |
| `created_at`, `updated_at` | `TIMESTAMPTZ NOT NULL` | |
| `created_by`, `updated_by` | `UUID` | người dùng nhân viên, không PII |

- Chỉ mục `ix_storefront_banners_tenant_order ON storefront_banners (tenant_id, sort_order, created_at, id)`.
- RLS theo mẫu `sales/V1`: `ENABLE` + `FORCE`, policy `tenant_isolation` (USING + WITH CHECK theo `app.tenant_id`) và `platform_read_all` FOR SELECT (nội dung công khai, không PII, khác `shop_contact_requests`). Quyền cho `sales_app` do `afterMigrate.sql` + default privileges cấp sẵn.
- Entity `StorefrontBannerJpaEntity` có `@TenantId` (cột `tenant_id`, `updatable = false`) và `@Version`. **Không** thêm vào `TENANT_EXEMPT_TABLES`; `TenantArchitectureRulesTest` hiện có tự bắt nếu thiếu `@TenantId`. Không native query.

**Quy tắc nghiệp vụ** (domain `Banner` + value object `BannerLink`, Java thuần; controller chỉ chặn thô kích thước):

- Văn bản thuần: chuẩn hóa NFC, cắt khoảng trắng đầu/cuối, từ chối mọi ký tự điều khiển (kể cả xuống dòng: tiêu đề/phụ đề một dòng); độ dài đếm theo code point: `title` 1..100, `subtitle` ≤ 200 (rỗng → `null`). Không escape HTML ở server (như ADR-0011 2.12d); FE hiển thị bằng text.
- `linkPath`: đường dẫn **tương đối với gốc storefront của tenant** (FE tự ghép `/t/{code}` hoặc router tương ứng), ví dụ `/books?sort=newest`, `/books/<uuid>`. Rỗng → `null`. Hợp lệ khi: ≤ 200 ký tự; bắt đầu bằng đúng một `/` (không `//`); chỉ gồm `[A-Za-z0-9-._~/?#=&+,%]`; mỗi `%` theo sau đúng 2 chữ số hex; không chứa `//`, `\`, đoạn `..`. Hệ quả: mọi scheme (`javascript:`, `data:`, `http:`) bị loại vì `:` không nằm trong tập cho phép. Sai → `400 INVALID_BANNER` (`details` chỉ tên trường).
- Hiệu lực tại thời điểm `now` (Clock UTC của sales): `active AND (starts_at IS NULL OR starts_at <= now) AND (ends_at IS NULL OR now < ends_at)`.
- Giới hạn: tối đa **20** banner mỗi tenant (mọi trạng thái), vượt → `409 BANNER_LIMIT_EXCEEDED`; công khai trả tối đa **10** banner đang hiệu lực, thứ tự `sort_order, created_at, id`. ⚠️ Đếm-rồi-chèn không khóa: hai lệnh tạo đồng thời có thể vượt 20 một chút; chấp nhận (công khai vẫn cắt 10), không thêm khóa.
- Sửa: `PUT` thay toàn bộ trường, bắt buộc `version`; lệch → `409 BANNER_VERSION_CONFLICT`. Xóa cứng (`DELETE`, 204). Ghi `sales_audit_log` cùng transaction (`BANNER_CREATED|UPDATED|DELETED`, `data` chỉ `bannerId`), như mẫu ADR-0010 2.6.

**API** (sales, `@RequiresModule(SALES)`; lms-api chuyển nguyên, chỉ xác thực):

| Khóa (sales) | Service | lms-api (FE quản trị) | Vào / ra chính |
|---|---|---|---|
| `SalesBannerController/list` | `GET /internal/sales/banners` | `GET /api/sales/banners` | `[BannerAdminView]` mọi trạng thái (≤ 20, không phân trang) |
| `SalesBannerController/get` | `GET /internal/sales/banners/{id}` | `GET /api/sales/banners/{id}` | `BannerAdminView`; `404 BANNER_NOT_FOUND` (gồm id của tenant khác, RLS) |
| `SalesBannerController/create` | `POST /internal/sales/banners` | `POST /api/sales/banners` | `SaveBannerRequest` → `201 BannerAdminView` |
| `SalesBannerController/update` | `PUT /internal/sales/banners/{id}` | `PUT /api/sales/banners/{id}` | `SaveBannerRequest + version` → `BannerAdminView` |
| `SalesBannerController/delete` | `DELETE /internal/sales/banners/{id}` | `DELETE /api/sales/banners/{id}` | `204` |
| `StorefrontBannerController/list` | `GET /internal/sales/storefront/banners`, **`@StorefrontAccessible`** | không có (khách gọi customer-api) | `[{id, title, subtitle, linkPath, sortOrder}]` ≤ 10 đang hiệu lực |

- `SalesBannerController` **không** `@StorefrontAccessible`: token `svc` gọi vào bị `403 FORBIDDEN` ở interceptor (khách vãng lai vẫn không có đường ghi dữ liệu tenant, ADR-0011 2.1).
- Tên lớp/method chốt rồi không đổi (là khóa quyền, ADR-0003). Đổi tên phải sửa seed và ADR này.
- **Seed khóa** (identity `V26__sales_banner_permissions.sql`, theo cấu trúc `V22`/`V25`): đăng ký 7 khóa (5 `SalesBannerController/*`, `StorefrontBannerController/list`, `StorefrontController/suggestions`) để màn hình phân quyền thấy ngay; gán cho mẫu **Quản lý cửa hàng (c3)** và backfill role c3 "còn nguyên mẫu" của tenant đã bật SALES (role đã chỉnh: `RAISE WARNING`, tenant admin gán tay). Không gán cho Thu ngân, Thủ kho, Khách hàng. Admin tenant (`is_admin`) không cần khóa, nên yêu cầu "admin tenant tự sửa" đạt kể cả khi không seed; seed chỉ để Quản lý cửa hàng dùng được như các tính năng cửa hàng khác (xác nhận ở Q-A, mục 6). Quyền mới hiệu lực ở lần đăng nhập/refresh kế tiếp.

### 2.6. customer-api: kiểm chặt tham số, gọi sales bằng token `svc`, đệm banner ở server

- `BookQuery(String keyword, String sort, String inStock, Integer page, Integer size)`: `sort` chỉ nhận đúng `newest|price_asc|price_desc` (phân biệt hoa thường), `inStock` chỉ nhận đúng `true`; giá trị khác (kể cả `false`, `1`, chuỗi rỗng có ký tự) → `400 VALIDATION_FAILED` **trước khi** gọi sales. Gửi sang sales đúng chuỗi đã kiểm. `page`/`size`/`keyword` giữ quy tắc hiện có.
- `GET /public/t/{code}/books/suggestions?q&limit`: method mới trong `BookController` (Spring ưu tiên đoạn literal `suggestions` hơn `{bookRef}`, không phụ thuộc thứ tự khai báo; AC7 vẫn test). `q` trim, 2..50 code point, không ký tự điều khiển, sai → 400; `limit` vắng/< 1 → 5, > 8 → 8. Phản hồi `List<SuggestionView{bookRef, title, author}>`, `max-age=15, public`. Không cần sửa `SecurityConfiguration` (đã khớp `/public/t/*/books/*`).
- `GET /public/t/{code}/banners`: nhóm mới `lms.customer.banner` (`client` + `client/http` + `dto` + `web`, theo cấu trúc ADR-0011 2.9). Thêm `GET|HEAD /public/t/*/banners` vào `PUBLIC_API`. Đệm ở server: Caffeine `customer.storefront.banners`, khóa `tenantId` (từ `StorefrontAccess`), TTL **30 giây**, tối đa 10 000 mục, đệm cả danh sách rỗng, không đệm lỗi; `max-age=60, public`. Lý do đệm khác danh mục: banner giống hệt cho mọi khách của tenant, nằm trên mọi lần vào trang chủ, nên hit rate gần 100%; đổi banner hiện ra chậm tối đa khoảng 90 giây (30 + 60), cũng là độ trễ tối đa khi banner bắt đầu/hết hiệu lực.
- Ánh xạ từng trường từ record upstream sang DTO công khai (như `HttpCatalogClient`), lỗi sales đi qua cùng `call(...)` (401/403 khác `MODULE_NOT_ENABLED|TENANT_REQUIRED` → 503). `{code}` không khả dụng → `404 SHOP_NOT_AVAILABLE` trước khi gọi sales (cơ chế sẵn có). Không mã lỗi công khai mới.

### 2.7. nginx, cổng, biến môi trường

Không đổi nginx (cả hai đường dẫn mới nằm dưới `location /public/`, áp `public_read` + `public_tenant`), không cổng mới, không biến môi trường mới. Không thêm vùng `public_suggest` (Q4 đã chốt); điều kiện xem lại ở mục 5.

### 2.8. Cập nhật danh sách đóng `@StorefrontAccessible` (ADR-0011 2.1)

Thêm `StorefrontController/suggestions` và `StorefrontBannerController/list` (sales, chỉ đọc). ADR-0011 đã được ghi chú trỏ sang mục này.

## 3. Thiết kế

### 3.1. Luồng

```
Trình duyệt ── GET /public/t/{code}/books?sort&inStock | /books/suggestions?q&limit | /banners
   │  nginx: location /public/ (public_read 20r/s theo IP, public_tenant 50r/s theo code)
   ▼
customer-api ── kiểm tham số (400 trước khi gọi) ── StorefrontAccessCache.resolve(code) (404 SHOP_NOT_AVAILABLE)
   │   banners: Caffeine 30s theo tenantId
   │   Authorization: Bearer <token svc của tenant>
   ▼
sales /internal/sales/storefront/{items | suggestions | banners}   (@StorefrontAccessible, @RequiresModule(SALES), RLS)
   │  items không keyword + price_*: titleIdsMissingCoverPrice(≤500) → catalog.lookup → refreshCoverPrices
   │                                  → listStorefront (DB, chỉ mục) → catalog.lookup(trang) → tự sửa snapshot (best-effort)
   │  items có keyword / suggestions: catalog.lookupByKeyword (≤200) → findStorefrontRows → sắp/cắt trong bộ nhớ
   │  banners: storefront_banners WHERE hiệu lực ORDER BY sort_order LIMIT 10 (không gọi catalog)
   ▼
catalog /internal/books/lookup (POST lô | GET ?keyword)  bằng token svc chuyển tiếp

FE quản trị ── /api/sales/banners/** ── nginx ── lms-api (chỉ xác thực) ── sales /internal/sales/banners/**
                                                                          (SalesBannerController/*, khóa động)
```

### 3.2. Gói trong sales

```
lms.sales.banner.domain          Banner, BannerLink, BannerText (chuẩn hóa), BannerLimits
lms.sales.banner.application     BannerRepository (port), BannerAdminUseCases, PublicBannerQuery, views
lms.sales.banner.infrastructure  web/{SalesBannerController, StorefrontBannerController}, persistence/{StorefrontBannerJpaEntity, adapter},
                                 config/BannerBeanConfiguration
```

Luật ArchUnit mới trong `SalesArchitectureTest`: `banner` chỉ phụ thuộc `shared` (không `stock`, `ordering`, `settings`, `contact`); không context nào phụ thuộc `banner`. Mã lỗi mới trong `ErrorCode` + ánh xạ HTTP ở `SalesExceptionHandler`: `BANNER_NOT_FOUND` 404, `BANNER_VERSION_CONFLICT` 409, `BANNER_LIMIT_EXCEEDED` 409, `INVALID_BANNER` 400.

### 3.3. Hợp đồng

Chi tiết trường, ví dụ và mã lỗi cho FE: `docs/api/storefront-kham-pha.md`. Tóm tắt: `BookSummaryView` giữ nguyên 6 trường; `SuggestionView` đúng 3 trường `bookRef, title, author`; `BannerView` đúng 5 trường `id, title, subtitle, linkPath, sortOrder`.

## 4. Phân việc

Thứ tự phụ thuộc: B1 trước B2/B3 (validate schema khi chạy và IT); B4, B5 làm song song theo hợp đồng (mock client), ghép khi B2/B3 xong. Mỗi agent một phạm vi file riêng.

| | Việc | Ai | Phạm vi file | Phụ thuộc |
|---|---|---|---|---|
| ⏳ B1 | Migration: `sales/V6__storefront_sort_cover_price.sql` (2.1), `sales/V7__create_storefront_banners.sql` (2.5), `identity/V26__sales_banner_permissions.sql` (2.5, theo V22/V25). Liệt kê lại số `V<n>` ngay trước khi tạo (sales V5, identity V23 đến V25 đang chưa commit ở phiên khác). | be-dev | `services/system-service/src/main/resources/db/migration/{sales,identity}/` | không |
| ⏳ B2 | sales `stock`: `StorefrontSort`, `StorefrontRow`, các phương thức `StockPort` mới + adapter (JPQL, `switch` enum), cột chỉ đọc `effectivePrice`/`coverPriceSnapshot` ở `StockItemJpaEntity`, ghi snapshot ở `RegisterStockItemUseCase`, `StorefrontQueryUseCases.list` (bảng 2.2) và `suggest`, `StorefrontController` (`StorefrontQuery` mới, `GET /suggestions`). Cập nhật bản giả `StockPort` trong test. Không sửa `ErrorCode` (dùng `INVALID_REQUEST` sẵn có). | be-dev | `services/sales-service/src/main/java/lms/sales/stock/**` + test tương ứng | B1 (V6) |
| ⏳ B3 | sales `banner`: context mới (3.2), `ErrorCode`/`SalesExceptionHandler` (4 mã), hằng audit trong `SalesAuditPort.Actions`, luật ArchUnit mới, bean config. | be-dev | `services/sales-service/src/main/java/lms/sales/banner/**`, `shared/domain/ErrorCode.java`, `shared/infrastructure/web/SalesExceptionHandler.java`, `shared/application/SalesAuditPort.java`, `src/test/.../architecture/SalesArchitectureTest.java` | B1 (V7); song song B2 |
| ⏳ B4 | customer-api: `BookQuery` mới + kiểm `sort`/`inStock`, `CatalogClient.list(...)` thêm tham số, `suggestions`, nhóm `banner` + Caffeine, `SecurityConfiguration` (`/banners`), DTO mới. | be-dev | `customer-api/src/main/java/lms/customer/{catalog,banner,config/SecurityConfiguration.java}` | hợp đồng; ghép sau B2, B3 |
| ⏳ B5 | lms-api: `SalesBannerController` `/api/sales/banners/**`, `SalesClient`/`HttpSalesClient` thêm 5 phương thức, DTO trong `SalesDtos`. | be-dev | `lms-api/src/main/java/lms/api/sales/**` | hợp đồng; ghép sau B3 |
| ⏳ B6 | (tùy chọn) Mục menu quản trị "Banner cửa hàng" (`/sales/banners`, khóa `SalesBannerController/list`, module SALES) ở `DefaultMenus` của system-service. ⚠️ File đang có thay đổi chưa commit của phiên khác: chờ phiên đó commit. | be-dev | `services/system-service/.../menu/application/DefaultMenus.java` | sau phiên khác |
| ⏳ T1 | WebMvc customer-api: AC1 đến AC10 (mock client; verify `never()` khi 400/404; tập tên trường `SuggestionView`, `BannerView`; `Cache-Control` 30/15/60; token svc). | tester | test customer-api | B4 |
| ⏳ T2 | sales: test đơn vị `StorefrontSort.parse` (AC12), use case sắp/lọc cả hai nhánh, hồi quy mặc định (AC2), luật `BannerLink` (bảng ca xấu: `//x`, `/\x`, `/a/../b`, `javascript:`, `/%zz`, `/%2`, ký tự điều khiển, khoảng trắng, Unicode, 201 ký tự), hiệu lực theo thời gian (biên `starts_at = now`, `ends_at = now`). | tester | test sales | B2, B3 |
| ⏳ T3 | IT sales (Testcontainers, vai trò `sales_app`): RLS hai tenant cho `listStorefront` mọi `sort`/`inStock` và cho banner (AC11, đọc/sửa/xóa id tenant khác → 404); đồng bộ lười snapshot; cột sinh cập nhật khi đổi `sellingPrice`; token `svc` gọi `SalesBannerController/*` → 403; người không có khóa → 403. Chạy `-Dtest=<TênIT>` (Docker 29: `-DargLine=-Dapi.version=1.44`). | tester | IT sales | B1 đến B3 |
| ⏳ T4 | ArchUnit `mvn test` xanh (AC15), nginx 429 thủ công (AC16). | tester | không | tất cả |
| ⏳ P1 | Đo `EXPLAIN ANALYZE` + p95 danh sách `price_asc`/`newest` + `inStock=true` với 10 000 mặt hàng/tenant, 20 tenant; lần đầu sắp theo giá (đồng bộ lười 500 id). | perf-tester | không | B2 |
| ⏳ P2 | Gợi ý: p95 ở 20 r/s với 50 000 đầu sách catalog/tenant; ngưỡng 150 ms (2.4). Đo cả giới hạn 8 lời gọi đồng thời customer-api → sales khi gợi ý và danh sách chạy chung (rủi ro R4). | perf-tester | không | B2, B4 |
| ⏳ P3 | Mô phỏng nhiều khách cùng một tenant gõ gợi ý (debounce 300 ms): có chạm `public_tenant` 50 r/s không (rủi ro R3). | perf-tester | không | B4 |

## 5. Rủi ro và việc chưa kiểm chứng

- ⚠️ R1 Lệch thứ tự giá: giá bìa đổi ở catalog sau lần đồng bộ cuối thì thứ tự sắp lệch cho tới lần mặt hàng đó hiện trong một trang storefront (tự sửa). Số tiền hiển thị/thanh toán không bị ảnh hưởng. Mặt hàng chưa từng hiện và chưa được đồng bộ lười (tenant > 500 mặt hàng chỉ có giá bìa) tạm vắng khỏi kết quả sắp theo giá cho tới các request kế tiếp: `total` nhỏ hơn tạm thời.
- ⚠️ R2 Ghi trên GET công khai: tự sửa snapshot là lệnh UPDATE trong luồng đọc công khai (tối đa 50 dòng, chỉ khi lệch, giá trị lấy từ catalog, không do khách điều khiển). Phải best-effort và nằm ngoài transaction đọc; tester kiểm lỗi ghi không làm hỏng 200.
- ⚠️ R3 `public_tenant` 50 r/s dùng chung cho mọi khách của một cửa hàng: cửa hàng đông khách gõ gợi ý có thể làm cả danh mục của cửa hàng đó bị 429. Q4 đã chọn dùng chung; nếu P3 cho thấy chạm ngưỡng thì đề xuất lại vùng `public_suggest` riêng.
- ⚠️ R4 customer-api giới hạn 8 lời gọi đồng thời mỗi đích (`app.resilience.max-concurrent-calls`): gợi ý tăng số lời gọi tới sales, khi đầy thì danh sách cũng nhận 503 ngay. Đo ở P2 trước khi chỉnh.
- ⚠️ R5 Hiệu năng gợi ý (`LIKE '%q%'` không chỉ mục) chưa đo; không bỏ dấu tiếng Việt; `%`/`_` chưa escape ở catalog.
- ⚠️ R6 Migration: sales `V5`, identity `V23` đến `V25` chưa commit; nếu phiên khác đổi số thì `V6`/`V7`/`V26` phải đổi theo. Không sửa file đã chạy. Thêm cột sinh `STORED` viết lại `stock_items` và khóa bảng lúc migrate.
- ⚠️ R7 Hibernate `ddl-auto=validate` với cột sinh ánh xạ chỉ đọc: dự kiến hợp lệ (cột tồn tại, kiểu `bigint`), chưa chạy thử.
- ⚠️ R8 RLS với `ALTER TABLE ... ADD COLUMN ... GENERATED STORED` trên bảng `FORCE ROW LEVEL SECURITY`: viết lại bảng là DDL, không chịu policy; chưa chạy thử trên DB có dữ liệu nhiều tenant (T3).
- ⚠️ R9 Đổi tên `SalesBannerController`, `StorefrontBannerController` hoặc method → mất quyền đã gán và lệch danh sách đóng (2.8).
- ℹ️ R10 Lỗi sẵn có được sửa kèm: nhánh không từ khóa lọc mặt hàng không giá sau khi phân trang. Với `price_*` đã lọc ở DB; với `newest` vẫn như cũ (chỉ xảy ra khi catalog không trả đầu sách).
- ℹ️ R11 `total` của `inStock=true` là số mặt hàng còn hàng (không phải số lượng tồn); BA đã chấp nhận.

## 6. Câu hỏi còn mở (cần người dùng)

- ⏳ Q-A: Có gán sẵn 5 khóa quản trị banner cho mẫu Quản lý cửa hàng (c3) không? Khuyến nghị **có** (cùng cách với liên hệ khách ở V22); nếu không, V26 chỉ đăng ký khóa, chỉ admin tenant dùng được.
- ⏳ Q-B (Q3 của đề bài): Xác nhận bộ sắp xếp (a) `newest`, `price_asc`, `price_desc` + `inStock`, và quy tắc "có từ khóa mà không `sort` thì theo tên". Khuyến nghị **giữ như ADR**; khoảng giá và `name_asc` để sau.
- ⏳ Q-C: Giới hạn 20 banner/tenant và 10 banner hiển thị có phù hợp không? Khuyến nghị **giữ**.
