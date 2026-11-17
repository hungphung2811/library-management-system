# Dashboard, biểu đồ, top đầu sách và báo cáo CSV: `/api/dashboard/**`, `/api/reports/**`

Nền: ADR-0018 (`docs/decisions/0018-dashboard-thong-ke-bao-cao-va-du-lieu-demo.md`), đề bài `docs/requirements/2026-11-17-dashboard-bao-cao-demo.md`. Tài liệu này là hợp đồng của lớp `lms-api` (nhóm `dashboard` và `reports`); số liệu do catalog, circulation, sales tính, `lms-api` chỉ gộp, bổ sung tên và chuyển luồng CSV. `lms-api` **không phân quyền**: mỗi nguồn tự kiểm khóa `Controller/method` và module trên token được chuyển tiếp.

Trạng thái: phía `lms-api` đã cài đặt và có test với client giả. Các endpoint `/internal/**` của catalog, circulation, sales do agent khác cài đồng thời theo ADR mục 2.5; chưa đối chiếu với kết quả thật (xem "Điều chưa kiểm chứng" cuối file).

## 1. Quy ước chung

- Gọi qua nginx `<ORIGIN>` (xem `README.md`), `Authorization: Bearer <accessToken>` của nhân viên/quản trị. Token khách storefront bị 401.
- Múi giờ nghiệp vụ `Asia/Ho_Chi_Minh`: "hôm nay" và mọi bucket ngày/tuần cắt theo giờ VN. Ngày là `yyyy-MM-dd`; thời điểm là ISO-8601 có offset `+07:00`. Tiền là số nguyên VND.
- Khoảng ngày `from`, `to` (cả hai bao gồm), mặc định 30 ngày gần nhất tới hôm nay, tối đa 366 ngày. `lms-api` **không kiểm** các tham số này mà chuyển nguyên cho service, nên mã lỗi là của service (mục 6).
- **Platform admin** (token `platformAdmin=true`, không có `tenantId`) xem số liệu **một tenant** bằng header `X-Tenant-Id: <uuid>` (ADR-0016). Không có header: `403 TENANT_REQUIRED`; sai định dạng: `400 INVALID_TENANT_ID`. Không có chế độ cộng dồn mọi tenant. Người không phải platform admin gửi header này thì header bị bỏ qua (chỉ thấy tenant của mình). Mỗi lần xem được ghi log `platform-audit operation=act-as-tenant` ở service.
- Tổng quan nền tảng (số tenant, hợp đồng) là endpoint riêng của identity (mục 5).

## 2. Endpoint

| Method | Đường dẫn | Query | Nguồn |
|---|---|---|---|
| GET | `/api/dashboard/summary` | không | catalog + circulation + sales (song song) |
| GET | `/api/dashboard/charts/borrowings` | `from`, `to`, `granularity` (`day` mặc định, `week`) | circulation |
| GET | `/api/dashboard/charts/revenue` | `from`, `to` | sales |
| GET | `/api/dashboard/top-titles/borrowed` | `from`, `to`, `limit` (1 đến 50, mặc định 10) | circulation + tên từ catalog |
| GET | `/api/dashboard/top-titles/sold` | `from`, `to`, `limit` (1 đến 50, mặc định 10) | sales |
| GET | `/api/reports/overdue-borrowings` | không | circulation, CSV |
| GET | `/api/reports/stock` | `lowStockOnly` (`true`/`false`, tùy chọn) | sales, CSV |
| GET | `/api/reports/orders` | `from`, `to`, `channel` (`POS`/`ONLINE`), `status`, tất cả tùy chọn | sales, CSV |
| GET | `/api/users/export` | (đã có, không đổi) | identity, CSV |
| GET | `/api/platform/dashboard/overview` | `withinDays` (1 đến 365, mặc định 30) | identity (chỉ platform admin) |

## 3. Dashboard

### GET /api/dashboard/summary

