# ADR-0011: `customer-api`, cổng công khai cho khách (trang giới thiệu SaaS, storefront mua sách) và context `cms`

- Trạng thái: đề xuất; phần C0 đã có code trong working tree (chưa commit, chưa kiểm chứng ở bước SA). Đề bài đã được người dùng duyệt, dùng giả định A1 đến A14 của BA.
- Cập nhật 2026-11-17: theo đề bài đã sửa (mục 0): khách vãng lai chỉ xem đầu sách và đọc preview; thêm quyết định O11 (mục 2.12), sửa 2.1, 2.4, 2.6, 2.7, 2.8, mục 3 đến 6; ghi nhận rà soát của tester (mục 2.13).
- Cập nhật 2026-11-17 (ADR-0013): CORS của `/public/**` chuyển từ nginx sang `customer-api` (CORS động theo origin của tenant); các câu "CORS do nginx" ở 2.9 không còn áp cho `/public/**`. Thêm `POST /public/t/{code}/auth/refresh|logout`, cache storefront có TTL mục tối đa 60 giây.
- Liên quan: ADR-0003 (phân quyền động), ADR-0005 (BFF `lms-api`), ADR-0006 (multi-tenant), ADR-0007 (system-service, `system_db`), ADR-0009 (module theo tenant), ADR-0010 (sales-service)
- Đề bài: `docs/requirements/2026-11-16-customer-api.md` (điểm mở O1 đến O11 ở mục 7, danh sách phải bổ sung ở 7.1). Liên hệ đọc giả: `docs/api/customer-api-contact-reader.md` (đề xuất, chưa thuộc phạm vi code).
- ℹ️ Đề bài đặt mục 4.7 trước 4.6 và 6.5 trước 6.4; ADR này tham chiếu theo số mục (4.6 = lỗi và giới hạn, 4.7 = preview và quyền vãng lai, 6.4 = dữ liệu `cms`, 6.5 = dữ liệu catalog cho preview), không theo thứ tự xuất hiện.

## 1. Bối cảnh và mục tiêu

Mở cổng công khai qua một BFF thứ hai `customer-api` (không DB) cho: (1) trang giới thiệu SaaS và form liên hệ/dùng thử; (2) storefront theo tenant: xem sách bán, đăng ký, đăng nhập, đặt/xem/hủy đơn của mình. `customer-api` không giữ nghiệp vụ tiền/tồn kho (ADR-0010).

Ràng buộc: service nội bộ không tin BFF (tự kiểm JWT, quyền, module); không atomicity xuyên DB; khóa quyền là `Controller/method`; `TenantContext` hiện chỉ đặt từ claim `tenantId` của JWT; không cho `X-Internal-Api-Key` thành đặc quyền rộng.

### 1.1. Hiện trạng đã đối chiếu trong code (nhánh `main`, gồm thay đổi chưa commit)

| | Điểm | Kết quả thật |
|---|---|---|
| ✅ | Cổng 8088 | Không có trong `docker-compose.yml`, mọi `application.yml`, `nginx.conf` (grep toàn repo, bỏ `target/`); chỉ xuất hiện trong đề bài. 8087 chỉ có trong tài liệu ADR-0010 (sales chưa có code). **Chốt 8088.** |
| ✅ | Ảnh bìa ở catalog | **Không có.** `titles` (catalog `V1`) chỉ có `id, name, author, isbn, cover_price, created_at`; không có trường ảnh nào trong `lms.catalog`. |
| ✅ | Endpoint tra cứu của catalog | Không có endpoint công khai. `TitleController` (`/internal/books`): `create`, `update`, `search` (`GET`, trả `List` không phân trang, `@CrossTenantReadable`), `get` (`/{id}`). Cập nhật 2026-11-17: **đã có `POST /internal/books/lookup`** (`TitleController/lookup`, tối đa 100 id, `TitleLookupView{id, name, author, isbn, coverPrice}`, **có ISBN**), chưa `@StorefrontAccessible`, khóa chưa seed (đăng ký động lần gọi đầu). Chưa có biến thể tra theo từ khóa. |
| ✅ | `titles` cho preview (2026-11-17) | Domain `Title`, `TitleJpaEntity`, `UpdateTitleRequest` chưa có `description`/`preview_text`. Migration catalog mới nhất `V3__platform_read_all.sql` → kế tiếp **`V4`**. |
| ✅ | sales-service (2026-11-17) | **Đã có module** (`<module>services/sales-service</module>`), migration `sales/V1__create_sales_tables.sql` (`stock_items` có `selling_price`, `active`), domain/application `stock`, `ordering`, `settings`. Chỉ có **một** controller (`SalesSettingsController`); chưa có `StockItemController`, `StorefrontController`, `ShopController`, chưa có adapter HTTP cho `CatalogLookupPort`. `ListStockItemsUseCase` ghi rõ chưa có lọc theo từ khóa. Signup `POST /internal/auth/signup`, cờ `signup_default` chưa có. Tức ADR-0010 pha 1 🔄 đang làm. |
| ✅ | C0 của ADR này (2026-11-17) | Đã có code chưa commit: module `customer-api` (nhóm `site`, `auth`, `storefront` chỉ có `GET /public/t/{code}`), `@StorefrontAccessible` + nhánh `svc` ở `PermissionInterceptor`, `IssueStorefrontTokenUseCase` ở identity, `system/V3__create_cms.sql` + context `cms`, `cms_pages`/`cms_contact_requests` trong `TENANT_EXEMPT_TABLES`, nginx vùng `public_read`/`public_form`. `SecurityConfiguration` của customer-api chưa `permitAll` `/public/t/*/books/**`, signup. |
| ✅ | Thứ tự kiểm đăng nhập ở identity | `LoginUseCase`: tìm tài khoản (không có → `UNAUTHORIZED`) → `ensureCanLogin` (`423 ACCOUNT_LOCKED`, `403 ACCOUNT_DISABLED`) **trước** khi kiểm mật khẩu và trước khi kiểm tenant → mật khẩu → tenant (`NO_TENANT_ACCESS`). Xem 2.13. |
| ✅ | Phần nền ADR-0009 | Đã có code: `@RequiresModule`, `TenantModule`, `JwtClaims.MODULES`, nhánh module trong `PermissionInterceptor`, identity `V16__tenant_modules.sql`, system `V2__add_menu_module.sql`. |
| ✅ | `PermissionInterceptor` | Thứ tự: `PUBLIC` → chưa xác thực 401 → `AUTHENTICATED` không module → `PLATFORM_ADMIN` → đọc xuyên tenant → `TENANT_REQUIRED` → đăng ký khóa → module → khóa. Thông điệp 403 có chứa tên khóa (`"Thiếu quyền: " + key`): lộ tên controller nội bộ nếu chuyển nguyên. |
| ✅ | `TenantContextFilter` | Chỉ đặt tenant từ claim `tenantId` của `JwtAuthenticationToken`; không đọc header. |
| ✅ | `CurrentUser.from` | Bắt buộc `sub` là UUID, sai thì 401. |
| ✅ | `TenantArchitectureRules` | Luật chỉ kích hoạt với entity **có cột `tenant_id`**; bảng không có cột này tự qua luật. `TENANT_EXEMPT_TABLES` hiện gồm 10 bảng. |
| ✅ | Khóa nội bộ | `X-Internal-Api-Key` chỉ được kiểm ở `InternalPermissionController` (identity); cùng một giá trị `INTERNAL_API_KEY` cho mọi service trong compose. |
| ✅ | Đăng nhập theo tenant | `LoginRequest(username, password, tenantCode)` đã có; không truy cập được tenant → `403 NO_TENANT_ACCESS` (khác mã sai mật khẩu). Phản hồi có `availableTenants`. |
| ✅ | `lms-api` | Không dùng security-common (không có `PermissionInterceptor`), chỉ xác thực JWT. `IdentityGatewayController` chuyển **toàn bộ** `/api/platform/**` sang identity. 4xx chuyển nguyên thân lỗi, 5xx thành 502. |
| ✅ | Migration | (bản 2026-11-16) identity mới nhất `V16`; system mới nhất `V2`; catalog `V3`. Bản 2026-11-17: system đã có `V3__create_cms.sql`, sales có `V1`; identity vẫn `V16`, catalog vẫn `V3`. `system/afterMigrate.sql` cấp `SELECT, INSERT, UPDATE, DELETE ON ALL TABLES` + default privileges cho `system_app`: bảng mới tự được cấp. |
| ✅ | nginx | Chỉ có vùng `login` (10r/m); `/internal/` và `/.well-known/` trả 404; `location / { return 404; }`. |

Hệ quả lớn nhất: phần storefront (danh mục, đặt hàng, signup) **phụ thuộc ADR-0010 pha 1 và pha 2** chưa làm. Phần trang giới thiệu, form liên hệ, cơ chế phiên storefront và khung `customer-api` làm được ngay (mục 4). Phần catalog của preview (cột, API nhân viên, endpoint đọc cho token dịch vụ) **không** phụ thuộc sales và làm được ngay (B-C6).

## 2. Quyết định

### 2.1. (O1) Gọi nội bộ thay khách chưa đăng nhập: token dịch vụ storefront ngắn hạn do identity cấp

| Tiêu chí | **A. Token dịch vụ theo tenant (chọn)** | B. `X-Internal-Api-Key` + header `X-Tenant-Id` tin cậy | C. Endpoint nội bộ `@PublicEndpoint`, chặn bằng mạng, use case tự `callAs` | D. Khóa riêng cho customer-api + header tenant |
|---|---|---|---|---|
| Đặt `TenantContext` | Không đổi gì: claim `tenantId` → `TenantContextFilter` → RLS | Phải sửa filter của mọi service để tin header | Mỗi use case tự `callAs`, dễ sót | Như B |
| Kiểm module SALES | Tự nhiên: token mang `modules`, `@RequiresModule` chạy như thường | Service phải gọi identity | Service phải gọi identity | Như B |
| Phạm vi đặc quyền | Token chỉ dùng được ở endpoint đánh dấu `@StorefrontAccessible`, chỉ một tenant, sống 5 phút | Khóa chung của mọi service: lộ một service là giả được mọi tenant | Ai trong mạng cũng đọc được mọi tenant | Bí mật phải phân phối tới mọi service |
| Lan sang catalog | sales chuyển tiếp nguyên token như mẫu hiện có | Phải truyền header qua mọi bước | catalog cũng phải mở công khai | Như B |
| Chi phí | Một endpoint ở identity, một nhánh trong interceptor, bộ đệm ở customer-api | Nhỏ nhưng rộng | Rải khắp nơi | Trung bình |

Chọn **A**.

