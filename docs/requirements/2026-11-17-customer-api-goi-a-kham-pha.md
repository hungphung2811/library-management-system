# Đề bài: customer-api Gói A, trang chủ và khám phá cho storefront hiệu sách

Ngày: 2026-11-17. Người viết: BA. Trạng thái: người dùng đã chốt Q1, Q2, Q4, Q5 (mục 11); SA đã quyết các điểm kỹ thuật ở `docs/decisions/0019-storefront-kham-pha-sap-xep-goi-y-banner.md`, hợp đồng FE ở `docs/api/storefront-kham-pha.md`. Khi ADR-0019 khác nội dung bên dưới thì theo ADR-0019.

Chú thích độ chắc chắn: ✅ = đã xác minh trong code, ⚠️ = suy đoán hoặc chưa kiểm chứng, 🚫 = cần quyết định của người dùng trước khi làm.

## 1. Mục tiêu

ℹ️ Làm cho storefront của tenant hiệu sách bán online sinh động hơn, chỉ bằng chức năng công khai (khách chưa đăng nhập, không thêm dữ liệu cá nhân):
- Trang chủ có banner/khuyến mãi do tenant quản lý.
- Danh sách sách sắp xếp và lọc được.
- Có rail "Sách mới về".
- Gợi ý tìm kiếm (autocomplete) khi gõ.

## 2. Người dùng và role

- Khách chưa đăng nhập của tenant `{code}` (đọc công khai).
- Khách đã đăng nhập cũng gọi được các endpoint này nhưng kết quả không phụ thuộc người xem (✅ BookController luôn gọi sales bằng token dịch vụ).
- Admin tenant: không có thao tác mới ở Gói A (xem mục 6, riêng banner có vướng mắc).

## 3. Hiện trạng đã xác minh

- ✅ customer-api có `GET /public/t/{code}/books` (keyword, page, size, tối đa 50, cache 30 giây), `/{bookRef}` (30 giây), `/{bookRef}/preview` (60 giây). Sales: `StorefrontController` `/internal/sales/storefront/items[/{titleId}[/preview]]`.
- ✅ `StorefrontQueryUseCases.list` (sales) hiện:
  - Không có từ khóa: lấy `stock.listActive` sắp theo `createdAt desc, id` (tức mặc định đã là "mới nhất", nhưng ngầm, không có tham số), rồi tra tên/tác giả/giá bìa từ catalog theo lô.
  - Có từ khóa: gọi `catalog.lookupByKeyword` (tối đa 200 đầu sách), giao với mặt hàng đang bán, sắp theo tên trong bộ nhớ, phân trang trong bộ nhớ.
  - Tên, tác giả, mô tả nằm ở catalog, còn giá, tồn, `createdAt`, `active` nằm ở sales (`StockItemJpaEntity` có `created_at`).
- ✅ DTO công khai (`BookSummaryView`) chỉ gồm `bookRef, title, author, price, inStock, hasPreview`. Cố ý không có tồn chính xác, ISBN, ảnh, thể loại, ngày tạo. Có test so tập tên trường.
- ✅ CMS (`/public/site/pages[/{slug}]`, system-service) là CMS của NỀN TẢNG SaaS: bảng `cms_pages` KHÔNG có `tenant_id`, không bật RLS (V3__create_cms.sql); customer-api trả "trang giới thiệu SaaS". `bodyHtml` do system-service render (escape HTML thô, bỏ link nguy hiểm).
- ✅ nginx: `location /public/` áp `public_read` (20r/s, burst 40, theo IP) và `public_tenant` (50r/s, burst 100, theo mã tenant). `public_form` (5r/m) chỉ dành cho POST.
- ✅ Catalog-service có `CategoryController`, `AuthorController`, `PublisherController`, nhưng sales `CatalogLookupPort.CatalogTitle` chưa mang thể loại/nhà xuất bản/năm.

## 4. Phát hiện làm thay đổi cách làm banner

