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
| GET | `/public/t/{code}` | Thông tin storefront `{code, name}` | `max-age=30, public` |
| GET | `/public/t/{code}/books` | Danh mục sách đang bán (query `keyword?, page?, size?`, size tối đa 50) | `max-age=30, public` |
| GET | `/public/t/{code}/books/{bookRef}` | Chi tiết sách | `max-age=30, public` |
| GET | `/public/t/{code}/books/{bookRef}/preview` | Đoạn đọc thử | `max-age=60, public` |
| POST | `/public/t/{code}/auth/signup` | Đăng ký tài khoản khách, 201 không thân | `no-store` |
| POST | `/public/t/{code}/auth/login` | Đăng nhập khách theo tenant của đường dẫn | `no-store` |
| POST | `/public/t/{code}/auth/refresh` | Làm mới phiên, identity kiểm token thuộc đúng cửa hàng (khuyến nghị) | `no-store` |
| POST | `/public/t/{code}/auth/logout` | Đăng xuất, 204 | `no-store` |
| POST | `/public/auth/refresh` | (cũ, không khuyến nghị) làm mới phiên, không kiểm tenant | `no-store` |
| POST | `/public/auth/logout` | (cũ, không khuyến nghị) đăng xuất, 204 | `no-store` |
| POST | `/public/t/{code}/orders` | Đặt hàng (cần đăng nhập) | `no-store` |
| GET | `/public/t/{code}/orders` | Đơn của tôi (cần đăng nhập, `page?`, `size?` tối đa 50) | `no-store` |
| GET | `/public/t/{code}/orders/{orderId}` | Một đơn của tôi (cần đăng nhập) | `no-store` |
| POST | `/public/t/{code}/orders/{orderId}/cancellation` | Hủy đơn đang chờ xác nhận (cần đăng nhập) | `no-store` |

Tập đường dẫn là DANH SÁCH ĐÓNG: mọi `/public/**` ngoài bảng trên (kể cả `/public/t/{code}/anything`) bị chặn (401 khi
chưa đăng nhập). Chỉ 4 route `orders` cần đăng nhập; các route còn lại công khai và BỎ QUA header `Authorization`.

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
- Login, refresh đều do customer-api tự đặt `channel: "STOREFRONT"` gửi identity (client không gửi được). Token trả về có
  `aud = ["lms-storefront"]`, chỉ dùng được ở route `orders` của customer-api, không dùng được ở `/api/**` của nhân viên.
- `POST /public/t/{code}/auth/refresh` và `/logout` nhận `{refreshToken}`. Refresh gửi kèm `tenantId` của `{code}`: token
  của tenant khác trả `401 UNAUTHORIZED` và KHÔNG bị xoay vòng. Logout không kiểm tenant.
- Đường dẫn cũ `/public/auth/refresh|logout` giữ để tương thích: không kiểm tenant, CORS chỉ cho domain nền tảng và origin dev.

### Danh mục sách

Phản hồi chỉ có các trường sau (test so tập tên trường); KHÔNG có số tồn, giá nhập, ngưỡng, tenantId, ISBN, ảnh.

| Endpoint | Trường |
|---|---|
| `GET .../books` | `{items:[{bookRef, title, author, price, inStock, hasPreview}], page, size, total}`; `page` từ 0; `size` mặc định 20, tối đa 50 (lớn hơn thì cắt về 50); `keyword` <=100 ký tự (dài hơn hoặc có ký tự điều khiển: 400) |
| `GET .../books/{bookRef}` | `{bookRef, title, author, price, inStock, hasPreview, description}` (`description` null nếu chưa nhập) |
| `GET .../books/{bookRef}/preview` | `{bookRef, title, previewText}` |

`bookRef` là UUID (chính là `titleId` khi đặt hàng). `price` là số nguyên VND. `bookRef` sai định dạng hoặc sách không có/không
đang bán: `404 BOOK_NOT_FOUND`. Văn bản thuần: hiển thị bằng `textContent`, không `innerHTML`.

### Đơn hàng (cần đăng nhập)

Header `Authorization: Bearer <accessToken của login storefront>`. Lỗi phiên: không token, token hỏng/hết hạn, token nhân
viên (thiếu `aud` lms-storefront) hoặc token dịch vụ: `401`. Token của cửa hàng khác: `403 SHOP_SESSION_MISMATCH`.

`POST /public/t/{code}/orders` body (chỉ các trường này; giá, giảm giá, phí ship, trạng thái, người dùng, tenant do server tính, trường lạ bị bỏ qua):

```json
{"lines":[{"titleId":"<uuid>","quantity":1}],"fulfilment":"PICKUP|DELIVERY","paymentMethod":"BANK_TRANSFER|COD|CASH",
 "recipient":{"name":"..","phone":"..","address":".."},"clientRequestId":"<uuid>"}
```

