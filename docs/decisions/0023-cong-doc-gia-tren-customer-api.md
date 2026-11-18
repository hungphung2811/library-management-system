# ADR-0023: Cổng độc giả trên `customer-api` (`/public/t/{code}/me/**`) và gỡ API "của tôi" khỏi `lms-api`/FE nhân viên

- Trạng thái: đề xuất (SA). Chưa có code. Người dùng đã chốt hướng "thêm mới ở customer-api rồi gỡ ở admin" và phạm vi 4 nhóm; các câu hỏi mở ở mục 6.
- Liên quan: ADR-0003 (khóa `Controller/method`), ADR-0005 (BFF `lms-api`), ADR-0006 (multi-tenant, RLS), ADR-0009 (module theo tenant), ADR-0011 (customer-api, `StorefrontAccessCache`, `ShopSessionGuard`, `PublicExceptionHandler`), ADR-0013 (CORS theo tenant), `docs/api/token-audience.md` (kênh token STOREFRONT, `aud = lms-storefront`).

## 1. Bối cảnh và mục tiêu

Độc giả thư viện hiện xem phiếu mượn, phạt, đặt trước, thẻ độc giả **trong FE nhân viên** qua `lms-api`. Mục tiêu: đưa 4 nhóm API tự phục vụ của độc giả sang `customer-api` (cổng công khai, BFF thứ hai), rồi gỡ chúng khỏi `lms-api` và FE nhân viên.

Ràng buộc: service nội bộ tự kiểm JWT/khóa/module; `customer-api` không phụ thuộc `security-common`/`tenant-common`, không DB (ArchUnit `CustomerApiArchitectureTest`); một mã `404 SHOP_NOT_AVAILABLE` cho mọi lý do tenant không khả dụng (ADR-0011 2.5); không thiết kế vượt nhu cầu.

### 1.1. Hiện trạng đã đối chiếu trong code (2026-11-18, nhánh `main` + working tree)

