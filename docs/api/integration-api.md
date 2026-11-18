# Integration API: token OAuth2 client_credentials và endpoint đọc v1 (SaaS API Authentication, pha I2 và I3)

Nền: `docs/architecture/SAAS_API_AUTHENTICATION_DESIGN.md` (D1, D2, D6, D7, 4.1.2, 4.3.3, 4.4), ADR-0027, `docs/api/applications.md` (tạo Application, scope, credential), `docs/api/token-audience.md` (kênh INTEGRATION).

Dành cho **backend của tenant** (ERP/CRM), không phải trình duyệt: không có CORS. Pha I2: **token endpoint** (mục 2). Pha I3: **endpoint đọc** `books`, `inventory`, `orders` (mục 8), giới hạn tốc độ theo application (mục 9) và log truy cập. Chưa có endpoint ghi, idempotency (pha I4), `customers`, `categories`, danh sách đầu sách theo `updatedSince` (hoãn, cần catalog V10).

Đường dẫn tương đối so với `<ORIGIN>` (nginx 8080, local `http://localhost:8080`): nginx chuyển `/api/integration/**` tới `integration-api` (cổng 8089, nội bộ), `integration-api` chuyển form tới identity `POST /internal/oauth2/token`. Hai đường dẫn nội bộ này **không** ra ngoài (nginx `/internal/**` trả `404`).

## 1. Cách lấy credential

1. Tenant admin tạo Application `kind=INTEGRATION` (`POST /api/applications`, cần hợp đồng thuê ACTIVE).
2. Cấp scope: `PUT /api/applications/{id}/scopes` (chỉ cấp được `books:read`, `inventory:read`, `orders:read`).
3. Tạo credential: `POST /api/applications/{id}/credentials`. Phản hồi có `clientId` (= id Application) và `clientSecret` (`lms_sk_...`, **chỉ hiện một lần**, không đọc lại được).
4. Backend của tenant lưu `clientId`/`clientSecret` ở nơi bí mật (biến môi trường, kho khóa), **không** nhúng vào frontend.

## 2. Token endpoint

`POST /api/integration/v1/oauth/token`

| | |
|---|---|
| Content-Type | `application/x-www-form-urlencoded` (bắt buộc; JSON bị `400 invalid_request`) |
| Xác thực client | Một trong hai, không dùng cả hai: (a) `Authorization: Basic base64(urlencode(client_id) + ":" + urlencode(client_secret))` (khuyến nghị); (b) trường form `client_id` và `client_secret` |
| `grant_type` | Bắt buộc, đúng `client_credentials` |
| `scope` | Tùy chọn, các scope cách nhau bằng dấu cách. Vắng thì cấp mọi scope đã cấp cho Application (đã lọc theo module, xem dưới) |
| Query string | **Cấm**: mọi tham số ở thân. Query có nội dung bị `400 invalid_request` (secret không được nằm trên URL) |

Phản hồi thành công `200`, header `Cache-Control: no-store`, `Pragma: no-cache`:

```json
{ "access_token": "eyJ...", "token_type": "Bearer", "expires_in": 300, "scope": "books:read inventory:read" }
```

- `expires_in` mặc định 300 giây (identity `lms.identity.integration.token-ttl`, biến `INTEGRATION_TOKEN_TTL`, mặc định `PT5M`). Không có refresh token: hết hạn thì xin token mới (client_credentials). Nên lưu token và dùng lại tới sát hạn, không xin cho mỗi request.
- `scope` trả về là phần thực sự có trong token: giao của (scope yêu cầu hoặc mọi scope đã cấp) với scope khả dụng theo module tenant đang bật. Tenant tắt module `SALES` thì `inventory:*`, `orders:*` bị loại khỏi token; kết quả rỗng là `invalid_scope`.
- Một Application có thể có tối đa 2 credential ACTIVE (giai đoạn xoay vòng), mỗi credential đều xin token được.

### Claim của access token (RS256, khóa của identity)

