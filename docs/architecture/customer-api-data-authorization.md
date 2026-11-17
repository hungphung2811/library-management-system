# Phân quyền DATA ở `customer-api` (cổng công khai cho khách bên ngoài)

- Trạng thái: đánh giá của `sa`, ngày 2026-11-17. Chỉ đọc code và tài liệu; không sửa code. Không tạo ADR mới; các điểm cần thành quyết định lâu dài được **đề xuất** bổ sung vào ADR-0011 (mục 8).
- Câu hỏi của người dùng: "đánh giá xem làm sao để phân quyền DATA ở customer api bên ngoài" (website/FE của tenant và khách vãng lai gọi `customer-api`).
- Liên quan: ADR-0003 (khóa động `Controller/method`), ADR-0005 (BFF), ADR-0006 (multi-tenant, RLS), ADR-0008 (đọc xuyên tenant), ADR-0009 (module), ADR-0010 (sales), ADR-0011 (customer-api, token `svc`), ADR-0012 (hợp đồng), ADR-0013 (CORS theo tenant). Đề bài: `docs/requirements/2026-11-16-customer-api.md`, `docs/requirements/2026-11-16-hieu-sach-module-ban-hang.md`.
- Quy ước bằng chứng: `đường/dẫn:dòng` là số dòng trong file tại working tree hiện tại (có thay đổi chưa commit). Điều không đối chiếu được ghi ⚠️ chưa kiểm chứng. SA không chạy test.

## 1. Bối cảnh và mục tiêu

### 1.1. Câu hỏi thật cần trả lời

Phân quyền "chức năng" (ai gọi được endpoint nào) đã có khung: khóa `Controller/method` (ADR-0003), module (ADR-0009), token `svc` chỉ vào được endpoint `@StorefrontAccessible` (ADR-0011 2.1). Phân quyền **dữ liệu** là câu hỏi khác: khi đã gọi được endpoint thì được thấy **những dòng nào** và **những trường nào**. Với cổng công khai, ba trục phải đúng cùng lúc:

1. **Dòng theo tenant**: chỉ dữ liệu của tenant của `{code}`.
2. **Dòng theo chủ sở hữu**: trong một tenant, khách chỉ thấy đơn/liên hệ/hồ sơ của chính mình.
3. **Trường**: chỉ tập trường đã liệt kê cho từng lớp dữ liệu (allowlist), không bao giờ là "trả DTO nội bộ rồi bỏ bớt".

Ràng buộc: service không tin BFF (tự kiểm JWT, module, khóa); không atomicity xuyên DB; `customer-api` không DB, không `security-common`/`tenant-common` (ArchUnit `CustomerApiArchitectureTest`); không hằng số permission.

### 1.2. Hiện trạng đã đối chiếu trong code