- identity thêm `POST /internal/storefront/access-tokens` (`StorefrontAccessController`, `@PublicEndpoint`, `permitAll` **đúng một đường dẫn** trong `SecurityConfiguration` như `/internal/permissions/registrations`). Xác thực bằng header `X-Storefront-Client-Secret`, so sánh thời gian hằng (`MessageDigest.isEqual`) với `STOREFRONT_CLIENT_SECRET`; **bí mật riêng**, chỉ identity và customer-api có; rỗng thì từ chối mọi lời gọi (fail-closed, như `InternalPermissionController`). Không dùng `INTERNAL_API_KEY`.
- Body `{tenantCode}`. Use case (application của identity, không ở controller): tenant tồn tại **và** `ACTIVE` **và** `tenant_modules` có `SALES` bật → `200 {tenantId, code, name, accessToken, expiresIn}`; mọi trường hợp khác → `404 SHOP_NOT_AVAILABLE` (một mã).
- Claim của token: `sub` = UUID cố định dành riêng (không trùng user nào; `be-dev` chọn hằng), `svc = "customer-api"` (claim mới `JwtClaims.SERVICE`), `tenantId`, `modules`, `permissions = []`; không `admin`, không `platformAdmin`, không `roles`. TTL **5 phút** (`lms.identity.storefront.token-ttl: PT5M`). Ký bằng khóa JWT hiện có, xác minh bằng JWKS như mọi token.
- security-common: annotation mới `@StorefrontAccessible` (class/method), **cộng thêm** vào chế độ hiện có của endpoint. Nhánh mới trong `PermissionInterceptor`, ngay sau bước 401:

```
token có claim svc:
    endpoint không có @StorefrontAccessible        -> 403 FORBIDDEN   (token dịch vụ không dùng được ở đâu khác,
                                                                       kể cả @AuthenticatedOnly và @PlatformAdminOnly)
    token không có tenantId                        -> 403 TENANT_REQUIRED
    @RequiresModule(m) và modules không chứa m     -> 403 MODULE_NOT_ENABLED
    còn lại                                        -> cho qua (không đăng ký khóa, không kiểm khóa)
token người dùng: luồng cũ giữ nguyên (endpoint @StorefrontAccessible vẫn kiểm khóa Controller/method với người dùng)
```

  `@PublicEndpoint` vẫn cho mọi token qua như cũ. `TenantContextFilter`, RLS, `@TenantId` không đổi.
- Đây không phải "hằng số permission" (ADR-0003): là một chế độ truy cập như `@PlatformAdminOnly`, gắn với loại token chứ không với quyền của người dùng.
- **Danh sách đóng các endpoint `@StorefrontAccessible`** (cập nhật 2026-11-17; thêm endpoint mới vào đây phải sửa ADR này), đều chỉ đọc:

  | Service | Khóa `Controller/method` | Dùng cho |
  |---|---|---|
  | catalog | `TitleController/lookup` (đã có code, thiếu annotation) | danh sách, chi tiết |
  | catalog | `TitleController/lookupByKeyword` (`GET /internal/books/lookup?keyword=`, tối đa 200; `be-dev` đã chốt tên, B-C6) | lọc danh sách |
  | catalog | `TitleController/preview` (mới, 2.12) | preview |
  | sales | `StorefrontController/list`, `/get`, `/preview` (mới) | danh sách, chi tiết, preview |
  | sales | `StorefrontController/suggestions`, `StorefrontBannerController/list` (đề xuất, ADR-0019 2.8; chưa có code) | gợi ý tìm kiếm, banner trang chủ |

  Không bao giờ gắn lên endpoint ghi, đơn hàng (`ShopController/*`), liên hệ đọc giả, signup, hay API nhân viên (`TitleController/updatePreview`). Hệ quả: khách vãng lai **không có đường ghi dữ liệu tenant nào**; ghi công khai duy nhất là form SaaS (bảng nền tảng, 2.3). Đặt hàng và liên hệ cửa hàng dùng token người dùng (2.4).
- Tác động phân quyền: với **token người dùng**, `@StorefrontAccessible` không đổi gì (vẫn đăng ký và kiểm khóa `Controller/method`). Vì vậy `TitleController/lookup` vẫn phải nằm trong role nhân viên bán và mẫu Khách hàng (sales gọi catalog bằng token khách khi đặt hàng, ADR-0010 B2.3); `TitleController/preview`, `TitleController/updatePreview` cần gán cho role nhân viên được nhập preview (2.12). Đổi tên `TitleController` hay các method này làm role đã gán mất quyền (ADR-0003) và làm danh sách trên lệch: phải sửa ADR cùng lúc.

### 2.2. (O2) Xác định tenant theo `{code}` và đăng nhập khách

- Phân giải `code → tenant` nằm **trong cùng lời gọi cấp token** ở 2.1 (identity là chủ của `tenants`, `tenant_modules`). Không thêm API tra tenant riêng.
- customer-api giữ bộ đệm trong bộ nhớ `StorefrontAccessCache` (Caffeine, có giới hạn kích thước, ví dụ 10 000 mục): khóa = `code` đã chuẩn hóa (`trim`, chữ thường); giá trị = kết quả + token; làm mới khi token còn dưới 60 giây; **kết quả phủ định** (`SHOP_NOT_AVAILABLE`) giữ 60 giây. Nhiều request trượt cùng mã gộp thành một lời gọi (`Cache.get(key, loader)`). identity lỗi/chết → `503 SERVICE_UNAVAILABLE`, không đệm lỗi.
- `code` sai định dạng (đề xuất `^[a-z0-9-]{2,50}$`, `be-dev` đối chiếu với luật mã tenant hiện có) → `404 SHOP_NOT_AVAILABLE` ngay, không gọi identity, không đưa vào bộ đệm.
- `GET /public/t/{code}` trả `{code, name}` lấy thẳng từ kết quả trên, không gọi thêm.
- **Đăng nhập khách đi qua customer-api**, không qua `lms-api`: `POST /public/t/{code}/auth/login` → (phân giải storefront trước) → identity `POST /internal/auth/login` với `tenantCode = {code}` lấy từ đường dẫn (bỏ qua mọi `tenantCode` trong body). `POST /public/auth/refresh`, `POST /public/auth/logout` chuyển tới identity. Lý do: FE storefront chỉ phụ thuộc một BFF, CORS/giới hạn tốc độ riêng, ép tenant theo đường dẫn, và **lọc phản hồi**: chỉ trả `{accessToken, refreshToken, expiresIn}`, bỏ `availableTenants` (không lộ khách đang là thành viên tenant nào khác). Không mở `switch-tenant` ở customer-api.
- Sai mật khẩu và `NO_TENANT_ACCESS` (tài khoản không thuộc tenant này) cùng trả `401 LOGIN_FAILED` ở customer-api, để không xác nhận được mật khẩu đúng của tài khoản ở tenant khác. Tài khoản bị khóa giữ mã riêng như identity hiện có. ⚠️ Cập nhật 2026-11-17: tester chỉ ra việc giữ mã riêng cho phép dò trạng thái khóa của tài khoản bất kỳ tenant nào; khuyến nghị đổi sang gộp, chờ người dùng xác nhận (2.13, Q4).
- Đã loại: dùng `lms-api /api/auth/login` (FE storefront phụ thuộc BFF nhân viên, lộ `availableTenants`); thêm `GET /internal/tenants/by-code/{code}` công khai trong mạng (vẫn cần cơ chế đặt tenant cho lời gọi kế tiếp, tức quay về 2.1).

### 2.3. (O3) Bảng `cms` không có tenant: bảng nền tảng, không cột `tenant_id`, không RLS, ghi vào `TENANT_EXEMPT_TABLES`

| Tiêu chí | **a. Không `tenant_id`, không RLS, liệt kê ngoại lệ (chọn)** | b. `tenant_id` của "tenant nền tảng" + RLS |
|---|---|---|
| Khớp mô hình | Đúng như `tenants`, `organizations`, `permissions`: dữ liệu cấp nền tảng | Không có khái niệm tenant nền tảng; phải bịa một UUID |
| Platform admin (token không `tenantId`) | Đọc/ghi trực tiếp | Mọi use case phải `callAs(tenantNềnTảng)` |
| Endpoint công khai không có tenant | Ghi trực tiếp | Như trên |
| ArchUnit | Luật không kích hoạt (không có cột `tenant_id`) | Phải `@TenantId` |

Chọn **a**. Thêm `cms_pages`, `cms_contact_requests` vào `TenantArchitectureRules.TENANT_EXEMPT_TABLES` dù luật không bắt buộc, để danh sách là sổ đăng ký bảng toàn cục có chủ đích (khớp tiêu chí nghiệm thu của đề bài) và nếu sau này ai thêm cột `tenant_id` thì phải quyết định lại. Danh sách bảng toàn cục trong ADR-0006 (Quyết định 7) đã được bổ sung `cms_pages`, `cms_contact_requests` ngày 2026-11-17 (tester phát hiện tài liệu thiếu; code `TENANT_EXEMPT_TABLES` đã có). Bảo vệ dữ liệu bằng tầng ứng dụng: admin `@PlatformAdminOnly`; công khai chỉ đọc cột đã xuất bản và chỉ tạo yêu cầu liên hệ. Không native query.

### 2.4. (O4) Phân quyền phần cần đăng nhập: chỉ sales kiểm khóa, customer-api chỉ xác thực

| Tiêu chí | **A. customer-api chỉ xác thực JWT + kiểm tenant khớp; sales kiểm khóa (chọn)** | B. customer-api cũng chạy `PermissionInterceptor` với khóa riêng |
|---|---|---|
| Nguồn ma trận quyền | Một (ADR-0005) | Hai: role Khách hàng phải có cả `CustomerShopController/*` lẫn `ShopController/*`; lệch là 403 khó hiểu |
| Seed | Không thêm khóa | Thêm khóa, thêm test đối chiếu cho service không DB |
| Bảo mật | sales vẫn kiểm khóa, module, sở hữu | Thêm một lớp nhưng không thêm bảo đảm (sales đã kiểm) |

Chọn **A**, khớp `lms-api`. Đây là điểm **khác dòng "Khóa động ... ở customer-api" trong bảng mục 5 của đề bài**; đề bài để O4 cho sa quyết. Tiêu chí "thiếu khóa 403" vẫn đạt (sales trả). customer-api làm thêm hai kiểm tra rẻ, không thay sales:

- Token có `svc` (token dịch vụ lọt ra) → `401`.
- `tenantId` của token khác tenant đã phân giải từ `{code}` (kể cả token không có `tenantId`) → `403 SHOP_SESSION_MISMATCH`. Không lộ gì vì chỉ chạy sau khi `{code}` đã được xác nhận là storefront khả dụng (thông tin vốn công khai).

Cập nhật 2026-11-17 (đề bài 4.7, 5):