| | Điểm | Kết quả thật |
|---|---|---|
| ✅ | circulation `BorrowingController` | `@RequiresModule(LENDING)`. `GET /internal/borrowings/mine` và `GET /internal/borrowings/{id}` (`executeOwn`, của người khác → `404 BORROWING_NOT_FOUND`) đều `@AuthenticatedOnly`, userId từ `CurrentUser.from(jwt)`. `BorrowingResponse` có `userId`, `tenantId`, `items[].bookCopyId`, không có tên sách. |
| ✅ | circulation `FineController` | `@RequiresModule(LENDING)`. `GET /internal/fines/mine` `@AuthenticatedOnly`. `FineResponse` có `readerId`, `bookCopyId`, `tenantId`. |
| ✅ | circulation `ReservationController` | `@RequiresModule(LENDING)`. `reserve` (POST), `mine` (GET), `cancel` (DELETE `/{id}`), `expireNow` **đều là khóa thường** (không `@AuthenticatedOnly`): cần `ReservationController/reserve|mine|cancel` trong claim `permissions`. readerId luôn từ token. `ReservationResponse` có `copyId`. |
| ✅ | Tạo đặt trước gọi service khác bằng token người dùng | `ReserveTitleUseCase` → identity `GET /internal/users/me/reader-profile` (`@AuthenticatedOnly`) và catalog `GET /internal/books/{id}` (**khóa `TitleController/get`**); 403 từ catalog bị `CatalogRestClientAdapter` biến thành `503 DEPENDENCY_UNAVAILABLE`. |
| ✅ | identity `ReaderProfileController.me` | `GET /internal/users/me/reader-profile`, `@AuthenticatedOnly`, không module. **circulation dùng endpoint này** (đặt trước) nên endpoint nội bộ phải giữ. 404 → `READER_NOT_FOUND`. `ReaderProfileResponse` có `userId` + thông tin cá nhân (điện thoại, địa chỉ, ngày sinh...). |
| ✅ | Tra tenant theo `{code}` | `ResolveStorefrontTenantUseCase` (identity): tenant tồn tại, `ACTIVE` **và có `SALES`**; ngược lại rỗng → `404 SHOP_NOT_AVAILABLE`. Dùng chung cho token `svc` (`IssueStorefrontTokenUseCase`) **và** `SignupUseCase`. Phản hồi `AccessTokenResponse` không có `modules` (claim `modules` chỉ nằm trong token `svc`). |
| ✅ | Đăng nhập khách | `POST /public/t/{code}/auth/login` phân giải storefront trước (nên tenant chỉ có LENDING hiện **không đăng nhập được** ở cổng công khai), gửi `channel=STOREFRONT`. Token STOREFRONT: `aud=lms-storefront`, có `tenantId`, `modules`, không `admin`; `permissions` lọc theo allowlist `lms.identity.token.storefront-permissions`, mặc định `ShopController/*,TitleController/lookup` (`AuthBeanConfiguration`, `TokenService.DEFAULT_STOREFRONT_PERMISSIONS`); không thấy ghi đè trong `docker-compose.yml`/`application.yml`. |
| ✅ | `aud` ở service nội bộ | Chỉ `lms-api` (`ChannelAudienceFilter`) và `customer-api` kiểm `aud`; circulation/identity không kiểm, nên token STOREFRONT dùng được ở endpoint `@AuthenticatedOnly`. |
| ✅ | `PermissionInterceptor` với `@AuthenticatedOnly` + `@RequiresModule` | Đi tiếp xuống kiểm `TENANT_REQUIRED` và `MODULE_NOT_ENABLED` rồi cho qua (không kiểm khóa). Token `svc` ở endpoint không `@StorefrontAccessible` → 403. |
| ✅ | customer-api | `ShopSessionGuard` (401 nếu token `svc` hoặc thiếu `aud lms-storefront`; `403 SHOP_SESSION_MISMATCH` nếu tenant lệch); `SecurityConfiguration` có `PUBLIC_API` và `AUTHENTICATED_API` (danh sách đóng), còn lại `denyAll`. `PublicCorsFilter` áp mọi `/public/t/{code}/**`, chỉ cho `GET, POST, OPTIONS`. `ServicesProperties` chỉ có identity, system, sales. |
| ✅ | nginx | `location /public/` (vùng `public_read` 20r/s + `public_tenant` 50r/s theo mã) phủ mọi đường dẫn mới `/public/t/*/me/**`; chỉ login/signup/contact có location riêng. |
| ✅ | FE nhân viên dùng các API này | `pages/circulation/Mine.tsx` (`/api/borrowings/mine`); `Fines.tsx` nhánh không phải nhân viên (`/api/fines/mine`); `BorrowingDetail.tsx`, `_components/ReturnDesk.tsx` nhánh thiếu `BORROWING_GET_ANY` (`/api/borrowings/{id}`); `Reservations.tsx` + `_components/ReservationsView.tsx` (danh sách/tạo/hủy của tôi **và** nút "hủy đặt trước quá hạn" `POST /api/reservations/expirations` của nhân viên); `catalog/_components/ReserveButton.tsx` trong `BookDetail.tsx`; `Profile.tsx` thẻ "thẻ thư viện" (`/api/users/me/reader-profile`, 404 thì ẩn); `nav-links.ts` mục "Phiếu của tôi" và "Đặt trước"; `search-keywords.ts`; `router.tsx` route `mine`, `reservations`; e2e `circulation-2.spec.ts`, `circulation.spec.ts`, `sidebar.spec.ts`, `shell-pages.spec.ts`, mock `e2e/mock/circulation.mjs`. |
| ✅ | Menu động | system `V4__add_default_menu_children.sql` + `DefaultMenus`: `circulation-mine` (`/circulation/mine`) và `circulation-reservations` (khóa `ReservationController/mine`). Migration system mới nhất `V5`. |
| ⚠️ | FE cổng độc giả | Không tìm thấy FE storefront/cổng khách trong workspace (`library-management-system-fe` chỉ là app nhân viên). Chưa biết FE nào sẽ gọi `/public/t/{code}/me/**` (Q1). |

## 2. Quyết định

### 2.1. Tenant khả dụng ở cổng công khai: identity trả `modules`, customer-api kiểm module theo nhóm route

| Tiêu chí | **A. Nới điều kiện ở identity (ACTIVE và có SALES hoặc LENDING), trả thêm `modules`; customer-api kiểm SALES/LENDING theo route (chọn)** | B. Endpoint/tham số riêng cho cổng độc giả (`purpose=LIBRARY`), bộ đệm thứ hai | C. Giữ điều kiện SALES, cổng độc giả chỉ cho tenant có cả SALES |
|---|---|---|---|
| Thư viện chỉ thuê LENDING | Dùng được | Dùng được | ❌ Không dùng được (đúng nhóm khách chính) |
| Chống dò tenant | Một lời gọi, một bộ đệm, một mã 404 như cũ | Hai đường tra, phải giữ hai nhánh cùng thời gian/cùng thân | Như cũ |
| Thay đổi | 1 điều kiện + 1 trường ở identity; `modules` trong `StorefrontAccess`; 1 hàm kiểm ở customer-api | Endpoint mới, cache mới, client mới | Không |

Chọn **A**.