`lms-api` tính mốc "hôm nay" một lần (giờ VN) và truyền cho mọi nguồn (`asOf`), gọi song song với hạn chót chung **3 giây**, mỗi lời gọi connect 1s/read 3s. Module lấy từ claim `modules` của token; token platform admin kèm `X-Tenant-Id` thì tra `GET /api/platform/tenants/{id}/modules` của identity (vì ADR-0016 bỏ qua kiểm module ở service). Module tắt thì phần đó `null` và không gọi nguồn.

Response `200`:

```json
{
  "generatedAt": "2026-11-17T10:15:30.123+07:00",
  "timezone": "Asia/Ho_Chi_Minh",
  "tenantId": "00000000-0000-0000-0000-0000000000d1",
  "modules": ["LENDING", "SALES"],
  "catalog": { "titles": 62, "copies": { "total": 155, "byStatus": { "AVAILABLE": 100, "LOANED": 40, "RESERVED": 5, "LOST": 5, "DAMAGED": 5 } } },
  "lending": { "activeBorrowings": 18, "overdueBorrowings": 6, "overdueCopies": 9, "borrowedToday": 3, "returnedToday": 2 },
  "fines": { "unpaidCount": 4, "unpaidAmount": 120000 },
  "sales": {
    "ordersToday": 2, "revenueToday": 480000, "pendingOnlineOrders": 1, "lowStockCount": 5,
    "lowStockItems": [ { "titleId": "<uuid>", "titleName": "Clean Code", "available": 0, "threshold": 5 } ]
  },
  "disabledModules": [],
  "forbiddenSections": [],
  "unavailableSections": []
}
```

- `tenantId`: chỉ có khi platform admin dùng `X-Tenant-Id` hợp lệ; trường vắng mặt hẳn nếu không.
- `modules`: module đang bật (mọi module trừ `disabledModules`). Token cũ không có claim `modules` thì `lms-api` gọi cả hai nguồn và để service quyết định.
- Các phần `catalog`, `lending`, `fines`, `sales` là JSON của service chuyển nguyên (định nghĩa chỉ số: mục 4). Phần thiếu là `null`. Khi LENDING tắt thì `catalog.copies` là `null` (số bản sao thuộc LENDING), `catalog.titles` vẫn có.
- `lowStockItems[].titleName`: `lms-api` bổ sung bằng một lần tra lô catalog (`TitleController/lookup`); thiếu khóa hoặc catalog lỗi thì `null`, không làm hỏng summary.

Suy giảm một phần (quy tắc theo thứ tự):

| # | Tình huống | Kết quả |
|---|---|---|
| 1 | Module tắt (theo claim/identity, hoặc service trả `403 MODULE_NOT_ENABLED`) | Phần đó `null`, mã module (`"LENDING"`, `"SALES"`) vào `disabledModules` |
| 2 | Nguồn trả `403 FORBIDDEN` (thiếu khóa) | Phần đó `null`, tên vào `forbiddenSections` |
| 3 | Nguồn 5xx, mất kết nối, quá hạn chót | Phần đó `null`, tên vào `unavailableSections` |
| 4 | Lỗi mức người gọi từ nguồn: `401`, `403 TENANT_REQUIRED`, `400 INVALID_TENANT_ID` | `lms-api` trả nguyên lỗi đó (không trả 200 rỗng) |
| 5 | Mọi nguồn đã gọi đều thuộc dòng 2 | `403 FORBIDDEN` |
| 6 | Mọi nguồn đã gọi đều thuộc dòng 3 | `503 SERVICE_UNAVAILABLE` |
| 7 | Còn lại | `200` |

Tên phần trong `forbiddenSections`/`unavailableSections`: `catalog`, `lending`, `fines`, `sales`. Nguồn circulation trả cả `lending` và `fines` nên khi nó lỗi thì **cả hai** tên cùng xuất hiện (`["lending","fines"]`). Tra module của tenant đích (platform admin) bị 5xx/mất kết nối: `lending`, `sales`, `fines` vào `unavailableSections`, `catalog` vẫn được gọi; lỗi 4xx khi tra (ví dụ `404 TENANT_NOT_FOUND`) được trả nguyên.

## 4. Biểu đồ và top đầu sách

