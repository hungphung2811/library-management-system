# Storefront Gói A "khám phá": sắp xếp/lọc, sách mới về, gợi ý tìm kiếm, banner (ADR-0019)

> ⚠️ Hợp đồng **đề xuất, chưa có code** (ADR-0019 trạng thái đề xuất). `be-dev` có thể chốt chi tiết nhỏ; đổi gì thì sửa file này.
> **Base URL:** `<ORIGIN>` = `http://localhost:8080` (nginx). Phần công khai dưới `/public/` (customer-api, không `/api`); phần quản trị dưới `/api/sales/` (lms-api, JWT nhân viên).

Quy ước chung của customer-api (lỗi `{code, message, details?}`, `404 SHOP_NOT_AVAILABLE`, CORS, `503 SERVICE_UNAVAILABLE`, giới hạn tốc độ nginx) giữ như `customer-api.md`. Mọi endpoint công khai dưới đây: `GET` (và `HEAD`), không cần đăng nhập, **bỏ qua** header `Authorization` (kết quả không phụ thuộc người xem), nằm trong vùng nginx `public_read` (20 r/s theo IP) và `public_tenant` (50 r/s theo `{code}`).

## 1. Danh sách sách có sắp xếp/lọc (mở rộng endpoint sẵn có)

`GET /public/t/{code}/books?keyword&sort&inStock&page&size` — `Cache-Control: public, max-age=30`

| Tham số | Giá trị | Ghi chú |
|---|---|---|
| `keyword` | ≤ 100 ký tự, không ký tự điều khiển | Như cũ. Tìm theo tên/tác giả, khớp tối đa 200 đầu sách (kết quả bị cắt nếu nhiều hơn) |
| `sort` | `newest` \| `price_asc` \| `price_desc` | Phân biệt hoa thường. **Vắng**: không có `keyword` thì như `newest`; có `keyword` thì theo tên (hành vi cũ) |
| `inStock` | chỉ `true` | Chỉ sách còn hàng. Vắng = không lọc. `false`, `1`, `yes`... → 400 |
| `page` | ≥ 0 | Âm → 0 |
| `size` | 1..50, mặc định 20 | Lớn hơn 50 → 50 |

- `newest` = mới đưa vào bán trước (ngày cửa hàng đưa sách vào bán, không phải ngày xuất bản).
- `price_*` sắp theo giá hiển thị (`price`). Hai sách cùng giá có thứ tự ổn định giữa các trang.
- Phản hồi giữ nguyên: `{items:[{bookRef, title, author, price, inStock, hasPreview}], page, size, total}`. Không trường mới. Với `inStock=true`, `total` là số đầu sách còn hàng.
- Lỗi: `400 VALIDATION_FAILED` (`sort`, `inStock`, `keyword` sai; không gọi phía sau), `404 SHOP_NOT_AVAILABLE`, `503 SERVICE_UNAVAILABLE`.

### Rail "Sách mới về"

Không có endpoint riêng: `GET /public/t/{code}/books?sort=newest&size=10` (thêm `inStock=true` nếu chỉ muốn sách còn hàng). Cache 30 giây.

## 2. Gợi ý tìm kiếm

`GET /public/t/{code}/books/suggestions?q&limit` — `Cache-Control: public, max-age=15`

| Tham số | Giá trị |
|---|---|
| `q` | Bắt buộc, sau khi cắt khoảng trắng 2..50 ký tự, không ký tự điều khiển. Sai → `400 VALIDATION_FAILED` |
| `limit` | 1..8, mặc định 5; lớn hơn 8 → 8; vắng hoặc < 1 → 5 |

Phản hồi `200`: mảng (không phân trang), tối đa `limit` mục, chỉ sách đang bán của cửa hàng:

```json
[ { "bookRef": "5b0c...-uuid", "title": "Nhà giả kim", "author": "Paulo Coelho" } ]
```

- Đúng 3 trường; không giá, không `inStock`, không mô tả. Bấm gợi ý → mở chi tiết `GET /public/t/{code}/books/{bookRef}`.
- Không khớp: `200 []`. Tên bắt đầu bằng `q` xếp trước, sau đó theo tên.
- Khớp không phân biệt hoa thường nhưng **có phân biệt dấu** ("nha gia kim" không khớp "Nhà giả kim").
- FE bắt buộc: chỉ gọi khi đủ 2 ký tự, debounce 300 ms, hủy request cũ khi gõ tiếp; gặp `429` thì im lặng bỏ qua gợi ý (không báo lỗi cho người dùng).

## 3. Banner trang chủ (công khai)

`GET /public/t/{code}/banners` — `Cache-Control: public, max-age=60`

```json
[ { "id": "uuid", "title": "Giảm 20% sách thiếu nhi", "subtitle": "Đến hết 30/11", "linkPath": "/books?sort=newest", "sortOrder": 0 } ]
```

- Tối đa 10 banner đang hiệu lực, đã sắp theo `sortOrder` (FE giữ nguyên thứ tự). Không có banner: `200 []` (không 404).
- `subtitle`, `linkPath` có thể `null`. `linkPath` là đường dẫn **tương đối với gốc storefront của cửa hàng**: FE ghép vào route storefront hiện tại (ví dụ `/t/{code}` + `linkPath`) và điều hướng bằng router; `null` = banner không bấm được. Server bảo đảm `linkPath` bắt đầu bằng một `/`, không có scheme, không `//`.
- `title`, `subtitle` là văn bản thuần: hiển thị bằng text (`textContent`/mặc định của framework), không `innerHTML`.
- Thay đổi của admin (hoặc banner bắt đầu/hết hạn) hiện ra chậm tối đa khoảng 90 giây.
- Lỗi: `404 SHOP_NOT_AVAILABLE`, `503 SERVICE_UNAVAILABLE`.