`clientRequestId` bắt buộc, UUID do FE sinh mỗi lần bấm đặt hàng; gửi lại cùng khóa trả đơn cũ. `lines` 1..50 mục, `quantity` >=1;
`address` bắt buộc khi `DELIVERY`; `COD` chỉ với `DELIVERY`, `CASH` chỉ với `PICKUP` (sales kiểm, lỗi `400 VALIDATION_FAILED`).
Phản hồi `201` + `Location`, đơn:

`{id, orderNo, status, paymentStatus, paymentMethod, fulfilment, recipient{name,phone,address}, currency, subtotal, discountTotal,
vatTotal, shippingFee, grandTotal, createdAt, confirmedAt, paidAt, completedAt, cancelledAt, lines[{titleId, titleName, quantity,
unitPrice, lineTotal}], cancellable}`. `cancellable = (status == PENDING_CONFIRMATION)`.

`GET /orders?page&size` trả `{items:[đơn], page, size, total}` (size tối đa 50). `POST /orders/{id}/cancellation` body tùy chọn
`{reason?<=500}`: `409 ORDER_STATE_CONFLICT` nếu đã qua bước chờ xác nhận. Đơn của người khác hoặc id sai định dạng: `404 ORDER_NOT_FOUND`.

Mã lỗi nghiệp vụ thêm: `409 INSUFFICIENT_STOCK` (`details` chỉ có `titleId=<uuid>`, không có số tồn), `409 CLIENT_REQUEST_CONFLICT`,
`409 TITLE_NOT_AVAILABLE`, `404 ORDER_NOT_FOUND`, `409 ORDER_STATE_CONFLICT`, `404 BOOK_NOT_FOUND`. `403 FORBIDDEN` có thông điệp cố định (không lộ tên khóa).

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
| 413 | `PAYLOAD_TOO_LARGE` | Thân request lớn hơn 16 KB (kiểm theo `Content-Length` ở `/public/**`, trước khi đọc thân) |
| 400 | `REQUEST_REJECTED` | Service phía sau từ chối bằng mã không nằm trong danh sách cho phép |
| 401 | `LOGIN_FAILED` | Đăng nhập thất bại (gộp sai mật khẩu, không có tài khoản, không thuộc tenant, tài khoản bị khóa/vô hiệu) |
| 401 | `UNAUTHORIZED` | Phiên/refresh token không hợp lệ hoặc hết hạn |
| 403 | `ACCOUNT_DISABLED` | Chỉ ở `POST /public/auth/refresh`: tài khoản bị vô hiệu hóa. Login không bao giờ trả mã này |
| 403 | `FORBIDDEN` | Không có quyền (thông điệp cố định) |
| 404 | `CMS_PAGE_NOT_FOUND` | Trang không tồn tại, chưa xuất bản hoặc slug sai định dạng |
| 404 | `SHOP_NOT_AVAILABLE` | Tenant không tồn tại/bị khóa/chưa bật module storefront (một thân cố định cho mọi lý do, chống dò tenant) |
| 404 | `NOT_FOUND` | Đường dẫn công khai không có handler |
| 403 | `SHOP_SESSION_MISMATCH` | Token của khách thuộc cửa hàng khác với `{code}` |
| 429 | `SHOP_CONTACT_LIMIT_EXCEEDED` | Khách gửi quá 5 yêu cầu liên hệ trong 24 giờ (thông điệp do customer-api viết; khác 429 của nginx không có `code`) |
| 404 | `BOOK_NOT_FOUND`, `ORDER_NOT_FOUND` | Sách/đơn không có (đơn của người khác cùng thân) |
| 409 | `INSUFFICIENT_STOCK`, `CLIENT_REQUEST_CONFLICT`, `TITLE_NOT_AVAILABLE`, `ORDER_STATE_CONFLICT` | Nghiệp vụ đặt/hủy đơn |
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
| `POST /public/t/{code}/contact-requests` | `public_form` | 5 yêu cầu/phút, burst 3 (thân tối đa 16 KB; nginx không thêm CORS) |
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
- Hợp đồng nội bộ phía sau: system-service `/internal/cms/public/**` (xem `docs/api/cms.md`), identity `/internal/storefront/access-tokens` (header `X-Storefront-Client-Secret`, body `{tenantCode}`, phản hồi thêm `allowedOrigins`), `/internal/auth/{login,signup,refresh,logout}` (login/refresh có `channel=STOREFRONT`, refresh có `tenantId?`), sales `/internal/sales/storefront/**` (token dịch vụ) và `/internal/sales/shop/**` (token khách).