| | Điểm | Bằng chứng |
|---|---|---|
| ✅ | Danh sách đường dẫn công khai là danh sách đóng; còn lại `denyAll` | `customer-api/.../config/SecurityConfiguration.java:31-37` (`PUBLIC_API`), `:65` (`anyRequest().denyAll()`). Hiện **chưa** có `books`, `signup`, `orders` (C1/C2 chưa làm). |
| ✅ | Đường dẫn công khai bỏ qua header `Authorization` | `SecurityConfiguration.java:47` (resolver trả `null` khi khớp `PUBLIC_API`). |
| ✅ | Token `svc` từ ngoài vào `customer-api` bị 401 | `ServiceTokenRejectionFilter.java:32-35`, gắn sau `BearerTokenAuthenticationFilter` (`SecurityConfiguration.java:68`). |
| ✅ | So tenant của token với tenant của `{code}` | `storefront/ShopSessionGuard.java:27-33` (`svc` → 401, khác tenant hoặc không có `tenantId` → `403 SHOP_SESSION_MISMATCH`). ⚠️ Chưa được endpoint nào dùng (chưa có `orders`); chỉ có unit test `ShopSessionGuardTest`. |
| ✅ | Phân giải `{code}` một mã lỗi chung | `StorefrontAccessCache.java:27` (regex), `:72-78`; `PublicApiException.java:26` (`404 SHOP_NOT_AVAILABLE`). |
| ✅ | Token `svc` không bao giờ trả ra ngoài | `StorefrontDtos.java:14` (`StorefrontAccess` chỉ dùng nội bộ, `toString` che token); `ShopController` (customer-api) chỉ trả `ShopView(code, name)`. |
| ✅ | Đăng nhập: tenant ép theo đường dẫn, phản hồi lọc trường | `AuthController.java:37-41`; `HttpAuthClient.java:37-39` (body `LoginBody` do BFF dựng, `tenantCode` từ đường dẫn); `AuthDtos.java:29` (`IdentityTokenView` chỉ 3 trường, bỏ `availableTenants`). |
| ✅ | Gộp lỗi đăng nhập (chống dò trạng thái tài khoản) | `HttpAuthClient.java:24-25, 41-46`: `UNAUTHORIZED`, `NO_TENANT_ACCESS`, `ACCOUNT_LOCKED`, `ACCOUNT_DISABLED` → `401 LOGIN_FAILED`. Tức B-C11 của ADR-0011 đã có code. |
| ✅ | Lỗi công khai theo danh sách cho phép, thông điệp do BFF viết | `PublicExceptionHandler.java:46-60` (allowlist), `:89-95` (`MODULE_NOT_ENABLED`/`TENANT_REQUIRED` → `404 SHOP_NOT_AVAILABLE`; `FORBIDDEN` → thông điệp chung, không lộ tên khóa), `:99-106` (mã lạ → `400 REQUEST_REJECTED`), `:186-188` (luôn bỏ `details` của downstream). |
| ✅ | Nhánh `svc` trong interceptor | `security-common/.../PermissionInterceptor.java:83-85` (xét `svc` **trước** mọi nhánh khác, kể cả đọc xuyên tenant ở `:93-96`), `:123-148` (không `@StorefrontAccessible` → 403; thiếu `tenantId` → 403; thiếu module → 403). |
| ✅ | Thông điệp 403 nội bộ chứa tên khóa | `PermissionInterceptor.java:115` (`"Thiếu quyền: " + key`). Đã được `PublicExceptionHandler` viết lại; lms-api thì chuyển nguyên (ngoài phạm vi). |
| ✅ | Profile tắt kiểm khóa tắt **toàn bộ** interceptor (kể cả nhánh `svc`) | `PermissionAutoConfiguration.java:26` (`@ConditionalOnProperty ... enabled ... matchIfMissing = true`). Trả lời rủi ro "⚠️ chưa kiểm" ở ADR-0011 mục 5: ở profile dev, token `svc` vào được mọi endpoint. |
| ✅ | Token không có `aud`; resource server không kiểm `iss`/`aud` | `identity/.../security/JwtTokenIssuer.java` (`issue`, `issueService`: chỉ `.issuer(issuer)`, không `audience`); `customer-api/src/main/resources/application.yml` chỉ có `jwk-set-uri`. Hệ quả: token đăng nhập ở storefront và token của `lms-api` là **cùng một loại**, dùng chéo được (mục 3, A1). |
| ✅ | Token `svc` có `sub` cố định | `IssueStorefrontTokenUseCase.java:17` (`...5f01`). Endpoint `@StorefrontAccessible` không được dùng `CurrentUser` làm người thực hiện. |
| ✅ | Endpoint catalog mở cho token `svc` | `catalog/.../TitleController.java:84-89` (`lookup`), `:95-99` (`lookupByKeyword`, tối đa 200), `:102-106` (`preview`). Cả ba **không** kiểm "đang bán"; `TitleLookupView.java:9-10` có `isbn`, `coverPrice`. Quy tắc "chỉ sách đang bán" chỉ nằm ở sales (thiết kế ADR-0011 2.12b). |
| ✅ | RLS + FORCE cho mọi bảng sales | `system-service/.../migration/sales/V1__create_sales_tables.sql:236-237` + policy `tenant_isolation`, `platform_read_all` chỉ `FOR SELECT` khi `app.tenant_scope = 'all'`. sales **không** có `@CrossTenantReadable` (grep). |
| ✅ | Chống trùng đơn theo `(tenant_id, client_request_id)` | `V1__create_sales_tables.sql:164`; `PlacePosOrderUseCase.java:48-50, 56-58` (trùng thì **trả đơn cũ**); `RequestSupport.java:45-55` (chuỗi không phải UUID được băm **xác định** thành UUID). Xem A3. |
| ✅ | Đọc đơn chỉ theo `(tenant, id)`, chưa có truy vấn theo chủ | `SalesOrderRepository.java:20` (`findById(tenantId, id)`), `OrderQueryUseCases.java:58-61`. Đã có chỉ mục `(tenant_id, customer_user_id, created_at DESC)` (`V1...sql:169`) nhưng chưa có method dùng. |
| ✅ | DTO đơn của nhân viên chứa trường nội bộ | `ordering/infrastructure/web/OrderView.java:12-16`: `customerUserId`, `version`, `vatRateBps`, `lines` là `OrderResponse.Line` có `isbn` (`OrderResponse.java:23-25`). Dòng đơn lưu `isbn` snapshot (`V1...sql:182`). |
| ✅ | Số đơn tuần tự theo tenant | `DocumentNumberAdapter.java:48-49` (`DH-000123`). |
| ✅ | Kích thước trang tối đa của sales | `RequestSupport.java:14` (`MAX_PAGE_SIZE = 100`), khác mức 50 của storefront (ADR-0011 2.7). |
| ✅ | Endpoint `@AuthenticatedOnly` nhận id mà không kiểm chủ | `circulation/.../BorrowingController.java:121-124` (`get(@PathVariable id)` → `GetBorrowingUseCase.execute(borrowingId)` không có `userId`), class `@RequiresModule(LENDING)` (`:40`); lms-api mở `GET /api/borrowings/{id}` (`lms-api/.../circulation/web/BorrowingController.java:84`). Xem A6. |
| ✅ | Mẫu kiểm chủ đúng đã có | `support/.../NotificationController.java:58-60` truyền `CurrentUser.from(jwt).userId()` vào use case. |
| 🚫 | `ShopController` (sales), `StorefrontController`, signup, mẫu role Khách hàng | Không có trong code: sales chỉ có `PosOrderController`, `SalesOrderController`, `StockItemController`, `GoodsReceiptController`, `StockAdjustmentController`, `SupplierController`, `SalesSettingsController`; identity không có `signup` (grep), migration identity mới nhất trên đĩa là `V16__tenant_modules.sql` (chưa có `role_templates` SALES/`signup_default`). Toàn bộ phần đơn của khách dưới đây là **thiết kế**, ⚠️ chưa kiểm chứng bằng code. |
| ℹ️ | Lệch tài liệu và code | (1) Chú thích `SecurityConfiguration.java:22` còn ghi "CORS do nginx" (ADR-0013 đổi nhưng chưa có code). (2) Đầu ADR-0011 ghi "cache storefront có TTL mục tối đa 60 giây" nhưng `StorefrontAccessCache.java:58-60` giữ kết quả dương tới `expiresIn − refreshMargin` (khoảng 4 phút); đây là thay đổi của ADR-0013 chưa làm. |

## 2. Mô hình phân lớp dữ liệu

### 2.1. Năm lớp

| Lớp | Nội dung | Nguồn (service/bảng) | Đường ra công khai |
|---|---|---|---|
| **L0. Công khai nền tảng** | Trang giới thiệu SaaS **đã xuất bản**; form liên hệ SaaS (chỉ ghi) | system `cms_pages` (cột `published_*`), `cms_contact_requests` | `/public/site/**` |
| **L1. Công khai theo tenant** | `{code, name}` của tenant ACTIVE+SALES; sách **đang bán**: `bookRef, title, author, price, inStock, hasPreview`, chi tiết thêm `description`, preview `previewText` | identity `tenants`; sales `stock_items` (đang bán) + catalog `titles` | `/public/t/{code}`, `/public/t/{code}/books/**` |
| **L2. Riêng của khách** | Đơn của mình (gồm người nhận, địa chỉ do chính khách nhập), hủy đơn của mình, liên hệ cửa hàng của mình, phiên đăng nhập | sales `sales_orders` (lọc `customer_user_id`), nơi lưu liên hệ (chưa chốt), identity `refresh_tokens` | `/public/t/{code}/orders/**`, `/public/t/{code}/contact-requests`, `/public/auth/**` |
| **L3. Nội bộ tenant** | `on_hand`, `reserved`, `available`, `low_stock_threshold`, `selling_price` của sách chưa bán, `unit_cost` phiếu nhập, nhà cung cấp, `payment_records.reference/recorded_by`, `created_by/confirmed_by/cancelled_by`, `cancel_reason`, `version`, `client_request_id`, ISBN, `coverPrice` tách riêng, sách chưa đưa vào bán, preview của sách chưa bán, đơn và PII của khách khác, audit, danh sách thành viên | sales, catalog, identity, support | **Không có**. Chỉ qua `lms-api` với khóa |
| **L4. Nền tảng riêng** | Hợp đồng, giá thuê, danh sách tenant, module của tenant khác, sự tồn tại của tenant không khả dụng, `cms_contact_requests` (đọc), bản nháp CMS | identity (`tenants`, `tenant_modules`, hợp đồng ADR-0012), system | **Không có**. Chỉ `@PlatformAdminOnly` qua `lms-api` |

### 2.2. Ma trận lớp dữ liệu × người đọc

