# Public API v1 (Public Site Key, SaaS API Authentication, pha P2)

Nền: `docs/architecture/SAAS_API_AUTHENTICATION_DESIGN.md` (D4, 4.1.1, 4.4.1, 4.4.2), ADR-0027, ADR-0011 (customer-api), ADR-0013 (CORS theo domain). Dành cho **website của tenant** gọi từ trình duyệt: tenant và Application được xác định bằng header `X-Site-Key` (site key công khai `lms_pk_...` của một Application kiểu `PUBLIC_SITE`, tạo ở `docs/api/applications.md`), không có mã cửa hàng trên đường dẫn.

Đường cũ `/public/t/{code}/**` (FE storefront của nền tảng) **giữ nguyên, không đổi hành vi**; Public API v1 là đường song song, bổ sung (D4 phương án A).

Mọi đường dẫn tương đối so với `<ORIGIN>` (nginx 8080 -> customer-api 8088). Chạy trong `customer-api`, không DB, không phụ thuộc `security-common`.

## 1. Nguyên tắc bảo mật

- **Site key là định danh công khai, KHÔNG phải bí mật và KHÔNG phải bằng chứng xác thực.** Nó chỉ chọn Application/tenant. Có thể nhúng trong mã JavaScript/HTML của website.
- **Tuyệt đối không đặt client secret (`lms_sk_...`) hay bất kỳ bí mật nào ở frontend.** Client secret chỉ dành cho backend của tenant (Integration API, `docs/api/integration-api.md`) và không dùng được ở Public API v1.
- Site key không cho ghi gì: mọi thao tác ghi (đặt/hủy đơn) cần **phiên khách** (access token kênh STOREFRONT từ `POST /sessions`). Đọc catalog dùng token dịch vụ phía sau, chỉ đọc và chỉ gồm trường công khai.
- Domain allowlist của Application chỉ là **CORS** (trình duyệt), không chặn client ngoài trình duyệt (curl, server). `Origin` không được coi là xác thực.
- Không có giỏ hàng lưu ở server (giỏ giữ ở trình duyệt) và không đặt đơn vãng lai: đặt đơn bắt buộc đăng nhập. Không có `/categories` (storefront chưa lọc theo danh mục).
- Site key không bao giờ vào log hay khóa bộ đệm của customer-api (bộ đệm dùng SHA-256 của key).

## 2. Header `X-Site-Key` và lỗi chung

`X-Site-Key: lms_pk_<base64url 32..56 ký tự>` **bắt buộc ở mọi đường dẫn** dưới `/api/public/v1/**`.

Header thiếu, sai định dạng, không tồn tại, đã bị xoay vòng, Application `SUSPENDED`/xóa, tenant ngưng hoặc không bật module bán hàng (SALES): cùng một phản hồi, không phân biệt, không lộ site key có tồn tại hay không:

```json
HTTP/1.1 404
{ "code": "SHOP_NOT_AVAILABLE", "message": "Cửa hàng không khả dụng", "timestamp": "..." }
```

Site key sai định dạng bị trả ngay, không gọi identity. Kết quả "không khả dụng" được nhớ 60 giây; thay đổi ở identity (xoay vòng key, tạm ngưng, đổi domain) có hiệu lực tối đa sau **60 giây**.

Các lỗi khác như đường cũ (`PublicExceptionHandler`): `400 VALIDATION_FAILED`, `401 UNAUTHORIZED` (thiếu/sai phiên), `401 LOGIN_FAILED`, `403 SHOP_SESSION_MISMATCH` (phiên khách thuộc tenant khác với tenant của site key), `404 BOOK_NOT_FOUND`/`ORDER_NOT_FOUND`, `413 PAYLOAD_TOO_LARGE` (thân > 16 KB), `429`, `503 SERVICE_UNAVAILABLE`. Đường dẫn/phương thức ngoài danh sách bên dưới: `401`/`403`.

Lưu ý thứ tự kiểm: endpoint cần đăng nhập (`/orders/**`) mà không có token thì `401` trước khi tới bước kiểm site key.

## 3. Endpoint