- identity: `ResolveStorefrontTenantUseCase` trả tenant khi `ACTIVE` và `modules ∩ {SALES, LENDING} ≠ ∅` (vẫn đúng một truy vấn module và một truy vấn origin ở mọi nhánh). `AccessTokenResponse` thêm `modules` (danh sách mã đang bật). Giữ tên lớp (không đổi khóa quyền nào).
- **`SignupUseCase` tự kiểm `SALES`** (trước đây dựa vào port): tenant chỉ có LENDING → `403 SIGNUP_NOT_AVAILABLE`. Thẻ độc giả do thủ thư cấp (`ReaderProfileController/issue`), không mở tự đăng ký cho thư viện (Q3).
- customer-api: `StorefrontAccess` thêm `modules`; thiếu trường (identity cũ) thì coi là `[SALES]` (identity cũ chỉ trả tenant có SALES), để triển khai customer-api trước identity được. Thêm một hàm phân giải có điều kiện, ví dụ `storefronts.resolve(code, Feature.LIBRARY)`: tenant không có module tương ứng → `404 SHOP_NOT_AVAILABLE` **cùng thân**, không gọi service phía sau.
  - Route bán hàng hiện có (`books/**`, `banners`, `orders/**`, `contact-requests`, `auth/signup`) chuyển sang yêu cầu `SALES` (hiện vẫn an toàn nhờ sales trả `MODULE_NOT_ENABLED` → 404, nhưng kiểm ở BFF tránh gọi thừa và giữ nguyên hành vi cũ).
  - Route `me/**` của cổng độc giả yêu cầu `LENDING`.
  - `GET /public/t/{code}`, `auth/login|refresh|logout`: đủ một trong hai module.
- `GET /public/t/{code}` thêm `shopEnabled`, `libraryEnabled` (boolean) để FE biết hiện phần nào. Không trả danh sách module thô.
- **Không thêm mã lỗi mới.** Đã loại `LIBRARY_NOT_AVAILABLE`: phân biệt được "tenant có SALES nhưng không LENDING", tức lộ hợp đồng thuê, và FE phải xử lý hai mã cho cùng một ý. Tên `SHOP_NOT_AVAILABLE` hơi lệch nghĩa với thư viện, chấp nhận để không phá hợp đồng FE.
- Hệ quả cố ý: tenant chỉ có LENDING trước đây `404` ở `GET /public/t/{code}` và login, nay `200`. CORS theo origin của tenant (ADR-0013) tự áp cho tenant này vì `tryResolve` thành công.

### 2.2. Đăng nhập: dùng lại `POST /public/t/{code}/auth/login`, kênh STOREFRONT

Dùng lại nguyên luồng hiện có (tenant ép theo `{code}`, gộp lỗi `401 LOGIN_FAILED`, token `aud=lms-storefront`, refresh theo `{code}`). Không thêm endpoint đăng nhập riêng cho độc giả: cùng tài khoản, cùng tenant, cùng loại token; một độc giả vừa mua sách vừa mượn sách dùng một phiên. Đã loại: kênh token thứ ba `LIBRARY` (thêm `aud`, cấu hình allowlist thứ hai, mà không thêm bảo đảm vì service phía sau vẫn tự kiểm module/khóa/sở hữu).

### 2.3. Gọi phía sau: chuyển tiếp Bearer của độc giả (như `OrderController`), không dùng token `svc`

| Tiêu chí | **A. Chuyển tiếp token người dùng (chọn)** | B. Token `svc` + userId do BFF gửi |
|---|---|---|
| Nguồn userId | `sub` của JWT, service tự lấy bằng `CurrentUser.from(jwt)` | BFF gửi userId: service phải tin BFF, mở đường xem dữ liệu người khác nếu BFF sai |
| Sửa service | Không (endpoint đã có) | Thêm endpoint `@StorefrontAccessible` nhận userId, trái ADR-0011 2.1 (chỉ endpoint đọc công khai) |
| Module, tenant, RLS | Theo claim của token người dùng | Theo token `svc` |

Chọn **A**. Mỗi endpoint: `resolve(code, LIBRARY)` → `ShopSessionGuard.requireSession` (giữ nguyên lớp, không đổi tên) → chuyển `Authorization: Bearer <token độc giả>`. **Không** gắn `@StorefrontAccessible` cho bất kỳ endpoint nào trong ADR này: token `svc` lọt qua vẫn bị circulation/identity trả 403.

Endpoint nội bộ dùng được và việc phải làm:

| Nhóm | Endpoint nội bộ | Chế độ | Cần làm |
|---|---|---|---|
| Phiếu mượn | `GET /internal/borrowings/mine`, `GET /internal/borrowings/{id}` | `@AuthenticatedOnly` + LENDING | Không |
| Phạt | `GET /internal/fines/mine` | `@AuthenticatedOnly` + LENDING | Không |
| Thẻ độc giả | `GET /internal/users/me/reader-profile` | `@AuthenticatedOnly` | Không |
| Đặt trước (xem, hủy) | `GET /internal/reservations`, `DELETE /internal/reservations/{id}` | khóa `ReservationController/mine`, `/cancel` | Thêm hai khóa vào allowlist `storefront-permissions` |
| Đặt trước (tạo) | `POST /internal/reservations` | khóa `ReservationController/reserve`; bên trong gọi catalog `TitleController/get` bằng token độc giả | Hoãn (2.6, Q2) |

- Allowlist STOREFRONT mới: `ShopController/*,TitleController/lookup,ReservationController/mine,ReservationController/cancel` (sửa cả `@Value` default ở `AuthBeanConfiguration` lẫn `TokenService.DEFAULT_STOREFRONT_PERMISSIONS` và `docs/api/token-audience.md`). Đã loại: đổi ba method đặt trước sang `@AuthenticatedOnly` (mất khả năng tenant admin thu hồi quyền đặt trước qua role, và mất khóa đang dùng cho menu `circulation-reservations`). Khóa được giữ nguyên nên role đã gán không mất quyền; tenant admin vẫn tắt được bằng cách bỏ khóa khỏi role độc giả.
- Rủi ro lộ dữ liệu người khác: không có tham số userId/readerId ở bất kỳ route nào; id trong đường dẫn (`borrowingId`, `reservationId`) chỉ đi tới `executeOwn`/`cancel(readerId, id)` vốn so sở hữu ở application; RLS chặn tenant khác. customer-api kiểm `{id}` là UUID trước khi gọi (sai → 404 mã tương ứng, không gọi phía sau).

### 2.4. DTO công khai (record riêng trong `lms.customer.reader.dto`, so tập tên trường khi test)

| Route | Trường trả | Bỏ so với nội bộ |
|---|---|---|
| `GET /public/t/{code}/me/borrowings` (mảng) và `/me/borrowings/{borrowingId}` | `id, status, borrowDate, dueDate, renewCount, items[{returnDate, condition}]` | `userId`, `tenantId`, `fines`, `items[].bookCopyId` |
| `GET /public/t/{code}/me/fines` | `totalUnpaid, fines[{id, borrowingId, reason, amount, status, createdAt, paidAt}]` | `readerId`, `bookCopyId`, `tenantId` |
| `GET /public/t/{code}/me/reservations` (mảng) | `id, bookRef, title, status, queuePosition, reservedAt, holdUntil` | `copyId` (bản sao cụ thể là dữ liệu kho) |
| `POST /public/t/{code}/me/reservations/{reservationId}/cancellation` | như một phần tử ở trên | |
| `GET /public/t/{code}/me/reader-profile` | `cardNo, fullName, email, readerType, cardExpiryDate, status, cardValid, phone, dateOfBirth, gender, address, faculty, className, memberCode, cardIssuedDate` | `userId` |

- `bookRef = titleId` (như ADR-0011 2.8). `title` (tên sách, null được) lấy bằng **một** lời gọi lô `POST /internal/books/lookup` của catalog với token `svc` (`TitleController/lookup` đã `@StorefrontAccessible`, không `@RequiresModule`, đã nằm trong danh sách đóng ADR-0011 2.1), tối đa 100 id, ghép theo `Map<titleId, name>`; catalog lỗi thì `title = null`, không làm hỏng danh sách. Thêm `app.services.catalog-url`. Lý do: `ReservationView` chỉ có `titleId`, không tên thì danh sách vô nghĩa với độc giả; FE nhân viên hiện cũng phải tra tên. Phiếu mượn giữ như màn "Phiếu của tôi" hiện có (số cuốn, không tên) để không mở `BookCopyController/lookup` cho token `svc`.
- Thông tin cá nhân của thẻ là dữ liệu của chính người gọi nên được trả; mọi route `me/**` đặt `Cache-Control: no-store` (`PublicCache.noStore`). Không đệm ở server.
- Không ghi chú nhân viên: các DTO nội bộ trên không có trường ghi chú (✅ đã đọc record); nếu sau này circulation thêm trường thì record công khai không tự lộ vì là record allowlist.
- Danh sách phiếu mượn/đặt trước không phân trang ở circulation (trả `List`); customer-api trả nguyên mảng như admin hiện nay. ⚠️ Độc giả lâu năm có thể có vài trăm phiếu; đo ở P-R1, phân trang là việc sau.