- Tập đường dẫn công khai ở customer-api là **danh sách đóng** (`permitAll` liệt kê từng mẫu, không `/public/t/*/**`): `GET /public/site/**`, `POST /public/site/contact-requests`, `GET /public/t/*`, `GET /public/t/*/books`, `GET /public/t/*/books/*`, `GET /public/t/*/books/*/preview`, `POST /public/t/*/auth/{signup,login}`, `POST /public/auth/{refresh,logout}`. Mọi `/public/**` khác cần đăng nhập: không token → `401` (đã có ở `SecurityConfiguration`).
- Nhóm cần đăng nhập: đơn hàng (`/public/t/*/orders/**`) và liên hệ cửa hàng (`/public/t/*/contact-requests`, khi chốt `customer-api-contact-reader.md`). Cùng hai kiểm tra trên (`ServiceTokenRejectionFilter` chặn token `svc`, `ShopSessionGuard` so tenant); chuyển tiếp **token người dùng**; endpoint nhận ở service phía sau **không** gắn `@StorefrontAccessible`, nên kể cả token `svc` lọt qua BFF cũng bị interceptor trả `403 FORBIDDEN`.
- Đọc công khai (danh sách, chi tiết, preview) luôn dùng token `svc`, kể cả khi request có header `Authorization` của khách (bỏ qua header như 2.9): nội dung công khai không phụ thuộc người xem, và mẫu Khách hàng không cần khóa đọc preview.

### 2.5. (O5) Chống dò tenant

- Một mã duy nhất `404 SHOP_NOT_AVAILABLE`, cùng thân `{code, message}` cố định cho: mã sai định dạng, không tồn tại, bị ngưng, chưa bật SALES. Mọi đường dẫn `/public/t/{code}/**` (kể cả signup, login, orders) **phân giải storefront trước**, nên các bước sau không bao giờ chạy với tenant không khả dụng.
- Thời gian phản hồi: kết quả dương và phủ định đều được đệm (2.2), lần trượt đầu đều đi một vòng tới identity; trong identity, use case luôn đọc `tenants` theo mã rồi mới kiểm trạng thái/module (chênh lệch một truy vấn nhỏ, ⚠️ đo ở P-C2).
- Bộ đệm có giới hạn kích thước nên mã ngẫu nhiên không làm phình bộ nhớ; nginx `limit_req` chặn quét hàng loạt.
- Signup: identity vẫn trả `403 SIGNUP_NOT_AVAILABLE` khi tenant không có role `signup_default` (chỉ lộ "storefront này tắt tự đăng ký", vốn hợp lệ). Trùng tên đăng nhập toàn hệ thống vẫn lộ tên đã tồn tại (rủi ro sẵn có của ADR-0010, giảm bằng giới hạn tốc độ).

### 2.6. (O6) Danh mục công khai: sales ghép và trả DTO công khai; customer-api không ghép

| Tiêu chí | **A. sales có endpoint storefront, tự gọi catalog theo lô (chọn)** | B. customer-api gọi sales (giá, còn hàng) rồi catalog (tên, tác giả) và ghép |
|---|---|---|
| Giá hiệu lực `sellingPrice ?? coverPrice` | Tính ở sales (đúng ADR-0010: giá tính ở BE) | `coverPrice` nằm ở catalog: BFF phải tính giá, ADR-0010 đã loại |
| Số tồn chính xác | Không bao giờ rời sales (chỉ trả `inStock`) | sales phải trả `available` cho BFF rồi BFF giấu |
| Số lần gọi mỗi trang | customer-api → sales (1) → catalog (1 lô) | customer-api → sales (1) + catalog (1) |
| Phụ thuộc | customer-api chỉ gọi identity, sales, system | Thêm catalog |

Chọn **A**.

- sales thêm `StorefrontController` (`@RequiresModule(SALES)`, `@StorefrontAccessible`): `GET /internal/sales/storefront/items` (query record `{keyword?, page, size ≤ 50}`), `GET /internal/sales/storefront/items/{titleId}`, và (2.12) `GET /internal/sales/storefront/items/{titleId}/preview`. Chỉ mặt hàng đã "đưa vào bán" và đang hoạt động (`stock_items.active`); `inStock = available > 0` tính ở sales. Không trả `onHand`, `reserved`, `available`, giá nhập, ngưỡng, `tenant_id`, ISBN.
- DTO (cập nhật 2026-11-17, thay bộ trường cố định cũ `{titleId, name, author, price, inStock}`):

  | Endpoint sales | Trường trả | Không bao giờ có |
  |---|---|---|
  | danh sách | `{titleId, name, author, price (long, VND), inStock, hasPreview}` | `description`, `previewText` |
  | chi tiết | như danh sách + `description` (null nếu chưa nhập) | `previewText` |
  | preview | `{titleId, name, previewText}` | mọi trường khác |

  Preview **không đi qua danh sách hay chi tiết**; chỉ có endpoint preview từng sách.
- Không N+1: một truy vấn trang `stock_items` + **một** lời gọi lô tới catalog (`TitleController/lookup`, tối đa 100 id), dùng nguyên token dịch vụ chuyển tiếp (mẫu `BearerTokens.current()`). Chi tiết dùng cùng `lookup` với một id (không mở `TitleController/get` cho token `svc`: trả ISBN, tenant). `TitleLookupView` thêm `description` và `hasPreview` (cộng thêm, không phá hợp đồng với người gọi hiện có), **không** thêm `previewText` (đề bài 6.5: tránh kéo tối đa 100 × 2000 ký tự cho mỗi trang). `lookup` vẫn trả ISBN cho sales (người gọi nội bộ); sales không chuyển ra ngoài.
- Lọc theo từ khóa (tên/tác giả nằm ở catalog): catalog bổ sung biến thể tra theo từ khóa trả **tối đa 200 id** (cùng `TitleController/lookup` với tham số `keyword`, hoặc method riêng; `be-dev` chốt tên rồi không đổi); sales lấy giao với mặt hàng đang bán rồi phân trang. Cùng cơ chế dùng cho `StockItemController/search` (ADR-0010 chưa nói cách lọc từ khóa). Endpoint tra của catalog có thêm `@StorefrontAccessible` (người dùng vẫn cần khóa như cũ).
- Đã loại thêm: bản sao tên/tác giả trong `stock_items` để tìm kiếm (trái quyết định "không bản sao đầu sách" của ADR-0010, lệch khi catalog đổi tên vì chưa có event `TitleUpdated`); mở `TitleController/search` hiện có cho token dịch vụ (không phân trang, trả `coverPrice`, có `@CrossTenantReadable`).
- Không đệm danh mục ở server giai đoạn này: customer-api đặt `Cache-Control: public, max-age=30` cho danh mục/chi tiết sách; đo ở P-C1 rồi mới quyết thêm bộ đệm. `inStock` cũ tối đa 30 giây là chấp nhận được vì đặt hàng luôn kiểm lại tồn (`409 INSUFFICIENT_STOCK`).

### 2.7. (O7) Tải và lạm dụng

nginx (vùng theo `$binary_remote_addr`):

| Vùng | Áp cho | Mức đề xuất |
|---|---|---|
| `public_read` (mới) | mọi `/public/**` còn lại | `20r/s`, `burst=40 nodelay` |
| `public_form` (mới) | `POST /public/site/contact-requests`, `POST /public/t/*/auth/signup` | `5r/m`, `burst=3 nodelay` |
| `login` (sẵn có) | `POST /public/t/*/auth/login` | như `/api/auth/login` |
| `public_preview` (mới, 2.12) | `GET /public/t/*/books/*/preview` | `30r/m`, `burst=10 nodelay` (nghiêm hơn `public_read`) |

- Không dùng `location ^~ /public/` nếu có location regex cho signup/login (`^~` chặn việc xét regex); dùng `location /public/` thường và các location regex. ✅ Theo rà soát của tester (2.13), regex phải chấp nhận biến thể `/` cuối và tham số đường dẫn `;x=1`, mẫu đang dùng trong `nginx.conf`: `"^/public/t/[^/]+/auth/login(?:[/;].*)?$"`. Location preview theo cùng mẫu: `"^/public/t/[^/]+/books/[^/]+/preview(?:[/;].*)?$"`.
- `client_max_body_size 16k` trong các location `/public/`.
- IP thật: nginx đang là biên, `$binary_remote_addr` đúng. ⚠️ Nếu sau này đặt sau load balancer/CDN thì phải cấu hình `set_real_ip_from` + `real_ip_header`, nếu không mọi khách chung một IP và bị 429 hàng loạt.
- Form liên hệ có trường honeypot `website` (ẩn ở FE): có giá trị thì customer-api trả `201` như thường nhưng không chuyển tới system-service, chỉ tăng bộ đếm metric (không log nội dung). Không captcha (A6).
- Kích thước trang tối đa 50 ở cả customer-api (validate) lẫn sales.

### 2.8. (O8) Ảnh bìa, mã tham chiếu, mô tả và preview trong DTO công khai

- catalog **không có** ảnh bìa (mục 1.1). Giai đoạn 1 DTO công khai **không có trường ảnh** (thêm trường sau là thay đổi không phá hợp đồng); không thêm upload ảnh (A4).
- `bookRef` = `titleId` (UUID). Đã loại mã công khai riêng: UUID ngẫu nhiên không dò được, RLS chặn xuyên tenant, và khách vẫn phải gửi `titleId` khi đặt hàng; thêm mã riêng cần cột, chỉ mục duy nhất và ánh xạ hai chiều mà không thêm an toàn.
- ISBN không lộ (A5).
- Cập nhật 2026-11-17 (thay câu "danh sách trường cố định" cũ): DTO công khai của customer-api là

  | Endpoint | Trường (đúng tập này, tester so tập tên trường) |
  |---|---|
  | `GET /public/t/{code}/books` (mỗi mục) | `bookRef, title, author, price, inStock, hasPreview` |
  | `GET /public/t/{code}/books/{bookRef}` | `bookRef, title, author, price, inStock, hasPreview, description` |
  | `GET /public/t/{code}/books/{bookRef}/preview` | `bookRef, title, previewText` |

  `description` luôn có khóa, giá trị `null` nếu chưa nhập. `hasPreview = previewText đã nhập` (sau chuẩn hóa, chuỗi rỗng là chưa có). Định dạng của `description` và `previewText`: **văn bản thuần** (2.12). Vẫn không ISBN, không số tồn, không giá vốn, không `tenant_id`.

### 2.9. (O9) Hạ tầng

