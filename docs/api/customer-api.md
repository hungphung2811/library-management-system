# customer-api: hợp đồng công khai /public/** (C0, C1 danh mục, C2 đơn hàng và phiên khách, CORS theo tenant)

> **Base URL:** `<ORIGIN>` = `http://localhost:8080` (nginx). Mọi đường dẫn dưới đây bắt đầu bằng `/public/` và **không có `/api`**. Không gọi cổng 8088 trực tiếp. Chi tiết và bảng nhầm lẫn thường gặp: [README.md](README.md).

customer-api (ADR-0011) là cổng công khai cho khách của hệ thống SaaS: trang giới thiệu, form liên hệ, thông tin
storefront và đăng nhập khách. Không dùng JWT của FE quản trị, không có tenant trong header. Mọi đường dẫn nằm dưới
`/public/**`; nginx (8080) chuyển `/public/` tới customer-api. Lỗi luôn có dạng `{code, message, details?}`; thông điệp do
customer-api viết, không chép thông điệp nội bộ.

## Endpoint hiện có (C0)

| Method | Đường dẫn | Mô tả | Cache-Control |
|---|---|---|---|
| GET | `/public/site/pages` | Danh sách trang đã xuất bản `[{slug, title, sortOrder}]` | `max-age=60, public` |
| GET | `/public/site/pages/{slug}` | Trang đã xuất bản `{slug, title, bodyHtml, publishedAt}` (`bodyHtml` đã được system-service render an toàn) | `max-age=60, public` |
| POST | `/public/site/contact-requests` | Gửi form liên hệ/dùng thử, trả 201 không thân | `no-store` |
| GET | `/public/t/{code}` | Thông tin cửa hàng/thư viện `{code, name, shopEnabled, libraryEnabled}` | `max-age=30, public` |
| GET | `/public/t/{code}/books` | Danh mục sách đang bán (query `keyword?, page?, size?`, size tối đa 50) | `max-age=30, public` |
| GET | `/public/t/{code}/books/{bookRef}` | Chi tiết sách | `max-age=30, public` |
| GET | `/public/t/{code}/books/{bookRef}/preview` | Đoạn đọc thử | `max-age=60, public` |
| GET | `/public/t/{code}/books/featured` | Sản phẩm nổi bật: mảng `BookSummaryView` (cùng trường phần tử của `books`), tối đa 8 (xem mục dưới) | `max-age=60, public` |
| GET | `/public/t/{code}/authors/featured` | Tác giả nổi bật `[{name}]`, tối đa 5 (xem mục dưới) | `max-age=60, public` |
| POST | `/public/t/{code}/auth/signup` | Đăng ký tài khoản khách, 201 không thân | `no-store` |
| POST | `/public/t/{code}/auth/login` | Đăng nhập khách theo tenant của đường dẫn | `no-store` |
| POST | `/public/t/{code}/auth/refresh` | Làm mới phiên, identity kiểm token thuộc đúng cửa hàng (khuyến nghị) | `no-store` |
| POST | `/public/t/{code}/auth/logout` | Đăng xuất, 204 | `no-store` |
| POST | `/public/auth/refresh` | (cũ, không khuyến nghị) làm mới phiên, không kiểm tenant | `no-store` |
| POST | `/public/auth/logout` | (cũ, không khuyến nghị) đăng xuất, 204 | `no-store` |
| POST | `/public/t/{code}/orders` | Đặt hàng (cần đăng nhập) | `no-store` |
| GET | `/public/t/{code}/orders` | Đơn của tôi (cần đăng nhập, `page?`, `size?` tối đa 50) | `no-store` |
| GET | `/public/t/{code}/orders/{orderId}` | Một đơn của tôi (cần đăng nhập) | `no-store` |
| POST | `/public/t/{code}/orders/{orderId}/cancellation` | Hủy đơn đang chờ xác nhận hoặc đang chờ thanh toán (cần đăng nhập) | `no-store` |
| GET | `/public/t/{code}/orders/{orderId}/payment` | Trạng thái thanh toán của đơn của tôi, kèm QR SePay (cần đăng nhập, ADR-0029, xem "Thanh toán online qua SePay") | `no-store` |
| GET | `/public/t/{code}/payment-options` | Phương thức thanh toán cửa hàng đang mở (cần đăng nhập, ADR-0029) | `no-store` |
| GET | `/public/t/{code}/me/borrowings` | Phiếu mượn của tôi (cần đăng nhập, module LENDING) | `no-store` |
| GET | `/public/t/{code}/me/borrowings/{borrowingId}` | Một phiếu mượn của tôi | `no-store` |
| GET | `/public/t/{code}/me/fines` | Phạt của tôi | `no-store` |
| GET | `/public/t/{code}/me/reservations` | Đặt trước của tôi (kèm tên sách) | `no-store` |
| POST | `/public/t/{code}/me/reservations/{reservationId}/cancellation` | Hủy đặt trước của tôi | `no-store` |
| GET | `/public/t/{code}/me/reader-profile` | Thẻ độc giả của tôi | `no-store` |

⚠️ Đề xuất, chưa có code (ADR-0019, hợp đồng ở `storefront-kham-pha.md`): thêm `sort`, `inStock` cho `GET .../books`,
`GET /public/t/{code}/books/suggestions` (`max-age=15`) và `GET /public/t/{code}/banners` (`max-age=60`). Khi có code thì
chuyển các dòng này vào bảng trên.

Tập đường dẫn là DANH SÁCH ĐÓNG: mọi `/public/**` ngoài bảng trên (kể cả `/public/t/{code}/anything`) bị chặn (401 khi
chưa đăng nhập). Các route `orders`, `contact-requests` và `me/**` cần đăng nhập; các route còn lại công khai và BỎ QUA header `Authorization`.

`HEAD` đi cùng đường `GET` công khai (`/public/site/**`, `/public/t/{code}`): trả cùng status và header, không thân, không
còn 401 (crawler, giám sát, CDN dùng HEAD).

`slug` phải khớp `^[a-z0-9-]{1,80}$`, sai định dạng trả 404 `CMS_PAGE_NOT_FOUND` mà không gọi system-service. Trang không
tồn tại được đệm phủ định tối đa 20 giây (tối đa 500 slug) nên slug lạ lặp lại không chạm system-service; trang vừa xuất
bản có thể hiện chậm tối đa 20 giây so với đệm phủ định.

**Mã tenant `{code}`** chỉ nhận ASCII `[a-zA-Z0-9-]`, 2 đến 50 ký tự, không phân biệt hoa thường (tự chuẩn hóa chữ thường,
bỏ khoảng trắng đầu/cuối). Ký tự ngoài tập này (kể cả Unicode như Kelvin U+212A) trả 404 `SHOP_NOT_AVAILABLE` ngay, không
gọi identity.