🚫 Đề bài ban đầu giả định banner lấy từ CMS bằng slug quy ước. Code cho thấy CMS hiện là của nền tảng, dùng chung mọi tenant (không có `tenant_id`). Nếu dùng slug `home-banner` ở CMS hiện có thì MỌI cửa hàng hiển thị cùng một banner, không phù hợp với SaaS nhiều hiệu sách. Cần chọn một trong ba hướng (câu hỏi Q1):
- A. Banner theo tenant lưu ở sales-service, bảng/ cấu hình mới có `tenant_id` + RLS, admin tenant sửa qua `/api/**` (lms-api). Khuyến nghị: đúng mô hình SaaS, chủ cửa hàng tự quản.
- B. Dùng CMS hiện có kèm quy ước slug theo mã tenant (`banner-{code}`) do quản trị nền tảng nhập. Rẻ nhất nhưng chủ tenant không tự sửa được, và lộ mã tenant trong trang CMS chung.
- C. Chỉ banner mặc định tĩnh cấu hình ở FE, chưa có BE. Rẻ nhất, không đáp ứng "khuyến mãi qua CMS".

Phần mô tả banner ở mục 7.5 dựa theo hướng A, kèm ghi chú nếu chọn B. Hướng chưa chốt thì mục 7.5 chưa làm.

## 5. Phạm vi và ngoài phạm vi

Trong phạm vi:
- ⏳ F1 Sắp xếp và lọc danh sách sách (7.1).
- ⏳ F2 Sách mới về (7.2).
- ⏳ F3 Gợi ý tìm kiếm (7.3).
- ⏳ F4 Banner/khuyến mãi trang chủ (7.5, chờ Q1).

Ngoài phạm vi (ghi rõ để không tự thêm):
- ⏭️ Ảnh bìa (FE dùng placeholder).
- ⏭️ Thể loại/tác giả/nhà xuất bản dạng duyệt và sách liên quan (Gói B).
- ⏭️ Bán chạy và nổi bật (xem mục 9).
- ⏭️ Giỏ hàng, yêu thích, đánh giá, hồ sơ, đặt trước/mượn, thông báo (mọi thứ cần đăng nhập hoặc có dữ liệu cá nhân).
- ⏭️ Thay đổi hành vi đặt hàng, đăng nhập, CORS.

## 6. Quy tắc nghiệp vụ

1. Chỉ mặt hàng đã đưa vào bán và `active = true` xuất hiện (✅ đã là quy tắc hiện có, giữ nguyên).
2. Không lộ tồn kho chính xác: chỉ `inStock` (boolean); không thêm `onHand/reserved/available`, ngưỡng, giá nhập, ISBN, `tenantId`. Lọc "chỉ còn hàng" dùng `inStock=true`, không phát sinh đếm công khai. ⚠️ Cần tránh kênh phụ: `total` của kết quả lọc còn hàng vẫn là số mặt hàng (không phải số lượng tồn), chấp nhận được; không trả thêm bất kỳ số tồn nào.
3. Allowlist tham số (giá trị lạ trả 400 `VALIDATION_FAILED`, không bỏ qua im lặng):
   - `sort` ∈ {`newest` (mặc định), `price_asc`, `price_desc`}. ⚠️ `name_asc`: sales chỉ sắp theo tên được khi có từ khóa (tên ở catalog, sắp trong bộ nhớ). Gói A khuyến nghị không đưa `name_asc` khi không có `keyword`; có `keyword` thì giữ hành vi hiện tại (sắp theo tên). Chốt ở Q3.
   - `inStock` ∈ {`true`}; vắng mặt = không lọc. Giá trị khác `true` thì 400.
   - `minPrice`, `maxPrice`: số nguyên VND `0..1_000_000_000`, `minPrice <= maxPrice`. ⚠️ Chưa chốt có đưa vào Gói A không (Q3); mặc định không đưa để giữ gói nhỏ.
