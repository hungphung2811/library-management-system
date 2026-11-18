# Integration API: lấy token OAuth2 client_credentials (SaaS API Authentication, pha I2)

Nền: `docs/architecture/SAAS_API_AUTHENTICATION_DESIGN.md` (D1, D2, D6, D7, 4.1.2, 4.3.3, 4.4), ADR-0027, `docs/api/applications.md` (tạo Application, scope, credential), `docs/api/token-audience.md` (kênh INTEGRATION).

Dành cho **backend của tenant** (ERP/CRM), không phải trình duyệt: không có CORS. Pha I2 chỉ có **token endpoint** và health; endpoint dữ liệu (`books`, `inventory`, `orders`) là pha I3. Token do endpoint này cấp **chưa dùng được ở đâu** cho tới I3 (gọi `/api/integration/v1/**` khác bằng token hợp lệ trả `404`).

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

`iss` (`library-auth-service`), `sub` = `client_id` = id Application, `aud` = `["lms-integration"]`, `client_id`, `application_id`, `tenantId`, `scope` (chuỗi cách nhau dấu cách), `modules`, `jti`, `iat`, `exp`. **Không có** `admin`, `platformAdmin`, `roles`, `permissions`, `svc`, `sid`. Tenant của mọi lời gọi lấy từ claim `tenantId` do identity ký; client không gửi tenant ở bất kỳ đâu.

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

Dùng token (từ pha I3): `Authorization: Bearer <access_token>` tới `/api/integration/v1/**`. Hiện tại `/api/integration/v1/<khác>` với token hợp lệ trả `404 NOT_FOUND`; không token, sai `aud`/`iss`, hết hạn, sai chữ ký, token nhân viên/storefront/dịch vụ đều `401 UNAUTHORIZED` (thân `ErrorResponse` thống nhất, `WWW-Authenticate: Bearer`). Mọi đường dẫn ngoài `/api/integration/v1/**` (và health) `403`.

## 5. Bảo mật và giới hạn

- Không CORS (API cho backend). Header: `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy: no-referrer`, `Content-Security-Policy: default-src 'none'`.
- nginx: token endpoint 30 yêu cầu/phút/IP (burst 10), thân 4 KB; phần còn lại 20 yêu cầu/giây/IP (burst 40), thân 64 KB. ⚠️ Số đề xuất chờ đo. Không khóa client theo số lần sai (tránh DoS bởi người biết `client_id`). Giới hạn theo application bằng Redis là pha I3.
- Secret client 256 bit ngẫu nhiên (SHA-256 phía server); identity so sánh bằng `MessageDigest.isEqual` và luôn thực hiện đủ truy vấn/so sánh ở mọi nhánh để giảm chênh lệch thời gian.
- Không log thân, header `Authorization` hay secret ở integration-api và identity.
- JWKS vẫn **không** ra internet (nginx chặn `/.well-known/`, Q11): client không cần xác minh token của chính mình.

## 6. Chạy tay (dev)

```bash
mvn -pl integration-api -am spring-boot:run          # cổng 8089, cần identity 8081 (JWKS + token endpoint)
# hoặc: docker compose --profile apps --profile integration up -d --build integration-api nginx
```

Biến môi trường: `IDENTITY_SERVICE_URL` (mặc định `http://localhost:8081`), `JWT_JWK_SET_URI`, `JWT_ISSUER` (mặc định `library-auth-service`, phải khớp `auth.jwt.issuer` của identity), `INTEGRATION_API_PROFILE` (compose; `prod` là fail-fast khi `JWT_JWK_SET_URI` rỗng/localhost hoặc `JWT_ISSUER` rỗng). `scripts/dev-start.ps1` không quản lý integration-api (như customer-api, sales).

## 7. Thay đổi hợp đồng

Thêm mới, không phá gì có sẵn: endpoint `POST /api/integration/v1/oauth/token` (integration-api, qua nginx) và `POST /internal/oauth2/token` (identity, nội bộ, `permitAll` đúng đường dẫn này). Không đổi khóa quyền `Controller/method` nào (`OAuthTokenController` là `@PublicEndpoint`, không đăng ký khóa). FE quản trị không bị ảnh hưởng.