| Nơi | Thay đổi |
|---|---|
| Maven | Module mới `customer-api/` ở gốc (cạnh `lms-api`), artifactId `customer-api`, package `lms.customer`; thêm `<module>customer-api</module>` vào `pom.xml` gốc. Phụ thuộc: `common-kernel`, `observability-common`, starter `web`, `validation`, `security`, `oauth2-resource-server`, `actuator`, `caffeine`; test: `spring-boot-starter-test`, `spring-security-test`, `archunit-junit5`. **Không** JPA, JDBC, Kafka, `security-common`, `tenant-common`. |
| Cấu trúc | Như `lms-api` (BFF không có domain): `lms.customer.{config,web}` và theo nhóm `site`, `storefront`, `auth`, `shop`, mỗi nhóm `client` (interface + `http`), `dto`, `web`. |
| `application.yml` | `server.port: 8088`; `app.services.{identity,sales,system}-url` (`IDENTITY_SERVICE_URL`, `SALES_SERVICE_URL`, `SYSTEM_SERVICE_URL`), timeout ngắn (connect 2s, read 5s); `jwk-set-uri` (`JWT_JWK_SET_URI`); `app.storefront.client-secret: ${STOREFRONT_CLIENT_SECRET:}`; actuator chỉ `health,info`. |
| Security | JWT resource server qua JWKS. `permitAll` có danh sách: `GET /public/site/**`, `POST /public/site/contact-requests`, `GET /public/t/*`, `GET /public/t/*/books`, `GET /public/t/*/books/*`, `GET /public/t/*/books/*/preview` (cập nhật 2026-11-17: liệt kê từng mẫu thay cho `/books/**`, để tập endpoint vãng lai là danh sách đóng), `POST /public/t/*/auth/{signup,login}`, `POST /public/auth/{refresh,logout}` (bỏ qua header `Authorization` ở các đường dẫn này, như `lms-api`); `/public/t/*/orders/**` authenticated; health; **còn lại `denyAll`** (gồm `/internal/**`, `/api/**`). CORS do nginx. |
| Lỗi | `PublicExceptionHandler`: chỉ chuyển các mã nghiệp vụ trong **danh sách cho phép** (`INSUFFICIENT_STOCK`, `ORDER_NOT_FOUND`, `ORDER_STATE_CONFLICT`, `TITLE_NOT_AVAILABLE`, `BOOK_NOT_FOUND` (danh sách, chi tiết, preview; đã có trong code), `SIGNUP_NOT_AVAILABLE`, mã trùng tài khoản, `VALIDATION_FAILED`, `CMS_PAGE_NOT_FOUND`...), **thông điệp do customer-api viết** (không chép thông điệp nội bộ, vì `FORBIDDEN` của interceptor chứa tên khóa); bỏ `details.available` của `INSUFFICIENT_STOCK` (không lộ số tồn). `MODULE_NOT_ENABLED`, `TENANT_REQUIRED` từ phía sau → `404 SHOP_NOT_AVAILABLE`; `FORBIDDEN` → `403 FORBIDDEN`; 4xx khác → `400 REQUEST_REJECTED`; 5xx và mất kết nối → `503 SERVICE_UNAVAILABLE`. Không log thân request. |
| Dockerfile | `customer-api/Dockerfile` theo mẫu `lms-api/Dockerfile` (`-pl customer-api -am`, `EXPOSE 8088`). |
| Compose | Service `customer-api` (profile `apps`, `expose: "8088"`, không `ports`), env: `JAVA_TOOL_OPTIONS`, `IDENTITY_SERVICE_URL`, `SALES_SERVICE_URL` (khi sales có), `SYSTEM_SERVICE_URL`, `JWT_JWK_SET_URI`, `STOREFRONT_CLIENT_SECRET: ${STOREFRONT_CLIENT_SECRET:-dev-storefront-secret}`; `depends_on` identity, system (thêm sales khi có). identity thêm `STOREFRONT_CLIENT_SECRET`. nginx thêm `depends_on: customer-api`. Production bắt buộc đặt bí mật riêng. |
| nginx | `set $customer_api http://customer-api:8088;`, hai vùng mới, các location ở 2.7 (giữ chặn `/internal/`, `/.well-known/` ở trước); không route `/actuator`. CORS: dùng chung `map $cors_origin`, thêm origin storefront khi có (bearer token, không cookie nên rủi ro thấp). |
| lms-api | API quản trị CMS ở `/api/cms/**` (không dùng `/api/platform/**` vì `IdentityGatewayController` chuyển nguyên tiền tố đó sang identity); `SystemClient` thêm phương thức; DTO riêng. |
| ArchUnit customer-api | Không phụ thuộc `jakarta.persistence..`, `org.springframework.jdbc..`, `org.springframework.kafka..`, `lms.api..`, `lms.sales..`, `lms.catalog..`; `dto` không phụ thuộc `client.http`; nhóm này không dùng `web` của nhóm khác. |
| Tài liệu | README (bảng service/cổng: `customer-api` 8088; mục BFF), CLAUDE.md (lệnh chạy, cổng, "FE storefront gọi `/public/**`"), ADR-0005 (dòng trỏ: có BFF thứ hai), ADR-0006 (bảng toàn cục `cms_*`), ADR-0010 (mẫu Khách hàng cần khóa `TitleController/lookup`, mục 6 của ADR này). |

### 2.10. (O10) Lưu giữ dữ liệu form liên hệ

- Mặc định **12 tháng** kể từ `created_at`, cấu hình cấp nền tảng `lms.system.cms.contact-retention: P365D` (không theo tenant vì dữ liệu không thuộc tenant). Áp cho **mọi** trạng thái, kể cả `NEW` chưa xử lý (tối thiểu hóa dữ liệu).
- Ẩn danh, không xóa dòng: `full_name`, `email`, `phone`, `message` → NULL, ghi `anonymized_at`; giữ `organization`, `business_type`, `status`, mốc thời gian để thống kê.
- Job `@Scheduled` hằng ngày trong system-service (`cms.infrastructure`, tắt được bằng cấu hình) gọi `AnonymizeExpiredContactRequestsUseCase`, cập nhật theo lô (500 dòng/transaction), idempotent (chạy chồng nhiều instance chỉ cập nhật 0 dòng). Không cần `TenantContext` (bảng không RLS). Ghi log `platform-audit operation=contact-requests-anonymized count=...` (không PII), đúng tiền lệ ADR-0006.
- Platform admin ẩn danh ngay một yêu cầu khi người gửi đòi xóa: `POST /internal/cms/contact-requests/{id}/anonymization` (cùng phương thức domain với job). Đã loại: xóa hẳn dòng (mất thống kê); thời hạn theo trạng thái (thêm cấu hình mà chưa có nhu cầu).

### 2.11. Nội dung CMS và chống XSS

- Lưu Markdown thô. **Hai bản trong một dòng**: cột làm việc (`title`, `body_markdown`) và cột đã xuất bản (`published_title`, `published_body_markdown`, `published_at`). Xuất bản = chép bản làm việc sang bản xuất bản; gỡ = xóa bản xuất bản. Truy vấn công khai **chỉ đọc cột `published_*` và `published_at IS NOT NULL`**, nên bản nháp không thể lộ kể cả khi đang sửa trang đã xuất bản. Trạng thái hiển thị cho admin suy ra: `DRAFT`, `PUBLISHED`, `PUBLISHED_WITH_CHANGES`.
- Đầu ra công khai là HTML do **system-service render** bằng `commonmark-java` với `escapeHtml(true)` (mọi HTML thô trong Markdown thành văn bản) và `sanitizeUrls(true)` (bỏ `javascript:` và scheme lạ trong link/ảnh). Render lúc đọc (trang ít, customer-api đệm 60 giây). Thư viện chỉ dùng trong `cms.infrastructure` sau một port ở application.
- Đã loại: trả Markdown cho FE tự render (an toàn phụ thuộc FE); OWASP HTML Sanitizer (thêm thư viện thứ hai, không cần khi đã escape toàn bộ HTML thô); chỉ văn bản thuần (không đủ cho trang giới thiệu có tiêu đề/liên kết).

### 2.12. (O11) Preview và mô tả ngắn của đầu sách (thêm 2026-11-17)

Người dùng đã chốt: `preview_text` tối đa 2000 ký tự, `description` tối đa 500 ký tự, cả hai tùy chọn, văn bản thuần, lưu trong `titles` của catalog; vãng lai xem danh sách, chi tiết, preview; giới hạn theo IP ở nginx; không API preview theo lô.

**a. Tách endpoint preview** (chọn) thay vì gộp vào chi tiết.

- Chọn `GET /public/t/{code}/books/{bookRef}/preview`. Lý do: chi tiết giữ nhẹ (≤ 500 ký tự mô tả); vùng `limit_req` riêng chỉ áp được khi có đường dẫn riêng; `Cache-Control` riêng; tester kiểm "không đường nào trả nhiều preview" bằng tập đường dẫn.
- Đã loại: gộp `previewText` vào chi tiết (mọi lần xem chi tiết kéo 2000 ký tự; muốn chống cào preview phải siết luôn chi tiết, làm hỏng trải nghiệm duyệt sách).

**b. Ai kiểm "đang bán" và ghép**: sales kiểm rồi gọi catalog (chọn).

| Tiêu chí | **A. sales kiểm `stock_items` đang bán rồi gọi catalog (chọn)** | B. catalog trả thẳng cho customer-api | C. customer-api hỏi sales "đang bán?" rồi gọi catalog |
|---|---|---|---|
| Quy tắc "chỉ sách đang hiển thị" (4.7) | Ở sales, chủ của "đưa vào bán", cùng điều kiện với danh sách | catalog không biết "đang bán": lộ preview sách chưa bán, sai 4.7 | Quy tắc nằm ở BFF (trái nguyên tắc BFF không giữ nghiệp vụ) |
| Phụ thuộc của customer-api | Giữ identity, sales, system (2.6) | Thêm catalog | Thêm catalog |
| Số lời gọi | customer-api → sales → catalog (1 id) | 1 | 2 nối tiếp từ BFF |

  Luồng: sales đọc `stock_items` theo `titleId` (transaction đọc ngắn); không có hoặc `active = false` → `404 BOOK_NOT_FOUND` **không gọi catalog**; có thì gọi catalog `GET /internal/books/{id}/preview` **ngoài transaction** bằng token `svc` chuyển tiếp; catalog `404` hoặc `previewText` null → `404 BOOK_NOT_FOUND` (cùng thân). Chênh lệch thời gian giữa "không bán" và "đang bán nhưng chưa có preview" chỉ lộ việc sách đang bán, vốn công khai qua danh sách.

**c. API catalog và API nhân viên**: endpoint riêng cho preview (chọn) thay vì mở rộng `PUT /books/{id}`.