4. Giới hạn: `size` mặc định 20, tối đa 50 (giữ nguyên); `page >= 0`; `keyword` tối đa 100 ký tự, không ký tự điều khiển (đã có). Autocomplete: `q` từ 2 đến 50 ký tự, `limit` mặc định 5, tối đa 8.
5. Sắp xếp giá/mới nhất khi không có keyword phải làm trong truy vấn DB của sales (phân trang ở DB), không tải toàn bộ. Khi có keyword, nguồn vẫn là tối đa 200 đầu sách của catalog, sắp trong bộ nhớ rồi phân trang (giới hạn đã có, ghi nhận). Hệ quả: tìm kiếm khớp nhiều hơn 200 đầu sách thì kết quả bị cắt; ghi nhận như giới hạn hiện có, không sửa ở Gói A.
6. Giá: `price` là số nguyên VND, lấy giá bán của mặt hàng, nếu trống thì giá bìa (✅ `priceOf`). Sắp xếp theo giá dùng cùng giá hiệu lực này. ⚠️ Giá hiệu lực nằm một phần ở catalog (`coverPrice`) nên sắp theo giá ở DB chỉ đúng với mặt hàng có `selling_price`; cần SA quyết định cách xử lý mặt hàng chỉ có giá bìa (Q3 / mục 12).
7. Cache công khai (dùng `PublicCache.publicFor`):
   - Danh sách/sắp xếp/lọc: 30 giây (như hiện có).
   - Sách mới: 60 giây.
   - Autocomplete: 15 giây.
   - Banner: 60 giây.
   - Khóa cache gồm `{code}` và toàn bộ tham số đã chuẩn hóa. Không có `Authorization` trong khóa; mọi phản hồi dùng `Cache-Control: public, max-age` (không `private`), không chứa dữ liệu theo người dùng.
8. Rate limit: dùng vùng nginx hiện có của `location /public/` (`public_read` 20r/s burst 40 theo IP, `public_tenant` 50r/s burst 100 theo mã tenant). Không dùng `public_form` (chỉ POST). Autocomplete bắn nhiều request khi gõ nên FE phải debounce (khuyến nghị 300 ms) và chỉ gọi từ 2 ký tự; ⚠️ cân nhắc vùng riêng `public_suggest` (ví dụ 10r/s theo IP) nếu đo thấy autocomplete ăn hết hạn mức `public_read` (Q4). Không tự thêm vùng nếu người dùng không chọn.
9. Lỗi: giữ danh sách mã lỗi cho phép của `PublicExceptionHandler`; `{code}` không khả dụng thì `404 SHOP_NOT_AVAILABLE` trước khi gọi sales (✅ hiện có).

## 7. API dự kiến (mức ranh giới)

Tất cả công khai, `GET`, dưới `/public/t/{code}`; xác thực: `permitAll` theo danh sách ở `SecurityConfiguration`, thêm đường dẫn mới vào danh sách đó (✅ cơ chế hiện có, còn lại `denyAll`).

### 7.1 Danh sách có sắp xếp/lọc (mở rộng endpoint hiện có)
- `GET /public/t/{code}/books?keyword&sort&inStock&page&size`. Query gom thành một `record` (CLAUDE.md).
- Phản hồi: `BookPageView` như hiện có, KHÔNG thêm trường vào `BookSummaryView`, trừ khi người dùng duyệt (xem 7.2 về `newest`).
- Lỗi: `400 VALIDATION_FAILED` cho `sort`/`inStock`/khoảng giá sai.

### 7.2 Sách mới về
- Phương án khuyến nghị: không thêm endpoint riêng, dùng `GET /public/t/{code}/books?sort=newest&size=10` (sales đã sắp `createdAt desc` mặc định). FE hiển thị rail "Mới về". Không cần DTO mới, không lộ `createdAt`.
- Phương án phụ: `GET /public/t/{code}/books/new` nếu muốn cache và rate riêng; cùng DTO `BookPageView`. ⚠️ Chưa cần, ghi nhận để SA chọn.
- 🚫 "Mới" ở đây là mới đưa vào bán (`stock_items.created_at`), không phải mới xuất bản. Có chấp nhận không (Q2)?