### POST /public/site/contact-requests

```json
{
  "fullName": "Nguyễn An", "email": "an@example.vn", "phone": "0900000000",
  "organization": "Thư viện X", "businessType": "LIBRARY",
  "message": "Muốn dùng thử", "consent": true, "website": ""
}
```

- `fullName` bắt buộc, tối đa 100; `email` bắt buộc, đúng định dạng, tối đa 254; `phone` tùy chọn, tối đa 20;
  `organization` bắt buộc, tối đa 200; `businessType` là `LIBRARY|BOOKSTORE|OTHER`; `message` bắt buộc, tối đa 2000.
- **Ký tự điều khiển** (NUL, ESC, DEL, C1...) bị từ chối với 400 `VALIDATION_FAILED` ở `fullName`, `email`, `phone`,
  `organization` và `message`; riêng `message` cho phép xuống dòng và tab (LF, CR, TAB). `details` chỉ có tên trường
  và quy tắc, không kèm giá trị.
- `consent` phải là boolean JSON `true` tường minh: thiếu hoặc `false` trả 400 `CONSENT_REQUIRED`; chuỗi (`"true"`,
  `"yes"`) hoặc số (`1`) trả 400 `VALIDATION_FAILED` (không ép kiểu).
- `website` là honeypot (FE để trống, ẩn): có giá trị (kể cả rất dài) thì vẫn trả 201 nhưng không lưu, không có ràng buộc
  độ dài để bot không phân biệt được ô bẫy.
- Chuyển sang system-service `POST /internal/cms/public/contact-requests` với body
  `{fullName, email, phone, organization, businessType, message, consent}` (không có `website`).

### POST /public/t/{code}/auth/login

Body `{username, password}` (username tối đa 100, password tối đa 72). Phản hồi `{accessToken, refreshToken, expiresIn}`.
Tenant lấy từ `{code}` (đã qua kiểm tra storefront), body không có `tenantCode`. Sai mật khẩu, không có tài khoản hoặc
không thuộc tenant, **tài khoản bị khóa tạm (423) hoặc bị vô hiệu hóa (403)** đều trả cùng 401 `LOGIN_FAILED`, cùng
status, mã, thông điệp và Cache-Control (ADR-0011 2.13 phương án A), để không dò được trạng thái tài khoản qua cổng công
khai. FE chỉ hiển thị thông điệp chung, nên gợi ý "thử lại sau ít phút hoặc liên hệ cửa hàng". `POST /public/auth/refresh`
và `/public/auth/logout` nhận `{refreshToken}`. Refresh giữ mã riêng `423 ACCOUNT_LOCKED` / `403 ACCOUNT_DISABLED`: người
gọi đã có refresh token hợp lệ (đã xác thực trước đó) nên không phải cửa dò tài khoản.

Lưu ý ngoài phạm vi: cửa `/api/auth/login` của nhân viên (lms-api, identity) có cùng điểm yếu (kiểm khóa trước mật khẩu,
trả 423/403 riêng). Đó là việc riêng, chưa xử lý ở đây.

### Đăng ký, phiên khách (kênh token STOREFRONT)

- `POST /public/t/{code}/auth/signup`, body `{username (3..100), password (8..72), fullName (<=255), email (hợp lệ, <=255)}`.
  Trường khác bị bỏ qua (tenant luôn lấy từ đường dẫn). `201` không thân (chưa đăng nhập; gọi login riêng). Lỗi:
  `403 SIGNUP_NOT_AVAILABLE` (cửa hàng không mở đăng ký), `409 DUPLICATE_USERNAME`, `400 VALIDATION_FAILED`,
  `404 SHOP_NOT_AVAILABLE`.
- Xác minh email bằng OTP (audit A3, chi tiết ở `docs/api/identity-signup.md`; identity bật mặc định, tắt bằng `lms.identity.email-verification.enabled=false` thì mọi tài khoản mới coi như đã xác minh):
  - Sau `signup` identity gửi mail chứa OTP 6 chữ số (hết hạn 10 phút theo cấu hình). Tài khoản chưa xác minh KHÔNG đăng nhập được: `POST /public/t/{code}/auth/login` trả `403 EMAIL_NOT_VERIFIED` (chỉ sau khi mật khẩu đúng; sai mật khẩu vẫn là `401 LOGIN_FAILED`). FE nên chuyển sang màn nhập mã.
  - `POST /public/t/{code}/auth/verify-email`, body `{username? (<=100), email? (hợp lệ, <=255), otp (đúng 6 chữ số)}` (một trong `username`/`email`; trường khác bị bỏ qua). `204` không thân khi đúng. Mọi lý do sai (không có tài khoản, OTP sai/hết hạn/hết lượt thử, đã xác minh) đều là `400 EMAIL_VERIFICATION_FAILED` cùng thân; sai định dạng body là `400 VALIDATION_FAILED`; `404 SHOP_NOT_AVAILABLE`. Quá số lần thử (mặc định 5) thì OTP bị vô hiệu cho tới khi gửi lại.
  - `POST /public/t/{code}/auth/resend-verification`, body `{username?, email?}`. LUÔN `202` không thân (không lộ tài khoản có tồn tại; giãn cách tối thiểu 60 giây giữa hai lần gửi, trần 5 lần/24 giờ): FE nên khóa nút gửi lại 60 giây, không dựa vào phản hồi để biết thư có được gửi.
  - Cả hai `Cache-Control: no-store`.
- Login, refresh đều do customer-api tự đặt `channel: "STOREFRONT"` gửi identity (client không gửi được). Token trả về có
  `aud = ["lms-storefront"]`, chỉ dùng được ở route `orders` của customer-api, không dùng được ở `/api/**` của nhân viên.
- `POST /public/t/{code}/auth/refresh` và `/logout` nhận `{refreshToken}`. Refresh gửi kèm `tenantId` của `{code}`: token
  của tenant khác trả `401 UNAUTHORIZED` và KHÔNG bị xoay vòng. Logout không kiểm tenant.
- Đường dẫn cũ `/public/auth/refresh|logout` giữ để tương thích: không kiểm tenant, CORS chỉ cho domain nền tảng và origin dev.

### Danh mục sách

Phản hồi chỉ có các trường sau (test so tập tên trường); KHÔNG có số tồn, giá nhập, ngưỡng, tenantId, ISBN. Thông tin bổ sung công khai (tùy chọn, null; `tags` rỗng nếu chưa có) xem `docs/api/catalog-title-extra-fields.md`.