| Tiêu chí | **A. `PUT /books/{id}/preview` riêng (chọn)** | B. Thêm `description`, `previewText` vào body `PUT /books/{id}` hiện có |
|---|---|---|
| Quyền riêng (đề bài 4.7: "quyền riêng theo khóa động") | Khóa riêng `TitleController/updatePreview`: giao nhập preview cho người không sửa được giá bìa/ISBN | Chung khóa `TitleController/update` |
| Ngữ nghĩa PUT | Thay đúng hai trường; null/rỗng = xóa, rõ ràng | Client cũ (FE hiện tại) không gửi hai trường → hoặc xóa mất preview, hoặc phải đặt luật "null = giữ nguyên" mâu thuẫn với "null = xóa" |
| Tải `GET /books` (search, không phân trang) | Không đổi | Muốn sửa phải đọc được: dễ kéo preview vào `TitleResponse` của search |

  Hợp đồng (`be-dev` chốt chi tiết, tên method chốt rồi không đổi vì là khóa quyền):

  | Lớp | Endpoint | Khóa / chế độ | Vào / ra |
  |---|---|---|---|
  | catalog | `GET /internal/books/{id}/preview` | `TitleController/preview`, **`@StorefrontAccessible`**, không `@RequiresModule` (catalog lõi, như `lookup`) | ra `{id, name, description, previewText}` (null nếu chưa nhập); không ISBN, `coverPrice`, `tenantId`; `404 TITLE_NOT_FOUND` (gồm đầu sách của tenant khác, RLS) |
  | catalog | `PUT /internal/books/{id}/preview` | `TitleController/updatePreview`, **không** `@StorefrontAccessible` | vào `{description?, previewText?}` (thay cả hai; null/rỗng = xóa); ra như GET; `400 TITLE_PREVIEW_TOO_LONG` (exception domain mới; không dùng `InvalidTitleException` vì handler hiện ánh xạ nó thành `422 INVALID_TITLE`, còn đề bài 8 yêu cầu `400`), `400` lỗi validate hiện có của catalog (chặn thô), `404 TITLE_NOT_FOUND` |
  | lms-api | `GET`, `PUT /api/books/{id}/preview` | lms-api chỉ xác thực (ADR-0005) | ánh xạ 1-1; `CatalogClient` + DTO riêng |

  Nhân viên cần cả hai khóa để mở màn hình sửa. Token `svc` và token khách gọi `updatePreview` → `403` (không annotation, khóa không gán cho mẫu Khách hàng). Ghi cuối thắng (không cột `version`, A12 không có phiên bản); chấp nhận vì một trường văn bản do ít người sửa.
- Seed khóa: thêm `TitleController/preview`, `TitleController/updatePreview` vào mẫu role Quản lý cửa hàng và Thủ kho của SALES (ADR-0010 B2.3; nếu B2.3 đã nhập thì migration identity riêng, số kế tiếp hiện là `V17`, ⚠️ kiểm ngay trước khi tạo; chèn vào `permissions` trước như mẫu ADR-0010). Role đã có của tenant hiện hữu không tự có khóa: tenant admin gán tay; quyền mới chỉ hiệu lực ở lần đăng nhập/refresh kế tiếp (ADR-0003). **Không** gán cho mẫu Khách hàng.

**d. Văn bản thuần** (chọn) thay vì Markdown.

- Lưu và trả **nguyên văn** (không escape HTML ở server, vì escape ở server buộc FE dùng `innerHTML` và dễ escape hai lần). Hợp đồng FE: hiển thị như text (`textContent`/mặc định của framework), xuống dòng bằng CSS `white-space: pre-wrap` (đã thống nhất với tài liệu hợp đồng FE, trước đây ghi `pre-line`); tuyệt đối không `innerHTML`. Ở API: JSON `application/json` + `X-Content-Type-Options: nosniff` (đã có trong `snippets/common.conf`), nên `<script>` trong chuỗi không thực thi khi mở thẳng URL.
- Chuẩn hóa ở domain `Title` (không ở controller): chuẩn hóa Unicode NFC (văn bản tiếng Việt dạng tổ hợp đếm ra nhiều ký tự hơn), `\r\n` → `\n`, bỏ ký tự điều khiển trừ `\n` và `\t`, cắt khoảng trắng đầu/cuối, rỗng → `null`. Đếm độ dài bằng **code point** (khớp `VARCHAR(n)` của PostgreSQL đếm theo ký tự); không đặt `@Size` 2000/500 ở request vì `@Size` đếm theo UTF-16 (emoji tính 2) sẽ từ chối sai chuỗi hợp lệ; chỉ đặt chặn thô `@Size(max = 8000)`/`(max = 2000)` để chặn thân quá lớn.
- Đã loại: Markdown render bằng `commonmark` như CMS (thêm thư viện và cấu hình an toàn vào catalog, phải chọn nơi render; nhu cầu chỉ là một đoạn trích, A12); escape HTML ở server (lý do trên); từ chối ký tự `<`, `>` (sách có thể chứa các ký tự này).

**e. Dữ liệu catalog**: migration `catalog/V4__titles_description_preview.sql` (✅ hiện catalog mới nhất `V3`; ⚠️ kiểm lại ngay trước khi tạo): `ALTER TABLE titles ADD COLUMN description VARCHAR(500)`, `ADD COLUMN preview_text VARCHAR(2000)`, cả hai NULL được, CHECK `btrim(...) <> ''` khi khác NULL (giữ `hasPreview` nhất quán nếu ai chèn tay). Chỉ thêm cột, không ghi dữ liệu nên không cần `app.tenant_id`; RLS, chính sách và `@TenantId` của `titles` giữ nguyên; quyền cấp ở mức bảng trong `afterMigrate.sql` phủ cột mới. Giới hạn ở DB bảo đảm "không đường nào trả dài hơn giới hạn" kể cả khi chèn thẳng DB.

**f. Chống cào** (đề bài 4.7: chỉ làm chậm, không ngăn tuyệt đối):

- Không có endpoint preview theo lô ở mọi lớp: customer-api một sách, sales một `titleId`, catalog một `id`; `lookup` không trả `previewText`; danh sách và chi tiết không chứa preview.
- nginx vùng `public_preview` `30r/m`, `burst=10 nodelay` theo IP (khoảng 1 preview mỗi 2 giây kéo dài; người đọc thật không chạm ngưỡng). Đã loại `1r/s` (cào 86 400 preview/ngày/IP) và giới hạn theo tài khoản (A13: vãng lai không có tài khoản).
- `Cache-Control: public, max-age=60` cho preview (đổi nội dung hoặc gỡ khỏi bán hiện ra chậm tối đa 60 giây, cộng 5 phút của token, 2.1). Không đệm ở server.
- `bookRef` là UUID ngẫu nhiên, không duyệt tuần tự được; muốn cào phải đi qua danh sách (vùng `public_read`).

### 2.13. Ghi nhận rà soát của tester (2026-11-17)

- ✅ nginx: location regex cho form, signup, login trước đây chỉ khớp đúng đường dẫn, biến thể `/` cuối hoặc `;x=1` lọt sang `location /public/` (vùng `public_read`, nhẹ hơn). Đã sửa thành `(?:[/;].*)?$` trong `nginx.conf`. Mẫu này áp cho mọi location giới hạn tốc độ mới (gồm `public_preview`).
- 🔄 Markdown CMS lồng sâu (danh sách/trích dẫn lồng nhiều tầng làm tốn CPU/stack khi render): đã giới hạn độ sâu, đang sửa ở system-service (`cms.infrastructure.markdown`). Chờ tester chạy lại `CmsAdversarialIT`.
- ⚠️ Còn mở: dò trạng thái khóa tài khoản qua `POST /public/t/{code}/auth/login`. identity kiểm `ensureCanLogin` **trước** mật khẩu và trước tenant (1.1), nên với bất kỳ tên đăng nhập nào, chỉ cần một `{code}` storefront khả dụng là biết được tài khoản tồn tại và đang `ACCOUNT_LOCKED`/`ACCOUNT_DISABLED`, kể cả tài khoản thuộc tenant khác và không cần mật khẩu. Kết hợp với khóa sau N lần sai, kẻ tấn công cũng có thể chủ động khóa tài khoản rồi kiểm tra.

  | Phương án | Ưu | Nhược |
  |---|---|---|
  | **A. customer-api gộp `ACCOUNT_LOCKED`, `ACCOUNT_DISABLED` vào `401 LOGIN_FAILED` (khuyến nghị)** | Đóng cửa dò ở cổng công khai cho khách; sửa nhỏ, chỉ trong `HttpAuthClient` (thêm hai mã vào `LOGIN_FAILURE_CODES`) và bỏ hai mục khỏi danh sách cho phép của `PublicExceptionHandler`; identity, lms-api không đổi | Khách bị khóa thật không biết lý do: thông điệp chung nên gợi ý "thử lại sau ít phút hoặc liên hệ cửa hàng" |
  | B. Giữ mã riêng (quyết định gốc 2.2) | Trải nghiệm rõ ràng | Dò được trạng thái khóa xuyên tenant từ internet |
  | C. Sửa identity: khi khóa/vô hiệu trả cùng mã với sai mật khẩu cho mọi kênh | Đóng cả `/api/auth/login` | Đổi hành vi màn hình nhân viên, ngoài phạm vi ADR này; cần quyết định riêng |

  Khuyến nghị **A** ngay. ℹ️ Cửa tương tự ở `lms-api` `/api/auth/login` có từ trước, nằm ngoài phạm vi; ghi cho người dùng quyết (Q4).

## 3. Thiết kế

### 3.1. Thành phần và luồng

```
Trình duyệt ──/public/**──> nginx 8080 (limit_req, chặn /internal) ──> customer-api 8088 (không DB)
                                                                        │  StorefrontAccessCache (Caffeine)
     ┌──────────────────────────────────────────────────────────────────┼───────────────────────────────┐
     │ POST /internal/storefront/access-tokens (X-Storefront-Client-Secret)                             │
     ▼                                                                  ▼                                ▼
identity 8081                                       sales 8087 /internal/sales/storefront/**     system 8086 /internal/cms/public/**
 (code → tenant ACTIVE+SALES, ký token svc 5')       /internal/sales/shop/** (token khách)        (@PublicEndpoint, bảng cms_* không RLS)
 /internal/auth/{login,refresh,logout,signup}             │ token chuyển tiếp
                                                          ▼
                                                    catalog 8082 /internal/books/lookup, /internal/books/{id}/preview
                                                                 (@StorefrontAccessible, chỉ đọc)
Nhân viên/quản trị ──/api/cms/**──> nginx ──> lms-api 8085 ──> system 8086 /internal/cms/** (@PlatformAdminOnly)
Nhân viên tenant ──/api/books/{id}/preview──> nginx ──> lms-api 8085 ──> catalog 8082 PUT|GET /internal/books/{id}/preview
                                                                          (token nhân viên, khóa TitleController/updatePreview|preview)
```

Khách vãng lai chỉ đi được các mũi tên đọc: danh sách, chi tiết, preview, `{code, name}`, trang CMS đã xuất bản (và form SaaS, đăng ký, đăng nhập). Không có mũi tên ghi nào từ token `svc`.

### 3.2. Danh mục storefront (khách chưa đăng nhập)

