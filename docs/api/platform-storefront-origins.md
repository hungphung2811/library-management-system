# Origin storefront theo tenant (platform): `/api/platform/tenants/{tenantId}/storefront-origins`

Nền: ADR-0013. Mỗi tenant khai báo website (origin) được gọi `customer-api` `/public/t/{code}/**` từ trình duyệt; `customer-api` quyết CORS theo danh sách này (hiệu lực khoảng 1 phút). Chỉ dành cho platform admin (claim `platformAdmin=true`, token không cần `tenantId`, `@PlatformAdminOnly`, không dùng khóa `Controller/method`): quản trị viên tenant và người dùng thường nhận `403`, không token nhận `401`. `lms-api` chuyển nguyên `/api/platform/**` xuống identity nên không có route riêng. Tenant admin chỉ xem: xem `tenant-storefront-origins.md`.

> Giới hạn cam kết (hiển thị trên màn hình admin): danh sách origin chỉ là kiểm soát CORS của trình duyệt. Nó KHÔNG phải xác thực và KHÔNG chống bot hay `curl`: `Origin` giả được ngoài trình duyệt, dữ liệu công khai của storefront vẫn cào được. Thay đổi có hiệu lực trong khoảng 1 phút.

## Endpoint

| Method | Đường dẫn | Vào | Ra | Lỗi |
|---|---|---|---|---|
| GET | `/api/platform/tenants/{tenantId}/storefront-origins` | | `200 {maxOrigins, origins:[...]}` sắp theo `createdAt` | `404 TENANT_NOT_FOUND` |
| POST | `/api/platform/tenants/{tenantId}/storefront-origins` | `{origin}` | `201` một phần tử, `enabled=true`, `Location` tới phần tử | `400 ORIGIN_INVALID`, `409 ORIGIN_DUPLICATED`, `409 ORIGIN_LIMIT_EXCEEDED`, `404 TENANT_NOT_FOUND` |
| PUT | `/api/platform/tenants/{tenantId}/storefront-origins/{originId}` | `{enabled}` (bắt buộc, boolean) | `200` phần tử | `404 STOREFRONT_ORIGIN_NOT_FOUND`, `400` thiếu `enabled` |
| DELETE | `/api/platform/tenants/{tenantId}/storefront-origins/{originId}` | | `204` | `404 STOREFRONT_ORIGIN_NOT_FOUND` |

Phần tử:

```json
{ "id": "<uuid>", "origin": "https://shop.example.com", "enabled": true,
  "createdAt": "2026-11-17T01:00:00Z", "createdBy": "<uuid platform admin>",
  "updatedAt": "2026-11-17T01:00:00Z", "updatedBy": "<uuid platform admin>" }
```

`GET` trả `{ "maxOrigins": 10, "origins": [ ...phần tử... ] }`.

- Không sửa chuỗi `origin`: muốn đổi thì xóa rồi thêm (lịch sử rõ ràng). Muốn chuyển domain sang tenant khác cũng là xóa ở tenant cũ rồi thêm ở tenant mới.
- `PUT` giá trị không đổi là no-op: `200`, không ghi lịch sử, không phát event.
- `originId` của tenant khác gọi qua đường dẫn của tenant này: `404 STOREFRONT_ORIGIN_NOT_FOUND`, dữ liệu không đổi.
- Không có seed: tenant mặc định (`default`) không có origin nào; môi trường dev đi qua cấu hình `dev-origins` của customer-api.

## Quy tắc

- Origin duy nhất toàn hệ thống (kể cả dòng đang tắt): thêm origin đã thuộc tenant bất kỳ (kể cả chính tenant này) là `409 ORIGIN_DUPLICATED`. Khi thuộc tenant khác, `details` chứa `["tenantId=<uuid chủ>"]` (chỉ platform admin thấy).
- Tối đa 10 origin mỗi tenant, đếm cả dòng đang tắt (cấu hình `lms.identity.storefront.max-origins-per-tenant`). Thêm cái thứ 11 là `409 ORIGIN_LIMIT_EXCEEDED`. Kiểm dưới khóa dòng tenant nên hai yêu cầu đồng thời không vượt giới hạn.
- Chỉ `https`. `http` chỉ cho host `localhost` hoặc `127.0.0.1`, hoặc khi môi trường đặt `STOREFRONT_ALLOW_HTTP_ORIGINS=true` (staging không TLS).

### Chuẩn hóa (identity làm khi lưu)

Đầu vào `trim`, kết quả `scheme://host[:port]`: scheme và host chữ thường, tên miền quốc tế chuyển sang punycode (`https://ngân.vn` thành `https://xn--ngn-jla.vn`), bỏ cổng mặc định (`:443` với https, `:80` với http), bỏ dấu `/` cuối. Khác cổng hoặc khác scheme là origin khác. Ví dụ: `HTTPS://Shop.Example.com/` thành `https://shop.example.com`; `https://shop.example.com:8443` giữ nguyên.