Người đọc: **Vãng lai** (không token; `customer-api` gọi thay bằng token `svc`), **Khách** (token người dùng có `tenantId` = tenant của `{code}`, role Khách hàng), **Nhân viên** (token có khóa tương ứng, qua `lms-api`), **Admin tenant** (`admin=true`), **Platform admin** (`platformAdmin=true`, không `tenantId`).

| Lớp | Vãng lai | Khách | Nhân viên | Admin tenant | Platform admin |
|---|---|---|---|---|---|
| L0 | Đọc bản xuất bản; gửi form | Như vãng lai | Như vãng lai | Như vãng lai | Đọc/ghi mọi bản, đọc form (`@PlatformAdminOnly`) |
| L1 | Đọc (tập trường cố định) | Đọc (vẫn đi bằng token `svc`, ADR-0011 2.4) | Đọc qua `lms-api` (dạng nội bộ, đủ trường) | Như nhân viên | Đọc theo tenant qua `@CrossTenantReadable` nơi có |
| L2 | Không (401) | **Chỉ dòng của mình**; tập trường công khai | Đọc theo khóa; PII che một phần, xem đủ bằng khóa riêng `getContact` | Như nhân viên có mọi khóa | Không qua sales (sales không `@CrossTenantReadable` → `403 TENANT_REQUIRED`) |
| L3 | Không | Không | Theo khóa | Có | Chỉ endpoint `@CrossTenantReadable` + GET, có log audit |
| L4 | Không (chỉ `{code, name}` của tenant khả dụng) | Không | Không | Đọc hợp đồng **của mình** (DTO rút gọn ADR-0012 2.5.2) | Có |

### 2.3. Cơ chế chặn theo tầng

| Lớp | `customer-api` | Service nhận | DB |
|---|---|---|---|
| L0 | Danh sách đường dẫn đóng; record DTO riêng (`SiteDtos`) | `CmsPublicController` `@PublicEndpoint`, truy vấn chỉ cột `published_*` | Bảng toàn cục, không RLS (chủ đích, ADR-0011 2.3) |
| L1 | Phân giải `{code}` → token `svc` đúng tenant; record DTO công khai; bỏ `Authorization` của khách | Interceptor: `svc` + `@StorefrontAccessible` + `tenantId` + module SALES; sales lọc "đang bán" và chỉ dựng DTO storefront | RLS `tenant_isolation` theo claim `tenantId` |
| L2 | Xác thực JWT; từ chối `svc`; `ShopSessionGuard`; chuyển tiếp **token của khách**; record DTO công khai; dựng body downstream | Khóa `ShopController/*` (role Khách hàng); module SALES; **sở hữu trong điều kiện truy vấn** (`customer_user_id = sub`) | RLS theo tenant (sở hữu **không** ở DB, xem 5.4) |
| L3 | Không có route; `denyAll` mặc định | Khóa của nhân viên; DTO nội bộ không bao giờ đi qua `customer-api` | RLS |
| L4 | Không có route | `@PlatformAdminOnly`; use case lọc `tenant_id` tường minh cho tenant admin (bảng toàn cục) | Không RLS (bảng toàn cục, ADR-0006 mục 7) |

### 2.4. Trường không bao giờ ra `customer-api`

`tenant_id`/`tenantId`, `customerUserId`/`sub` của người khác, `onHand`, `reserved`, `available` (số), `lowStockThreshold`, `unitCost`/giá nhập, `coverPrice` tách riêng (chỉ trả `price` hiệu lực), ISBN (kể cả `isbn` snapshot ở dòng đơn), `createdBy`/`confirmedBy`/`cancelledBy`/`recordedBy`, `payment_records.reference`, `clientRequestId`, `version` (khách hủy đơn không cần khóa lạc quan; nếu cần thì dùng ETag), `exchangeOfReturnId`, `piiAnonymizedAt`, `walkIn*`, `availableTenants`, `mustChangePassword` (⚠️ xem 7), mọi tên khóa quyền, tên service/host, `details` của lỗi downstream (gồm `details.available` của `INSUFFICIENT_STOCK`).

## 3. Hướng tấn công và lỗi phân quyền dữ liệu

Mức: **Cao** = lộ L2/L3 của người khác hoặc chiếm quyền; **Trung bình** = lộ có điều kiện hoặc lộ thông tin kinh doanh; **Thấp** = lộ thông tin ít giá trị, hoặc chỉ ở dev.