### 7.3 Gợi ý tìm kiếm
- `GET /public/t/{code}/books/suggestions?q=&limit=`.
- DTO mới `SuggestionView { UUID bookRef, String title, String author }`; phản hồi `List<SuggestionView>` (không phân trang, tối đa `limit`).
- Không trả giá, `inStock`, mô tả; chỉ mặt hàng đang bán.
- Lỗi: `400 VALIDATION_FAILED` khi `q` dưới 2 hoặc quá 50 ký tự, hoặc có ký tự điều khiển; `q` hợp lệ nhưng không khớp thì `200 []`.
- ⚠️ Cần xác nhận catalog có đường tìm theo tiền tố đủ nhẹ; hiện chỉ có `lookupByKeyword` (khớp tên/tác giả, tối đa 200). Có thể tái dùng với `limit` nhỏ, chưa tối ưu.
- Đường dẫn `/books/suggestions` phải khai báo trước `/books/{bookRef}` để không bị hiểu là `bookRef` (hiện `bookRef` không phải UUID sẽ trả 404 BOOK_NOT_FOUND).

### 7.4 Thay đổi ở sales-service (nếu làm F1)
- ✅ Hiện chưa có tham số `sort`/`inStock` ở `StorefrontController.list` (`StorefrontQuery(keyword, page, size)`), `StorefrontQueryUseCases.list`, `StockPort.listActive`, `StockPersistenceAdapter.listActive`.
- ⚠️ Việc cần đổi (suy đoán theo cấu trúc code, SA xác nhận): thêm `sort`, `inStock` vào `StorefrontQuery` và use case; thêm tham số vào `StockPort.listActive` với truy vấn JPQL có `order by` theo allowlist (không nối chuỗi tham số vào JPQL, dùng ánh xạ enum sang mệnh đề cố định) và điều kiện `(on_hand - reserved) > 0`; thêm endpoint hoặc tham số cho suggestions; mở rộng `CatalogClient`/`HttpCatalogClient` của customer-api cho tham số mới.
- ✅ lms-api: không bắt buộc đổi ở Gói A vì customer-api gọi thẳng sales `/internal/sales/storefront/**`. ⚠️ Riêng banner hướng A cần thêm API quản trị ở sales và route ở lms-api (xem 7.5).
- ⚠️ Index cho `(tenant_id, active, created_at)` và cho sắp theo giá: cần SA kiểm; nếu thêm index thì là migration mới ở `system-service/.../db/migration/sales/` (kiểm số `n` kế tiếp).

### 7.5 Banner/khuyến mãi trang chủ (chờ Q1)
- Endpoint: `GET /public/t/{code}/banners`, phản hồi `List<BannerView { String id, String title, String subtitle, String linkPath, int sortOrder }>`, tối đa 10 mục, cache 60 giây.
- Hướng A (khuyến nghị): bảng mới ở sales (có `tenant_id`, RLS + FORCE, `@TenantId`; ArchUnit `TenantArchitectureRulesTest` bắt thiếu). Cột gợi ý: tiêu đề, phụ đề, `linkPath`, `sortOrder`, `active`, thời gian bắt đầu/kết thúc (tùy chọn). Admin tenant CRUD qua `/api/**` (lms-api) + khóa `Controller/method` do `PermissionInterceptor` tự đăng ký, role nào dùng do admin tenant gán. ⚠️ Chưa có gì trong code về bảng này (chưa tồn tại).
- Hướng B: customer-api gọi lại `site.getPage("banner-" + code)`; nếu trang không có thì trả `[]`. Nhưng `site` là CMS nền tảng (không theo tenant, xem mục 4).
- Quy tắc an toàn (mọi hướng): văn bản thuần, không HTML; `linkPath` chỉ nhận đường dẫn nội bộ bắt đầu bằng `/` (không `//`, không scheme như `javascript:`/`data:`/`http`), tối đa 200 ký tự, không ký tự điều khiển; tiêu đề tối đa 100, phụ đề tối đa 200. Nếu bắt buộc dùng nội dung HTML (hướng B) thì chỉ dùng `bodyHtml` đã được system-service sanitize, không tự thêm HTML ở customer-api, và FE chỉ render qua cơ chế an toàn.
- Mã lỗi: `404 SHOP_NOT_AVAILABLE` nếu `{code}` không khả dụng; không có banner thì `200 []` (không 404).

## 8. Phân quyền