`iss` (`library-auth-service`), `sub` = `client_id` = id Application, `aud` = `"lms-integration"` (chuỗi, vì Nimbus ghi `aud` một phần tử dạng chuỗi; decoder chấp nhận cả chuỗi lẫn mảng), `client_id`, `application_id`, `tenantId`, `scope` (chuỗi cách nhau dấu cách), `modules`, `jti`, `iat`, `exp`. **Không có** `admin`, `platformAdmin`, `roles`, `permissions`, `svc`, `sid`. Tenant của mọi lời gọi lấy từ claim `tenantId` do identity ký; client không gửi tenant ở bất kỳ đâu.

⚠️ Tên claim là `application_id` (snake_case, theo `JwtClaims.APPLICATION_ID` đã commit ở pha P0), khác `applicationId` ghi ở mục 4.3.3 của thiết kế.

## 3. Mã lỗi (RFC 6749 mục 5.2)

Thân lỗi luôn dạng `{ "error": "<mã>", "error_description": "<thông điệp cố định>" }`, kèm `Cache-Control: no-store`, `Pragma: no-cache`.

| HTTP | `error` | Khi nào |
|---|---|---|
| 400 | `invalid_request` | Sai Content-Type, có query string, tham số lặp, dùng cả Basic lẫn client_id/client_secret trong thân, thiếu `grant_type` |
| 400 | `unsupported_grant_type` | `grant_type` khác `client_credentials` |
| 400 | `invalid_scope` | Scope ngoài danh mục, chưa cấp cho Application, hoặc kết quả sau khi lọc theo module rỗng |
| 401 | `invalid_client` | **Một mã cho mọi lý do**: thiếu thông tin client, `client_id` không tồn tại, secret sai, credential thu hồi hoặc hết hạn, Application không `ACTIVE` hoặc không phải `INTEGRATION`, tenant không hoạt động, Basic hỏng. Kèm `WWW-Authenticate: Basic realm="lms-integration"`. Không có cách phân biệt lý do từ phản hồi |
| 413 | `PAYLOAD_TOO_LARGE` (thân `ErrorResponse`) | Thân quá 4 KB (nginx và ứng dụng) |
| 429 | (nginx) | Quá 30 yêu cầu/phút/IP (dồn tối đa 10) |
| 503 | `temporarily_unavailable` | identity không phản hồi hoặc trả lỗi hạ tầng; không lộ chi tiết nội bộ |

Lý do thất bại thật chỉ có trong log của identity (`oauth2-token denied reason=... applicationId=...`), không có secret.

## 4. Ví dụ

```bash
# Basic (khuyến nghị). client_id/secret chỉ gồm ký tự an toàn nên không cần urlencode thêm.
curl -sS -X POST "$ORIGIN/api/integration/v1/oauth/token" \
  -u "$CLIENT_ID:$CLIENT_SECRET" \
  -d "grant_type=client_credentials" \
  -d "scope=books:read inventory:read"

# client_id/client_secret trong thân (hợp lệ, kém an toàn hơn vì dễ vào log phía client)
curl -sS -X POST "$ORIGIN/api/integration/v1/oauth/token" \
  --data-urlencode "grant_type=client_credentials" \
  --data-urlencode "client_id=$CLIENT_ID" \
  --data-urlencode "client_secret=$CLIENT_SECRET"
```

Dùng token: `Authorization: Bearer <access_token>` tới `/api/integration/v1/**` (mục 8). Đường dẫn dưới `/api/integration/v1/**` không nằm trong mục 8 (ví dụ `/orders/{id}/contact`) với token hợp lệ trả `404 NOT_FOUND`; không token, sai `aud`/`iss`, hết hạn, sai chữ ký, token nhân viên/storefront/dịch vụ đều `401 UNAUTHORIZED` (thân `ErrorResponse` thống nhất, `WWW-Authenticate: Bearer`). Mọi đường dẫn ngoài `/api/integration/v1/**` (và health) `403`.

## 5. Bảo mật và giới hạn