## 4. Quản trị banner (FE quản trị, lms-api)

Header `Authorization: Bearer <JWT nhân viên>`; tenant theo token. Cần module SALES bật (không bật: `403 MODULE_NOT_ENABLED`). Khóa quyền (ADR-0003) do sales kiểm; admin tenant không cần khóa. Thiếu khóa: `403 FORBIDDEN`.

| Method | Đường dẫn | Khóa | Vào | Ra |
|---|---|---|---|---|
| GET | `/api/sales/banners` | `SalesBannerController/list` | | `200 [BannerAdminView]` mọi trạng thái, sắp `sortOrder, createdAt` (≤ 20) |
| GET | `/api/sales/banners/{id}` | `SalesBannerController/get` | | `200 BannerAdminView` |
| POST | `/api/sales/banners` | `SalesBannerController/create` | `SaveBannerRequest` | `201 BannerAdminView` |
| PUT | `/api/sales/banners/{id}` | `SalesBannerController/update` | `SaveBannerRequest` + `version` | `200 BannerAdminView` |
| DELETE | `/api/sales/banners/{id}` | `SalesBannerController/delete` | | `204` |

`SaveBannerRequest`:

```json
{ "title": "Giảm 20% sách thiếu nhi", "subtitle": "Đến hết 30/11", "linkPath": "/books?sort=newest",
  "sortOrder": 0, "active": true, "startsAt": "2026-11-20T00:00:00+07:00", "endsAt": "2026-12-01T00:00:00+07:00",
  "version": 3 }
```

| Trường | Quy tắc |
|---|---|
| `title` | Bắt buộc, 1..100 ký tự sau khi cắt khoảng trắng, một dòng, không ký tự điều khiển |
| `subtitle` | Tùy chọn, ≤ 200, một dòng; rỗng = `null` |
| `linkPath` | Tùy chọn, ≤ 200; rỗng = `null`. Bắt đầu bằng đúng một `/`; chỉ chữ/số ASCII và `- . _ ~ / ? # = & + , %`; `%` phải theo sau 2 chữ số hex; không `//`, `\`, `..`, khoảng trắng. Mọi URL có scheme (`https:`, `javascript:`...) bị từ chối |
| `sortOrder` | 0..999, mặc định 0 |
| `active` | mặc định `true` |
| `startsAt`, `endsAt` | ISO-8601 có múi giờ, tùy chọn; nếu có cả hai thì `startsAt < endsAt`. Hiệu lực: `startsAt <= now < endsAt` |
| `version` | Chỉ ở `PUT`, bắt buộc (lấy từ lần đọc gần nhất) |

`PUT` thay **toàn bộ** trường (trường vắng = `null`/mặc định). `BannerAdminView`:
`{id, title, subtitle, linkPath, sortOrder, active, startsAt, endsAt, status, version, createdAt, updatedAt}`, `status` do server tính: `INACTIVE` (`active=false`), `SCHEDULED` (chưa tới `startsAt`), `LIVE`, `EXPIRED` (đã qua `endsAt`).

Lỗi (chuyển nguyên từ sales):

| Status | code | Khi nào |
|---|---|---|
| 400 | `INVALID_BANNER` | Vi phạm quy tắc trường; `details` chỉ tên trường, không kèm giá trị |
| 400 | `VALIDATION_FAILED` (mã sẵn có của `SalesExceptionHandler`) | JSON hỏng, sai kiểu, vượt chặn thô kích thước |
| 404 | `BANNER_NOT_FOUND` | Không có (kể cả id của cửa hàng khác) |
| 409 | `BANNER_VERSION_CONFLICT` | `version` cũ: tải lại rồi sửa |
| 409 | `BANNER_LIMIT_EXCEEDED` | Đã có 20 banner (mọi trạng thái): xóa bớt |
| 403 | `FORBIDDEN`, `MODULE_NOT_ENABLED` | Thiếu khóa, chưa bật SALES |

Khóa mới chỉ có hiệu lực ở lần đăng nhập/refresh kế tiếp. ⚠️ Gán sẵn cho mẫu Quản lý cửa hàng chờ người dùng xác nhận (ADR-0019 Q-A).
## 5. Phía nội bộ (tham khảo, FE không gọi)

| Service | Đường dẫn | Khóa / chế độ |
|---|---|---|
| sales | `GET /internal/sales/storefront/items?keyword&sort&inStock&page&size` | `StorefrontController/list`, `@StorefrontAccessible` |
| sales | `GET /internal/sales/storefront/suggestions?q&limit` → `[{titleId, name, author}]` | `StorefrontController/suggestions`, `@StorefrontAccessible` |
| sales | `GET /internal/sales/storefront/banners` → `[{id, title, subtitle, linkPath, sortOrder}]` | `StorefrontBannerController/list`, `@StorefrontAccessible` |
| sales | `/internal/sales/banners/**` | `SalesBannerController/*`, **không** `@StorefrontAccessible` (token dịch vụ bị 403) |