| # | Hướng | Hiện trạng (bằng chứng) | Mức | Đề xuất |
|---|---|---|---|---|
| A1 | **Token chéo kênh**: token đăng nhập storefront = token nhân viên đầy đủ; token khách dùng được ở `lms-api` | Không `aud`, không kiểm `iss`/`aud` (1.2). Nhân viên/admin tenant đăng nhập ở storefront nhận token có `admin=true` và mọi khóa, nằm trong JS của **website của tenant** (ADR-0013: origin do tenant khai báo, có thể là bên thứ ba). XSS ở website đó lấy được token admin dùng ở `lms-api`. Ngược lại token khách (tự đăng ký) gọi được mọi `@AuthenticatedOnly` ở `lms-api` | **Cao** (khi mở đăng nhập công khai) | 5.3: tách `aud` cho token storefront, bỏ `admin`/`platformAdmin`, `lms-api` từ chối, `customer-api` bắt buộc |
| A2 | **IDOR đơn hàng** (khách A đọc/hủy đơn khách B cùng tenant, đoán hoặc lấy được `orderId`) | Chưa có code `ShopController`. Repository chỉ có `findById(tenant, id)` (`SalesOrderRepository.java:20`); dùng lại là IDOR | **Cao** (thiết kế, chặn C2) | 5.2: kiểm sở hữu trong điều kiện truy vấn, không có → `404 ORDER_NOT_FOUND` cùng thân với đơn không tồn tại |
| A3 | **IDOR qua khóa chống trùng** `clientRequestId` | Duy nhất theo `(tenant_id, client_request_id)` (`V1...sql:164`), trùng thì **trả đơn cũ** (`PlacePosOrderUseCase.java:48-50, 56-58`), chuỗi bất kỳ băm xác định (`RequestSupport.java:45-55`). Nếu luồng online chép mẫu này: khách B gửi `clientRequestId` trùng của A (ví dụ FE sinh `"cart-1"`) nhận nguyên đơn của A gồm tên, điện thoại, địa chỉ người nhận; hoặc chiếm trước khóa để chặn A | **Cao** (thiết kế, chặn C2) | 5.5: online bắt buộc UUID; tra trùng theo `(tenant, customer_user_id, client_request_id)`; trùng khóa nhưng khác chủ → `409` không thân dữ liệu |
| A4 | **Lộ trường qua DTO** (trả `OrderView`/entity) | `OrderView` có `customerUserId`, `version`, dòng có `isbn` (1.2). Jackson serialize mọi accessor của record | **Cao** (thiết kế, chặn C2) | 5.1: DTO storefront riêng ở sales và record riêng ở `customer-api`; test so **tập tên trường** |
| A5 | **Mass assignment** ở signup/đặt hàng (khách tự gán role, `tenantId`, giá, giảm giá, phí ship, `customerUserId`, `channel`, `status`) | Mẫu đúng đã có: `LoginBody` do BFF dựng, `tenantCode` lấy từ đường dẫn (`HttpAuthClient.java:37-39`). Spring Boot mặc định bỏ qua trường JSON lạ nên record không có trường là an toàn ⚠️ (chưa kiểm cấu hình Jackson thật của từng service) | Trung bình (thiết kế) | 5.6: record request không có các trường đó; BFF dựng body downstream; sales tính giá/ship từ `stock_items`/`sales_settings`; test gửi trường thừa |
| A6 | **IDOR có sẵn ở `lms-api`** mà khách tự đăng ký chạm được | `GET /api/borrowings/{id}` → `BorrowingController/get` `@AuthenticatedOnly`, không kiểm chủ (`BorrowingController.java:121-124`). Khách hàng là thành viên tenant nên có token hợp lệ; tenant bật cả LENDING lẫn SALES thì khách đọc được phiếu mượn bất kỳ theo id (gồm `userId` người mượn). Tách `aud` (A1) **không** chặn được vì khách vẫn tự đăng nhập `lms-api` bằng `/api/auth/login` | Trung bình (UUID khó đoán; tăng mức khi mở signup) | 5.7: vá trước khi mở signup; rà mọi `@AuthenticatedOnly` nhận id |
| A7 | **Token khách tenant X gọi `/public/t/Y/...`** | `ShopSessionGuard.java:27-33` (403 `SHOP_SESSION_MISMATCH`), chạy sau phân giải nên `Y` không khả dụng vẫn ra 404 chung. ⚠️ Chưa nối vào endpoint nào | Thấp nếu nối đúng | Bắt buộc dùng một hàm chung "phân giải rồi kiểm phiên" (5.8); test ma trận |
| A8 | **Token `svc` lọt sang endpoint khác / endpoint ghi** | Interceptor chặn (`PermissionInterceptor.java:83-85, 123-148`); `customer-api` từ chối `svc` từ ngoài (`ServiceTokenRejectionFilter`); danh sách `@StorefrontAccessible` đóng, chỉ đọc. Nhưng: (a) profile dev tắt cả interceptor (`PermissionAutoConfiguration.java:26`); (b) nếu token `svc` lộ (log, dump), ai trong mạng nội bộ đọc được **toàn bộ** đầu sách của tenant qua catalog (gồm ISBN, `coverPrice`, preview sách chưa bán) trong 5 phút | Thấp | Giữ; test không log token; ArchUnit "endpoint `@StorefrontAccessible` không dùng `CurrentUser`" (5.9). Không thêm kiểm "đang bán" vào catalog (đã loại ở ADR-0011 2.12b) |
| A9 | **Tìm kiếm/lookup trả dữ liệu tenant khác** | RLS + FORCE ở mọi bảng sales (`V1...sql:236-237`), catalog `titles` từ `V2`; token `svc` luôn có `tenantId` và nhánh `svc` xét trước đọc xuyên tenant; sales không có `@CrossTenantReadable` | Thấp | Giữ; IT cách ly cho `StorefrontController`/`ShopController` theo mẫu `CatalogTenantIsolationIT`. Không thêm native query (CLAUDE.md) |
| A10 | **Dò tenant** qua thông điệp/độ trễ | Một mã `404 SHOP_NOT_AVAILABLE` (1.2), bộ đệm cả kết quả phủ định. Chênh lệch thời gian ⚠️ chưa đo (P-C2 của ADR-0011) | Thấp | Giữ; P-C2 |
| A11 | **Dò tài khoản** | Đăng nhập đã gộp 4 mã (1.2). Signup sẽ lộ `DUPLICATE_USERNAME` **xuyên tenant** vì tên đăng nhập toàn hệ thống (`PublicExceptionHandler.java` allowlist có mã này; ADR-0011 2.5 đã chấp nhận) | Thấp | Giữ, dựa `limit_req public_form`. Đóng hẳn cần xác minh email (ngoài phạm vi) |
| A12 | **Đọc ngoài module** (SALES tắt) | Token `svc` chỉ cấp khi SALES bật; interceptor kiểm module cho cả `svc`; token khách mang `modules` | Thấp | Giữ. Độ trễ tới 5 phút (ADR-0011 Q2) |
| A13 | **Cào dữ liệu** (phân trang không giới hạn) | sales tối đa 100/trang (`RequestSupport.java:14`); ADR chọn 50 cho storefront; `lookupByKeyword` tối đa 200 id | Thấp (L1 vốn công khai) | `customer-api` validate `size ≤ 50`; `StorefrontController` tự chặn 50; `myOrders` tối đa 20 |
| A14 | **Lộ số lượng đơn** qua `orderNo` tuần tự (`DH-000123`) | `DocumentNumberAdapter.java:48-49`. Khách đặt hai đơn cách nhau một tuần biết tenant bán bao nhiêu đơn | Thấp | 5.10: dùng `orderId` (UUID) làm tham chiếu ở URL; `orderNo` hiển thị là chấp nhận được, hỏi người dùng (Q3) |
| A15 | **Log chứa PII** | `customer-api` không log thân (`PublicExceptionHandler`), record che `toString` (`SiteDtos.ContactRequest.toString`, `AuthDtos`); nginx log `$request` gồm query (từ khóa tìm kiếm, `orderId`), không có token | Thấp | Giữ; record request đơn hàng/liên hệ phải che `toString`; test bắt log như T-C3 |
| A16 | **Lộ tên khóa nội bộ trong 403** | Đã viết lại ở `PublicExceptionHandler.java:93-95` | Thấp | Giữ test T-C4 |
| A17 | **CORS coi như bảo mật** | ADR-0013 1.3 đã nói rõ: CORS không phải xác thực, bearer token không cookie | ℹ️ | Không dựa vào CORS cho bất kỳ quyết định dữ liệu nào |
| A18 | **Refresh token không gắn kênh/cửa hàng** | `/public/auth/refresh` không có `{code}` (`AuthController.java:43-46`); refresh token của `lms-api` đổi được qua `customer-api` và ngược lại | Thấp hiện tại; thành lỗ nếu làm 5.3 mà quên refresh | 5.3 phải lưu kênh trong `refresh_tokens` |
| A19 | **Quyền mặc định của role Khách hàng quá rộng** | Mẫu chưa có (1.2). ADR-0010 dự kiến `ShopController/*` + `TitleController/lookup` | Trung bình nếu seed nhầm | 5.11: allowlist khóa cho mẫu Khách hàng + test đối chiếu |