Từ chối với `400 ORIGIN_INVALID`, `details` là một mã lý do:

| Mã | Khi |
|---|---|
| `SYNTAX` | rỗng, dài hơn 300, sai cú pháp, có khoảng trắng |
| `SCHEME` | thiếu scheme hoặc không phải `http`/`https` |
| `PATH` | có path (trừ `/` cuối), query, fragment hoặc userinfo |
| `WILDCARD` | có `*` |
| `NULL` | giá trị `null` (không phân biệt hoa thường) |
| `HOST` | thiếu host, IPv6, dấu chấm cuối, ký tự cấm |
| `PORT` | cổng ngoài 1..65535 hoặc không phải số |
| `HTTP_NOT_ALLOWED` | `http` ngoài localhost/127.0.0.1 khi chưa bật cờ |

## Audit

Mỗi thay đổi thật ghi đúng một dòng vào bảng `tenant_storefront_origin_history` (chỉ thêm: trigger chặn `UPDATE`/`DELETE`, vai trò ứng dụng bị `REVOKE`) trong cùng transaction: `ADDED`, `ENABLED`, `DISABLED`, `REMOVED` (giữ chuỗi origin của dòng đã xóa) kèm `actor_id` và `occurred_at`. Sau commit phát `PlatformOperationEvent` (`STOREFRONT_ORIGIN_ADDED|ENABLED|DISABLED|REMOVED`, `detail=origin=<chuỗi>`) và log `platform-audit` (Kafka best-effort). Chưa có API đọc lịch sử; đọc bằng SQL khi điều tra.

## Hợp đồng identity cho customer-api (nội bộ, không qua lms-api)

### `POST /internal/storefront/access-tokens` thêm `allowedOrigins`

Header `X-Storefront-Client-Secret`, body `{tenantCode}` như ADR-0011. Phản hồi `200`:

```json
{ "tenantId": "<uuid>", "code": "shop-a", "name": "Hiệu sách A", "accessToken": "<jwt svc>", "expiresIn": 300,
  "allowedOrigins": ["https://shop.example.com", "http://localhost:5173"] }
```

- `allowedOrigins`: chỉ origin ĐANG BẬT của tenant, đã chuẩn hóa (chữ thường, punycode, không cổng mặc định, không `/` cuối), theo thứ tự tạo; mảng rỗng nếu chưa khai báo. Không nằm trong claim JWT.
- Điều kiện cấp token không đổi: tenant tồn tại, `ACTIVE` và bật `SALES`. Mọi lỗi cùng `404 SHOP_NOT_AVAILABLE` một thân cố định (không phân biệt lý do, không lộ tenant).
- customer-api so khớp `Origin` của trình duyệt (chữ thường) chính xác với tập này, không chuẩn hóa lại.

### `POST /internal/auth/refresh` thêm `tenantId` tùy chọn

Body `{refreshToken, channel?, tenantId?}` (`tenantId` là UUID). customer-api gửi `channel: "STOREFRONT"` và `tenantId` = tenant đã phân giải từ `{code}`.

- Có `tenantId` mà khác tenant của refresh token (kể cả token phiên nền tảng, không có tenant) là `401` cùng thân với token không hợp lệ. Token KHÔNG bị xoay vòng và không bị thu hồi: gọi lại đúng tenant vẫn dùng được.
- Thứ tự kiểm: token tồn tại; token đã thu hồi (vẫn thu hồi mọi phiên của người dùng, giữ nguyên bảo vệ dùng lại, kể cả khi `tenantId` sai); hết hạn; sai kênh; sai tenant; rồi mới xoay vòng.
- Không gửi `tenantId` = hành vi cũ (lms-api và `/public/auth/refresh` đường cũ không đổi).
- Chỉ nhận `tenantId` (UUID), không nhận `tenantCode`.

### `POST /internal/auth/logout`

Body như refresh; nhận nhưng BỎ QUA `tenantId` (thu hồi token nào cũng vô hại, idempotent, luôn `204`), đúng ADR-0013 2.4.

## Khóa phân quyền động

| Endpoint | Khóa | Ghi chú |
|---|---|---|
| `/api/platform/tenants/{tenantId}/storefront-origins` (4 method) | không dùng khóa | `@PlatformAdminOnly` |
| `GET /internal/storefront-origins` (qua `GET /api/storefront-origins`) | `TenantStorefrontOriginController/list` | xem `tenant-storefront-origins.md` |

## Migration

`V21__tenant_storefront_origins.sql` (identity): bảng `tenant_storefront_origins` (UNIQUE `origin`, CHECK dạng chuẩn hóa), `tenant_storefront_origin_history`, trigger chặn sửa/xóa lịch sử; `afterMigrate.sql` thêm `REVOKE UPDATE, DELETE` lịch sử cho `identity_app`. Cả hai bảng là bảng toàn cục (không RLS, nằm trong `TENANT_EXEMPT_TABLES`, ADR-0006 mục 7).