### 2.5. Lỗi, giới hạn tốc độ, CORS

- `PublicExceptionHandler.FORWARDED_CODES` thêm: `BORROWING_NOT_FOUND` ("Không tìm thấy phiếu mượn"), `RESERVATION_NOT_FOUND` ("Không tìm thấy đặt trước"), `INVALID_RESERVATION` ("Đặt trước không thể hủy ở trạng thái hiện tại"; ✅ `Reservation.cancel` ném khi không còn hiệu lực, handler trả 422; đặt trước của người khác → `404 RESERVATION_NOT_FOUND` do `CancelReservationUseCase` lọc `isOwnedBy`), `READER_NOT_FOUND` → **alias** thành `READER_PROFILE_NOT_FOUND` ("Bạn chưa có thẻ thư viện") để FE phân biệt với lỗi tài khoản. Các nhánh sẵn có giữ nguyên: `MODULE_NOT_ENABLED`/`TENANT_REQUIRED` → `404 SHOP_NOT_AVAILABLE`; `FORBIDDEN` (role độc giả thiếu khóa đặt trước) → `403 FORBIDDEN` thông điệp chung, không lộ tên khóa; `DEPENDENCY_UNAVAILABLE`/5xx → `503 SERVICE_UNAVAILABLE`; mã lạ → `400 REQUEST_REJECTED`.
- `SecurityConfiguration.AUTHENTICATED_API` thêm đúng các mẫu: `GET /public/t/*/me/borrowings`, `GET /public/t/*/me/borrowings/*`, `GET /public/t/*/me/fines`, `GET /public/t/*/me/reservations`, `POST /public/t/*/me/reservations/*/cancellation`, `GET /public/t/*/me/reader-profile`. Không dùng `/public/t/*/me/**`.
- Hủy đặt trước dùng `POST .../cancellation` (như hủy đơn), **không** `DELETE`: `PublicCorsFilter` chỉ cho `GET, POST`; giữ nguyên CORS.
- nginx: không đổi. `location /public/` (`public_read` 20r/s, `public_tenant`) đủ cho đọc; ghi duy nhất là hủy, bị chặn bởi nghiệp vụ (chỉ hủy được đặt trước của mình, đang hiệu lực).

### 2.6. Đặt trước mới: hoãn sang đợt có tra cứu sách thư viện ở cổng độc giả

Tạo đặt trước cần (a) FE cổng độc giả có chỗ chọn đầu sách thư viện (chưa có: danh mục công khai hiện là mặt hàng của sales, tenant chỉ LENDING không có), và (b) thêm `TitleController/get` vào allowlist STOREFRONT vì `ReserveTitleUseCase` gọi catalog bằng token độc giả. Làm (b) mà chưa có (a) là mở rộng token không ai dùng. Hợp đồng dự kiến cho đợt sau: `POST /public/t/{code}/me/reservations` body `{bookRef}`, lỗi `READER_NOT_ELIGIBLE`, `COPY_AVAILABLE`, `ALREADY_RESERVED`, `TITLE_NOT_FOUND`→`BOOK_NOT_FOUND`. Ảnh hưởng tới phần gỡ ở admin: xem Q2.

### 2.7. Gỡ ở admin

Khóa là `Controller/method` của **service nội bộ**; các method nội bộ không đổi tên, không xóa, nên không role nào mất quyền. Xóa method ở `lms-api` không đổi khóa nào (lms-api không đăng ký khóa).