## 4. Đánh giá các quyết định hiện có

| | Quyết định | Đánh giá |
|---|---|---|
| ✅ | ADR-0011 2.1 token `svc` + `@StorefrontAccessible` | Đúng và đủ cho L1. Tenant lấy từ claim, RLS tự áp, không thêm header tin cậy. Thiếu: test nhánh `svc` khi `enabled=false` chỉ có nghĩa ở dev (ghi rõ trong tài liệu dev). |
| ✅ | ADR-0011 2.4 `customer-api` chỉ xác thực, sales kiểm khóa | Đúng cho phân quyền chức năng. **Không đủ** cho phân quyền dữ liệu: khóa `ShopController/myOrder` chỉ nói "được gọi", không nói "đơn nào". Sở hữu phải là quy tắc ở application của sales (5.2). |
| ✅ | ADR-0011 2.4 danh sách đường dẫn công khai đóng, `denyAll` còn lại | Đúng, đã có code. Khi thêm C1/C2 phải liệt kê từng mẫu (không `/public/t/*/**`). |
| ✅ | ADR-0011 2.6, 2.8 DTO công khai riêng, sales trả `inStock` thay vì số | Đúng hướng (allowlist). Chưa có code nên chưa kiểm được; cần áp **cùng nguyên tắc cho đơn hàng** (chưa có trong ADR). |
| ✅ | ADR-0011 2.8 `bookRef = titleId` (UUID) | Đúng: UUID ngẫu nhiên, RLS chặn xuyên tenant, sales lọc "đang bán". |
| ✅ | ADR-0011 2.9 `PublicExceptionHandler` allowlist mã, bỏ `details` | Đúng, đã có code. |
| ✅ | ADR-0011 2.13 gộp lỗi đăng nhập | Đã có code (`HttpAuthClient`). Q4 của ADR-0011 có thể đóng phần `customer-api`. |
| ⚠️ | ADR-0011 2.2 "đăng nhập khách đi qua `customer-api`" | Đúng về luồng nhưng **thiếu** giới hạn token: token phát ra là token đầy đủ (A1). Cần bổ sung 5.3. |
| ⚠️ | ADR-0010 "`userId` lấy từ token; đơn người khác `404 ORDER_NOT_FOUND`" | Đúng ý định; chưa nói **cách** (điều kiện truy vấn hay kiểm sau đọc), chưa nói `channel`, chưa nói chống trùng theo chủ (A3). |
| ❌ | Chống trùng `(tenant_id, client_request_id)` trả đơn cũ | Đúng cho POS (nhân viên cùng tenant được xem mọi đơn), **sai** nếu dùng nguyên cho đơn online (A3). |
| ⚠️ | ADR-0012 bảng hợp đồng toàn cục, use case lọc tường minh | Đúng; không có đường ra `customer-api`. Không ảnh hưởng ở đây. |
| ℹ️ | ADR-0013 CORS theo tenant | Đúng khi tự nhận không phải bảo mật. Nhưng chính nó làm A1 nặng hơn: token khách sống trong JS của website do tenant kiểm soát. |

## 5. Quyết định đề xuất

### 5.1. DTO công khai là allowlist hai lớp, không bao giờ dùng lại DTO nội bộ

| Phương án | Ưu | Nhược |
|---|---|---|
| **A. sales có DTO storefront riêng (`ShopOrderView`, `StorefrontItemView`) dựng tường minh từ domain; `customer-api` có record riêng và chỉ ánh xạ các trường đã liệt kê (chọn)** | Thêm trường ở DTO nội bộ không lộ ra ngoài; hai lớp chặn độc lập; test so tập trường ở cả hai | Hai bộ record phải giữ đồng bộ |
| B. Dùng `OrderView` rồi `@JsonIgnore` / bỏ trường ở BFF | Ít code | Thêm trường mới ở nội bộ là lộ ngay (blocklist); đã bị ADR-0012 2.5.2 loại vì cùng lý do |
| C. `@JsonView` trên một DTO | Một lớp | Dễ sót annotation; khó kiểm bằng ArchUnit |

Chọn **A**. `customer-api` đã đi đúng hướng: `IdentityTokenView` chỉ ba trường, Jackson bỏ qua trường lạ khi đọc phản hồi downstream.

`ShopOrderView` đề xuất (`be-dev` chốt tên): `orderRef` (= `id`), `orderNo`, `status`, `paymentStatus`, `paymentMethod`, `fulfilment`, `recipient{name, phone, address}` (đầy đủ, do chính khách nhập), `lines[{bookRef, title, quantity, unitPrice, lineDiscount, lineTotal}]`, `subtotal`, `discountTotal`, `vatTotal`, `shippingFee`, `grandTotal`, `createdAt`, `cancellable`. Không có các trường ở 2.4.

### 5.2. Sở hữu nằm trong điều kiện truy vấn, trả 404

| Phương án | Ưu | Nhược |
|---|---|---|
| **A. Port riêng `findOwnOrder(tenantId, customerUserId, orderId)`, `pageOwnOrders(tenantId, customerUserId, page)`, `channel = ONLINE`; không thấy → `404 ORDER_NOT_FOUND` (chọn)** | Không đọc được dòng của người khác vào bộ nhớ; một nhánh lỗi nên không phân biệt "không tồn tại" và "của người khác"; dùng sẵn chỉ mục `(tenant_id, customer_user_id, created_at DESC)` | Thêm method repository |
| B. `findById` rồi so `customerUserId` ở use case | Dùng lại code | Dễ quên ở một use case (hủy đơn, liên hệ có `orderId`); dễ trả 403 khác 404 làm lộ sự tồn tại |
| C. RLS theo người dùng (xem 5.4) | Chặn ở DB | Đã loại ở 5.4 |

Chọn **A**. Quy tắc đặt ở `ordering.application` của sales (use case shop riêng), `userId` **chỉ** từ `CurrentUser.from(jwt)`, không bao giờ từ đường dẫn/body/query. Hủy đơn dùng cùng điều kiện rồi mới xét trạng thái (`409 ORDER_STATE_CONFLICT` chỉ khi đơn là của mình). Liên hệ cửa hàng có `orderId` kiểm bằng cùng truy vấn.

### 5.3. Tách kênh token: `aud` cho phiên storefront