```mermaid
sequenceDiagram
    participant B as Trình duyệt
    participant C as customer-api
    participant I as identity
    participant S as sales
    participant K as catalog
    B->>C: GET /public/t/{code}/books?keyword=&page=&size=
    C->>C: kiểm định dạng code; tra StorefrontAccessCache
    alt trượt bộ đệm
        C->>I: POST /internal/storefront/access-tokens {tenantCode}
        I-->>C: 200 {tenantId, name, accessToken(svc, tenantId, modules), expiresIn} hoặc 404 SHOP_NOT_AVAILABLE
    end
    C->>S: GET /internal/sales/storefront/items (Bearer token svc)
    S->>S: interceptor: svc + @StorefrontAccessible + tenant + SALES; TenantContext từ claim, RLS
    S->>K: lookup theo lô (Bearer token svc chuyển tiếp)
    K-->>S: [{id, name, author, isbn, coverPrice, description, hasPreview}] (không previewText)
    S-->>C: trang [{titleId, name, author, price, inStock, hasPreview}]
    C-->>B: 200 DTO công khai (không ISBN, không description), Cache-Control max-age=30
```

### 3.2a. Preview (khách chưa đăng nhập, thêm 2026-11-17)

```mermaid
sequenceDiagram
    participant B as Trình duyệt
    participant N as nginx
    participant C as customer-api
    participant S as sales
    participant K as catalog
    B->>N: GET /public/t/{code}/books/{bookRef}/preview
    N->>N: limit_req public_preview (30r/m, burst 10) theo IP, vượt thì 429
    N->>C: chuyển tiếp
    C->>C: phân giải {code} (như 3.2), bỏ qua Authorization của khách
    C->>S: GET /internal/sales/storefront/items/{titleId}/preview (Bearer token svc)
    S->>S: interceptor svc; đọc stock_items theo titleId (RLS)
    alt không có hoặc không active
        S-->>C: 404 BOOK_NOT_FOUND (không gọi catalog)
    else đang bán
        S->>K: GET /internal/books/{id}/preview (token svc chuyển tiếp, ngoài transaction)
        K-->>S: {id, name, description, previewText} hoặc 404
        S-->>C: {titleId, name, previewText} hoặc 404 BOOK_NOT_FOUND nếu previewText null
    end
    C-->>B: 200 {bookRef, title, previewText}, Cache-Control public, max-age=60; hoặc 404 BOOK_NOT_FOUND cùng thân
```

### 3.3. Đặt hàng (khách đã đăng nhập)

customer-api: phân giải `{code}` → xác thực JWT (JWKS) → từ chối token `svc` → so `tenantId` của token với tenant của `{code}` → chuyển tiếp **token của khách** tới sales `/internal/sales/shop/orders` (`ShopController/*`, ADR-0010). sales kiểm module, khóa, sở hữu (`userId` từ token), giữ tồn, `clientRequestId`. customer-api lọc DTO, viết lại thông điệp lỗi.

### 3.4. Hợp đồng ở ranh giới (`be-dev` chốt chi tiết)

customer-api (công khai):

| Method, đường dẫn | Gọi tới | Ra chính / lỗi |
|---|---|---|
| GET `/public/site/pages` | system `GET /internal/cms/public/pages` | `[{slug, title, sortOrder}]` |
| GET `/public/site/pages/{slug}` | system `GET /internal/cms/public/pages/{slug}` | `{slug, title, bodyHtml, publishedAt}`; `404 CMS_PAGE_NOT_FOUND` (gồm trang chưa xuất bản) |
| POST `/public/site/contact-requests` | system `POST /internal/cms/public/contact-requests` | body `{fullName ≤100, email ≤254, phone? ≤20, organization ≤200, businessType (LIBRARY/BOOKSTORE/OTHER), message ≤2000, consent=true, website (honeypot)}` → `201` không thân; `400 VALIDATION_FAILED`, `400 CONSENT_REQUIRED` |
| GET `/public/t/{code}` | bộ đệm storefront | `{code, name}`; `404 SHOP_NOT_AVAILABLE` |
| GET `/public/t/{code}/books` | sales `StorefrontController/list` | query record `{keyword?, page, size ≤50}` → `{items:[{bookRef, title, author, price, inStock, hasPreview}], page, size, total}` |
| GET `/public/t/{code}/books/{bookRef}` | sales `StorefrontController/get` | `{bookRef, title, author, price, inStock, hasPreview, description}`; `404 BOOK_NOT_FOUND` |
| GET `/public/t/{code}/books/{bookRef}/preview` | sales `StorefrontController/preview` | `{bookRef, title, previewText}`; `404 BOOK_NOT_FOUND` (không tồn tại, chưa bán, chưa có preview: cùng thân); `429` từ nginx |
| POST `/public/t/{code}/auth/signup` | identity `POST /internal/auth/signup` (tenantCode từ đường dẫn) | `201`; `403 SIGNUP_NOT_AVAILABLE`, lỗi trùng chung, `400` |
| POST `/public/t/{code}/auth/login` | identity `/internal/auth/login` (tenantCode từ đường dẫn) | `{accessToken, refreshToken, expiresIn}`; `401 LOGIN_FAILED` |
| POST `/public/auth/refresh`, `/public/auth/logout` | identity | như trên, bỏ `availableTenants` |
| POST `/public/t/{code}/orders` | sales `ShopController/placeOrder` | `201` đơn; `409 INSUFFICIENT_STOCK` (không có số tồn), `401`, `403 SHOP_SESSION_MISMATCH`, `403 FORBIDDEN` |
| GET `/public/t/{code}/orders`, `/orders/{orderId}` | sales `ShopController/myOrders`, `/myOrder` | `404 ORDER_NOT_FOUND` cho đơn người khác |
| POST `/public/t/{code}/orders/{orderId}/cancel` | sales `ShopController/cancelMyOrder` | `409 ORDER_STATE_CONFLICT` |

system-service (`cms`), bảng `V3` (✅ đã có `system/V3__create_cms.sql` trong working tree, 2026-11-17):

- `cms_pages`: `id` UUID PK, `slug` VARCHAR(80) UNIQUE (`^[a-z0-9-]+$`), `title`, `body_markdown` TEXT (giới hạn ~50 000 ký tự ở domain), `published_title`, `published_body_markdown`, `published_at`, `sort_order`, `version` (`@Version`), `created_at`, `updated_at`, `updated_by` (UUID platform admin).
- `cms_contact_requests`: `id`, `full_name`, `email`, `phone`, `organization`, `business_type` (CHECK), `message`, `consent_at` NOT NULL, `status` (`NEW`/`IN_PROGRESS`/`HANDLED`/`SPAM`, CHECK), `version`, `created_at`, `handled_by`, `handled_at`, `anonymized_at`; chỉ mục `(status, created_at)` và `(created_at) WHERE anonymized_at IS NULL`.
- Controller: `CmsPublicController` (`@PublicEndpoint`; `SecurityConfiguration` của system `permitAll` đúng 3 đường dẫn `/internal/cms/public/**` theo method), `CmsPageAdminController` (`/internal/cms/pages`: list, get, create, update, `POST|DELETE /{id}/publication`, delete), `ContactRequestAdminController` (`/internal/cms/contact-requests`: search query record `{status?, from?, to?, page, size}`, get, `PUT /{id}/status {status, version}`, `POST /{id}/anonymization`); hai controller admin `@PlatformAdminOnly` ở mức class. Tên lớp duy nhất toàn hệ thống.
- Lỗi: `404 CMS_PAGE_NOT_FOUND`, `409 CMS_SLUG_DUPLICATED`, `409 CMS_VERSION_CONFLICT`, `400 CMS_SLUG_INVALID`, `400 CMS_CONTENT_TOO_LONG`, `404 CONTACT_REQUEST_NOT_FOUND`, `409 CONTACT_REQUEST_ANONYMIZED`, `400 CONSENT_REQUIRED`.
- Quy tắc nghiệp vụ ở `cms.domain`: slug, độ dài, chuyển trạng thái yêu cầu liên hệ, không sửa yêu cầu đã ẩn danh, đồng ý bắt buộc. Record/entity chứa email/điện thoại không in các trường đó trong `toString`.

lms-api: `/api/cms/pages/**`, `/api/cms/contact-requests/**` ánh xạ 1-1 tới system.

identity: `POST /internal/storefront/access-tokens` (mục 2.1); signup theo ADR-0010.

catalog (2.12): `GET /internal/books/{id}/preview` (`TitleController/preview`, `@StorefrontAccessible`), `PUT /internal/books/{id}/preview` (`TitleController/updatePreview`); `@StorefrontAccessible` thêm cho `TitleController/lookup`; `TitleLookupView` thêm `description`, `hasPreview`. Migration `catalog/V4`. Domain `Title` giữ hai trường, chuẩn hóa và kiểm độ dài.

lms-api: `GET`, `PUT /api/books/{id}/preview` ánh xạ 1-1 tới catalog (không qua customer-api).

sales: `StorefrontController` (`list`, `get`, `preview`), xem 2.6, 2.12.

security-common: `@StorefrontAccessible`, `JwtClaims.SERVICE = "svc"`, nhánh `svc` trong `PermissionInterceptor`, `PermissionKeyResolver` trả thêm cờ `storefrontAccessible`.

## 4. Phân việc

Thứ tự: **C0** làm ngay (không chờ sales) → **C1** sau ADR-0010 pha 1 → **C2** sau ADR-0010 pha 2. Cập nhật 2026-11-17: thêm **P0** (phần catalog của preview: B-C6, B-C6L, B-C10) làm ngay song song với phần còn lại của C0, không chờ sales; B-C1 đến B-C5 đã có code chưa commit (🔄 đang rà/sửa theo tester, SA không chạy test).

```
C0 (🔄)  B-C1 ─┬─> B-C2, B-C3, B-C4, B-C5          (đang hoàn thiện)
P0 (⏳)  B-C1 ─┴─> B-C6 (catalog) ──> B-C6L (lms-api) ; B-C10 (seed khóa, cùng ADR-0010 B2.3 hoặc migration riêng)
C1 (🚫)  ADR-0010 pha 1 + B-C6 ──> B-C7 (sales) ──> B-C8 (customer-api + nginx)
C2 (🚫)  ADR-0010 pha 2 (B2.1, B2.2, B2.3) ──> B-C9 (signup, orders)
         B-C11 (gộp mã khóa đăng nhập) chờ Q4, làm cùng người với B-C8 hoặc trước (cùng thư mục customer-api)
```