- Đọc công khai: không cần quyền, không `@RequirePermission`. customer-api chỉ xác thực JWT khi có và `permitAll` theo danh sách (✅ ADR-0011). Sales vẫn kiểm module `SALES` bật cho tenant (✅ mọi controller `@RequiresModule(SALES)`), nên tenant không bật module trả `404 SHOP_NOT_AVAILABLE` ở tầng phân giải.
- Quản trị banner (chỉ hướng A): khóa dạng `<TênController>/<tênMethod>` do hệ thống tự đăng ký; role nào dùng là dữ liệu do admin gán (ADR-0003), không hardcode. Đây là điều duy nhất ở Gói A chạm phân quyền.
- Token dịch vụ `svc`: customer-api gọi sales bằng token này (✅ BookController). Không đổi.

## 9. Để sau: "bán chạy" và "nổi bật"

Quyết định của người dùng: tự động theo số liệu, admin không thao tác. Không thuộc Gói A. Lưu ý dữ liệu để Gói B dùng:
- ⚠️ Số liệu bán nằm ở sales (đơn và dòng đơn); chưa xác minh có truy vấn tổng hợp tái dùng được. `SalesStatsController`/`SalesReportController` phục vụ admin, cần quyền, không dùng cho công khai.
- ⚠️ Tính khi có request sẽ nặng: nên tổng hợp định kỳ (job) hoặc bảng cache theo tenant, cửa sổ thời gian (ví dụ 30 ngày), chỉ tính đơn đã hoàn tất, bỏ đơn hủy/trả.
- ⚠️ Không lộ số lượng đã bán chính xác; chỉ thứ hạng hoặc danh sách top N.
- ⚠️ Tenant mới ít dữ liệu: cần quy tắc dự phòng (rail ẩn hoặc rơi về "mới về").
- "Nổi bật" tự động chưa có định nghĩa; cần chốt tiêu chí ở Gói B.

## 10. Tiêu chí nghiệm thu (kiểm được bằng test)

Mỗi mục là một test WebMvc của customer-api (mock client) hoặc test use case/IT của sales, trừ khi ghi khác.

- ⏳ AC1: `GET /books?sort=price_asc` chuyển đúng tham số sang sales và trả `BookPageView`; `sort=abc` trả `400 VALIDATION_FAILED`, sales không bị gọi.
- ⏳ AC2: `sort` mặc định (vắng mặt) cho cùng kết quả như hiện tại (mới nhất trước); test hồi quy cho hành vi hiện có.
- ⏳ AC3: `inStock=true` chỉ trả mặt hàng `available > 0`; `inStock=false` hoặc `inStock=1` trả `400`.
- ⏳ AC4: Test so tập tên trường `BookSummaryView` vẫn đúng 6 trường như cũ (không lộ tồn/ISBN/ngày tạo); `SuggestionView` đúng 3 trường `bookRef, title, author`.
- ⏳ AC5: `size=1000` bị chặn còn 50; `page=-1` thành 0; `keyword` dài hơn 100 ký tự hoặc có ký tự điều khiển trả `400`.
- ⏳ AC6: Suggestions: `q` 1 ký tự trả `400`; `q` 2 đến 50 ký tự hợp lệ trả tối đa `limit` mục (`limit=100` bị chặn còn 8); không khớp trả `200 []`; chỉ trả mặt hàng đang bán của đúng tenant.
- ⏳ AC7: Đường dẫn `/books/suggestions` không bị `BookController.get` bắt nhầm (test route).
- ⏳ AC8: Header `Cache-Control: public, max-age=N` đúng N cho từng endpoint: danh sách (gồm rail mới về `sort=newest`) 30, gợi ý 15, banner 60 (ADR-0019 2.3: mới về dùng chung endpoint danh sách nên 30, không phải 60); không có `Set-Cookie`, không có `private`.
- ⏳ AC9: `{code}` không tồn tại hoặc không khả dụng: cả 4 endpoint mới trả đúng `404 SHOP_NOT_AVAILABLE`, sales không bị gọi (mock verify never).
- ⏳ AC10: Request có Bearer token của khách vẫn gọi sales bằng token dịch vụ (verify token truyền xuống), kết quả không khác với request ẩn danh.
- ⏳ AC11: Sales: mặt hàng của tenant B không bao giờ xuất hiện khi gọi bằng tenant A với mọi `sort`/`inStock` (IT với RLS, chạy `-Dtest=<TênIT>`).
- ⏳ AC12: Sales: `sort` được ánh xạ qua enum cố định, giá trị ngoài allowlist không chạm DB (test đơn vị).
- ⏳ AC13 (chờ Q1): Banner: `GET /banners` trả tối đa 10 mục, đúng thứ tự `sortOrder`, chỉ mục đang hiệu lực; `linkPath` bắt đầu bằng `//`, `javascript:` hoặc `http` bị từ chối khi lưu (test ở API quản trị) và không bao giờ xuất hiện trong phản hồi công khai.
- ⏳ AC14 (chờ Q1): Banner không có dữ liệu trả `200 []`, không 404.
- ⏳ AC15: Kiểm kiến trúc: `mvn test` (ArchUnit) vẫn xanh; nếu có bảng mới thì `TenantArchitectureRulesTest` xanh.
- ⏳ AC16 (thủ công/nginx): `curl` lặp vượt `public_read` trả `429`; xác nhận không có vùng limit mới nào trừ khi Q4 chọn vùng riêng.