| Endpoint | Trường |
|---|---|
| `GET .../books` | `{items:[{bookRef, title, author, price, inStock, hasPreview, subtitle, publicationYear, edition, language, pageCount, format, translator, series, seriesNumber, coverImageUrl, tags}], page, size, total}`; `page` từ 0; `size` mặc định 20, tối đa 50 (lớn hơn thì cắt về 50); `keyword` <=100 ký tự (dài hơn hoặc có ký tự điều khiển: 400) |
| `GET .../books/{bookRef}` | `{bookRef, title, author, price, inStock, hasPreview, description, subtitle, publicationYear, edition, language, pageCount, format, translator, series, seriesNumber, coverImageUrl, tags}` (`description` null nếu chưa nhập; `format` là PAPERBACK|HARDCOVER|EBOOK; `coverImageUrl` chỉ http/https) |
| `GET .../books/{bookRef}/preview` | `{bookRef, title, previewText}` |

`bookRef` là UUID (chính là `titleId` khi đặt hàng). `price` là số nguyên VND. `bookRef` sai định dạng hoặc sách không có/không
đang bán: `404 BOOK_NOT_FOUND`. Văn bản thuần: hiển thị bằng `textContent`, không `innerHTML`.

#### Tác giả nổi bật

`GET /public/t/{code}/books/featured` -> `200 [BookSummaryView]`, tối đa 8 phần tử, `Cache-Control: max-age=60, public`, công khai (bỏ qua `Authorization`). Cửa hàng không khả dụng: `404 SHOP_NOT_AVAILABLE`, không gọi sales. Route literal, không bị hiểu là `{bookRef}`. Thuật toán: lấy tối đa 8 `titleId` đầu của `top-sold` (90 ngày, giữ thứ tự bán chạy), tra thẻ sách bằng MỘT lời gọi sales `GET /internal/sales/storefront/items/by-ids`, chỉ giữ sách `inStock=true`; chưa đủ 8 (hoặc chưa có đơn nào) thì bù bằng sách mới nhất còn hàng (`items?sort=newest&inStock=true`), loại trùng, để carousel trang chủ không trống.

`GET /public/t/{code}/authors/featured` -> `200 [{"name":"..."}]`, tối đa 5 phần tử, `Cache-Control: max-age=60, public`. Chỉ có trường `name` (không số lượng bán, doanh thu hay id). Cửa hàng không khả dụng: `404 SHOP_NOT_AVAILABLE` (module SHOP), không gọi catalog/sales. Thuật toán (cùng khoảng 90 ngày):
1. Lấy tác giả có ấn bản mới trong 90 ngày từ catalog (`GET /internal/books/new-authors`, mới nhất trước); rỗng thì trả `[]`.
2. Lấy 100 đầu sách bán chạy từ sales (`GET /internal/sales/storefront/top-sold`), tra tác giả bằng MỘT lời gọi lô `POST /internal/books/lookup`, cộng số lượng theo tác giả (so khớp bỏ khoảng trắng đầu/cuối và không phân biệt hoa thường).
3. Kết quả là các tác giả của bước 1 xếp theo số lượng bán giảm dần, hòa theo thứ tự mới nhất trước, lấy 5; tác giả chưa bán được (số lượng 0) điền vào chỗ trống ở cuối. Trả tên hiển thị gốc từ catalog.
Lỗi sales/catalog đi qua bộ xử lý lỗi chung (503 `SERVICE_UNAVAILABLE`), không hạ cấp.

#### Gộp biến thể theo tác phẩm (ADR-0026, pha 2c)

Chỉ THÊM, tương thích ngược; chi tiết ở `docs/api/sales.md` mục "Storefront gộp biến thể".
- `GET .../books` thêm query `group`: vắng hoặc `EDITION` (mặc định, như cũ) | `WORK` (gộp ấn bản cùng tác phẩm thành một thẻ). Chỉ nhận đúng `EDITION|WORK`, phân biệt hoa thường; giá trị khác (rỗng, `work`, `WORK,EDITION`...) là `400 VALIDATION_FAILED`, kiểm trước khi phân giải cửa hàng và không gọi sales. Vắng thì không gửi tham số sang sales. Cache giữ `max-age=30, public`.
- Mục của danh sách thêm `workId`, `workTitle`, `variantCount`, `priceFrom` (luôn có khóa, null khi không có; `variantCount`/`priceFrom` chỉ khác null ở `group=WORK`). Ở `WORK`, `bookRef`/`title`/`price` là của ấn bản đại diện, `inStock` là của cả nhóm, `priceFrom` là giá thấp nhất (VND) trong nhóm.
- `GET .../books/{bookRef}` thêm `workId`, `workTitle` và `variants[{bookRef, format, language, translator, edition, publicationYear, partNumber, partLabel, coverImageUrl, price, inStock}]` (gồm cả chính ấn bản đang xem, sắp theo `partNumber`; ấn bản chưa gắn tác phẩm có `workId = null` và `variants` chỉ có chính nó).
- Đặt hàng KHÔNG đổi: khách chọn biến thể rồi gửi `bookRef` của biến thể đó làm `lines[].titleId` như hiện có. Cổng độc giả (`me/**`, ADR-0023) không đổi.
- Vẫn KHÔNG có số tồn, giá nhập, ngưỡng, tenantId, ISBN; test so tập tên trường đã cập nhật.

### Đơn hàng (cần đăng nhập)

Header `Authorization: Bearer <accessToken của login storefront>`. Lỗi phiên: không token, token hỏng/hết hạn, token nhân
viên (thiếu `aud` lms-storefront) hoặc token dịch vụ: `401`. Token của cửa hàng khác: `403 SHOP_SESSION_MISMATCH`.

`POST /public/t/{code}/orders` body (chỉ các trường này; giá, giảm giá, phí ship, trạng thái, người dùng, tenant do server tính, trường lạ bị bỏ qua):

```json
{"lines":[{"titleId":"<uuid>","quantity":1}],"fulfilment":"PICKUP|DELIVERY","paymentMethod":"BANK_TRANSFER|COD|CASH|SEPAY",
 "recipient":{"name":"..","phone":"..","address":".."},"clientRequestId":"<uuid>"}
```

`clientRequestId` bắt buộc, UUID do FE sinh mỗi lần bấm đặt hàng; gửi lại cùng khóa trả đơn cũ. `lines` 1..50 mục, `quantity` >=1;
`address` bắt buộc khi `DELIVERY`; `COD` chỉ với `DELIVERY`, `CASH` chỉ với `PICKUP` (sales kiểm, lỗi `400 VALIDATION_FAILED`).
Phản hồi `201` + `Location`, đơn:

`{id, orderNo, status, paymentStatus, paymentMethod, fulfilment, recipient{name,phone,address}, currency, subtotal, discountTotal,
vatTotal, shippingFee, grandTotal, createdAt, confirmedAt, paidAt, completedAt, cancelledAt, lines[{titleId, titleName, quantity,
unitPrice, lineTotal}], cancellable}`. `cancellable = (status == AWAITING_PAYMENT) || (status == PENDING_CONFIRMATION && paymentStatus == UNPAID)` (ADR-0029; trước đây chỉ `status == PENDING_CONFIRMATION`). Đơn đã `PAID` thì `cancellable=false`: khách liên hệ cửa hàng.

`GET /orders?page&size` trả `{items:[đơn], page, size, total}` (size tối đa 50). `POST /orders/{id}/cancellation` body tùy chọn
`{reason?<=500}`: `409 ORDER_STATE_CONFLICT` nếu đã qua bước chờ xác nhận. Đơn của người khác hoặc id sai định dạng: `404 ORDER_NOT_FOUND`.

#### Thanh toán online qua SePay (ADR-0029, ⚠️ đang cài đặt, chưa kiểm chứng)

- `paymentMethod=SEPAY` đi được với `PICKUP` và `DELIVERY`; đơn trả về `status=AWAITING_PAYMENT`, `paymentStatus=UNPAID`, `shippingFee` đã chốt lúc đặt (phí cố định của cửa hàng, không đổi sau khi trả). Tắt cổng cho website: `400 PAYMENT_NOT_ENABLED` ("Phương thức thanh toán này hiện không khả dụng"). QR/MOMO/CARD: `400 VALIDATION_FAILED`.
- `GET /public/t/{code}/orders/{orderId}/payment` trả `OrderPaymentView` (danh sách trường đóng, test so tập tên trường):
  `{orderId, orderNo, orderStatus, paymentStatus, paymentMethod, intent}`; `intent` là `null` với đơn không qua cổng, còn lại
  `{status: PENDING|PAID|EXPIRED|CANCELLED, provider: "SEPAY", qrPayload, amount, currency: "VND", reference, expiresAt, paidAt}`.
  `qrPayload` (chuỗi VietQR, FE dựng ảnh QR) chỉ có khi `intent.status=PENDING`, trạng thái khác là `null`; `reference` luôn có (mã `LMS…` khách đưa cho cửa hàng khi khiếu nại). KHÔNG có `tenantId`, id lệnh, mã giao dịch cổng, `version`.
- Quyền sở hữu: chỉ đơn của chính khách; đơn của người khác, không tồn tại hoặc `orderId` không phải UUID: `404 ORDER_NOT_FOUND` cùng thân (id sai định dạng không gọi sales). Token cửa hàng khác: `403 SHOP_SESSION_MISMATCH`. Phản hồi `Cache-Control: no-store`.
- Thăm dò: FE gọi mỗi 3 giây khi lệnh còn `PENDING`, dừng khi trạng thái cuối, khi quá `expiresAt` hoặc tab bị ẩn. Mỗi lần gọi có thể làm sales hỏi cổng SePay, nhưng sales hãm (tối đa 1 lần/5 giây/lệnh) nên FE vẫn nhận phản hồi ngay từ DB. nginx giới hạn `public_poll` 2 yêu cầu/giây/IP, burst 10; vượt thì 429 do nginx, FE giãn nhịp.
- Hạn thanh toán mặc định 15 phút (đồng thời là hạn giữ tồn); hết hạn đơn tự `CANCELLED` và tồn được nhả. Khách muốn đặt lại phải sinh `clientRequestId` mới (cùng khóa và cùng nội dung luôn trả đơn cũ, kể cả đã hết hạn/hủy).
- Khi cổng báo đã nhận tiền: đơn thành `PENDING_CONFIRMATION` + `PAID` (nhân viên xác nhận/giao như đơn online thường).
- `POST .../cancellation` với đơn `AWAITING_PAYMENT`: hủy đơn và lệnh, nhả tồn; nếu cổng vừa báo đã nhận tiền thì `409 ORDER_STATE_CONFLICT` (FE đọc lại đơn). Đơn đã `PAID`: `409 ORDER_STATE_CONFLICT`.
- `GET /public/t/{code}/payment-options` trả `{methods:[{method, fulfilments[], intentTtlMinutes?}], deliveryFee}`; `SEPAY` chỉ có trong `methods` khi cửa hàng/hệ thống đã bật. FE dùng danh sách này để hiện lựa chọn thay vì tự đoán.
- Cả hai route GET mới chỉ cần đăng nhập storefront (không có khóa phân quyền riêng, không đổi allowlist token, xem `token-audience.md`).
- `/api/public/v1/orders` (ADR-0027): chưa hỗ trợ SEPAY, `paymentMethod=SEPAY` bị chặn `400 PAYMENT_NOT_ENABLED`.

Mã lỗi nghiệp vụ thêm: `409 INSUFFICIENT_STOCK` (`details` chỉ có `titleId=<uuid>`, không có số tồn), `409 CLIENT_REQUEST_CONFLICT`,
`409 TITLE_NOT_AVAILABLE`, `404 ORDER_NOT_FOUND`, `409 ORDER_STATE_CONFLICT`, `404 BOOK_NOT_FOUND`. `403 FORBIDDEN` có thông điệp cố định (không lộ tên khóa).

#### Yêu cầu xuất hóa đơn (ADR-0030 gói A; chi tiết ở `sales-invoices.md`)

`PUT /public/t/{code}/orders/{orderId}/invoice-request` (cần đăng nhập storefront như các route `orders`; nginx: PUT không bị vùng `public_order`, chỉ `public_read`/`public_tenant`). Body (record allowlist, trường lạ bị bỏ qua, thông tin là dữ liệu cá nhân nên không bị log):

```json
{"buyer":{"name":"..","company":"..","taxCode":"..","address":"..","email":".."}}
```