Một nguồn, `lms-api` chuyển nguyên JSON; lỗi của service đi theo `ApiExceptionHandler` (4xx giữ nguyên mã và nội dung, 5xx thành `502 DEPENDENCY_ERROR`, không gọi được `503 DEPENDENCY_UNAVAILABLE`). Mỗi lời gọi connect 1s/read 3s.

### GET /api/dashboard/charts/borrowings

```json
{ "from": "2026-10-19", "to": "2026-11-17", "granularity": "day",
  "points": [ { "bucket": "2026-10-19", "borrowed": 3, "returned": 1 } ] }
```
Điền đủ mọi bucket (giá trị 0). `granularity=week`: `bucket` là ngày thứ Hai của tuần ISO. `borrowed`/`returned` đếm theo bản sao.

### GET /api/dashboard/charts/revenue

Doanh thu theo **dòng tiền** (đã chốt): `gross` là tổng `grand_total` của đơn có `paid_at` trong ngày (kể cả đơn sau đó bị hủy), `refunds` là tổng khoản hoàn `REFUNDED` theo ngày hoàn, `net = gross - refunds` (tiền thực thu, có thể âm trong một ngày nếu hoàn nhiều hơn thu); `orders` là số đơn trong `gross`.

```json
{ "from": "2026-11-01", "to": "2026-11-17",
  "totals": { "gross": 5200000, "refunds": 300000, "net": 4900000, "orders": 41 },
  "points": [ { "date": "2026-11-01", "gross": 250000, "refunds": 0, "net": 250000, "orders": 2 } ] }
```

### GET /api/dashboard/top-titles/borrowed

```json
{ "items": [ { "titleId": "<uuid>", "titleName": "Clean Code", "count": 9 } ] }
```
Sắp `count` giảm, đồng hạng theo `titleId` tăng. Circulation chỉ giữ `titleId`; `titleName` do `lms-api` tra catalog theo lô, thiếu khóa `TitleController/lookup`, catalog lỗi hoặc đầu sách không còn thì `null` (HTTP vẫn 200). Phiếu mượn cũ trước migration không có `title_id` nên không vào top.

### GET /api/dashboard/top-titles/sold

```json
{ "items": [ { "titleId": "<uuid>", "titleName": "Clean Code", "quantity": 7, "revenue": 1750000 } ] }
```
`quantity` đã trừ số lượng trả hàng trong khoảng. `titleName` là snapshot của sales (`lms-api` không tra).

## 5. Tổng quan nền tảng và tra lô người dùng (identity)

### GET /api/platform/dashboard/overview?withinDays=

Chỉ platform admin (`@PlatformAdminOnly`, không có khóa `Controller/method`): tenant admin nhận `403 FORBIDDEN`, không token `401`. `lms-api` chuyển nguyên `/api/platform/**` sang identity (không có route riêng). Không có số liệu nghiệp vụ của tenant.

```json
{
  "tenants": { "total": 3, "active": 2, "suspended": 1 },
  "activeTenantsByModule": { "LENDING": 3, "SALES": 1 },
  "withinDays": 30,
  "expiringContractCount": 1,
  "expiringContracts": [
    { "contractId": "<uuid>", "tenantId": "<uuid>", "tenantCode": "demo", "tenantName": "Thư viện Demo", "name": "Gói 12 tháng",
      "endDate": "2026-12-07", "daysLeft": 20, "inGrace": false, "graceEndsDate": "2026-12-14", "outstandingAmount": 0 }
  ]
}
```
`expiringContracts` cùng định nghĩa với `GET /api/platform/contracts/expiring` (gồm hợp đồng đang ân hạn). `withinDays` ngoài 1 đến 365 là lỗi `400`.

### POST /internal/users/lookup (nội bộ, FE không gọi)

Circulation gọi identity để lấy họ tên độc giả cho báo cáo quá hạn. Nginx trả 404 cho `/internal/**`. Khóa động `UserController/lookup`.

Request `{"ids": ["<uuid>", ...]}` (1 đến 100 id, không null). Response `200` `[ { "id": "<uuid>", "fullName": "Nguyễn Văn A" } ]`. Chỉ trả người thuộc tenant hiện tại (lọc qua `tenant_memberships`); id ngoài tenant bị loại, không báo lỗi. Không trả email, điện thoại, tên đăng nhập.