- Không CORS (API cho backend). Header: `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy: no-referrer`, `Content-Security-Policy: default-src 'none'`.
- nginx: token endpoint 30 yêu cầu/phút/IP (burst 10), thân 4 KB; phần còn lại 20 yêu cầu/giây/IP (burst 40), thân 64 KB. ⚠️ Số đề xuất chờ đo. Không khóa client theo số lần sai (tránh DoS bởi người biết `client_id`). Giới hạn theo application bằng Redis (pha I3) ở mục 9.
- Secret client 256 bit ngẫu nhiên (SHA-256 phía server); identity so sánh bằng `MessageDigest.isEqual` và luôn thực hiện đủ truy vấn/so sánh ở mọi nhánh để giảm chênh lệch thời gian.
- Không log thân, header `Authorization` hay secret ở integration-api và identity. Log truy cập có cấu trúc mỗi request (mục 9.3) chỉ chứa định danh và mẫu route.
- JWKS vẫn **không** ra internet (nginx chặn `/.well-known/`, Q11): client không cần xác minh token của chính mình.

## 6. Chạy tay (dev)

```bash
mvn -pl integration-api -am spring-boot:run          # cổng 8089, cần identity 8081 (JWKS + token endpoint)
# hoặc: docker compose --profile apps --profile integration up -d --build integration-api nginx
```

Biến môi trường: `IDENTITY_SERVICE_URL` (mặc định `http://localhost:8081`), `CATALOG_SERVICE_URL` (`http://localhost:8082`), `SALES_SERVICE_URL` (`http://localhost:8087`), `REDIS_HOST`/`REDIS_PORT` (`localhost`/`6379`), `INTEGRATION_RATE_LIMIT_ENABLED` (mặc định `false`, docker-compose đặt `true`), `INTEGRATION_RATE_LIMIT_PER_MINUTE` (`600`), `JWT_JWK_SET_URI`, `JWT_ISSUER` (mặc định `library-auth-service`, phải khớp `auth.jwt.issuer` của identity), `INTEGRATION_API_PROFILE` (compose; `prod` là fail-fast khi `JWT_JWK_SET_URI` rỗng/localhost hoặc `JWT_ISSUER` rỗng). `scripts/dev-start.ps1` không quản lý integration-api (như customer-api, sales).

## 7. Thay đổi hợp đồng

Pha I3 (thêm mới, không phá gì có sẵn): `GET /api/integration/v1/books/{id}`, `POST /books/lookup`, `POST /books/lookup-by-isbn`, `GET /inventory`, `GET /inventory/{titleId}`, `GET /orders`, `GET /orders/{id}` (integration-api); gắn `@IntegrationScope` (không đổi khóa `Controller/method`, không đăng ký khóa mới) lên `TitleController/get|lookup|lookupByIsbn` (catalog, `books:read`), `StockItemController/search|get` (sales, `inventory:read`), `SalesOrderController/search|get` (sales, `orders:read`). Token integration vào được **đúng** các method này; mọi endpoint khác của catalog/sales vẫn `403` với token integration. Token nhân viên/storefront/dịch vụ không đổi hành vi. FE quản trị không bị ảnh hưởng.

Pha I2 (thêm mới, không phá gì có sẵn): endpoint `POST /api/integration/v1/oauth/token` (integration-api, qua nginx) và `POST /internal/oauth2/token` (identity, nội bộ, `permitAll` đúng đường dẫn này). Không đổi khóa quyền `Controller/method` nào (`OAuthTokenController` là `@PublicEndpoint`, không đăng ký khóa). FE quản trị không bị ảnh hưởng.

## 8. Endpoint đọc v1 (pha I3)

Mọi endpoint cần `Authorization: Bearer <access_token>` (token integration, mục 2) và scope tương ứng trong claim `scope`. **Tenant luôn là tenant trong token**: không có tham số hay header chọn tenant; `?tenantId=` và `X-Tenant-Id` bị bỏ qua (đã có test). Id của tenant khác trả `404` cùng thân với id không tồn tại. Mọi phản hồi `Cache-Control: no-store`. Tiền là số nguyên đồng, thời gian ISO-8601 UTC.