Cần `name` hoặc `company`; có `taxCode` (10 chữ số, `10-3` hoặc 12 chữ số) thì cần `company` và `address`. Phản hồi `200 {"orderId":"<uuid>","status":"OPEN|FULFILLED"}`, `Cache-Control: no-store` (không lặp lại thông tin khách đã gửi). Gửi lại khi yêu cầu còn `OPEN` thì ghi đè. Chỉ đơn ONLINE của chính khách: đơn của người khác, đơn POS, không tồn tại hoặc `orderId` sai định dạng đều `404 ORDER_NOT_FOUND` cùng thân. Mã lỗi nghiệp vụ thêm (customer-api viết thông điệp): `400 INVALID_TAX_CODE` ("Mã số thuế không hợp lệ"), `409 INVOICE_ALREADY_EXISTS` (đã xuất hóa đơn, liên hệ cửa hàng để đổi), `409 ORDER_NOT_INVOICEABLE` (đơn đã hủy). Hóa đơn nội bộ chưa có giá trị pháp lý của hóa đơn điện tử. FE storefront gọi route này sau khi đặt đơn thành công (hai request, không nguyên tử: lỗi thì đơn vẫn có, khách gửi lại từ chi tiết đơn).

### Cổng độc giả `/public/t/{code}/me/**` (cần đăng nhập, ADR-0023)

Cổng tự phục vụ của độc giả thư viện (phiếu mượn, phạt, đặt trước, thẻ). Dùng lại phiên STOREFRONT của
`POST /public/t/{code}/auth/login` (cùng token như `orders`, `aud = lms-storefront`). Không có tham số `userId`/`readerId`:
người gọi luôn là chủ của token (`sub`). Mọi phản hồi (kể cả lỗi) `Cache-Control: no-store`.

**Module theo nhóm route** (customer-api tự kiểm, không gọi service phía sau nếu không đạt; không đạt thì `404 SHOP_NOT_AVAILABLE`,
cùng một thân với mọi lý do không khả dụng, KHÔNG có mã `LIBRARY_NOT_AVAILABLE`):

| Nhóm route | Tenant cần |
|---|---|
| `books/**`, `banners`, `orders/**`, `contact-requests`, `auth/signup` | module SALES |
| `me/**` | module LENDING |
| `GET /public/t/{code}`, `auth/login`, `auth/refresh`, `auth/logout` | SALES hoặc LENDING |

`GET /public/t/{code}` trả thêm `shopEnabled` (có SALES) và `libraryEnabled` (có LENDING) để FE biết hiện phần nào; không trả
danh sách module thô. Tenant chỉ có LENDING trước đây `404` ở `GET /public/t/{code}` và login, nay `200`. Tenant chỉ có LENDING
không đăng ký tự do được (`auth/signup` cần SALES; thẻ độc giả do thủ thư cấp).

Thứ tự kiểm: không token/token hỏng/thiếu `aud`/token dịch vụ `401` (do bộ lọc bảo mật, trước controller); `{code}` không khả dụng
hoặc thiếu LENDING `404 SHOP_NOT_AVAILABLE`; token cửa hàng khác `403 SHOP_SESSION_MISMATCH`; rồi mới gọi circulation/identity
bằng token độc giả (các service tự kiểm module, quyền, sở hữu và tenant).

| Route | Phản hồi (chỉ các trường này, test so tập tên trường) | Lỗi nghiệp vụ |
|---|---|---|
| `GET .../me/borrowings` | mảng `[{id, status, borrowDate, dueDate, renewCount, items[{returnDate, condition}]}]` | |
| `GET .../me/borrowings/{borrowingId}` | một phiếu như trên | `404 BORROWING_NOT_FOUND` (không phải UUID, của người khác, không tồn tại: cùng thân) |
| `GET .../me/fines` | `{totalUnpaid, fines[{id, borrowingId, reason, amount, status, createdAt, paidAt}]}` | |
| `GET .../me/reservations` | mảng `[{id, bookRef, title, status, queuePosition, reservedAt, holdUntil}]` | `403 FORBIDDEN` (role thiếu khóa `ReservationController/mine`, thông điệp cố định) |
| `POST .../me/reservations/{reservationId}/cancellation` | một đặt trước như trên (thân request rỗng) | `404 RESERVATION_NOT_FOUND` (cả của người khác, id sai định dạng), `422 INVALID_RESERVATION` (không còn hiệu lực), `403 FORBIDDEN` |
| `GET .../me/reader-profile` | `{cardNo, fullName, email, readerType, cardExpiryDate, status, cardValid, phone, dateOfBirth, gender, address, faculty, className, memberCode, cardIssuedDate}` | `404 READER_PROFILE_NOT_FOUND` ("Bạn chưa có thẻ thư viện": chưa được cấp thẻ) |

- Đã bỏ so với dữ liệu nội bộ: `userId`, `readerId`, `tenantId`, `copyId`, `bookCopyId`, `fines` lồng trong phiếu. DTO công khai
  là record allowlist; trường nội bộ nào thêm sau này cũng không tự lộ.
- `bookRef` là `titleId` (như `books/{bookRef}`); `title` là tên sách, `null` nếu catalog không tra được (không làm hỏng
  danh sách). Tên được tra bằng một lời gọi lô tới catalog cho cả danh sách (không N+1). `status` đặt trước: `WAITING`, `READY`,
  `FULFILLED`, `EXPIRED`, `CANCELLED` (theo circulation); `holdUntil` chỉ có khi `READY`.
- `amount`, `totalUnpaid` là số (VND). Ngày `borrowDate`, `dueDate`, `holdUntil`, `cardExpiryDate`... dạng `yyyy-MM-dd`; thời điểm
  `createdAt`, `paidAt`, `reservedAt` dạng ISO-8601 UTC.
- Hủy đặt trước dùng `POST .../cancellation` (không `DELETE`): CORS công khai chỉ cho `GET, POST`. Danh sách đường dẫn `me/**` là
  DANH SÁCH ĐÓNG từng mẫu; phương thức/đường dẫn khác bị chặn (401/403/404/405), không tới controller.
- Danh sách phiếu mượn/đặt trước chưa phân trang (trả nguyên mảng như màn nhân viên cũ); phân trang là việc sau (P-R1).
- Chưa có: tạo đặt trước (`POST .../me/reservations`), hoãn tới đợt có tra cứu sách thư viện ở cổng độc giả (ADR-0023 2.6).
- Khóa `ReservationController/mine|cancel` phải nằm trong `permissions` của token STOREFRONT (identity thêm vào allowlist, B-R1) và
  role độc giả có khóa này; sau khi identity đổi, khóa mới chỉ có hiệu lực sau lần đăng nhập/refresh kế tiếp.

### Liên hệ gửi cửa hàng (cần đăng nhập, ADR-0015)

`POST /public/t/{code}/contact-requests`, header `Authorization: Bearer <accessToken storefront>`. Thứ tự kiểm: cửa hàng không khả dụng `404 SHOP_NOT_AVAILABLE` (trước mọi kiểm phiên của controller), rồi không token/token hỏng/thiếu `aud` lms-storefront/token dịch vụ `401`, token cửa hàng khác `403 SHOP_SESSION_MISMATCH`. Phân quyền theo khóa `ShopContactController/create` do sales kiểm (role Khách hàng có sẵn; thiếu khóa: `403 FORBIDDEN`, thông điệp cố định). Chỉ phương thức `POST`; mọi phương thức khác trên đường dẫn này bị chặn.