| # | Ai | Việc | Phạm vi thư mục | Phụ thuộc | Song song với |
|---|---|---|---|---|---|
| 🔄 B-C1 | be-dev | `@StorefrontAccessible`, `JwtClaims.SERVICE`, nhánh `svc` trong interceptor + resolver, unit test bảng quyết định (svc × annotation × tenant × module × mode); `cms_pages`, `cms_contact_requests` vào `TENANT_EXEMPT_TABLES` | `shared-libraries/security-common`, `shared-libraries/tenant-common` | không | B-C3 (phần domain), B-C5 |
| 🔄 B-C2 | be-dev | identity: use case cấp token storefront (tenant ACTIVE + SALES), `StorefrontAccessController`, `permitAll` một đường dẫn, cấu hình bí mật/TTL, `JwtTokenIssuer` phát token `svc` | `services/identity-service` | B-C1 | B-C3, B-C4 |
| 🔄 B-C3 | be-dev | system: migration `system/V3__create_cms.sql` (✅ đã tạo), giới hạn độ sâu Markdown (2.13, đang sửa), context `cms` (domain, application, infrastructure: persistence, render Markdown, job ẩn danh, 3 controller, exception handler), `permitAll` đường dẫn công khai, phụ thuộc `commonmark` (ghim phiên bản ở pom gốc), ArchUnit `cms`/`menu`/`migration` không phụ thuộc nhau | `services/system-service`, `pom.xml` gốc (chỉ `dependencyManagement`) | B-C1 cho `TENANT_EXEMPT_TABLES` (domain/application bắt đầu ngay) | B-C2, B-C4 |
| 🔄 B-C4 | be-dev | customer-api khung: pom, app, security, `PublicExceptionHandler`, `StorefrontAccessCache`, nhóm `site`, `auth`, `GET /public/t/{code}`, ArchUnit, Dockerfile; pom gốc `<module>`; compose (customer-api, bí mật cho identity, `depends_on` nginx); nginx (vùng, location); README/CLAUDE.md | `customer-api/`, `pom.xml` gốc (`<modules>`), `docker-compose.yml`, `infrastructure/nginx/`, `README.md`, `CLAUDE.md` | hợp đồng mục 3.4 (không chờ code) | B-C2, B-C3, B-C5 |
| 🔄 B-C5 | be-dev | lms-api: `/api/cms/**`, `SystemClient`, DTO, test client/controller | `lms-api/src/main/java/lms/api/system/**` và test tương ứng | hợp đồng B-C3 | B-C2, B-C3, B-C4 |
| ⏳ B-C6 | be-dev | catalog (2.12, 2.6): migration `catalog/V4__titles_description_preview.sql` (✅ hiện mới nhất `V3`, ⚠️ kiểm lại); domain `Title` thêm `description`, `previewText` (chuẩn hóa NFC, ký tự điều khiển, rỗng → null, đếm code point, exception `TitlePreviewTooLongException` → `400 TITLE_PREVIEW_TOO_LONG`); `TitleJpaEntity`; use case `GetTitlePreviewUseCase`, `UpdateTitlePreviewUseCase`; `TitleController/preview` (`@StorefrontAccessible`), `TitleController/updatePreview`; `@StorefrontAccessible` trên `TitleController/lookup`; `TitleLookupView` thêm `description`, `hasPreview` (không `previewText`); biến thể tra theo từ khóa (≤200 id); WebMvc (bật kiểm khóa, `@MockitoBean PermissionKeyRegistrar`), `TenantArchitectureRulesTest` xanh, IT cách ly preview theo mẫu `CatalogTenantIsolationIT` | `services/catalog-service/`, `services/system-service/src/main/resources/db/migration/catalog/` (chỉ file `V4`) | B-C1 (đã có code). **Không** chờ sales | B-C6L (sau khi chốt hợp đồng), B-C7, B-C10 |
| ⏳ B-C6L | be-dev | lms-api: `GET`, `PUT /api/books/{id}/preview` trong `CatalogController`, `CatalogClient`/`HttpCatalogClient`, DTO `TitlePreviewView`, test client/controller | `lms-api/src/main/java/lms/api/catalog/**`, `lms-api/src/test/java/lms/api/catalog/**` (⚠️ các file này đang có thay đổi chưa commit của người khác: sửa có mục tiêu) | hợp đồng B-C6 (bảng 2.12c) | B-C6, B-C7 |
| ⏳ B-C10 | be-dev | identity: seed `TitleController/preview`, `TitleController/updatePreview` vào mẫu role SALES Quản lý cửa hàng, Thủ kho (không vào Khách hàng); test đối chiếu tập khóa thật với seed như mẫu ADR-0010 | `services/system-service/src/main/resources/db/migration/identity/` (số kế tiếp, hiện `V17`, ⚠️ kiểm lại; gộp vào B2.3 nếu B2.3 chưa nhập) | tên khóa của B-C6 đã chốt; `role_templates` của ADR-0010 B2.3 | B-C6L, B-C7 |
| 🚫 B-C7 | be-dev | sales: `StorefrontController` (`list`, `get`, `preview`; `@RequiresModule(SALES)`, `@StorefrontAccessible`), DTO theo bảng 2.6; port `CatalogPreviewPort` + adapter HTTP (token chuyển tiếp, gọi ngoài transaction); preview kiểm `stock_items` đang bán trước khi gọi catalog; không dùng `CurrentUser`; WebMvc + test use case | `services/sales-service/` (chỉ thêm gói `storefront` hoặc trong `stock`; không sửa phần pha 1 người khác đang làm) | **ADR-0010 pha 1**: đọc `stock_items` theo trang/`titleId`, adapter HTTP của `CatalogLookupPort`, nối `PermissionInterceptor`/`@RequiresModule` ở sales (hiện sales chỉ có `SalesSettingsController`); B-C6 (hợp đồng `lookup` mở rộng, `preview`) | B-C6L, B-C10 |
| 🚫 B-C8 | be-dev | customer-api `/public/t/{code}/books`, `/books/{bookRef}`, `/books/{bookRef}/preview` (client sales, DTO 2.8, `Cache-Control` 30 s/60 s, `permitAll` liệt kê từng mẫu); nginx vùng `public_preview` + location regex theo mẫu `(?:[/;].*)?$` | `customer-api/`, `infrastructure/nginx/nginx.conf` | hợp đồng B-C7 (làm song song với B-C7 bằng client giả, chỉ nhập sau) | B-C7 |
| 🚫 B-C9 | be-dev | customer-api signup + orders; xác nhận mẫu Khách hàng (ADR-0010 B2.3) có `ShopController/*` **và** `TitleController/lookup` (sales gọi catalog bằng token khách khi đặt hàng) | `customer-api/` (+ ghi chú cho người làm B2.3) | ADR-0010 B2.1, B2.2, B2.3 | |
| ⏳ B-C11 | be-dev | customer-api: gộp `ACCOUNT_LOCKED`, `ACCOUNT_DISABLED` thành `401 LOGIN_FAILED` (2.13 phương án A), cập nhật test | `customer-api/src/main/java/lms/customer/auth/**`, `.../web/PublicExceptionHandler.java` | Q4 (người dùng xác nhận) | mọi việc trừ B-C8, B-C9 (cùng module: tuần tự hoặc cùng người) |
| ⏳ T-C1 | tester | Unit/WebMvc interceptor: token `svc` bị 403 ở endpoint thường, `@AuthenticatedOnly`, `@PlatformAdminOnly`; qua ở `@StorefrontAccessible` có tenant + SALES; thiếu SALES → `MODULE_NOT_ENABLED`; token người dùng ở endpoint `@StorefrontAccessible` vẫn kiểm khóa | | B-C1 | |
| ⏳ T-C2 | tester | IT identity: ACTIVE + SALES → token đúng claim; không tồn tại / ngưng / chỉ LENDING / sai mã → cùng `404 SHOP_NOT_AVAILABLE`, cùng thân; sai/thiếu bí mật → 401; bí mật cấu hình rỗng → luôn 401 | | B-C2 | T-C3 |
| ⏳ T-C3 | tester | IT system (`system_app`): DRAFT không bao giờ lộ, sửa trang đã xuất bản không lộ bản đang sửa; Markdown có `<script>`, `javascript:` ra HTML vô hại; admin platform 200, tenant admin 403; form thiếu consent/quá dài 400; job ẩn danh (đồng hồ giả) chỉ đụng dòng quá hạn, chạy lại không đổi; email/điện thoại không có trong log (bắt log) | | B-C3 | T-C2 |
| ⏳ T-C4 | tester | WebMvc customer-api (client giả): tập trường DTO đúng mục 2.6/3.4 (so tập tên trường); honeypot; mã tenant sai định dạng; token `svc` bị 401; tenant không khớp 403; downstream 5xx/mất kết nối → `503` gọn; `FORBIDDEN` không lộ tên khóa; `INSUFFICIENT_STOCK` không có số tồn; `/internal/**`, `/api/**` → 403/404. Thêm (2026-11-17, sau B-C8): tập trường đúng bảng 2.8 cho danh sách (có `hasPreview`, không `description`/`previewText`), chi tiết (có `description`, không `previewText`), preview (đúng `{bookRef, title, previewText}`); không token: danh sách/chi tiết/preview `200`, đơn hàng và `contact-requests` của đọc giả `401`; tập đường dẫn công khai đúng danh sách đóng 2.4 (không endpoint preview theo lô); `404 BOOK_NOT_FOUND` của sales chuyển ra cùng thân; preview có `Cache-Control: public, max-age=60`; chuỗi `<script>` trả nguyên văn trong JSON (không biến đổi). Sau B-C11: khóa/vô hiệu → `401 LOGIN_FAILED` cùng thân với sai mật khẩu | | B-C4; phần preview: B-C8 | |
| ⏳ T-C5 | tester | nginx: `/internal/**` qua 8080 → 404; vượt `public_form` → 429; `/actuator` không ra ngoài. Thêm: vượt `public_preview` → 429, kể cả biến thể `/preview/`, `/preview;x=1`, `%70review`; chi tiết `/books/{id}` không bị vùng preview chặn | | B-C4; vùng preview: B-C8 | T-C4 |
| 🚫 T-C6 | tester | Đầu-cuối + cách ly (mẫu `CatalogTenantIsolationIT`): tenant A không thấy sách/đơn B qua `{code}` hay token; hết hàng hiện `inStock=false`; sách chưa đưa vào bán không hiện; signup → login → đặt hàng; `clientRequestId` gửi lại không giữ tồn hai lần. Thêm preview: `bookRef` của B qua `{code}` của A → `404 BOOK_NOT_FOUND`; sách chưa bán / không tồn tại / chưa có preview → cùng `404`, cùng thân; `hasPreview` đúng dữ liệu; nhân viên nhập preview qua lms-api rồi đọc được ở storefront (sau ≤ 60 s cache) | | C1, C2 | |
| ⏳ T-C7 | tester | catalog (sau B-C6, không chờ sales): WebMvc + IT `catalog_app`: 2000 ký tự lưu được, 2001 → `400 TITLE_PREVIEW_TOO_LONG` và không lưu; `description` 501 → `400`; 2000 ký tự có emoji/tiếng Việt dạng tổ hợp vẫn lưu được; rỗng/khoảng trắng → null, `hasPreview=false`; chèn thẳng DB 2001 ký tự bị chặn (VARCHAR/CHECK); `V4` chạy trên DB có dữ liệu (cột NULL); RLS `titles` vẫn bật; nhân viên tenant A sửa preview của B → `404`; `updatePreview`: có khóa 200, thiếu khóa 403, token khách 403, token `svc` 403, không token 401; `preview` với token `svc` có tenant 200, không trả ISBN; `lookup` với token `svc` 200 và không có `previewText` | | B-C6 | T-C1 đến T-C5 |
| ⏳ P-C1 | perf-tester | Danh mục công khai: thông lượng, p95 khi trúng/trượt `StorefrontAccessCache`, số lời gọi identity mỗi phút; mục tiêu tham khảo ⚠️ chưa có số nền: p95 < 300 ms ở customer-api khi sales/catalog khỏe. Thêm: `lookup` 100 id sau khi `titles` có `preview_text` (entity tải cả cột 2000 ký tự dù không trả), p95 preview một sách | | C1 | |
| ⏳ P-C2 | perf-tester | Quét mã tenant ngẫu nhiên: bộ nhớ customer-api ổn định (giới hạn bộ đệm), chênh lệch thời gian phản hồi giữa mã có/không tồn tại, 429 đúng ngưỡng | | B-C2, B-C4 | P-C1 |