| Phương án | Ưu | Nhược |
|---|---|---|
| A. Giữ một loại token | Không đổi | A1 còn nguyên: token admin có thể nằm trong JS website tenant |
| B. `customer-api` từ chối kết quả đăng nhập có `admin`/`platformAdmin` | Rẻ, chỉ sửa BFF | Nhân viên không admin vẫn mang mọi khóa ra website; token khách vẫn dùng ở `lms-api`; admin không đặt hàng được |
| **C. identity phát token storefront có `aud = "lms-storefront"`, không `admin`, không `platformAdmin`; `refresh_tokens` lưu kênh để refresh giữ kênh; `lms-api` từ chối token có `aud` này; `customer-api` bắt buộc `aud` này (chọn)** | Token ở website chỉ dùng được qua tập route đóng của `customer-api` (mạng chặn `/internal/**`); token `lms-api` không dùng ở storefront; khớp mô hình "BFF quyết kênh" | Sửa identity (login nhận kênh, migration `refresh_tokens`), `lms-api` thêm validator, `customer-api` thêm validator; admin tenant muốn đặt hàng phải có role mang khóa `ShopController/*` |
| D. Token storefront chỉ mang khóa của role `signup_default` | Hẹp nhất | Gần như hằng số permission (trái ADR-0003); nhân viên đồng thời là khách phải có role Khách hàng |

Chọn **C**. Chi tiết ranh giới:

- identity `POST /internal/auth/login` và `/refresh` nhận thêm kênh (ví dụ trường `channel: STOREFRONT`), chỉ `customer-api` gửi. Không cần bí mật: kênh STOREFRONT chỉ **thu hẹp** token (bỏ `admin`/`platformAdmin`, thêm `aud`), không mở rộng.
- `refresh_tokens` thêm cột kênh (migration identity, ⚠️ số kế tiếp: trên đĩa mới nhất `V16`, `V17`/`V18` đã được sales B1.6/B2.3, ADR-0011 B-C10, ADR-0012 nhắm; kiểm ngay trước khi tạo). Refresh giữ kênh của token gốc; refresh kênh STOREFRONT qua `lms-api` → 401.
- `lms-api`: `JwtDecoder` thêm validator: token có `aud` chứa `lms-storefront` → 401. Token không `aud` (mọi token hiện có) vẫn hợp lệ, không phá tương thích.
- `customer-api`: validator bắt buộc `aud` chứa `lms-storefront` cho route cần đăng nhập; `ShopSessionGuard` giữ nguyên.
- Service nội bộ **không** đổi (không kiểm `aud`): giữ nguyên tắc "service không tin BFF" về khóa/module/sở hữu; `aud` là ranh giới kênh ở BFF.
- Không giải quyết A6 (khách vẫn đăng nhập `lms-api` bằng kênh thường). Muốn chặn khách vào `lms-api` là quyết định nghiệp vụ riêng (Q4).

Đề xuất ghi thành mục mới trong ADR-0011 (hoặc ADR riêng nếu người dùng muốn), vì đổi hợp đồng token toàn hệ thống.

### 5.4. RLS theo người dùng cho bảng đơn: không làm giai đoạn này

| Phương án | Ưu | Nhược |
|---|---|---|
| **A. Kiểm ở application bằng điều kiện truy vấn (5.2) + test IDOR tự động (chọn)** | Khớp mô hình hiện có (RLS chỉ theo tenant, ADR-0006); nhân viên cùng bảng không bị ảnh hưởng | Phụ thuộc kỷ luật code; bù bằng ArchUnit + test |
| B. Policy RLS `customer_user_id = current_setting('app.user_id')` khi `app.actor_kind = 'customer'` | Chặn cả khi use case sai | Phải đặt thêm hai biến phiên ở `TenantContextFilter` cho mọi service; policy phải phân biệt khách với nhân viên (nhân viên xem mọi đơn) bằng biến phiên do chính ứng dụng đặt, nên vẫn tin ứng dụng; job ẩn danh, POS, platform read đều phải xử lý; IT phức tạp |

Chọn **A**. Xem lại nếu số endpoint dữ liệu riêng của khách tăng (nhiều bảng, nhiều service).

### 5.5. Chống trùng đơn online gắn với chủ

- Online: `clientRequestId` bắt buộc là UUID (không băm chuỗi tự do như POS ở `RequestSupport.java:45-55`).
- Tra trùng theo `(tenant_id, customer_user_id, client_request_id)`; ràng buộc DB giữ `(tenant_id, client_request_id)` (`V1...sql:164`). Bắt `DuplicateClientRequestException` rồi đọc lại bằng điều kiện có chủ: thấy → trả đơn đó; không thấy (khóa thuộc người khác) → `409` mã mới (ví dụ `CLIENT_REQUEST_CONFLICT`), không thân dữ liệu. Thêm mã vào allowlist của `PublicExceptionHandler`.
- Đã loại: ràng buộc duy nhất `(tenant_id, customer_user_id, client_request_id)` thay cho ràng buộc hiện có (POS có `customer_user_id` NULL, phải sửa migration đã chạy hoặc thêm chỉ mục thứ hai mà không thêm an toàn).

### 5.6. Request là allowlist, BFF dựng body downstream

- `customer-api` có record riêng cho signup (`username, password, fullName, email, phone?`) và đặt hàng (`clientRequestId, lines[{bookRef, quantity}], fulfilment, paymentMethod, recipient?`). Không có `tenantCode`/`tenantId`, `role*`, `price`, `discount*`, `shippingFee`, `customerUserId`, `channel`, `status`.
- BFF dựng body gửi identity/sales (mẫu `LoginBody`): `tenantCode` lấy từ `{code}` đã phân giải.
- identity signup gán đúng role `signup_default` (ADR-0010 2.3); sales tính giá từ `stock_items`, phí ship từ `sales_settings`, không nhận giảm giá ở kênh online.
- Test: gửi thêm `role`, `admin`, `unitPrice`, `discount`, `customerUserId` → kết quả giống khi không gửi.

### 5.7. Vá IDOR có sẵn trước khi mở tự đăng ký

`BorrowingController/get` (`@AuthenticatedOnly`, không kiểm chủ). Phương án: (a) bỏ `@AuthenticatedOnly` để thành khóa nhân viên `BorrowingController/get` và thêm `GET /internal/borrowings/mine/{id}` lọc theo `userId` từ token (chọn: đúng mẫu `mine`, khớp ADR-0003); (b) use case nhận `userId` và cho xem nếu là chủ, còn lại cần khóa: phải kiểm khóa trong code, trái ADR-0003. Phương án (a) **đổi khóa và hành vi**: role đang dùng `get` qua `@AuthenticatedOnly` cần được gán khóa mới; FE màn hình độc giả phải chuyển sang `/mine/{id}`. Cùng việc: rà mọi `@AuthenticatedOnly` nhận id (hiện thấy `BorrowingController.get`; `NotificationController.read` đã truyền `userId`, ⚠️ chưa đọc use case để xác nhận điều kiện truy vấn).

### 5.8. Một hàm chung "phân giải rồi kiểm phiên" ở `customer-api`