## 6. Báo cáo CSV

Service sở hữu dữ liệu sinh CSV và ghi thẳng vào luồng phản hồi (theo lô); `lms-api` chuyển luồng byte, không giữ cả file trong bộ nhớ. Client riêng của `lms-api`: connect 2s, read 30s (khớp `proxy_read_timeout 30s` của nginx).

Phản hồi `200`:

| Header | Giá trị |
|---|---|
| `Content-Type` | `text/csv; charset=UTF-8` |
| `Content-Disposition` | `attachment; filename="<tên>-<yyyyMMdd>.csv"` (ngày giờ VN) |
| `X-Report-Truncated` | `true` khi bị cắt ở trần 10000 dòng; **vắng hẳn** khi đủ |
| `Cache-Control` | `no-store` |

Thân: BOM UTF-8 (`EF BB BF`), dấu phân cách `,`, xuống dòng CRLF, dòng tiêu đề tiếng Anh cố định, ô bắt đầu bằng `=` `+` `-` `@` được thêm dấu nháy đơn phía trước (chống CSV injection).

| Báo cáo | Cột |
|---|---|
| `overdue-borrowings` | `borrowingId, userId, readerName, titleName, barcode, borrowDate, dueDate, daysOverdue` (một dòng mỗi bản sao chưa trả của phiếu quá hạn, `daysOverdue` >= 1, sắp giảm dần; tra tên lỗi thì ô trống) |
| `stock` | `titleId, titleName, isbn, onHand, reserved, available, threshold, lowStock, sellingPrice` (sắp `available` tăng) |
| `orders` | `orderNo, channel, status, paymentStatus, createdAt, paidAt, itemCount, subtotal, discount, vat, shippingFee, total` (lọc theo ngày tạo giờ VN, mặc định 30 ngày; không có thông tin khách) |

**Lỗi trước byte đầu** là JSON lỗi bình thường (`400 INVALID_RANGE`/`RANGE_TOO_LARGE`, `403 FORBIDDEN`/`MODULE_NOT_ENABLED`/`TENANT_REQUIRED`, `401`). **Lỗi sau khi đã ghi byte** chỉ cắt được kết nối: FE nhận file thiếu (chấp nhận cho bản trình diễn). Mỗi lần xuất, service ghi log `report-export report=<tên> actorId=<sub> tenantId=<uuid> rows=<n> truncated=<bool>`.

## 7. Mã lỗi

| HTTP | `code` | Khi |
|---|---|---|
| 400 | `INVALID_RANGE` | `from` sau `to` |
| 400 | `RANGE_TOO_LARGE` | khoảng quá 366 ngày |
| 400 | `INVALID_GRANULARITY` | `granularity` khác `day`, `week` |
| 400 | `INVALID_TENANT_ID` | `X-Tenant-Id` sai định dạng (platform admin) |
| 400 | (do service) | `limit` ngoài 1 đến 50 |
| 401 | | không token, token hết hạn, token kênh storefront |
| 403 | `FORBIDDEN` | thiếu khóa (endpoint một nguồn); `summary` khi mọi nguồn đã gọi đều cấm |
| 403 | `TENANT_REQUIRED` | token không có `tenantId` (không phải platform admin) hoặc platform admin thiếu `X-Tenant-Id` |
| 403 | `MODULE_NOT_ENABLED` | tenant chưa bật module của endpoint một nguồn (`summary` thì xếp vào `disabledModules`) |
| 404 | `TENANT_NOT_FOUND` | platform admin gửi `X-Tenant-Id` của tenant không tồn tại (khi `summary` tra module) |
| 502 | `DEPENDENCY_ERROR` | endpoint một nguồn, service nội bộ lỗi 5xx |
| 503 | `DEPENDENCY_UNAVAILABLE` | endpoint một nguồn, không gọi được service |
| 503 | `SERVICE_UNAVAILABLE` | `summary` khi mọi nguồn đã gọi đều hỏng |