| Lớp | Gỡ | Giữ (lý do) |
|---|---|---|
| `lms-api` | `BorrowingController.mine` (`GET /api/borrowings/mine`), `BorrowingController.get` (`GET /api/borrowings/{id}`, phiếu của chính mình; ngoài danh sách bạn nêu, xem dưới), `FineController.mine`, `UserController.myReaderProfile`, `ReservationController.mine`, `.cancel`; phương thức `CirculationClient`/`UserClient` và DTO chỉ còn phục vụ các method này | `ReservationController.expireNow` (nhân viên); `.reserve` tùy Q2; `BorrowingController.detail`, `byReader`, `search`; `FineController.byReader`, `collect`; `UserController.me` (hồ sơ tài khoản nhân viên) |
| Service nội bộ | Không gỡ gì | `/internal/users/me/reader-profile` (circulation gọi khi đặt trước); mọi endpoint `mine` là đích của customer-api |
| FE nhân viên | `pages/circulation/Mine.tsx`; route `mine` và `MyBorrowings` ở `router.tsx`; mục "Phiếu của tôi" ở `nav-links.ts`; khóa `/circulation/mine` ở `search-keywords.ts`; nhánh không phải nhân viên ở `Fines.tsx` (thiếu `FINE_BY_READER` → `NoPermission`); nhánh `/api/borrowings/{id}` ở `BorrowingDetail.tsx` và `ReturnDesk.tsx` (thiếu `BORROWING_GET_ANY` → không xem được); thẻ "thẻ thư viện" ở `Profile.tsx` (Q4); `Reservations.tsx`, `ReservationsView.tsx`, mục "Đặt trước", `ReserveButton` (theo Q2); e2e và mock tương ứng | `Fines.tsx` nhánh nhân viên; `BorrowingDetail.tsx` nhánh `/detail`; `Profile.tsx` phần tài khoản/đăng nhập ngoài; nút "hủy đặt trước quá hạn" chuyển sang trang nhân viên khác, ví dụ đầu trang "Tra phiếu mượn" (`can(RESERVATION_EXPIRE)`) |
| system | Migration `system/V6__remove_reader_self_service_menus.sql` (⚠️ kiểm số kế tiếp ngay trước khi tạo; hiện `V5`): xóa `circulation-mine` (và `circulation-reservations` theo Q2) của tenant mặc định, theo mẫu `V5__remove_catalog_new_menu.sql` (`set_config('app.tenant_id', ...)` vì `menus` FORCE RLS); sửa `DefaultMenus` khớp | Tenant khác đã chép menu tự xóa ở Quản trị > Menu (như V5) |

- `BorrowingController.get` (lms-api) được đưa vào danh sách gỡ dù bạn không nêu: nó là bản "phiếu của tôi" theo id, chỉ FE nhân viên dùng ở nhánh người không có `BorrowingController/getAny`, tức độc giả; cổng độc giả có route tương ứng (2.4).
- Màn nhân viên đang dùng các endpoint này và **phải giữ phần nhân viên**: `Fines.tsx` (tra và thu phạt), `BorrowingDetail.tsx`/`ReturnDesk.tsx` (nhánh `/detail`), `Profile.tsx` (tài khoản), `ReservationsView.tsx` (chỉ nút hủy quá hạn của nhân viên, chuyển chỗ). Nhân viên có thẻ độc giả (ví dụ giảng viên kiêm thủ thư) dùng cổng độc giả bằng cùng tài khoản: token STOREFRONT không có `admin` nhưng các route `me/**` không cần `admin`.

## 3. Thiết kế

### 3.1. Thành phần

```
FE cổng độc giả ──/public/t/{code}/me/**──> nginx (location /public/) ──> customer-api
  customer-api, nhóm mới lms.customer.reader { client (interface), client.http, dto, web }
    ReaderPortalController
      1. storefronts.resolve(code, LIBRARY)     -> 404 SHOP_NOT_AVAILABLE nếu tenant không khả dụng/không LENDING
      2. guard.requireSession(access, auth)     -> 401 (svc, thiếu aud) | 403 SHOP_SESSION_MISMATCH
      3. CirculationReaderClient / IdentityReaderClient, header Authorization = token độc giả
      4. (chỉ đặt trước) CatalogTitleClient.lookup(ids) bằng access.accessToken() (token svc), một lô
      5. ánh xạ sang record công khai, Cache-Control: no-store
  circulation-service  /internal/borrowings/mine|{id}, /internal/fines/mine, /internal/reservations[/{id}]
  identity-service     /internal/users/me/reader-profile, /internal/storefront/access-tokens (thêm modules)
  catalog-service      /internal/books/lookup (token svc)
```

- `Feature` (enum nội bộ customer-api `SHOP`→`SALES`, `LIBRARY`→`LENDING`) nằm ở `lms.customer.storefront`, không import `TenantModule` (ArchUnit cấm `lms.security..`).
- ArchUnit: thêm luật nhóm `reader` chỉ dùng `web` của chính nó; luật hiện có đã cấm `lms.security..`, `lms.tenant..`, `lms.circulation..`, `lms.identity..`.
- Cấu hình: `app.services.circulation-url` (`CIRCULATION_SERVICE_URL`, mặc định `http://localhost:8083`), `app.services.catalog-url` (`CATALOG_SERVICE_URL`, mặc định `http://localhost:8082`); bulkhead cho hai đích mới theo mẫu đích hiện có. Compose: thêm hai biến cho `customer-api`, `depends_on` circulation/catalog `required: false`; nên đổi `sales-service` thành `required: false` để `customer-api` chạy được cho tenant chỉ thư viện (`be-dev` xác nhận cú pháp compose đang dùng).

### 3.2. Hợp đồng ở ranh giới (`be-dev` chốt chi tiết, ghi vào `docs/api/customer-api.md`)