Body (chỉ 6 trường này; `userId`, `tenantId`, `status`, `orderId`, `id`... bị bỏ qua):

```json
{"topic":"ORDER|PRODUCT|PAYMENT|OTHER","fullName":"..","email":"..","phone":"..","message":"..","consent":true}
```

`fullName` 1..100 ký tự, `email` hợp lệ <=254, `phone` tùy chọn <=20 (chữ số và `+ ( ) . -` khoảng trắng, tối thiểu 6 chữ số), `message` 1..2000 ký tự (tính theo code point; ký tự điều khiển bị từ chối, `message` cho phép xuống dòng và tab), `consent` phải là `true` (thiếu/`false`: `400 CONSENT_REQUIRED`; không phải boolean JSON: `400`). Sai/thiếu trường: `400 VALIDATION_FAILED`, không kèm giá trị. Phản hồi `201` không thân, `Cache-Control: no-store`, không `Location` (cũng `201` khi sales coi là gửi trùng trong 10 phút, để không phân biệt trùng với mới). Người gửi là `sub` của token. Tối đa 5 yêu cầu/người/24 giờ trượt: vượt thì `429 SHOP_CONTACT_LIMIT_EXCEEDED` (thân JSON có `code`, khác 429 của nginx). Khách không xem lại được yêu cầu đã gửi; nhân viên xử lý ở `/api/sales/contact-requests` (xem `sales.md`). FE: ẩn form khi chưa đăng nhập, khóa nút khi đang gửi, xử lý `201/400/401/403/404/429 (hai loại)/503`, không log nội dung.

## CORS (`/public/**`, ADR-0013)

customer-api quyết CORS theo `Origin` và `{code}`; nginx chuyển nguyên `Origin` và không tự trả CORS cho `/public/**`.

- Cho phép: domain nền tảng (`PUBLIC_CORS_PLATFORM_ORIGINS`), origin dev (`PUBLIC_CORS_DEV_ORIGINS`, mặc định localhost:3000,5173; production đặt rỗng) và `allowedOrigins` của tenant của `{code}` (identity cấp, hiệu lực <= 60 giây). `/public/site/**` và `/public/auth/refresh|logout` (cũ) chỉ nhận domain nền tảng + dev.
- So khớp chính xác `scheme://host[:port]` sau khi hạ chữ thường; `Origin: null`, nhiều header, dấu phẩy, quá 300 ký tự bị từ chối.
- Preflight `OPTIONS` luôn `204`; origin không được phép chỉ mất header CORS (không mã lỗi riêng, mã/thân phản hồi không đổi, không lộ tenant). Lỗi 401/404/503/413 mang header CORS khi origin được phép. Có `Vary: Origin`; `Allow-Methods: GET, POST, OPTIONS`; `Allow-Headers: Authorization, Content-Type, X-Correlation-Id`; `Max-Age: 600`; `Expose-Headers: X-Correlation-Id`.
- KHÔNG có `Access-Control-Allow-Credentials`: phiên là bearer token trong header. FE gọi `fetch` không `credentials: 'include'` (axios `withCredentials: false`).
- Chế độ `PUBLIC_CORS_MODE` (`app.public-cors.mode`): `ENFORCE` (mặc định application.yml), `REPORT` (cho phép mọi origin, ghi log `cors-would-reject` tối đa 1 dòng/cặp (mã, origin)/60 giây), `OFF` (cho mọi origin hợp lệ cú pháp; docker-compose dev mặc định OFF).
- Website của tenant gọi storefront đang không khả dụng nhận lỗi CORS (không đọc được 404); `429` do nginx cũng không có CORS. FE coi lỗi mạng là "thử lại sau".
- Triển khai customer-api (B-O4) và nginx (B-O6) CÙNG MỘT LẦN, nếu không phản hồi có hai `Access-Control-Allow-Origin` (nginx cũ trả cho `/public/**`).

## Mã lỗi công khai