| Endpoint | Scope | Tham số | Phản hồi |
|---|---|---|---|
| `GET /api/integration/v1/books/{id}` | `books:read` | `id` UUID | `BookView` |
| `POST /api/integration/v1/books/lookup` | `books:read` | thân `{"ids":[uuid,...]}`, 1 đến 100 id | `BookView[]` (chỉ phần tìm thấy) |
| `POST /api/integration/v1/books/lookup-by-isbn` | `books:read` | thân `{"isbns":["..."]}`, 1 đến 100 ISBN, mỗi ISBN tối đa 20 ký tự | `BookView[]` (chỉ phần tìm thấy) |
| `GET /api/integration/v1/inventory` | `inventory:read` | `lowStockOnly` (mặc định `false`), `page` (từ 0, mặc định 0), `size` (1 đến 100, mặc định 20) | `PageView<InventoryItemView>` |
| `GET /api/integration/v1/inventory/{titleId}` | `inventory:read` | `titleId` UUID | `InventoryItemView` |
| `GET /api/integration/v1/orders` | `orders:read` | `status` (tùy chọn, không phân biệt hoa thường: `PENDING_CONFIRMATION`, `CONFIRMED`, `READY_FOR_PICKUP`, `SHIPPING`, `COMPLETED`, `CANCELLED`, `AWAITING_PAYMENT`), `page`, `size` như trên | `PageView<OrderView>` |
| `GET /api/integration/v1/orders/{id}` | `orders:read` | `id` UUID | `OrderView` |

`inventory` và `orders` cần tenant đã bật module `SALES` (scope tương ứng chỉ có trong token khi module bật; sales-service kiểm lại). Tham số sai (UUID hỏng, `size` ngoài khoảng, `status` lạ, thân sai hình dạng hoặc vượt 100 phần tử) là `400 VALIDATION_FAILED`, không âm thầm cắt.

`PageView<T>`: `{ "items": [T], "total": <số phần tử>, "page": <từ 0>, "size": <kích thước> }`.

### 8.1. Trường trả về (allowlist riêng của integration-api, không phải DTO của service)

Thêm trường là thay đổi hợp đồng phải ghi ở đây. Trường không có trong danh sách dưới đây **không bao giờ** được trả, kể cả khi service phía sau có.

- `BookView`: `id`, `name`, `subtitle`, `author`, `authors[{id,name}]`, `isbn`, `coverPrice`, `description`, `publisherId`, `publisherName`, `categoryId`, `categoryName`, `publicationYear`, `edition`, `language`, `pageCount`, `format`, `translator`, `series`, `seriesNumber`, `coverImageUrl`, `tags[]`, `workId`, `workTitle`, `partNumber`, `partLabel`. `GET /books/{id}` có `authors`, `publisher*`, `category*` nhưng `description` là `null`; hai lookup có `description` nhưng `authors` là `null` và `publisher*`, `category*` là `null`. Không có `tenantId`, số bản sao sẵn có, nội dung đọc thử.
- `InventoryItemView`: `titleId`, `titleName`, `sku`, `onHand`, `reserved`, `available`, `sellingPrice`, `effectivePrice`, `lowStock`, `active`, `lowStockThreshold`, `lastRestockedAt`. Không có giá vốn, nhà cung cấp, vị trí kệ, ghi chú nội bộ, sổ cái, `tenantId`.
- `OrderView`: `id`, `orderNo`, `channel`, `status`, `paymentStatus`, `paymentMethod`, `fulfilment`, `currency`, `vatRateBps`, `pricesIncludeVat`, `subtotal`, `discountTotal`, `vatTotal`, `shippingFee`, `grandTotal`, `createdAt`, `confirmedAt`, `paidAt`, `completedAt`, `cancelledAt`, `version`, `lines[]`. `lines[]`: `lineNo`, `titleId`, `titleName`, `isbn`, `quantity`, `unitPrice`, `lineDiscount`, `allocatedOrderDiscount`, `amount`, `vatAmount`, `lineTotal`. **Không có** thông tin liên hệ của khách/người nhận (kể cả dạng che), `customerUserId`, ghi chú của khách hay nhân viên, lý do hủy, `tenantId`. Không có endpoint `contact`, `notes`, `receipt`.

### 8.2. Mã lỗi (thân `{ "code": "...", "message": "..." }`, `ErrorResponse` thống nhất)