Commit tách theo nhóm: `feat(security):`, `feat(identity):`, `feat(system):`, `feat(catalog):`, `feat(sales):`, `feat(customer-api):`, `feat(lms-api):`, `build:` cho compose/nginx/Dockerfile, `docs:` cho README/CLAUDE.md/ADR.

Song song được ngay (thư mục không chồng nhau): B-C6 (catalog) ‖ phần còn lại của C0 ‖ B-C11 (nếu Q4 chốt, không chạm `customer-api/storefront`). B-C6L và B-C10 bắt đầu khi tên khóa và hợp đồng 2.12c đã chốt (không cần chờ B-C6 xong). B-C7 ‖ B-C8 chỉ sau khi ADR-0010 pha 1 có phần đọc `stock_items` và adapter catalog. Thứ tự triển khai: system-service (migrate `catalog/V4`) trước catalog-service mới, vì catalog chạy `validate` sẽ không lên nếu entity có cột mà DB chưa có. README/CLAUDE.md: không thêm cổng; ghi khóa mới vào tài liệu phân quyền nếu có.

## 5. Rủi ro và việc chưa kiểm chứng

- 🚫 Storefront (danh mục, preview công khai, signup, đặt hàng) bị chặn bởi ADR-0010 pha 1/pha 2 (sales mới có domain/application và `SalesSettingsController`); C0 và phần catalog của preview (B-C6, B-C6L) không bị chặn.
- ⚠️ (2026-11-17) Preview văn bản thuần chỉ an toàn khi FE hiển thị như text; API trả nguyên văn `<script>`. Không kiểm được ở BE; cần ghi rõ trong tài liệu API cho FE và review FE. Tiêu chí 8 "đầu ra không thực thi được" ở BE được hiểu là: JSON + `nosniff` + không biến đổi chuỗi.
- ⚠️ (2026-11-17) `public_preview` `30r/m` theo IP: người dùng chung NAT (trường học, quán cà phê) có thể chạm 429 khi nhiều người cùng đọc thử; chưa có số liệu, P-C2 đo. Cào phân tán nhiều IP vẫn lấy hết preview; đề bài 4.7 đã chấp nhận.
- ⚠️ (2026-11-17) Gỡ sách khỏi bán hoặc sửa preview hiện ra chậm tối đa 60 giây (cache trình duyệt/proxy) cộng độ trễ token 5 phút khi tắt SALES.
- ⚠️ (2026-11-17) Nhập preview ghi cuối thắng (không `version`): hai nhân viên sửa cùng lúc thì một bản mất, không báo lỗi. Chấp nhận theo A12.
- ⚠️ (2026-11-17) `TitleJpaEntity` tải `preview_text` ở mọi truy vấn (`search`, `lookup`): tăng I/O khi danh mục lớn; chưa đo (P-C1). Nếu cần, `be-dev` dùng projection cho `lookup`.
- ⚠️ (2026-11-17) Thứ tự triển khai: catalog-service mới lên trước khi system-service migrate `V4` sẽ lỗi `validate`. Chưa kiểm chứng trong compose (`depends_on` hiện có của catalog với system).
- ⚠️ (2026-11-17) Jackson ở sales/lms-api bỏ qua trường lạ khi `TitleLookupView` thêm `description`, `hasPreview` (mặc định Spring Boot `FAIL_ON_UNKNOWN_PROPERTIES=false`): chưa kiểm cấu hình thật từng client.
- ⚠️ (2026-11-17) Dò trạng thái khóa tài khoản xuyên tenant qua đăng nhập storefront (2.13): còn mở tới khi Q4 chốt và B-C11 xong; cửa tương tự ở `/api/auth/login` có từ trước.
- 🔄 (2026-11-17) Giới hạn độ sâu Markdown CMS đang sửa ở system-service; chưa có kết quả test lại.
- ✅ (2026-11-17) nginx: location regex chấp nhận `/` cuối và `;` (2.13).
- ⚠️ Profile dev tắt kiểm khóa (`lms.security.permission.enabled=false`): ⚠️ chưa kiểm nhánh `svc` có còn chạy khi tắt hay không; nếu không thì ở dev token `svc` gọi được mọi endpoint. Chỉ ảnh hưởng dev, nhưng test tay ở dev không chứng minh được 2.1.
- ⚠️ Tenant bị ngưng hoặc tắt SALES vẫn hiện storefront tối đa **5 phút** (TTL token dịch vụ + bộ đệm); tenant vừa bật SALES chờ tối đa 60 giây (bộ đệm phủ định). Cùng tinh thần độ trễ đã chấp nhận ở ADR-0009 (M2). Ngưng gấp: có thể giảm TTL, nhưng tăng tải identity.
- ⚠️ Token dịch vụ dùng `sub` là UUID dành riêng: endpoint `@StorefrontAccessible` không được dùng `CurrentUser` làm người thực hiện. Chưa có luật ArchUnit bắt điều này; review khi làm B-C7.
- ⚠️ Nhánh `svc` thêm vào interceptor dùng chung mọi service: lỗi ở đây ảnh hưởng toàn hệ thống. Cần T-C1 đủ tổ hợp trước khi nhập.
- ⚠️ Lọc từ khóa = giao của tối đa 200 kết quả catalog với mặt hàng đang bán: từ khóa quá rộng có thể bỏ sót sách. Chấp nhận cho "lọc cơ bản"; cần số liệu thật về số đầu sách một hiệu sách.
- ⚠️ Chênh lệch thời gian phản hồi giữa mã tenant có/không tồn tại chỉ "xấp xỉ"; chưa đo.
- ⚠️ `STOREFRONT_CLIENT_SECRET` mặc định dev trong compose: production phải ghi đè; lộ bí mật cho phép lấy token đọc danh mục storefront của tenant khả dụng (dữ liệu vốn công khai), không mở được endpoint khác.
- ⚠️ IP thật sau load balancer/CDN chưa cấu hình (mục 2.7).
- ✅ Số migration system `V3` đã dùng cho `cms` (`V3__create_cms.sql`). Còn phải kiểm ngay trước khi tạo: `catalog/V4` (B-C6), `identity/V17` (B-C10, có thể trùng ADR-0010 B2.3).
- ⚠️ Hibernate với entity không `@TenantId` trong service đã bật multi-tenancy (`cms_*` cạnh `menus`): dự kiến không bị lọc, chưa chạy IT (T-C3).
- ⚠️ `commonmark-java` `sanitizeUrls` cần phiên bản đủ mới; `be-dev` kiểm phiên bản có API này.
- ⚠️ Mẫu role Khách hàng (ADR-0010 B2.3) phải có `TitleController/lookup`; thiếu thì khách đặt hàng bị 403 ở catalog (lỗi chỉ lộ khi chạy đầu-cuối).
- ℹ️ Dòng "khóa động ở customer-api" trong bảng phân quyền của đề bài được thay bằng quyết định 2.4 (sales kiểm khóa); tiêu chí nghiệm thu không đổi.
- ℹ️ Form liên hệ không gửi email (A8); form không tự tạo tenant.
- ℹ️ Nợ có sẵn: kiểm chứng còn nợ của ADR-0006; Outbox (ADR-0001) chưa có. customer-api không phát event.

## 6. Câu hỏi mở (cần người dùng quyết)

- Q1. Thứ tự làm: khuyến nghị làm **C0 ngay** (trang giới thiệu, form liên hệ, khung customer-api, cơ chế token storefront) rồi storefront theo nhịp ADR-0010. Nếu người dùng muốn storefront chạy trước cả sales (chỉ xem danh mục từ catalog, chưa có giá/tồn) thì hướng thiết kế 2.6 đổi; khuyến nghị **không**.
- Q2. Độ trễ tối đa 5 phút khi ngưng tenant/tắt SALES với storefront công khai: chấp nhận? Khuyến nghị **có** (khớp M2 của ADR-0009).
- Q3 (đề bài Q3). Giữ dữ liệu form 12 tháng rồi ẩn danh, có thao tác ẩn danh ngay theo yêu cầu: khuyến nghị **giữ như mục 2.10**; nếu pháp lý cần thời hạn khác chỉ đổi cấu hình.
- Q4 (2026-11-17). Đăng nhập storefront khi tài khoản bị khóa/vô hiệu: gộp thành `401 LOGIN_FAILED` (khách không biết lý do, không dò được) hay giữ mã riêng? Khuyến nghị **gộp ở customer-api** (2.13 phương án A). Câu phụ: có xử lý luôn cửa `/api/auth/login` của nhân viên (phương án C, đổi hành vi identity) không? Khuyến nghị tách thành việc riêng, không chặn ADR này.
- Q5 (2026-11-17). Quyền nhập preview mặc định gán cho mẫu Quản lý cửa hàng và Thủ kho (SALES), không gán Thu ngân, không gán Khách hàng: đúng ý người dùng? Khuyến nghị **có**; tenant admin chỉnh được vì role là dữ liệu.
- ℹ️ Đã chốt 2026-11-17 (không còn hỏi): preview 2000 ký tự, mô tả 500 ký tự, tùy chọn, văn bản thuần, lưu ở `titles`; vãng lai chỉ xem danh sách, chi tiết, preview, `{code, name}`, trang CMS; giới hạn theo IP; không API preview theo lô; thao tác khác cần token người dùng.