Mã `400`/`403` của khoảng ngày và module do service ném; `lms-api` giữ nguyên thân lỗi.

## 8. Khóa quyền (sinh ở service, lms-api không kiểm)

Khóa là `TênController/tênMethod`, đăng ký ở lần gọi đầu; role cần được gán khóa và đăng nhập/làm mới lại. Admin tenant (`is_admin`) và platform admin (có `X-Tenant-Id`) bỏ qua kiểm khóa. **Đổi tên controller/method làm role mất quyền.**

| Khóa | Đường nội bộ | Module | Phục vụ |
|---|---|---|---|
| `CatalogStatsController/summary` | catalog `GET /internal/reporting/catalog/summary` | lõi | `summary.catalog` |
| `LendingStatsController/summary` | circulation `GET /internal/reporting/lending/summary?asOf=` | LENDING | `summary.lending`, `summary.fines` |
| `LendingStatsController/borrowingTrend` | circulation `GET /internal/reporting/lending/borrowing-trend?from=&to=&granularity=` | LENDING | biểu đồ mượn/trả |
| `LendingStatsController/topBorrowedTitles` | circulation `GET /internal/reporting/lending/top-titles?from=&to=&limit=` | LENDING | top mượn |
| `SalesStatsController/summary` | sales `GET /internal/sales/reporting/summary?asOf=` | SALES | `summary.sales` |
| `SalesStatsController/revenueTrend` | sales `GET /internal/sales/reporting/revenue-trend?from=&to=` | SALES | biểu đồ doanh thu |
| `SalesStatsController/topSoldTitles` | sales `GET /internal/sales/reporting/top-titles?from=&to=&limit=` | SALES | top bán |
| `LendingReportController/overdueBorrowingsCsv` | circulation `GET /internal/reporting/lending/overdue-borrowings.csv` | LENDING | CSV quá hạn |
| `SalesReportController/stockCsv` | sales `GET /internal/sales/reporting/stock.csv?lowStockOnly=` | SALES | CSV tồn kho |
| `SalesReportController/ordersCsv` | sales `GET /internal/sales/reporting/orders.csv?from=&to=&channel=&status=` | SALES | CSV đơn hàng |
| `TitleController/lookup` | catalog `POST /internal/books/lookup` | lõi | bổ sung `titleName` (top mượn, tồn thấp); thiếu khóa thì `null` |
| `UserController/lookup` | identity `POST /internal/users/lookup` | lõi | circulation tra `readerName` |
| `UserController/exportUsers` | identity (đã có) | lõi | CSV người dùng |
| (không khóa, `@PlatformAdminOnly`) | identity `GET /api/platform/dashboard/overview` | không | tổng quan nền tảng |

**Khóa xuất CSV** (`LendingReportController/*`, `SalesReportController/*`) **không gán sẵn cho role mẫu**: chỉ tenant admin (`is_admin`) dùng được cho tới khi quản trị gán tay cho role khác. Khóa xem dashboard được gán sẵn cho mẫu thủ thư/quản lý thư viện (mượn trả) và mẫu quản lý cửa hàng (bán hàng) theo migration identity `V25__reporting_permissions.sql` (ADR-0018 mục 2.5).

## 9. Ghi chú cho FE

- **Platform admin** gửi `X-Tenant-Id: <uuid>` cho mọi endpoint ở mục 2 (trừ `/api/platform/dashboard/overview`) để xem một tenant. Với `summary`, response có `tenantId`.
- Trang dashboard của tenant nên chạy `summary` rồi vẽ từng phần; phần `null` kèm `disabledModules` (ẩn thẻ), `forbiddenSections` (ẩn/đánh dấu không có quyền), `unavailableSections` (hiện "tạm không khả dụng", cho tải lại). `503 SERVICE_UNAVAILABLE` và `403 FORBIDDEN` cho cả trang.
- **Tải CSV từ origin khác** (5173/3000): để đọc `Content-Disposition` (tên file) và `X-Report-Truncated` (cờ cắt) trong JavaScript, nginx phải liệt kê hai header này trong `Access-Control-Expose-Headers` (snippet `infrastructure/nginx/snippets/common.conf`, việc đã giao riêng; kiểm lại trước khi dùng). Nếu chưa, `fetch` không đọc được hai header này.
- Tải file: dùng `fetch` kèm Bearer rồi `blob()`; không dùng thẻ `<a href>` (không gửi được Bearer). Nếu `X-Report-Truncated: true`, báo người dùng file đã bị cắt ở 10000 dòng.
- Hạn chót `summary` 3 giây; biểu đồ/top mỗi lời gọi tối đa 3 giây đọc. Báo cáo CSV tối đa 30 giây.
- Số liệu thời gian thực: không có cache ở BFF; người xem mượn một cuốn rồi mở dashboard sẽ thấy số mới.