| HTTP | `code` | Khi nào |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Tham số/thân sai (xem trên) |
| 400 | `INVALID_REQUEST` | Service phía sau từ chối yêu cầu |
| 401 | `UNAUTHORIZED` | Không token, sai `aud`/`iss`, hết hạn, sai chữ ký, token loại khác (`WWW-Authenticate: Bearer`) |
| 403 | `INSUFFICIENT_SCOPE` | Token hợp lệ nhưng thiếu scope của endpoint (kiểm ở integration-api, lớp đầu) |
| 403 | `FORBIDDEN` | Service phía sau từ chối (thiếu scope/module/tenant). Thông điệp chung, **không** lộ tên khóa nội bộ |
| 404 | `NOT_FOUND` | Không có tài nguyên hoặc thuộc tenant khác; đường dẫn không mở cho tích hợp, kể cả khi dùng phương thức ghi (ví dụ `POST /api/integration/v1/books`, `POST /orders`: không có handler nào cho đường dẫn đó nên là 404, không phải 405) |
| 405 | `METHOD_NOT_ALLOWED` | Chỉ khi đường dẫn có endpoint nhưng sai phương thức (ví dụ `PUT /books/{id}`, `DELETE /inventory/{titleId}`; `GET` vào `/oauth/token`); không có endpoint ghi ở I3 |
| 429 | `RATE_LIMITED` | Vượt hạn mức theo application (mục 9) |
| 503 | `SERVICE_UNAVAILABLE` | catalog/sales lỗi 5xx, quá tải, mất kết nối, hết thời gian hoặc trả thân lạ. Thông điệp chung |

Thời gian chờ gọi service phía sau: kết nối 2 giây, đọc 5 giây (tối đa 30 giây, `app.services.*`).

### 8.3. Ví dụ

```bash
TOKEN=$(curl -sS -X POST "$ORIGIN/api/integration/v1/oauth/token" -u "$CLIENT_ID:$CLIENT_SECRET" \
  -d grant_type=client_credentials | jq -r .access_token)

curl -sS -H "Authorization: Bearer $TOKEN" "$ORIGIN/api/integration/v1/books/$BOOK_ID"
curl -sS -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"isbns":["9780132350884"]}' "$ORIGIN/api/integration/v1/books/lookup-by-isbn"
curl -sS -H "Authorization: Bearer $TOKEN" "$ORIGIN/api/integration/v1/inventory?lowStockOnly=true&page=0&size=50"
curl -sS -H "Authorization: Bearer $TOKEN" "$ORIGIN/api/integration/v1/orders?status=CONFIRMED&page=0&size=20"
```

## 9. Giới hạn tốc độ, bộ đếm và log truy cập (pha I3)

### 9.1. Hạn mức

- Cửa sổ cố định 60 giây theo `(tenant, application, nhóm endpoint)`; nhóm là `books`, `inventory`, `orders` (đường dẫn khác dưới `/api/integration/v1/` tính nhóm `other`). `tenant` và `application` lấy từ claim `tenantId` và `application_id` của token đã xác thực, không từ request. Token endpoint không bị giới hạn ở đây (đã có giới hạn IP ở nginx).
- Hạn mức mặc định cho mọi application: `app.integration.rate-limit.default-per-minute` (`INTEGRATION_RATE_LIMIT_PER_MINUTE`, mặc định **600/phút/nhóm**; ⚠️ số đề xuất chờ đo). ⚠️ Hạn mức riêng theo application (cột `applications.rate_limit_per_minute` của V32) **chưa được dùng**: token không mang giá trị này. Cần identity ghi thêm claim và integration-api đọc claim đó (việc của một pha sau).
- Mọi phản hồi được đếm có `X-RateLimit-Limit` và `X-RateLimit-Remaining`. Vượt hạn mức: `429 RATE_LIMITED`, thân `ErrorResponse`, `Retry-After: <giây tới cửa sổ kế>`, `X-RateLimit-Remaining: 0`, và request **không** được chuyển xuống catalog/sales. Request bị `401` không được đếm.
- Khóa Redis: `lms:rl:v1:t:{tenantId}:a:{applicationId}:{nhóm}:{epochMinute}` (TTL 120 giây), một script Lua nguyên tử `INCR` + `EXPIRE`.
- **Fail-open**: Redis lỗi hoặc chậm (timeout 200 ms) thì request vẫn được xử lý (không có header hạn mức), log WARN một dòng rồi tạm bỏ qua Redis 5 giây. Nginx vẫn chặn theo IP.
- Bật/tắt: `app.integration.rate-limit.enabled` (`INTEGRATION_RATE_LIMIT_ENABLED`). Mặc định `false` (không cần Redis); docker-compose đặt `true` kèm `REDIS_HOST=redis`; chạy tay ở dev cần Redis rồi đặt `true`.