Mọi route cần đăng nhập đi qua một helper (ví dụ `ShopRequests.requireSession(code, authentication)`): `StorefrontAccessCache.resolve(code)` trước (404 chung), rồi `ShopSessionGuard.requireSession` (401/403), rồi trả token khách để chuyển tiếp. Không controller nào tự ghép hai bước. Lý do: thứ tự sai làm lộ sự tồn tại tenant (403 trước 404).

### 5.9. Kiểm thử và luật kiến trúc tự động

- **ArchUnit `customer-api`**: kiểu trả về của method `@RestController` chỉ là `ResponseEntity<T>`/`T` với `T` thuộc `lms.customer..dto..` (hoặc `Void`); `..dto..` không phụ thuộc `client.http`.
- **ArchUnit sales**: lớp web của storefront/shop không phụ thuộc `OrderView`, `OrderResponse`, `StockItemView`, `..domain..` khi trả về; lớp có method `@StorefrontAccessible` không phụ thuộc `CurrentUser`; use case shop không nhận `customerUserId` từ request record (kiểm bằng review + test).
- **Ma trận vai trò × endpoint × dữ liệu** (tester): người gọi {không token, token `svc` lọt, khách A, khách B cùng tenant, khách tenant Y, nhân viên không khóa, admin tenant, platform admin, token kênh `lms-api`} × mọi route `/public/**` × dữ liệu {của mình, của người khác cùng tenant, của tenant khác, không tồn tại}. Kỳ vọng ghi thành bảng, test tham số hóa.
- **Tập tên trường**: so tập khóa JSON (không chỉ giá trị) cho mọi phản hồi công khai, gồm lỗi.

### 5.10. Mã tham chiếu đơn

`orderRef = id` (UUID) ở mọi URL; `orderNo` (`DH-000123`) chỉ để hiển thị. Đã loại: mã công khai ngẫu nhiên riêng (cột mới, chỉ mục duy nhất, sửa `DocumentNumberAdapter`) vì lộ quy mô đơn là thông tin kinh doanh mức thấp; chờ người dùng xác nhận (Q3).

### 5.11. Quyền mặc định của mẫu Khách hàng

Mẫu `signup_default` (ADR-0010 B2.3) chỉ có khóa "của tôi": `ShopController/*` (đặt, xem đơn của mình, hủy), `TitleController/lookup` (sales gọi catalog bằng token khách khi đặt hàng), `ShopContactController/create` (liên hệ cửa hàng, ADR-0015, seed ở identity V22; đã chốt: allowlist = `ShopController/*`, `TitleController/lookup`, `ShopContactController/create`). Test đối chiếu: tập khóa của mẫu là tập con của allowlist này; thêm khóa phải sửa test và ADR. Không `is_admin` (luật domain ADR-0010). ⚠️ Khóa `TitleController/lookup` cho khách cũng mở `POST /internal/books/lookup` trả ISBN/`coverPrice` nếu khách gọi được catalog trực tiếp; hiện không có route `lms-api` nào tới `lookup` (đã grep `CatalogController` của `lms-api`), nên chấp nhận.

## 6. Phân việc

Thứ tự: nhóm **chặn phát hành C2** (đặt hàng, đơn của tôi) làm cùng ADR-0010 B2.2/B2.3 và ADR-0011 B-C9; **DA-3** chặn mở signup (ADR-0010 B2.1); phần còn lại làm sau.

```
DA-1 (sales) ───────────┐
DA-2 (identity) ─> DA-2L (lms-api) ─┐
                        ├─> DA-4 (customer-api, gồm phần customer-api của DA-2) ─> T-DA1..T-DA4 ─> P-DA1
DA-5 (seed, cùng B2.3) ─┘
DA-3 (circulation + lms-api) ─> T-DA5            (song song, trước khi mở signup)
DA-6 (ArchUnit) song song với DA-1/DA-4
```

| # | Ai | Việc | Phạm vi | Phụ thuộc | Mức |
|---|---|---|---|---|---|
| ⏳ DA-1 | be-dev | sales: use case shop với port `findOwnOrder`/`pageOwnOrders` (`channel = ONLINE`, `customer_user_id` từ `CurrentUser`), 404 chung; chống trùng online theo chủ (5.5); `ShopOrderView` allowlist (5.1); giá/ship tính ở sales (5.6); `myOrders` tối đa 20/trang; `StorefrontController` tối đa 50 | `services/sales-service/` (gói `ordering` phần shop, không sửa POS) | ADR-0010 B2.2 | Chặn C2 |
| ⏳ DA-2 | be-dev | identity: kênh STOREFRONT ở login/refresh, `aud`, bỏ `admin`/`platformAdmin`; migration `refresh_tokens` thêm cột kênh (⚠️ số kế tiếp) | `services/identity-service/`, `services/system-service/.../migration/identity/` | Q1 | Chặn C2 |
| ⏳ DA-2L | be-dev | lms-api: validator từ chối `aud = lms-storefront` | `lms-api/.../config` | DA-2 (hợp đồng) | Chặn C2 |
| ⏳ DA-3 | be-dev | circulation: tách `BorrowingController/get` (khóa nhân viên) và `mine/{id}` (theo `userId`); lms-api route mới; rà mọi `@AuthenticatedOnly` nhận id; ghi chú FE đổi đường dẫn | `services/circulation-service/`, `lms-api/.../circulation/**` (⚠️ đang có thay đổi chưa commit của người khác) | không | Chặn signup |
| ⏳ DA-4 | be-dev | customer-api: helper 5.8; record request allowlist + BFF dựng body (signup, orders); validate `size ≤ 50`; validator `aud` (phần của DA-2); thêm `CLIENT_REQUEST_CONFLICT` vào allowlist lỗi; record đơn hàng/liên hệ che `toString` | `customer-api/` | DA-1 (hợp đồng), DA-2 (hợp đồng) | Chặn C2 |
| ⏳ DA-5 | be-dev | Seed mẫu Khách hàng đúng allowlist khóa 5.11 + test đối chiếu | migration identity (cùng ADR-0010 B2.3) | tên khóa `ShopController` chốt | Chặn C2 |
| ⏳ DA-6 | be-dev | ArchUnit 5.9 cho `customer-api` và sales | `customer-api/src/test`, `services/sales-service/src/test` | không | Sau, trước C2 nếu kịp |
| ⏳ T-DA1 | tester | Ma trận vai trò × endpoint × dữ liệu (5.9) cho mọi route `/public/**` | | DA-1, DA-4 | Chặn C2 |
| ⏳ T-DA2 | tester | IDOR đơn: xem/hủy đơn của B bằng token A → `404` cùng thân với id không tồn tại; `clientRequestId` của A gửi bởi B → `409` không dữ liệu; `clientRequestId` không phải UUID → `400` | | DA-1 | Chặn C2 |
| ⏳ T-DA3 | tester | Tập trường JSON (không ISBN, `customerUserId`, `tenantId`, `version`, `createdBy`, số tồn...) cho sách, đơn, lỗi; mass assignment (gửi trường thừa) cho signup và đặt hàng | | DA-1, DA-4 | Chặn C2 |
| ⏳ T-DA4 | tester | Token chéo kênh: token `lms-api` ở route cần đăng nhập của `customer-api` → 401; token storefront ở `lms-api` → 401; refresh chéo kênh → 401; admin tenant đăng nhập storefront → token không `admin`; token `svc` ở `/public/t/*/orders` → 401 | | DA-2, DA-2L, DA-4 | Chặn C2 |
| ⏳ T-DA5 | tester | Khách tự đăng ký (hoặc user chỉ có role Khách hàng) gọi `GET /api/borrowings/{id}` của người khác → 403/404 | | DA-3 | Chặn signup |
| ⏳ P-DA1 | perf-tester | p95 `myOrders` với chỉ mục `(tenant_id, customer_user_id, created_at)` trên dữ liệu lớn; tốc độ cào danh mục khi `size = 50` dưới `public_read` | | DA-1 | Sau |