## 11. Câu hỏi đã chốt và còn mở

- ✅ Q1 (người dùng chốt): hướng A, bảng banner mới theo tenant ở sales (`storefront_banners`, `tenant_id` + RLS + `@TenantId`), admin tenant tự sửa, `@RequiresModule(SALES)`. Thiết kế: ADR-0019 2.5. Mục 4 và các ghi chú "nếu chọn B" ở 7.5 không còn áp dụng.
- ✅ Q2 (người dùng chốt): "mới về" theo ngày đưa vào bán (`stock_items.created_at`). Không thêm endpoint: dùng `GET /books?sort=newest&size=10` (ADR-0019 2.3).
- ⚠️ Q3: SA dùng khuyến nghị (a) của BA, chờ người dùng xác nhận (ADR-0019 Q-B): `newest`, `price_asc`, `price_desc` + `inStock=true`; không khoảng giá, không `name_asc`; có `keyword` mà không `sort` thì giữ thứ tự theo tên như hiện tại. Sắp theo giá khi chỉ có giá bìa: SA đã quyết (ADR-0019 2.1) dùng cột `cover_price_snapshot` + cột sinh `effective_price` trong `stock_items` (migration sales `V6`), giá hiển thị và tính tiền vẫn lấy sống từ catalog.
- ✅ Q4 (người dùng chốt): autocomplete dùng chung `public_read` (và `public_tenant`), không thêm vùng nginx. Rủi ro chạm `public_tenant` khi một cửa hàng đông khách: ADR-0019 R3, đo ở P3.
- ✅ Q5 (người dùng chốt): chấp nhận giới hạn 200 đầu sách khi tìm theo từ khóa (cả danh sách và gợi ý); ghi trong `docs/api/storefront-kham-pha.md`.
- ✅ Ngoài phạm vi (người dùng chốt): ảnh bìa; nổi bật/bán chạy tự động để sau (mục 9).
- ✅ Gợi ý lấy dữ liệu từ đâu: SA quyết sales gọi `lookupByKeyword` của catalog rồi lọc mặt hàng đang bán (ADR-0019 2.4); độ nhẹ chưa đo (⚠️ P2).
- ⏳ Q-A (mới, ADR-0019): gán sẵn khóa quản trị banner cho mẫu Quản lý cửa hàng? Khuyến nghị có.
- ⏳ Q-C (mới, ADR-0019): giới hạn 20 banner/tenant, hiển thị tối đa 10? Khuyến nghị giữ.

## 12. Giả định

- ⚠️ Gói A không cần thay đổi lms-api, trừ khi chọn hướng A cho banner.
- ⚠️ "Sách mới" tính theo `created_at` của mặt hàng bán (xác minh cột tồn tại; ngữ nghĩa chờ Q2).
- ⚠️ Chưa kiểm tra FE hay tài liệu `docs/api/` nên hợp đồng trả về có thể cần cập nhật tài liệu.
- ⚠️ Chưa rà ADR-0010/0011/0013 toàn bộ; các quy tắc cache/CORS/tenant chỉ lấy từ code và CLAUDE.md.
- ℹ️ Việc phân rã chi tiết và thiết kế truy vấn do `sa` làm tiếp sau khi chốt Q1 đến Q5.