| Status | code | Khi nào |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Body sai/thiếu/quá dài, JSON hỏng, `businessType` lạ, email sai. Không kèm giá trị bị từ chối; `details` chỉ có tên trường và quy tắc. Mã 400 `VALIDATION_ERROR` và `CONTACT_REQUEST_INVALID` của system-service cũng ra mã này (không có `details`) |
| 400 | `CONSENT_REQUIRED` | Chưa đồng ý xử lý dữ liệu |
| 400 | `PAYMENT_NOT_ENABLED` | Phương thức thanh toán (SEPAY) chưa được bật cho website, hoặc đặt SEPAY qua `/api/public/v1/orders` (ADR-0029) |
| 413 | `PAYLOAD_TOO_LARGE` | Thân request lớn hơn 16 KB (kiểm theo `Content-Length` ở `/public/**`, trước khi đọc thân) |
| 400 | `REQUEST_REJECTED` | Service phía sau từ chối bằng mã không nằm trong danh sách cho phép |
| 401 | `LOGIN_FAILED` | Đăng nhập thất bại (gộp sai mật khẩu, không có tài khoản, không thuộc tenant, tài khoản bị khóa/vô hiệu) |
| 401 | `UNAUTHORIZED` | Phiên/refresh token không hợp lệ hoặc hết hạn |
| 403 | `ACCOUNT_DISABLED` | Chỉ ở `POST /public/auth/refresh`: tài khoản bị vô hiệu hóa. Login không bao giờ trả mã này |
| 403 | `FORBIDDEN` | Không có quyền (thông điệp cố định) |
| 404 | `CMS_PAGE_NOT_FOUND` | Trang không tồn tại, chưa xuất bản hoặc slug sai định dạng |
| 404 | `SHOP_NOT_AVAILABLE` | Tenant không tồn tại/bị khóa/không bật module cần cho nhóm route (SALES cho bán hàng, LENDING cho `me/**`, một trong hai cho đăng nhập; một thân cố định cho mọi lý do, chống dò tenant) |
| 404 | `NOT_FOUND` | Đường dẫn công khai không có handler |
| 403 | `SHOP_SESSION_MISMATCH` | Token của khách thuộc cửa hàng khác với `{code}` |
| 429 | `SHOP_CONTACT_LIMIT_EXCEEDED` | Khách gửi quá 5 yêu cầu liên hệ trong 24 giờ (thông điệp do customer-api viết; khác 429 của nginx không có `code`) |
| 404 | `BOOK_NOT_FOUND`, `ORDER_NOT_FOUND` | Sách/đơn không có (đơn của người khác cùng thân) |
| 404 | `BORROWING_NOT_FOUND`, `RESERVATION_NOT_FOUND`, `READER_PROFILE_NOT_FOUND` | Cổng độc giả: phiếu mượn/đặt trước không có (của người khác cùng thân), chưa có thẻ độc giả |
| 422 | `INVALID_RESERVATION` | Hủy đặt trước không còn hiệu lực |
| 409 | `INSUFFICIENT_STOCK`, `CLIENT_REQUEST_CONFLICT`, `TITLE_NOT_AVAILABLE`, `ORDER_STATE_CONFLICT` | Nghiệp vụ đặt/hủy đơn |
| 422 | `IDEMPOTENCY_KEY_REUSED` | `clientRequestId` đã dùng cho một đơn của chính khách nhưng nội dung đơn khác (gửi lại đúng nội dung thì vẫn trả đơn cũ) |
| 429 | `ORDER_LIMIT_EXCEEDED` | Vượt trần chống lạm dụng của cửa hàng: số đơn đang chờ xác nhận hoặc số đơn trong 24 giờ (thông điệp do customer-api viết) |
| 429 | `ORDER_RATE_LIMITED` | Đặt đơn quá nhanh (giới hạn theo khách hoặc theo cả cửa hàng; khác 429 của nginx không có `code`) |
| 400 | `INVALID_QUANTITY` | Số lượng vượt trần của cửa hàng (mỗi dòng hoặc mỗi đơn) |
| 403 | `SIGNUP_NOT_AVAILABLE` | Cửa hàng không mở đăng ký |
| 409 | `DUPLICATE_USERNAME` | Tên đăng nhập/email đã dùng |
| 405 | `METHOD_NOT_ALLOWED` | Sai phương thức |
| 406 | `NOT_ACCEPTABLE` | Header `Accept` không khớp định dạng phản hồi (chỉ JSON) |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | `Content-Type` của body không phải `application/json` (ví dụ `text/plain`). Không log ERROR, không lộ chi tiết |
| 423 | `ACCOUNT_LOCKED` | Chỉ ở `POST /public/auth/refresh`: tài khoản tạm khóa. Login không bao giờ trả mã này |
| 429 | (nginx) | Vượt giới hạn tốc độ, xem bên dưới |
| 500 | `INTERNAL_ERROR` | Lỗi không lường trước |
| 503 | `SERVICE_UNAVAILABLE` | Service phía sau lỗi 5xx, không gọi được, trả 404 không phải `SHOP_NOT_AVAILABLE` (URL/proxy sai), hoặc customer-api đang đạt giới hạn lời gọi đồng thời tới service đó (xem dưới). Cũng là mã trả khi identity trả 200 nhưng thân null/rỗng hoặc thiếu `accessToken`/`refreshToken`/`expiresIn` (login, refresh), và khi system-service trả 200 thân rỗng cho `GET /public/site/pages/{slug}`: không bao giờ trả 200 thiếu token cho khách, không 500 |

Mọi phản hồi lỗi có `Cache-Control: no-store`. Mã nghiệp vụ của sales/identity chỉ được chuyển tiếp khi nằm trong danh sách cho phép, thông điệp do customer-api viết.

## Giới hạn tốc độ (nginx, theo IP)

| Phạm vi | Vùng | Giới hạn |
|---|---|---|
| Mọi `/public/**` | `public_read` | 20 yêu cầu/giây, burst 40 |
| `POST /public/site/contact-requests` | `public_form` | 5 yêu cầu/phút, burst 3 (chỉ tính POST) |
| `POST /public/t/{code}/auth/signup` | `public_form` | 5 yêu cầu/phút, burst 3 |
| `POST /public/t/{code}/auth/verify-email` và `/auth/resend-verification` | chưa có vùng riêng (hiện rơi vào `public_read`) | ⚠️ Đề xuất location regex `public_form` burst 5 trong `infrastructure/nginx/nginx.conf` (chưa thêm, file đang có thay đổi của phiên khác). Trần thật nằm ở identity: lượt thử sai theo OTP, giãn cách và trần gửi lại theo tài khoản |
| `POST /public/t/{code}/contact-requests` | `public_form` | 5 yêu cầu/phút, burst 3 (thân tối đa 16 KB; nginx không thêm CORS) |
| `POST /public/t/{code}/orders[/...]` | `public_order` | 20 yêu cầu/phút, burst 10 (chỉ tính POST, gồm hủy đơn; thân tối đa 64 KB). Lớp ngoài cùng theo IP; giới hạn theo khách và theo cửa hàng làm ở sales-service bằng Redis (`ORDER_RATE_LIMITED`) |
| `GET /public/t/{code}/orders/{orderId}/payment` | `public_poll` | 2 yêu cầu/giây, burst 10 (theo IP, thăm dò thanh toán 3 giây/lần, ADR-0029; location đặt trước `/orders`). ⚠️ Số đề xuất, chưa đo |
| `POST /public/t/{code}/auth/login` | `login` | 10 yêu cầu/phút, burst 5 (preflight OPTIONS cũng tính vào vùng này) |
| Mọi `/public/t/{code}/**` | `public_tenant` | 50 yêu cầu/giây, burst 100 theo `{code}` (mọi IP cộng dồn, bảo vệ backend; mã sai dạng/chữ hoa dồn một khóa chung; số chờ đo P-O2) |

Vượt giới hạn trả 429 do nginx. Tính theo IP biên của nginx; đặt sau load balancer/CDN cần cấu hình `real_ip`.

## Giới hạn đầu vào và độ bền (customer-api)

- Thân request tối đa **16 KB** cho `/public/**` (`413 PAYLOAD_TOO_LARGE`); JSON lồng tối đa 32 tầng, vượt thì 400
  `VALIDATION_FAILED`. Thân gửi kiểu chunked (không có `Content-Length`) bị Jackson chặn ở 16 KB bằng 400.
- Lời gọi sang identity/system: connect timeout 2 giây, read timeout 5 giây (luôn hữu hạn; cấu hình 0/âm/thiếu dùng mặc
  định, tối đa 30 giây). Chỉ `GET` được thử lại đúng một lần (lỗi kết nối hoặc 502/503/504, không thử lại khi hết thời
  gian đọc). Tối đa 8 lời gọi đồng thời tới mỗi đích (`app.resilience.max-concurrent-calls`); hết chỗ thì trả 503 ngay
  thay vì xếp hàng, để một service treo không cạn thread Tomcat.