| Method | Đường dẫn | Phiên | Ánh xạ đường cũ | Ghi chú |
|---|---|---|---|---|
| GET | `/api/public/v1/books` | Không | `GET /public/t/{code}/books` | Query `keyword`, `sort` (`newest`\|`price_asc`\|`price_desc`), `inStock` (`true`), `group` (`EDITION`\|`WORK`), `page` (từ 0), `size` (1..50, mặc định 20; lớn hơn bị cắt về 50). Tìm kiếm = tham số `keyword`, không có `/search` riêng |
| GET | `/api/public/v1/books/suggestions` | Không | `.../books/suggestions` | `q` (2..50 ký tự, bắt buộc), `limit` (1..8) |
| GET | `/api/public/v1/books/{bookRef}` | Không | `.../books/{bookRef}` | `bookRef` UUID, sai thì `404 BOOK_NOT_FOUND` |
| GET | `/api/public/v1/books/{bookRef}/preview` | Không | `.../books/{bookRef}/preview` | |
| GET | `/api/public/v1/banners` | Không | `.../banners` | |
| POST | `/api/public/v1/sessions` | Tạo phiên | `.../auth/login` | Body `{ username, password }` -> `200 { accessToken, refreshToken, expiresIn }`, `Cache-Control: no-store`; mọi lỗi đăng nhập gộp `401 LOGIN_FAILED` |
| POST | `/api/public/v1/sessions/refresh` | | `.../auth/refresh` | Body `{ refreshToken }`; token phải thuộc tenant của site key |
| POST | `/api/public/v1/sessions/logout` | | `.../auth/logout` | Body `{ refreshToken }` -> `204` |
| POST | `/api/public/v1/sessions/signup` | | `.../auth/signup` | Body `{ username (3-100), password (8-72), fullName, email }` -> `201` rỗng; khi tenant bật xác minh email, bước xác minh chưa có ở v1 (dùng đường cũ `/public/t/{code}/auth/verify-email`) |
| POST | `/api/public/v1/orders` | **Bắt buộc** | `POST /public/t/{code}/orders` | Body như đường cũ, **bắt buộc `clientRequestId` (UUID do website sinh mỗi lần bấm đặt, chống đặt trùng)** -> `201` + `Location: /api/public/v1/orders/{id}` |
| GET | `/api/public/v1/orders` | **Bắt buộc** | `GET .../orders` | `page`, `size` (1..50) |
| GET | `/api/public/v1/orders/{id}` | **Bắt buộc** | `GET .../orders/{id}` | |
| POST | `/api/public/v1/orders/{id}/cancellation` | **Bắt buộc** | `POST .../orders/{id}/cancellation` | Body tùy chọn `{ reason? }` |

Phiên bắt buộc = header `Authorization: Bearer <accessToken>` của khách (token từ `POST /sessions`, `aud = lms-storefront`). Token của tenant khác tenant của site key: `403 SHOP_SESSION_MISMATCH`.

Dạng dữ liệu (`BookPageView`, `BookDetailView`, `OrderView`, ...) giống hệt đường cũ: xem `docs/api/customer-api.md`, `docs/api/catalog-books.md`, `docs/api/sales.md`. Không có giỏ hàng server, `/categories` hay header `Idempotency-Key` (idempotency dùng `clientRequestId` trong body như đường cũ).

## 4. CORS

- Chỉ origin nằm trong **domain allowlist của chính Application** (`docs/api/applications.md`, mục domains) mới nhận `Access-Control-Allow-Origin`. Origin cấp tenant dùng cho đường cũ (chưa gắn Application), origin của Application khác và origin nền tảng/dev **không** hợp lệ với site key này. Origin không hợp lệ: phản hồi vẫn trả bình thường, chỉ thiếu header CORS (trình duyệt chặn đọc).
- **Không bao giờ** gửi `Access-Control-Allow-Credentials` (token nằm trong header, không cookie): đừng dùng `credentials: 'include'`.
- Phản hồi có `Vary: Origin, X-Site-Key`.
- Preflight `OPTIONS` do customer-api trả `204` (nginx không xử lý OPTIONS): `Access-Control-Allow-Methods: GET, POST, OPTIONS`, `Access-Control-Allow-Headers: Authorization, Content-Type, X-Site-Key, Idempotency-Key, X-Correlation-Id`. ⚠️ Trình duyệt **không gửi `X-Site-Key` trong preflight** nên preflight không thể quyết theo Application: nó phản chiếu mọi `Origin` hợp lệ về cú pháp (không mang dữ liệu, không thực thi gì); quyết định thật nằm ở phản hồi của request chính, nơi chỉ origin của Application nhận `Access-Control-Allow-Origin` (không có thì trình duyệt chặn đọc phản hồi).
- Domain allowlist đổi có hiệu lực tối đa sau 60 giây.

## 5. Giới hạn