Phần FE (không thuộc việc của các vai trên, ghi để điều phối viên chuyển): lưu token khách trong bộ nhớ/`sessionStorage` của website, không `localStorage` dùng chung với ứng dụng nhân viên; `fetch` không `credentials` (ADR-0013); hiển thị `previewText`/`description` bằng text (ADR-0011 2.12d); đổi màn hình độc giả sang `/mine/{id}` sau DA-3.

Commit tách theo nhóm: `feat(sales):`, `feat(identity):`, `feat(lms-api):`, `feat(customer-api):`, `fix(circulation):` (DA-3, đổi khóa nên ghi rõ trong message), `test:` cho ma trận, `docs:` cho ADR-0011.

## 7. Rủi ro và việc chưa kiểm chứng

- 🚫 Toàn bộ phần đơn hàng, signup, mẫu Khách hàng chưa có code; A2 đến A5, A19 là rủi ro của **thiết kế sắp làm**, không phải lỗi đang chạy.
- ⚠️ A1 đang có hiệu lực ở C0: `POST /public/t/{code}/auth/login` đã có code và trả token đầy đủ. Chỉ khai thác được khi nhân viên/admin đăng nhập trên website của tenant và website đó có XSS.
- ⚠️ A6 đang có hiệu lực với mọi tài khoản thành viên tenant (không chỉ khách); signup làm số tài khoản như vậy tăng và không kiểm soát.
- ⚠️ DA-3 đổi khóa `BorrowingController/get` từ "chỉ đăng nhập" sang khóa: role độc giả đang dựa vào nó mất quyền tới khi gán; FE phải đổi đường dẫn. Cần thông báo khi phát hành.
- ⚠️ DA-2 đổi hợp đồng đăng nhập/refresh của identity và thêm migration identity; nhiều ADR cùng nhắm số `V17`/`V18`. Kiểm số ngay trước khi tạo.
- ⚠️ Jackson bỏ qua trường JSON lạ (mặc định Spring Boot): chưa kiểm cấu hình thật của `customer-api`, sales, identity. Nếu có service bật `FAIL_ON_UNKNOWN_PROPERTIES` thì trường thừa thành 400 (vẫn an toàn).
- ⚠️ `NotificationController.read` truyền `userId` nhưng chưa đọc `MarkNotificationReadUseCase` để xác nhận lọc theo chủ trong truy vấn.
- ⚠️ `mustChangePassword` bị bỏ khỏi phản hồi đăng nhập công khai (`AuthDtos.java:29`): khách được tạo bởi nhân viên với mật khẩu tạm sẽ nhận token không có quyền mà FE không biết lý do. Không phải lỗ dữ liệu; ghi để FE/BE xử lý (khách tự đăng ký không có cờ này theo ADR-0010).
- ⚠️ Profile dev: interceptor không được đăng ký (`PermissionAutoConfiguration.java:26`), nên test tay ở dev không chứng minh được bất kỳ quyết định nào ở tài liệu này.
- ⚠️ Chênh lệch thời gian phản hồi giữa tenant có/không tồn tại chưa đo (P-C2 ADR-0011).
- ℹ️ Lệch tài liệu và code ở 1.2 (CORS, TTL bộ đệm) thuộc ADR-0013 chưa làm.
- ℹ️ Không có atomicity xuyên DB trong các đề xuất này: mọi kiểm sở hữu nằm trong một service (sales) và một DB.

## 8. Đề xuất cập nhật ADR-0011 (không tự sửa)

- Mục mới "Phân quyền dữ liệu L2": 5.1 (DTO đơn allowlist), 5.2 (sở hữu trong truy vấn, 404), 5.5 (chống trùng theo chủ), 5.6 (request allowlist), 5.8 (helper).
- Mục mới hoặc ADR riêng "Kênh token storefront" (5.3) nếu người dùng đồng ý Q1.
- Bảng 3.4: thêm `409 CLIENT_REQUEST_CONFLICT` cho `POST /public/t/{code}/orders`.
- Mục 5: thay "⚠️ chưa kiểm nhánh `svc` khi tắt" bằng kết quả đã đối chiếu (interceptor không đăng ký ở dev).

## 9. Câu hỏi cần người dùng quyết

- **Q1.** Tách kênh token storefront (5.3 phương án C): admin/nhân viên đăng nhập ở storefront nhận token không có quyền admin; token `lms-api` không dùng ở storefront và ngược lại. Khuyến nghị **có**, làm trước khi phát hành đặt hàng.
- **Q2.** "Đơn của tôi" có gồm đơn POS mà thu ngân gán `userId` không? Khuyến nghị **chỉ đơn ONLINE** ở giai đoạn 1: đơn POS có tên/điện thoại khách vãng lai và do nhân viên gán, gán nhầm là lộ dữ liệu cho người khác.
- **Q3.** Cho khách thấy `orderNo` tuần tự (`DH-000123`, lộ quy mô đơn của cửa hàng ở mức thấp)? Khuyến nghị **có**, URL dùng UUID.
- **Q4.** Tài khoản chỉ có role Khách hàng có được đăng nhập ứng dụng nhân viên (`lms-api`) không? Khuyến nghị **chưa chặn** ở giai đoạn này nhưng bắt buộc làm DA-3 trước khi mở signup; nếu muốn chặn thì cần quyết định riêng (cờ trên role hoặc kênh).
- **Q5.** Lý do hủy do nhân viên ghi (`cancel_reason`) có hiển thị cho khách không? Khuyến nghị **không** ở giai đoạn 1 (có thể chứa ghi chú nội bộ); khách chỉ thấy trạng thái `CANCELLED`.