### 9.2. Bộ đếm sử dụng

Cùng lượt với giới hạn: `INCR lms:usage:v1:t:{tenantId}:a:{applicationId}:{yyyyMMdd}` (ngày UTC, TTL 35 ngày), đếm mọi request tới endpoint dữ liệu (kể cả bị từ chối vì 429). Chỉ là **tổng yêu cầu mỗi ngày** (không tách thành công/lỗi hay theo nhóm endpoint). Đọc qua identity `GET /api/applications/{id}/usage?from&to` (tối đa 31 ngày, `docs/api/applications.md` mục "Thống kê sử dụng"). Bộ đếm chỉ chạy khi giới hạn tốc độ bật.

### 9.3. Log truy cập

Một dòng INFO mỗi request tới `/api/integration/v1/**` (kể cả `401`, `403`, `429` và token endpoint), logger `lms.integration.access`:

```
integration-access correlationId=... jti=... tenantId=... applicationId=... method=GET route=/api/integration/v1/books/{id} status=200 durationMs=12
```

`route` là **mẫu** đường dẫn (không có id thật, không có query); request không có token hợp lệ ghi `jti`, `tenantId`, `applicationId` là `-`. Không log thân, header `Authorization`, secret hay token. `X-Correlation-Id` (do client gửi nếu hợp lệ, nếu không sinh mới) được trả lại ở header phản hồi và chuyển sang catalog/sales.

## 10. Thu hồi token tức thời (pha I5, tùy chọn)

Mặc định (cờ tắt) token đã cấp **còn dùng được tối đa 5 phút** (TTL) sau khi tạm ngưng/xóa Application hoặc thu hồi credential; thu hồi credential có hiệu lực ngay với lần xin token kế tiếp. Muốn rút ngắn, bật `app.integration.revocation.enabled` (`INTEGRATION_REVOCATION_ENABLED=true`, mặc định `false`, cần Redis) ở **integration-api**, và cùng biến ở **identity** để identity ghi dấu. Hai nguồn từ chối (một lượt `MGET`, chạy sau xác thực token và trước giới hạn tốc độ; request bị từ chối không đếm vào hạn mức):

| Khóa Redis | Ai ghi | Ý nghĩa |
|---|---|---|
| `lms:revoked:v1:jti:{jti}` | vận hành (`SET key 1 EX <giây còn lại của token>`) | denylist một token cụ thể; tồn tại là bị thu hồi |
| `lms:revoked:v1:app:{applicationId}` | identity (sau commit) khi **tạm ngưng**, **xóa** Application hoặc thu hồi credential **`COMPROMISED`** | giá trị = giây epoch; token của application có `iat` nhỏ hơn giá trị này bị từ chối. TTL mặc định 1 giờ (`lms.identity.integration.revocation.marker-ttl`, phải lớn hơn `token-ttl`) |

- Token bị thu hồi nhận `401 UNAUTHORIZED` (thân và `WWW-Authenticate: Bearer` như token không hợp lệ, không nói lý do).
- **Fail-open**: Redis lỗi hoặc chậm thì request vẫn được xử lý, log WARN một dòng rồi tạm bỏ qua Redis 5 giây (như giới hạn tốc độ). Phía identity cũng best-effort: Redis lỗi chỉ log WARN, thao tác quản trị vẫn thành công.
- Giới hạn đã biết: (1) token không mang `credentialId` nên dấu là theo cả Application, vì vậy thu hồi `COMPROMISED` làm mất hiệu lực cả token đã cấp từ credential còn lại (client xin lại token là được); (2) không có endpoint ghi denylist `jti` (chưa cần; dùng `redis-cli`); (3) dấu chỉ ghi sau commit nên nếu identity chết giữa chừng thì không có dấu (quay về TTL 5 phút); (4) Application đã bị tạm ngưng rồi kích hoạt lại: token cấp trước thời điểm tạm ngưng vẫn bị từ chối đến hết TTL của token, xin token mới là dùng được.