- nginx: thân tối đa 16 KB; `public_read` 20 yêu cầu/giây/IP (burst 40); vùng `public_sitekey` theo site key 50 yêu cầu/giây (burst 100, cộng dồn mọi IP, bảo vệ backend); đăng nhập (`POST /sessions`) dùng vùng `login` (10 lần/phút/IP, burst 5); đăng ký (`/sessions/signup`) dùng `public_form` (5 lần/phút/IP, burst 3); đơn hàng dùng `public_order` (20 lần/phút/IP, burst 10). Vượt: `429`. ⚠️ Số đề xuất chờ đo.
- customer-api: thân > 16 KB `413`; `size` <= 50; giới hạn đặt đơn theo khách/tenant do sales kiểm (`ORDER_RATE_LIMITED`, `ORDER_LIMIT_EXCEEDED`).

## 6. Ví dụ

curl:

```bash
SITE_KEY=lms_pk_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx   # site key công khai của website

# Danh sách sách
curl -s "$ORIGIN/api/public/v1/books?keyword=java&size=20" -H "X-Site-Key: $SITE_KEY"

# Đăng nhập khách rồi đặt đơn
TOKEN=$(curl -s -X POST "$ORIGIN/api/public/v1/sessions" -H "X-Site-Key: $SITE_KEY" \
  -H 'Content-Type: application/json' -d '{"username":"an","password":"mat-khau-cua-an"}' | jq -r .accessToken)
curl -s -X POST "$ORIGIN/api/public/v1/orders" -H "X-Site-Key: $SITE_KEY" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"lines":[{"titleId":"<uuid>","quantity":1}],"fulfilment":"DELIVERY",
  "paymentMethod":"BANK_TRANSFER","recipient":{"name":"An","phone":"0900000000","address":"1 Lê Lợi"},
  "clientRequestId":"'$(uuidgen)'"}'
```

fetch (trình duyệt, website `https://shop.a.vn` đã khai báo domain này cho Application):

```js
const API = 'https://<nền-tảng>/api/public/v1';
const SITE_KEY = 'lms_pk_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'; // công khai; KHÔNG đặt client secret ở đây

async function listBooks(keyword) {
  const res = await fetch(`${API}/books?keyword=${encodeURIComponent(keyword)}&size=20`, {
    headers: { 'X-Site-Key': SITE_KEY },          // không dùng credentials: 'include'
  });
  if (res.status === 404) throw new Error('Cửa hàng không khả dụng'); // key sai/ngưng: cùng một lỗi
  return res.json();
}

async function placeOrder(accessToken, order) {
  const res = await fetch(`${API}/orders`, {
    method: 'POST',
    headers: {
      'X-Site-Key': SITE_KEY,
      'Authorization': `Bearer ${accessToken}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ ...order, clientRequestId: crypto.randomUUID() }),
  });
  return res.json();
}
```

## 7. Hợp đồng nội bộ (identity, cho người bảo trì)

`POST /internal/storefront/access-tokens` (header `X-Storefront-Client-Secret`): body nhận **đúng một** trong `{ "tenantCode": "..." }` (đường cũ) hoặc `{ "siteKey": "lms_pk_..." }`; cả hai hoặc không cái nào là `400 VALIDATION_FAILED`. Với `siteKey`: Application phải `ACTIVE`, loại `PUBLIC_SITE`; tenant `ACTIVE` và bật SALES (hoặc LENDING, như `{code}`); mọi thất bại cùng `404 SHOP_NOT_AVAILABLE`. Phản hồi thêm `applicationId` (null với `tenantCode`); `allowedOrigins` với `siteKey` chỉ gồm origin đang bật gắn đúng Application (origin cấp tenant có `application_id IS NULL` thuộc đường `{code}` và không có trong kết quả). Token dịch vụ (`svc`) thêm claim `application_id` khi cấp theo site key. customer-api cũ bỏ qua trường mới.

## 8. Chưa kiểm chứng / lệch thiết kế

- ⚠️ Preflight phản chiếu origin (mục 4), lệch với mô tả "CORS chỉ cho origin của Application" do giới hạn của trình duyệt.
- ⚠️ Header `Idempotency-Key` của thiết kế 4.4.2 chưa nhận (chỉ `clientRequestId` trong body).
- ⚠️ `last_used_at` của site key chưa ghi (migration V32 không có cột).
- ⚠️ Chưa kiểm chứng đầu-cuối qua nginx + identity + sales chạy thật, chưa đo tải identity khi cấp token theo site key (P-P1).