| Route công khai | Gọi nội bộ | Lỗi nghiệp vụ trả ra |
|---|---|---|
| `GET /public/t/{code}/me/borrowings` | `GET /internal/borrowings/mine` | 401, 403 `SHOP_SESSION_MISMATCH`, 404 `SHOP_NOT_AVAILABLE`, 503 |
| `GET /public/t/{code}/me/borrowings/{borrowingId}` | `GET /internal/borrowings/{id}` | + 404 `BORROWING_NOT_FOUND` (không phải UUID, của người khác, không tồn tại: cùng thân) |
| `GET /public/t/{code}/me/fines` | `GET /internal/fines/mine` | như trên |
| `GET /public/t/{code}/me/reservations` | `GET /internal/reservations` + `POST /internal/books/lookup` (svc) | + 403 `FORBIDDEN` (role thiếu khóa) |
| `POST /public/t/{code}/me/reservations/{reservationId}/cancellation` | `DELETE /internal/reservations/{id}` | + 404 `RESERVATION_NOT_FOUND` (cả của người khác), 422 `INVALID_RESERVATION` |
| `GET /public/t/{code}/me/reader-profile` | `GET /internal/users/me/reader-profile` | + 404 `READER_PROFILE_NOT_FOUND` |
| `GET /public/t/{code}` (sửa) | `POST /internal/storefront/access-tokens` (đã có, thêm `modules`) | thêm `shopEnabled`, `libraryEnabled` |

## 4. Phân việc

Thứ tự: identity và customer-api làm song song được (customer-api chịu được identity cũ); gỡ admin chỉ sau khi FE cổng độc giả đã chạy (Q1), FE nhân viên gỡ trước hoặc cùng lúc với `lms-api`.

| | Việc | Người | Phụ thuộc |
|---|---|---|---|
| ⏳ B-R1 | identity: `ResolveStorefrontTenantUseCase` nhận SALES hoặc LENDING, `AccessTokenResponse.modules`; `SignupUseCase` kiểm SALES; allowlist STOREFRONT thêm `ReservationController/mine,ReservationController/cancel` (hai chỗ mặc định); sửa `docs/api/token-audience.md` | be-dev | |
| ⏳ B-R2 | customer-api: `StorefrontAccess.modules` (vắng = `[SALES]`), `resolve(code, Feature)`, route bán hàng kiểm SALES, `GET /public/t/{code}` thêm hai cờ | be-dev | song song B-R1 |
| ⏳ B-R3 | customer-api: nhóm `reader` (client circulation, identity, catalog lookup; DTO 2.4; controller; `AUTHENTICATED_API`; mã lỗi 2.5; cấu hình URL, bulkhead, compose); `docs/api/customer-api.md` | be-dev | B-R2 |
| ⏳ T-R1 | WebMvc customer-api (client giả): không token 401; token không `aud lms-storefront` 401; token `svc` 401; tenant lệch 403 `SHOP_SESSION_MISMATCH`; tenant `modules=[SALES]` gọi `me/**` → 404 `SHOP_NOT_AVAILABLE` **không gọi client**; tenant `modules=[LENDING]` gọi `books`, `orders`, `signup` → 404 cùng thân; tập tên trường đúng bảng 2.4 (không `userId`, `readerId`, `tenantId`, `copyId`, `bookCopyId`); `no-store`; id không phải UUID → 404 không gọi client; `FORBIDDEN` không chứa tên khóa; `MODULE_NOT_ENABLED` → 404; 5xx → 503; `DELETE` trên đường dẫn hủy → 403/405, không tới controller | tester | B-R3 |
| ⏳ T-R2 | Client HTTP (`MockRestServiceServer`): header `Authorization` gửi circulation/identity là **token độc giả**, gửi catalog là token `svc`; danh sách N đặt trước → đúng 1 lời gọi lookup; catalog lỗi → `title = null`, vẫn 200 | tester | B-R3 |
| ⏳ T-R3 | ArchUnit `CustomerApiArchitectureTest` xanh với nhóm mới (không `lms.security..`, `lms.tenant..`, JPA); luật nhóm `reader` | tester | B-R3 |
| ⏳ T-R4 | identity: resolver (LENDING-only có kết quả và `modules`; không module công khai rỗng; số truy vấn như nhau mọi nhánh), signup LENDING-only → `SIGNUP_NOT_AVAILABLE`, `TokenChannelTest` cho allowlist mới; circulation: token có `aud=lms-storefront`, `modules=[LENDING]`, khóa `ReservationController/mine` gọi được `mine`/`cancel`, thiếu LENDING → `MODULE_NOT_ENABLED` | tester | B-R1 |
| ⏳ P-R1 | Đo `GET me/borrowings` và `me/reservations` với độc giả 500 phiếu/50 đặt trước: p95, kích thước phản hồi, số lời gọi phía sau không tăng theo số dòng | perf-tester | B-R3 |
| 🚫 B-R4 | Gỡ `lms-api` (bảng 2.7) và test WebMvc của các method đó; `docs/api/circulation.md` | be-dev | FE cổng độc giả chạy (Q1), Q2, Q4 |
| 🚫 F-R1 | FE nhân viên (repo `library-management-system-fe`): gỡ theo bảng 2.7, chuyển nút hủy quá hạn, sửa e2e/mock | be-dev hoặc fe-dev | cùng đợt B-R4 |
| 🚫 B-R5 | system `V6` xóa menu + `DefaultMenus` | be-dev | cùng đợt F-R1 |
| ⏳ D-R1 | Tài liệu: `CLAUDE.md` mục customer-api (danh sách endpoint), README, ADR-0011 (ghi chú: điều kiện khả dụng nay gồm LENDING, trỏ ADR này) | be-dev | B-R3 |