- Bộ đệm tra tenant: `expiresIn` của token bị chặn ở 24 giờ, lề làm mới là `min(60s, expiresIn/2)`; 404 từ identity chỉ
  được tin (và đệm phủ định 60 giây) khi thân có `code=SHOP_NOT_AVAILABLE`. Kết quả phủ định nằm ở bộ đệm riêng tối đa
  1000 mục (`customer.storefront.access.negative`), tách khỏi bộ đệm tenant hợp lệ: spam mã ngẫu nhiên không đẩy tenant
  hợp lệ khỏi bộ đệm.
- Đọc mã lỗi của service phía sau (`code` trong ErrorResponse) không bao giờ ném: thân rỗng, không phải JSON hoặc mã lạ
  khi đăng nhập được chuyển thành lỗi gốc (503 nếu 5xx, nếu không `REQUEST_REJECTED`), không bao giờ 500.
- Khởi động: ngoài profile `dev` (profile `prod`), `STOREFRONT_CLIENT_SECRET` rỗng hoặc là giá trị mặc định dev, hoặc
  `JWT_JWK_SET_URI` rỗng/trỏ localhost thì customer-api không khởi động. Profile `dev` (mặc định) chỉ cảnh báo một lần khi
  bí mật rỗng.
- Metric (Micrometer, không công khai qua actuator; chỉ `health,info` được mở): `customer.downstream.calls` (Timer; tag
  `target`=identity|system, `method`, `outcome`), `customer.downstream.rejected` và `customer.downstream.retries` (tag
  `target`), và `cache.*` của Caffeine cho bộ đệm `customer.storefront.access`, `customer.storefront.access.negative`, `customer.site.pagelist`,
  `customer.site.pages`, `customer.site.pages.missing`. Không có tag chứa mã tenant, đường dẫn hay dữ liệu khách.
- Docker: healthcheck của container gọi `/actuator/health`. Không kiểm JWKS của identity trong health: identity chậm tạm
  thời không được làm customer-api bị đánh dấu hỏng (các đường công khai không cần JWKS vẫn phục vụ được).

## Chưa làm / chưa kiểm chứng

- Liên hệ cửa hàng (`/public/t/{code}/contact-requests`) chưa làm (đang nằm ngoài danh sách đóng).
- Phía identity của ADR-0013 (bảng origin, trường `allowedOrigins`, kiểm `tenantId` khi refresh) do agent khác làm; customer-api đọc khoan dung (thiếu `allowedOrigins` = rỗng) và gửi `tenantId`/`channel` theo hợp đồng. Identity cũ chưa kiểm `tenantId` thì refresh theo cửa hàng không chặn được token tenant khác; nếu identity cũ từ chối trường lạ, đặt `app.storefront.refresh-tenant-id-enabled=false`.
- Cổng độc giả (ADR-0023): phía identity (B-R1: nới điều kiện tenant sang SALES hoặc LENDING, trả `modules` ở `/internal/storefront/access-tokens`, thêm khóa đặt trước vào allowlist STOREFRONT, `SignupUseCase` kiểm SALES) do agent khác làm. customer-api đọc khoan dung (thiếu `modules` = chỉ SALES) nên triển khai trước identity được; cho tới khi identity mới chạy, tenant chỉ LENDING vẫn `404` và độc giả chưa gọi được `me/reservations` (token thiếu khóa, `403 FORBIDDEN`). Triển khai customer-api trước hoặc cùng identity. Cần thêm `CIRCULATION_SERVICE_URL`, `CATALOG_SERVICE_URL` (mặc định `http://localhost:8083`, `http://localhost:8082`) cho container customer-api; `docker-compose.yml` chưa được cập nhật ở đợt này. Chưa kiểm chứng với circulation/identity/catalog chạy thật (chỉ test WebMvc, `MockRestServiceServer`).
- Hợp đồng nội bộ phía sau: system-service `/internal/cms/public/**` (xem `docs/api/cms.md`), identity `/internal/storefront/access-tokens` (header `X-Storefront-Client-Secret`, body `{tenantCode}`, phản hồi thêm `allowedOrigins`), `/internal/auth/{login,signup,refresh,logout,email-verifications/confirm,email-verifications/resend}` (login/refresh có `channel=STOREFRONT`, refresh có `tenantId?`), sales `/internal/sales/storefront/**` (token dịch vụ) và `/internal/sales/shop/**` (token khách).

## Public API v1 theo site key (pha P2, chỉ thêm)

Đường song song `/api/public/v1/**` cho website của tenant, nhận diện tenant/Application bằng header `X-Site-Key` thay cho `{code}`; hợp đồng đầy đủ ở `docs/api/public-api-v1.md`. Đường `/public/t/{code}/**` ở trên **không đổi hành vi**.

- Nhóm mới `lms.customer.publicv1` (controller mỏng, gọi lại client/DTO của nhóm khác): `books`, `books/suggestions`, `books/{bookRef}`, `books/{bookRef}/preview`, `banners`, `sessions` (đăng nhập) + `sessions/{refresh,logout,signup}`, `orders` (+ `{id}`, `{id}/cancellation`, bắt buộc phiên khách, `ShopSessionGuard`).
- `X-Site-Key` bắt buộc; thiếu/sai định dạng/không hợp lệ/ngưng cùng `404 SHOP_NOT_AVAILABLE` cùng thân. `StorefrontAccessCache.resolveBySiteKey`: khóa bộ đệm là `k:` + SHA-256 của site key (không lưu site key thô), mục dương <= 60 giây (`originTtl`), mục phủ định 60 giây, sai định dạng không gọi identity.
- Identity: `POST /internal/storefront/access-tokens` nhận `{tenantCode}` hoặc `{siteKey}` (đúng một), phản hồi thêm `applicationId`; `allowedOrigins` theo site key chỉ gồm origin của chính Application. Token `svc` thêm claim `application_id`.
- `PublicCorsFilter` và `RequestSizeLimitFilter` mở rộng sang `/api/public/`; CORS chỉ cho origin của Application (preflight phản chiếu origin, xem `public-api-v1.md` mục 4), không bao giờ `Access-Control-Allow-Credentials`. `SecurityConfiguration`: hai danh sách đóng `PUBLIC_API_V1` (đọc công khai, tạo phiên) và `AUTHENTICATED_API_V1` (đơn hàng), còn lại `denyAll`.
- nginx: `location ^~ /api/public/` (thân 16k, `public_read` theo IP, `public_sitekey` theo site key), đăng nhập `login`, đăng ký `public_form`, đơn `public_order`; chỉ `security-headers.conf`, không CORS ở nginx, không trả OPTIONS.