## 10. Ví dụ curl

```bash
ORIGIN=http://localhost:8080
TOKEN=$(curl -s -X POST $ORIGIN/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"demo.admin","password":"<mật khẩu demo>"}' | jq -r .accessToken)

# Tổng quan tenant
curl -s $ORIGIN/api/dashboard/summary -H "Authorization: Bearer $TOKEN" | jq

# Biểu đồ mượn/trả theo tuần, doanh thu, top
curl -s "$ORIGIN/api/dashboard/charts/borrowings?from=2026-10-19&to=2026-11-17&granularity=week" -H "Authorization: Bearer $TOKEN"
curl -s "$ORIGIN/api/dashboard/charts/revenue?from=2026-11-01&to=2026-11-17" -H "Authorization: Bearer $TOKEN"
curl -s "$ORIGIN/api/dashboard/top-titles/borrowed?limit=5" -H "Authorization: Bearer $TOKEN"
curl -s "$ORIGIN/api/dashboard/top-titles/sold?limit=5" -H "Authorization: Bearer $TOKEN"

# Báo cáo CSV (lưu theo tên file server gợi ý, in header để xem X-Report-Truncated)
curl -s -OJ -D - "$ORIGIN/api/reports/overdue-borrowings" -H "Authorization: Bearer $TOKEN"
curl -s -OJ "$ORIGIN/api/reports/stock?lowStockOnly=true" -H "Authorization: Bearer $TOKEN"
curl -s -OJ "$ORIGIN/api/reports/orders?from=2026-11-01&to=2026-11-17&channel=POS" -H "Authorization: Bearer $TOKEN"

# Platform admin xem một tenant
PA=$(curl -s -X POST $ORIGIN/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"platform.admin","password":"<mật khẩu>"}' | jq -r .accessToken)
curl -s $ORIGIN/api/dashboard/summary -H "Authorization: Bearer $PA" \
  -H "X-Tenant-Id: 00000000-0000-0000-0000-0000000000d1" | jq

# Tổng quan nền tảng (chỉ platform admin)
curl -s "$ORIGIN/api/platform/dashboard/overview?withinDays=30" -H "Authorization: Bearer $PA" | jq
```

## 11. Điều chưa kiểm chứng

- ⚠️ Hình dạng JSON của `/internal/**` ở catalog, circulation, sales là **đoán theo ADR/đề bài** (agent khác cài đồng thời): `lms-api` chuyển nguyên thân nên sai khác tên trường không làm gãy BFF, trừ các điểm BFF đọc: circulation `summary` phải có khóa `lending` và `fines`; sales `summary.lowStockItems[]` có `titleId` (và có `titleName` thì BFF không tra); top mượn `items[].titleId`; catalog lookup `[ {id, name} ]`; module identity `[ {module, enabled} ]`.
- ⚠️ Kiểm qua nginx 8080 với service thật chưa chạy; test của `lms-api` dùng máy chủ HTTP giả và `MockRestServiceServer`.
- ⚠️ `Access-Control-Expose-Headers` do agent khác sửa nginx; chưa kiểm FE khác origin đọc được `Content-Disposition`.
- ⚠️ Hạn chót 3 giây không hủy được lời gọi mạng đã bắt đầu (JDK `HttpURLConnection` không ngắt được); lời gọi đó tự kết thúc theo timeout read 3 giây và kết quả bị bỏ.