## 5. Rủi ro và việc chưa kiểm chứng

- ⚠️ Thay đổi hành vi công khai: tenant chỉ LENDING nay `200` ở `GET /public/t/{code}` và đăng nhập được ở cổng công khai. Mọi route bán hàng phải kiểm SALES ở BFF (T-R1), nếu sót thì vẫn fail-closed nhờ sales trả `MODULE_NOT_ENABLED` → 404.
- ⚠️ Thứ tự triển khai: identity mới với customer-api cũ làm tenant LENDING-only hiện như storefront (customer-api cũ không kiểm module). Triển khai customer-api (B-R2) trước hoặc cùng identity (B-R1).
- ⚠️ Khóa đặt trước trong token STOREFRONT chỉ có sau lần đăng nhập/refresh kế tiếp (ADR-0003); role độc giả của tenant tạo sau V9 phải có khóa `ReservationController/mine|cancel` (⚠️ chưa kiểm mẫu role của tenant mới), nếu không trả `403 FORBIDDEN`.
- ⚠️ Gỡ ở admin trước khi có FE cổng độc giả làm độc giả mất toàn bộ màn tự phục vụ. Không tìm thấy FE storefront/cổng khách trong workspace.
- ⚠️ Danh sách phiếu mượn/đặt trước không phân trang (P-R1).
- ℹ️ Dò tài khoản/trạng thái khóa qua login: không đổi so với ADR-0011 2.13 (đã gộp `LOGIN_FAILED`), nhưng phạm vi tenant áp dụng rộng hơn (thêm tenant LENDING-only).
- ℹ️ `aud` không được kiểm ở circulation/identity: token STOREFRONT dùng được ở mọi endpoint `@AuthenticatedOnly` nội bộ. Không mới (nginx chặn `/internal/**`), nhưng nếu sau này service bật kiểm `aud` phải cho phép `lms-storefront` ở các endpoint trong bảng 2.3.

## 6. Câu hỏi mở (cần người dùng quyết)

- Q1. FE nào làm cổng độc giả (app storefront riêng, hay thêm vào FE nào hiện có) và ai làm? Khuyến nghị: cùng app storefront với khách mua sách (một phiên STOREFRONT, cùng origin tenant theo ADR-0013); chỉ gỡ admin (B-R4, F-R1, B-R5) sau khi FE này lên.
- Q2. Tạo đặt trước: chấp nhận độc giả tạm thời **không tự tạo** đặt trước từ khi gỡ admin tới đợt có tra cứu sách thư viện ở cổng độc giả? Khuyến nghị: chuyển xem + hủy ngay; **giữ** `lms-api` `ReservationController.reserve`, `.mine`, `.cancel`, trang "Đặt trước" và `ReserveButton` ở FE nhân viên cho tới đợt đó, rồi gỡ cùng lúc. Nếu chấp nhận gián đoạn thì gỡ hết trong đợt này.
- Q3. Có mở tự đăng ký tài khoản độc giả ở cổng thư viện không? Khuyến nghị: không (thẻ do thủ thư cấp); `SignupUseCase` kiểm SALES.
- Q4. Thẻ "thẻ thư viện" ở trang "Hồ sơ của tôi" (FE nhân viên) cho nhân viên có thẻ độc giả: khuyến nghị gỡ cùng `UserController.myReaderProfile` (họ xem ở cổng độc giả bằng cùng tài khoản); nếu muốn giữ thì giữ endpoint lms-api này và bỏ nó khỏi danh sách gỡ.
