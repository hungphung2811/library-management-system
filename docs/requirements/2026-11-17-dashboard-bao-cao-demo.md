# Đề bài: Dashboard thống kê, biểu đồ, xuất báo cáo CSV và dữ liệu mẫu để trình diễn

- Ngày: 2026-11-17
- Trạng thái: ⏳ chờ người dùng duyệt "Câu hỏi mở" (mục 14), rồi `sa` thiết kế
- Liên quan: ADR-0003 (khóa `Controller/method`), ADR-0005 (BFF `lms-api`), ADR-0006 (multi-tenant, RLS), ADR-0008 (đọc xuyên tenant), ADR-0009 (module LENDING/SALES, `MODULE_NOT_ENABLED`), ADR-0010 (sales), ADR-0016 (platform admin ghi/đọc trong tenant qua `X-Tenant-Id`), `docs/api/borrowings-search.md`, `docs/api/sales.md`, `docs/api/identity-account-admin.md` (đã có `GET /api/users/export`), `docs/requirements/2026-11-17-hop-dong-thue-dich-vu.md`

## 1. Mục tiêu

ℹ️ Trang admin cần đủ chức năng để TRÌNH DIỄN. Hiện có CRUD sách/bản sao, mượn trả, đặt trước, phạt, bán hàng, CMS, người dùng/role, tenant, hợp đồng, nhưng KHÔNG có endpoint thống kê, dashboard hay báo cáo nào, và dữ liệu thì trống. Đề bài gồm 4 nhóm đã chốt với người dùng:

1. Dashboard tổng quan theo tenant; platform admin có thêm tổng quan toàn hệ thống.
2. Dữ liệu theo thời gian cho biểu đồ (mượn/trả, doanh thu, top sách mượn và bán).
3. Xuất báo cáo CSV: mượn quá hạn, tồn kho, đơn hàng, người dùng.
4. Dữ liệu mẫu (seed) cho một tenant demo để màn hình không trống.

## 2. Hiện trạng (đã đối chiếu tài liệu/code)

- ✅ `GET /api/borrowings/search?overdue=true` đã có định nghĩa "quá hạn": phiếu còn bản sao chưa trả và `dueDate` trước hôm nay (dùng lại, không định nghĩa khác).
- ✅ `GET /api/sales/stock-items?lowStockOnly=` và `lowStockThreshold` (theo mặt hàng và theo `sales/settings`, 0..100000) đã có: "sắp hết tồn" dùng lại đúng định nghĩa này.
- ✅ `GET /api/users/export` (CSV, BOM, tối đa 5000 dòng, chống CSV injection, khóa `UserController/exportUsers`) đã có: báo cáo người dùng dùng lại, không viết mới.
- ✅ `GET /api/platform/contracts/expiring`, `GET /api/platform/tenants`, `GET /api/platform/tenants/{id}/modules` đã có.
- ✅ Trạng thái: phiếu mượn `ACTIVE/RETURNED`; phạt `UNPAID/PAID`; bản sao `AVAILABLE/LOANED/RESERVED/LOST/DAMAGED`; đơn `PENDING_CONFIRMATION/CONFIRMED/READY_FOR_PICKUP/SHIPPING/COMPLETED/CANCELLED`; thanh toán `UNPAID/PAID/PARTIALLY_REFUNDED/REFUNDED`; kênh `POS/ONLINE`.
- ❌ Chưa có: endpoint thống kê ở circulation/catalog/sales/identity, endpoint gộp ở `lms-api`, báo cáo CSV ngoài users, cơ chế seed demo.
- ⚠️ Mỗi service một DB riêng, không join chéo DB (CLAUDE.md): mọi số liệu phải được tính trong service sở hữu dữ liệu, rồi `lms-api` gộp.

## 3. Người dùng

| Vai trò | Dùng gì |
|---|---|
| Tenant admin (`admin=true`) | Mọi phần thống kê/biểu đồ/báo cáo của tenant mình (phần SALES chỉ khi tenant bật) |
| Thủ thư / nhân viên có khóa | Phần thống kê/báo cáo mượn trả nếu role được gán khóa tương ứng |
| Quản lý cửa hàng có khóa | Phần thống kê/báo cáo bán hàng, tồn kho nếu role được gán khóa tương ứng |
| Người dùng thường (độc giả, khách storefront) | Không truy cập |
| Platform admin | Tổng quan toàn hệ thống; xem dashboard của một tenant bất kỳ qua `X-Tenant-Id` |

## 4. Phạm vi

### Làm
1. Số liệu tổng quan theo tenant (mục 5.2).
2. Dữ liệu biểu đồ: mượn/trả theo ngày hoặc tuần, doanh thu theo ngày, top sách mượn và top sách bán (mục 5.3).
3. Tổng quan platform: tenant theo trạng thái, hợp đồng sắp hết hạn/đang ân hạn, module đang bật (mục 5.4).
4. Xuất CSV: mượn quá hạn, tồn kho, đơn hàng (mới) và người dùng (đã có, bổ sung vào danh sách báo cáo của trang) (mục 5.5).
5. Seed dữ liệu demo cho tenant demo, chạy lại an toàn, chỉ ở dev/demo (mục 8).
6. Tài liệu `docs/api/dashboard-reports.md` và ghi lại cách chạy seed trong README.

### Ngoài phạm vi
- ❌ Xuất Excel (`.xlsx`), PDF; CSV mở được bằng Excel nhờ BOM UTF-8 (lý do: không thêm thư viện, đủ cho trình diễn).
- ❌ Báo cáo lên lịch, gửi email, dashboard theo thời gian thực (WebSocket/SSE), cảnh báo/ngưỡng tùy biến theo người dùng.
- ❌ Dashboard cho độc giả/khách storefront; số liệu của storefront (lượt xem, chuyển đổi).
- ❌ Gộp số liệu kinh doanh của TẤT CẢ tenant (doanh thu, mượn trả cộng dồn) cho platform admin; platform chỉ có số liệu quản trị nền tảng ở 5.4 (xem Giả định A3).
- ❌ Báo cáo lãi lỗ, giá vốn, VAT, đối soát tiền hợp đồng thuê dịch vụ.
- ❌ Thống kê ở `customer-api` (`/public/**`).
- ❌ Giao diện FE (repo `../library-management-system-fe`); BE chỉ cung cấp API, tài liệu và seed.
- ❌ Cache phân tán (Redis) cho số liệu; materialized view; bảng tổng hợp (rollup) chạy nền (xem Giả định A9).

## 5. Quy tắc nghiệp vụ và định nghĩa chỉ số

### 5.1. Quy ước chung
- Múi giờ nghiệp vụ `Asia/Ho_Chi_Minh` (UTC+7). "Hôm nay" là `[00:00, 24:00)` giờ VN; ngày/tuần của biểu đồ cắt theo giờ VN, KHÔNG theo UTC (một giao dịch 23:30 giờ VN thuộc ngày đó dù là ngày hôm sau theo UTC). Dữ liệu lưu UTC; xuất CSV và JSON dùng ISO-8601 có offset `+07:00` cho thời điểm, `yyyy-MM-dd` cho ngày.
- Tuần theo ISO: bắt đầu thứ Hai; nhãn tuần là ngày thứ Hai (`yyyy-MM-dd`).
- Tiền là số nguyên VND (đồng bộ ADR-0010), không đổi tiền tệ.
- Mọi số liệu chỉ tính trong MỘT tenant (RLS), không bao giờ cộng chéo tenant ở endpoint tenant.
- Khoảng thời gian: tham số `from` (bao gồm) và `to` (bao gồm, tính theo NGÀY giờ VN) dạng `yyyy-MM-dd`. Mặc định 30 ngày gần nhất tính tới hôm nay. `from > to`: 400 `INVALID_RANGE`. Dài hơn 366 ngày: 400 `RANGE_TOO_LARGE`. Mốc không có dữ liệu vẫn trả `0` (điền đủ mọi ngày/tuần trong khoảng, để biểu đồ liên tục).
- Số liệu "tức thời" (đang mượn, quá hạn, phạt chưa thu, tồn kho) phản ánh trạng thái tại lúc gọi; số liệu "theo thời gian" tính theo mốc sự kiện ghi ở bảng dưới.

### 5.2. Chỉ số dashboard theo tenant (`summary`)

| Nhóm | Trường | Định nghĩa | Module |
|---|---|---|---|
| Danh mục | `catalog.titles` | Số đầu sách của tenant (không tính đã xóa/ẩn nếu có cờ) | lõi |
| Danh mục | `catalog.copies.total`, `catalog.copies.byStatus{AVAILABLE,LOANED,RESERVED,LOST,DAMAGED}` | Số bản sao theo trạng thái; `total` = tổng các trạng thái | LENDING (BFF ẩn phần `copies` nếu tenant không bật LENDING) |
| Mượn trả | `lending.activeBorrowings` | Số PHIẾU mượn `status=ACTIVE` (còn ít nhất một bản sao chưa trả) | LENDING |
| Mượn trả | `lending.overdueBorrowings` | Số phiếu `ACTIVE` có `dueDate` trước hôm nay (giờ VN). Đúng bằng `total` của `GET /api/borrowings/search?overdue=true`. Phiếu đến hạn đúng hôm nay CHƯA quá hạn | LENDING |
| Mượn trả | `lending.overdueCopies` | Số bản sao chưa trả thuộc phiếu quá hạn | LENDING |
| Mượn trả | `lending.borrowedToday`, `lending.returnedToday` | Số lượt mượn (bản sao) có `borrowDate` hôm nay; số bản sao có thời điểm trả hôm nay (giờ VN) | LENDING |
| Phạt | `fines.unpaidCount`, `fines.unpaidAmount` | Số khoản và tổng tiền các khoản phạt `UNPAID` (VND) | LENDING |
| Bán hàng | `sales.ordersToday` | Số đơn tính doanh thu phát sinh hôm nay (định nghĩa doanh thu ngay dưới) | SALES |
| Bán hàng | `sales.revenueToday` | Doanh thu thuần hôm nay (VND) | SALES |
| Bán hàng | `sales.pendingOnlineOrders` | Số đơn ONLINE ở `PENDING_CONFIRMATION` (việc cần làm) | SALES |
| Bán hàng | `sales.lowStockCount` | Số mặt hàng sắp hết tồn (định nghĩa dưới) | SALES |
| Bán hàng | `sales.lowStockItems[]` | Tối đa 10 mặt hàng sắp hết tồn, ít nhất trước: `{titleId, available, threshold}`; tên đầu sách do BFF bổ sung từ catalog | SALES |

Định nghĩa dùng chung:
- **Quá hạn**: như bảng trên (phiếu `ACTIVE` và `dueDate` < hôm nay giờ VN). Không tính đặt trước hết hạn giữ chỗ.
- **Doanh thu (thuần)** trong một khoảng = (A) tổng `totalAmount` (tiền khách phải trả, đã gồm VAT, phí vận chuyển và giảm giá) của các đơn có `paymentStatus` thuộc `PAID`, `PARTIALLY_REFUNDED`, `REFUNDED` và `status` khác `CANCELLED`, tính theo NGÀY THANH TOÁN (POS: lúc tạo đơn; ONLINE: lúc đánh dấu đã thanh toán), TRỪ (B) tổng khoản hoàn đã ở trạng thái `REFUNDED`, tính theo NGÀY HOÀN. API trả đủ ba trường `gross` (A), `refunds` (B), `net` (A − B); "doanh thu" hiển thị mặc định là `net`. Số đơn (`orders`) là số đơn trong (A). Đơn chưa thanh toán, đơn hủy, khoản hoàn `PENDING` không ảnh hưởng.
- **Sắp hết tồn**: mặt hàng đã đăng ký tồn kho có `available = onHand − reserved` nhỏ hơn hoặc bằng ngưỡng; ngưỡng của mặt hàng (`lowStockThreshold` riêng) nếu có, ngược lại ngưỡng chung của tenant (`sales/settings.lowStockThreshold`). Đúng định nghĩa của `lowStockOnly=true`; không tạo ngưỡng mới. Mặt hàng `available = 0` vẫn tính là sắp hết.

### 5.3. Dữ liệu biểu đồ

| Biểu đồ | Phần tử trả về | Mốc thời gian | Module |
|---|---|---|---|
| Mượn/trả theo thời gian | `{bucket, borrowed, returned}` (`bucket` = ngày hoặc ngày đầu tuần); `borrowed` = số bản sao mượn theo `borrowDate`; `returned` = số bản sao trả theo thời điểm trả | giờ VN | LENDING |
| Doanh thu theo ngày | `{date, gross, refunds, net, orders}` theo định nghĩa 5.2; chỉ theo ngày (không có tuần) | giờ VN | SALES |
| Top sách được mượn | `{titleId, titleName, count}`: `count` = số bản sao đã mượn của đầu sách trong khoảng (theo `borrowDate`) | giờ VN | LENDING |
| Top sách bán chạy | `{titleId, titleName, quantity, revenue}`: `quantity` = tổng số lượng ở các dòng đơn thuộc tập đơn (A) của khoản 5.2, TRỪ số lượng đã có phiếu trả hàng trong khoảng; sắp theo `quantity` giảm | giờ VN | SALES |

- Tham số chung: `from`, `to` (5.1); mượn/trả thêm `granularity=day|week` (mặc định `day`; khác giá trị: 400 `INVALID_GRANULARITY`); top thêm `limit` (mặc định 10, 1..50; ngoài khoảng: 400).
- Sắp xếp top: `count`/`quantity` giảm dần, đồng hạng thì `titleId` tăng dần (ổn định, để test so sánh được).
- Đầu sách không còn trong catalog: `titleName` trả `null` (không làm hỏng cả biểu đồ).
- Điền đủ mọi bucket (giá trị 0) cho biểu đồ theo thời gian; top chỉ trả phần tử có số lượng > 0.

### 5.4. Tổng quan platform admin (`platform overview`)

| Trường | Định nghĩa |
|---|---|
| `tenants.total`, `tenants.byStatus{ACTIVE,SUSPENDED}` | Từ `identity_db.tenants` |
| `modules{LENDING,SALES}` | Số tenant đang bật mỗi module (`tenant_modules.enabled`) |
| `contracts.expiringSoon[]` | Hợp đồng HIỆU LỰC có ngày kết thúc trong `withinDays` ngày tới (mặc định 30): `{contractId, tenantId, tenantName, endDate, modules}`; dùng lại logic của `/api/platform/contracts/expiring` |
| `contracts.inGrace[]` | Hợp đồng đang ân hạn (cờ suy ra, hợp đồng thuê dịch vụ mục 5.2) |
| `contracts.activeCount` | Số hợp đồng HIỆU LỰC |
| `tenantsWithoutContract` | Số tenant không có hợp đồng HIỆU LỰC nào |

- Chỉ dữ liệu của identity (bảng toàn cục), nên không đụng RLS và không gọi service khác.
- Tiền hợp đồng KHÔNG đưa vào tổng quan (giữ ngoài phạm vi; tenant admin không thấy dòng này vì endpoint `@PlatformAdminOnly`).

### 5.5. Báo cáo CSV

Quy tắc chung:
- `200 text/csv; charset=UTF-8`, có BOM, `Content-Disposition: attachment; filename="<tên>-<yyyyMMdd>.csv"`, `Cache-Control: no-store`; dòng tiêu đề tiếng Anh cố định (tên cột như bảng dưới); dấu phân cách `,`; số không có dấu phân nhóm; ô bắt đầu bằng `=` `+` `-` `@` thêm dấu `'` phía trước (chống CSV injection, giống `users/export`).
- Tối đa 10000 dòng/lần; vượt thì cắt, thêm header `X-Report-Truncated: true` (cùng cách xử lý với `users/export`, chỉ khác trần).
- Không chứa dữ liệu cá nhân nhạy cảm ngoài những cột nêu (đơn hàng KHÔNG xuất tên, điện thoại, địa chỉ khách; đồng bộ với việc liên hệ khách bị che trong `sales.md`).
- Ghi log INFO có cấu trúc mỗi lần xuất: `report-export report=<tên> actorId=<sub> tenantId=<uuid> rows=<n>` (không ghi giá trị bộ lọc nhạy cảm).
- Phân quyền: mỗi báo cáo một khóa `Controller/method` riêng, tách khỏi khóa xem dashboard (xuất dữ liệu hàng loạt là quyền mạnh hơn xem số tổng).

| Báo cáo | Cột | Bộ lọc | Module |
|---|---|---|---|
| Mượn quá hạn | `borrowingId, userId, readerName, titleName, barcode, borrowDate, dueDate, daysOverdue` (một dòng mỗi BẢN SAO chưa trả của phiếu quá hạn; `daysOverdue` = số ngày giờ VN từ `dueDate` tới hôm nay, >= 1); sắp theo `daysOverdue` giảm | không (luôn là tập quá hạn tại thời điểm gọi) | LENDING |
| Tồn kho | `titleId, titleName, isbn, onHand, reserved, available, threshold, lowStock (true/false), sellingPrice` (sắp theo `available` tăng) | `lowStockOnly?` | SALES |
| Đơn hàng | `orderNo, channel, status, paymentStatus, createdAt, paidAt, itemCount, subtotal, discount, vat, shippingFee, total` | `from`, `to` (theo `createdAt`, ngày giờ VN, mặc định 30 ngày), `channel?`, `status?` | SALES |
| Người dùng | Đã có: `GET /api/users/export` (`username, fullName, email, department, status, roles`) | `keyword`, `departmentId`, `status` | lõi (không đổi) |

- `readerName`, `titleName`, `isbn` do `lms-api` bổ sung (gọi lô tới identity/catalog) vì DB của service sở hữu bảng không có; nếu tra cứu lỗi thì để trống và vẫn xuất đủ dòng (xem Giả định A7).

## 6. Phân quyền

### 6.1. Cơ chế
- Theo ADR-0003: endpoint mới ở service (circulation, catalog, sales, identity) mang khóa động `Controller/method`; `lms-api` KHÔNG kiểm quyền, chỉ chuyển token và gộp. Role `is_admin` bỏ qua kiểm khóa. Module kiểm bằng `@RequiresModule` (ADR-0009): controller thống kê của circulation gắn `LENDING`, của sales gắn `SALES`; luật ArchUnit hiện có (`controllersMustRequireModule`) phải tiếp tục xanh. Controller thống kê của catalog không gắn module (đầu sách là lõi); phần số bản sao do BFF ẩn khi tenant không bật LENDING.
- Khóa mới chỉ đăng ký ở lần gọi đầu; quyền gán cho role chỉ có hiệu lực ở token kế tiếp. Cần migration identity (kiểm số `n` kế tiếp ngay trước khi tạo, hiện đã tới ít nhất V23) gán khóa cho các role mẫu (`role_templates`) và role của tenant hiện có theo bảng 6.2, theo mẫu V17/V20/V22 (bảng `role_templates`/`role_permissions` có FK tới `permissions(code)`: khóa phải được chèn vào `permissions` trước). Tên controller/method phải ổn định (đổi tên làm role mất quyền).
- Gộp ở `lms-api`: BFF gọi từng service bằng token của người dùng; service nào trả 403 `FORBIDDEN` thì phần đó của kết quả gộp bị bỏ khỏi `sections` và liệt kê trong `forbiddenSections` (không làm cả yêu cầu thất bại); service lỗi 5xx/không kết nối được thì liệt kê trong `unavailableSections` (xem 7). Không ai thấy số liệu mình không có khóa.
- Phần SALES/LENDING chỉ hiện khi tenant bật module: BFF đọc claim `modules` của token; tenant không bật thì BFF không gọi service tương ứng, trả phần đó `null` và ghi tên vào `disabledModules`. Service vẫn tự chặn bằng `@RequiresModule` (không tin BFF).

### 6.2. Khóa và người được cấp (đề xuất, `sa` chốt tên)

| Khóa (controller/method) | Ở service | Cấp cho |
|---|---|---|
| `LendingStatsController/summary`, `.../borrowingTrend`, `.../topBorrowedTitles` | circulation | Tenant admin (mặc định `is_admin`), mẫu role thủ thư/quản lý |
| `CatalogStatsController/summary` | catalog | Như trên (mọi role có quyền xem sách nên xem được số tổng đầu sách/bản sao) |
| `SalesStatsController/summary`, `.../revenueTrend`, `.../topSoldTitles` | sales | Tenant admin, mẫu role quản lý cửa hàng |
| `LendingReportController/overdueBorrowingsCsv` | circulation | Tenant admin, mẫu role thủ thư/quản lý |
| `SalesReportController/stockCsv`, `.../ordersCsv` | sales | Tenant admin, mẫu role quản lý cửa hàng |
| `UserController/exportUsers` | identity | Không đổi (đã có) |
| `PlatformDashboardController/overview` | identity | Chỉ `@PlatformAdminOnly` (không dùng khóa động; tenant admin nhận 403 `FORBIDDEN`) |

- Người dùng thường, độc giả, khách storefront: không được cấp khóa nào ở trên; gọi bị 403 `FORBIDDEN`; chưa đăng nhập 401; token thiếu `tenantId` (không phải platform admin) 403 `TENANT_REQUIRED`.

### 6.3. Platform admin
- Tổng quan nền tảng: `@PlatformAdminOnly`, không cần tenant.
- Xem dashboard/biểu đồ/báo cáo của MỘT tenant: header `X-Tenant-Id: <uuid>` theo ADR-0016 (platform admin không có `tenantId` trong token; header sai định dạng: 400 `INVALID_TENANT_ID`; người gọi không phải platform admin: header bị bỏ qua). ADR-0016 bỏ qua kiểm khóa và kiểm module cho platform admin, nên `lms-api` quyết định phần nào hiện bằng cách đọc module đang bật của tenant đích qua `GET /api/platform/tenants/{id}/modules` (không dùng claim `modules` vì token platform không có). `lms-api` đã chuyển nguyên `X-Tenant-Id` xuống `/internal/**`.
- Không có `X-Tenant-Id` mà gọi endpoint dashboard tenant: 403 `TENANT_REQUIRED` (hành vi hiện có; không có chế độ cộng số liệu mọi tenant, xem Giả định A3). Query `tenantId` kiểu ADR-0008 KHÔNG áp dụng cho các endpoint này (không gắn `@CrossTenantReadable`).
- Mỗi lần platform admin xem/xuất dữ liệu tenant qua header đã được ghi log `platform-audit operation=act-as-tenant` (cơ chế ADR-0016, không làm thêm).

## 7. API dự kiến (mức ranh giới; `sa` chốt chi tiết)

Tất cả là `GET`, qua nginx 8080 → `lms-api` → `/internal/**`. Endpoint nhiều hơn 1 tham số query gom thành record (quy ước CLAUDE.md). Lỗi chung: 400 `INVALID_RANGE`, `RANGE_TOO_LARGE`, `INVALID_GRANULARITY`, `VALIDATION_FAILED`; 401; 403 `FORBIDDEN`/`TENANT_REQUIRED`/`MODULE_NOT_ENABLED`.

| Đường dẫn FE | Mục đích | Tham số | Trả về (trường chính) |
|---|---|---|---|
| `GET /api/dashboard/summary` | Dashboard tổng quan của tenant | không | `{generatedAt, timezone:"Asia/Ho_Chi_Minh", modules[], catalog{...}, lending{...}\|null, fines{...}\|null, sales{...}\|null, disabledModules[], forbiddenSections[], unavailableSections[]}` |
| `GET /api/dashboard/charts/borrowings` | Mượn/trả theo thời gian | `from, to, granularity` | `{from, to, granularity, points[{bucket, borrowed, returned}]}` |
| `GET /api/dashboard/charts/revenue` | Doanh thu theo ngày | `from, to` | `{from, to, totals{gross, refunds, net, orders}, points[{date, gross, refunds, net, orders}]}` |
| `GET /api/dashboard/top-titles/borrowed` | Top sách mượn | `from, to, limit` | `{items[{titleId, titleName, count}]}` |
| `GET /api/dashboard/top-titles/sold` | Top sách bán | `from, to, limit` | `{items[{titleId, titleName, quantity, revenue}]}` |
| `GET /api/reports/overdue-borrowings` | CSV mượn quá hạn | không | `text/csv` |
| `GET /api/reports/stock` | CSV tồn kho | `lowStockOnly?` | `text/csv` |
| `GET /api/reports/orders` | CSV đơn hàng | `from, to, channel?, status?` | `text/csv` |
| `GET /api/users/export` | CSV người dùng (đã có) | như hiện có | `text/csv` |
| `GET /api/platform/dashboard/overview` | Tổng quan nền tảng | `withinDays?` (mặc định 30, 1..365) | `{generatedAt, tenants{total, byStatus}, modules{LENDING, SALES}, contracts{activeCount, expiringSoon[], inGrace[]}, tenantsWithoutContract}` |

Nội bộ (BFF gọi; tên cuối do `sa` chốt): `/internal/stats/lending/**` (circulation), `/internal/stats/catalog` (catalog), `/internal/stats/sales/**` (sales), `/internal/reports/**` (circulation, sales), `/internal/platform/dashboard/overview` (identity).

Hành vi gộp:
- ✅ `summary` gọi các service song song, mỗi lời gọi có hạn thời gian ngắn (đề xuất 3 giây); một phần lỗi không làm hỏng toàn bộ (mục 6.1). Cả ba nguồn đều không dùng được: `503 SERVICE_UNAVAILABLE`.
- ✅ Số liệu trong các phần cùng một lần gọi `summary` dùng cùng mốc "hôm nay" (giờ VN) tại lúc BFF nhận yêu cầu, truyền xuống service bằng tham số (tránh lệch khi gọi qua nửa đêm).
- ℹ️ Với platform admin gửi `X-Tenant-Id`, `summary` trả thêm `tenantId` của tenant đang xem.

## 8. Dữ liệu mẫu demo (seed)

### 8.1. Nội dung tối thiểu
Một tenant demo (mã `demo`, tên "Thư viện Demo"), bật cả LENDING và SALES, có một hợp đồng HIỆU LỰC hết hạn trong khoảng 20 ngày kể từ lúc seed (để tổng quan platform có dữ liệu "sắp hết hạn"), gồm:

| Dữ liệu | Số lượng tối thiểu và yêu cầu hình dạng |
|---|---|
| Người dùng | 1 tenant admin (`demo.admin`), 1 thủ thư, 1 quản lý cửa hàng, ≥ 20 độc giả; mật khẩu demo lấy từ cấu hình (mặc định chỉ ở dev, ghi trong README) |
| Danh mục | ≥ 60 đầu sách (tên/tác giả/ISBN giả, nhiều thể loại), ≥ 150 bản sao phân bố đủ 5 trạng thái |
| Mượn trả | ≥ 120 phiếu rải trong 60 ngày gần nhất (có cao điểm cuối tuần để biểu đồ không phẳng); đúng 6 phiếu quá hạn (`dueDate` trước hôm nay, còn chưa trả); ≥ 10 phiếu `ACTIVE` chưa quá hạn; một số đặt trước |
| Phạt | đúng 4 khoản `UNPAID` (tổng tiền cố định, ghi trong README seed) và ≥ 6 khoản `PAID` |
| Bán hàng | ≥ 40 đơn rải 30 ngày gần nhất, cả POS và ONLINE, đủ các trạng thái `PAID`/`UNPAID`/`CANCELLED`, ít nhất 2 đơn hôm nay đã thanh toán; ≥ 3 phiếu trả hàng và khoản hoàn (1 `REFUNDED`, 1 `PENDING`); ≥ 2 phiếu nhập hàng đã xác nhận; ≥ 1 nhà cung cấp |
| Tồn kho | ≥ 30 mặt hàng đã đăng ký tồn; đúng 5 mặt hàng sắp hết (có cả `available = 0`); ngưỡng chung của tenant đặt rõ trong seed |
| Nội dung phụ | Menu mặc định của tenant (dùng cơ chế ADR-0009 M3), trang CMS mẫu nếu đã có cơ chế seed CMS sẵn (không bắt buộc) |

- Ngày giờ của dữ liệu tính TƯƠNG ĐỐI so với thời điểm chạy seed (theo giờ VN), nên biểu đồ luôn "tươi". Sinh dữ liệu BẰNG bộ sinh ngẫu nhiên có hạt giống cố định (cùng cấu hình thì cùng hình dạng và cùng các con số "đúng 6", "đúng 4", "đúng 5" ở trên).
- Dữ liệu mang dấu hiệu nhận biết là demo (mã tenant `demo`, tiền tố `DEMO-` ở số đơn/mã, email miền `@demo.local`) để không nhầm với dữ liệu thật và để làm khóa tự nhiên cho idempotency.

### 8.2. Ràng buộc
- **Idempotent**: chạy lần hai (hoặc nhiều lần) không tạo trùng, không lỗi, không đổi dữ liệu đã có; bổ sung phần còn thiếu nếu lần trước dừng giữa chừng. Khóa tự nhiên: mã tenant, username, ISBN, số đơn `DEMO-…`, mã phiếu/barcode có tiền tố. Kiểm bằng cách đếm bản ghi tenant demo trước/sau lần chạy thứ hai.
- **Chỉ chạy ở dev/demo, tuyệt đối không ở production**:
  - Seed KHÔNG nằm trong migration Flyway của `system-service` (migration chạy ở mọi môi trường, kể cả production).
  - Chỉ chạy khi bật rõ ràng (profile Spring `demo-seed` hoặc tham số tường minh của script `scripts/seed-demo.ps1`); không bao giờ tự chạy khi khởi động mặc định.
  - Từ chối chạy (thoát với lỗi rõ ràng, không ghi gì) khi profile `prod` đang bật hoặc khi cấu hình môi trường báo production, dù `demo-seed` có bật.
  - Không có đường HTTP nào gọi seed (không endpoint "tạo dữ liệu demo"), để production không thể kích hoạt từ ngoài.
  - Không đặt mật khẩu/khóa cố định mặc định trong mã dùng được ở production: tài khoản demo chỉ tồn tại sau khi seed chạy.
- **Tôn trọng RLS và multi-tenant (ADR-0006)**: mọi ghi vào bảng có `tenant_id` phải chạy với `TenantContext` đặt là tenant demo (cùng cơ chế `callAs` như `CreateTenantUseCase`) và bằng vai trò DB của service (`<svc>_app`, NOBYPASSRLS), không dùng superuser/BYPASSRLS để lách. Không chạm dữ liệu của tenant khác; không đổi `TENANT_EXEMPT_TABLES`; không thêm native query ngoài danh sách đã rà.
- **Đi qua nghiệp vụ khi có thể**: tenant, người dùng, role, hợp đồng, module nên tạo bằng use case sẵn có (bảo đảm chép role mẫu, quy tắc module, lịch sử hợp đồng). Dữ liệu cần ngày quá khứ (mượn trả, đơn, phạt, sổ cái tồn kho) không tạo được qua API vì thời điểm = hiện tại, nên được phép ghi trực tiếp qua tầng persistence của chính service sở hữu, nhưng phải giữ bất biến của miền (đơn đã trả có khoản thu khớp tổng, sổ cái tồn kho cộng đúng `onHand`, phạt khớp phiếu quá hạn, bản sao `LOANED` khớp phiếu `ACTIVE`).
- **Không phát sinh tác dụng phụ ngoài**: seed không gửi event Kafka lên hệ thống thật, không gửi thông báo, không gọi dịch vụ ngoài (best-effort có thể bỏ qua publisher khi chạy seed).
- **Làm mới**: seed có chế độ làm mới tùy chọn (mặc định tắt) xóa và tạo lại CHỈ dữ liệu của tenant demo để dịch ngày về hiện tại (dữ liệu cũ tính tương đối sẽ "già" sau vài tuần); lệnh làm mới không được động vào tenant khác. Xem Câu hỏi mở Q5.
- **Cách chạy**: một lệnh (script `scripts/seed-demo.ps1` hoặc tương đương; có thể kết hợp cờ của `scripts\dev-start.ps1`), in tóm tắt số bản ghi đã tạo/bỏ qua và thông tin đăng nhập demo; thứ tự: `system-service` đã migrate xong, các service đang chạy (nếu seed qua API) hoặc DB đã có schema.

## 9. Dữ liệu và migration

- ✅ Không cần bảng mới cho thống kê/báo cáo (tính trực tiếp từ bảng hiện có). Việc `sa` cần xác nhận: các truy vấn đếm/nhóm theo ngày có dùng được chỉ mục hiện có (đa số dẫn đầu bằng `tenant_id`); nếu thiếu chỉ mục cho `borrowDate`, `dueDate`, `createdAt`, `paidAt` thì thêm migration chỉ mục (mới, không sửa file đã chạy; kiểm số `n` kế tiếp ở thư mục từng target `circulation`, `sales`).
- ⚠️ Migration identity gán khóa quyền mới cho role mẫu/role hiện có (mục 6.1): bảng `role_permissions` có RLS nên phần ghi phải đặt `app.tenant_id` hoặc chạy bằng chủ bảng/superuser theo cách các migration V17/V20/V22 đã làm; chèn `permissions(code)` trước khi gán.
- ⚠️ Cột "mốc thanh toán" (`paidAt`) và "mốc trả sách" cần có sẵn trong schema để tính theo ngày: nếu chưa có cột/nguồn tương ứng thì `sa` báo lại để chốt phương án (thêm cột + backfill hay lấy từ nhật ký/sổ cái), không tự đổi định nghĩa ở mục 5.
- ℹ️ Tên đầu sách nằm ở catalog, `circulation` và `sales` chỉ giữ `titleId`: top sách và báo cáo dùng lời gọi lô tới catalog từ `lms-api` (đã có tiền lệ `catalog-lookup.md` và lời gọi lô của storefront); `titleName` là `null` khi catalog lỗi.

## 10. Yêu cầu phi chức năng

- Hiệu năng: với dữ liệu seed (mục 8.1), `GET /api/dashboard/summary` p95 dưới 1 giây và mỗi biểu đồ dưới 500 ms trên máy dev; báo cáo 10000 dòng trả dưới 5 giây và dùng streaming (không giữ toàn bộ trong bộ nhớ).
- An toàn: không SQL cộng chuỗi từ tham số; `limit`, khoảng ngày đều bị chặn trên; mọi ô CSV được chống injection; không log nội dung báo cáo.
- Không phá vỡ hợp đồng hiện có: chỉ thêm endpoint/trường mới; không đổi tên controller/method sẵn có (khóa quyền, ADR-0003).
- Tuân kiến trúc: `domain` thuần; use case ở `application` là POJO và bean tạo trong `*BeanConfiguration`; truy vấn thống kê qua port (repository/query port), không để controller chạm JPA; ArchUnit xanh (`TenantArchitectureRulesTest`, luật tầng, luật module).

## 11. Tiêu chí nghiệm thu (kiểm được bằng lệnh/hành vi/test)

Ký hiệu: `T` = tenant demo đã seed; `TOKEN_ADMIN_T` = token tenant admin `demo.admin` của T (đăng nhập `POST /api/auth/login`); `TOKEN_PA` = token platform admin.

Dashboard và định nghĩa chỉ số:
- ✅ AC1. Sau seed, `GET /api/dashboard/summary` với `TOKEN_ADMIN_T` trả 200 và: `lending.overdueBorrowings = 6`; `fines.unpaidCount = 4`; `sales.lowStockCount = 5`; `catalog.titles >= 60`; `catalog.copies.total >= 150`; `sales.ordersToday >= 2` và `sales.revenueToday > 0`; `timezone = "Asia/Ho_Chi_Minh"`.
- ✅ AC2. `lending.overdueBorrowings` bằng `total` của `GET /api/borrowings/search?overdue=true` với cùng token (so khớp hai API); phiếu có `dueDate` đúng hôm nay không tính quá hạn (test đơn vị với dữ liệu biên).
- ✅ AC3. `sales.lowStockCount` bằng `total` của `GET /api/sales/stock-items?lowStockOnly=true`; mặt hàng có ngưỡng riêng dùng ngưỡng riêng, mặt hàng không có dùng ngưỡng chung (test với cả hai loại, và `available = 0`).
- ✅ AC4. Doanh thu: đơn `UNPAID`, `CANCELLED` và khoản hoàn `PENDING` không làm đổi `net`; khoản hoàn `REFUNDED` làm `net` giảm đúng số tiền theo NGÀY HOÀN; `net = gross − refunds` (test đơn vị với tập đơn nhỏ tự dựng có đủ các trạng thái).
- ✅ AC5. Múi giờ: giao dịch lúc 23:30 giờ VN ngày D (16:30 UTC cùng ngày) thuộc ngày D, giao dịch lúc 00:10 giờ VN ngày D+1 (17:10 UTC ngày D) thuộc ngày D+1; áp cho `borrowedToday`, `ordersToday`, `revenueToday` và các bucket biểu đồ (test biên chạy với đồng hồ cố định).
- ✅ AC6. Tenant không bật SALES: `summary` trả `sales = null` và `disabledModules` chứa `SALES`; gọi thẳng endpoint sales nội bộ cho cùng token trả 403 `MODULE_NOT_ENABLED`. Tenant chỉ bật SALES: `lending = null`, `fines = null`. (WebMvc + IT.)
- ✅ AC7. Một service phía sau trả lỗi/ngắt kết nối: `summary` vẫn 200 với phần đó `null` và tên ghi ở `unavailableSections`; cả ba nguồn hỏng: 503 `SERVICE_UNAVAILABLE` (test `lms-api` với client giả).

Biểu đồ:
- ✅ AC8. `GET /api/dashboard/charts/borrowings?from=<D-29>&to=<D>&granularity=day` trả đúng 30 phần tử, thứ tự ngày tăng, ngày không có dữ liệu có `0`; `granularity=week` trả bucket là các ngày thứ Hai; `granularity=month` trả 400 `INVALID_GRANULARITY`.
- ✅ AC9. Tổng `borrowed` của biểu đồ trong khoảng bằng số bản sao có `borrowDate` trong khoảng (đối chiếu truy vấn trực tiếp qua IT); `from > to` trả 400 `INVALID_RANGE`; khoảng 400 ngày trả 400 `RANGE_TOO_LARGE`.
- ✅ AC10. `GET /api/dashboard/charts/revenue` có `totals.net` bằng tổng `points[].net`; ngày không có đơn có `0`.
- ✅ AC11. `GET /api/dashboard/top-titles/borrowed?limit=5` trả tối đa 5 phần tử sắp theo `count` giảm, đồng hạng theo `titleId` tăng; `limit=0` hoặc `limit=51` trả 400; đầu sách catalog không tìm thấy có `titleName = null`. `top-titles/sold` tương tự theo `quantity`, và số lượng đã trả hàng trong khoảng bị trừ.

Báo cáo CSV:
- ✅ AC12. `GET /api/reports/overdue-borrowings` trả `text/csv; charset=UTF-8` có BOM (3 byte đầu `EF BB BF`), header `Content-Disposition` có `attachment` và tên `.csv`, `Cache-Control: no-store`; hàng tiêu đề đúng tên cột ở 5.5; số dòng dữ liệu bằng `lending.overdueCopies` của `summary` (6 phiếu quá hạn có tổng số bản sao tương ứng); mọi dòng có `daysOverdue >= 1`.
- ✅ AC13. `GET /api/reports/stock?lowStockOnly=true` có đúng 5 dòng và cột `lowStock = true`; không có tham số thì có đủ mọi mặt hàng đã đăng ký tồn. `GET /api/reports/orders?from=&to=` chỉ chứa đơn trong khoảng (theo ngày giờ VN), lọc `channel=POS` chỉ có đơn POS; file KHÔNG có cột tên/điện thoại/địa chỉ khách (kiểm hàng tiêu đề).
- ✅ AC14. Chống CSV injection: tên đầu sách bắt đầu bằng `=cmd`, `+1`, `-1`, `@x` được xuất với dấu `'` phía trước (test đơn vị bộ ghi CSV; ô có dấu phẩy/ngoặc kép/xuống dòng được bao `"` và nhân đôi `"`).
- ✅ AC15. Vượt 10000 dòng: trả đúng 10000 dòng dữ liệu kèm `X-Report-Truncated: true` (test với trần cấu hình thấp); ít hơn thì không có header đó.
- ✅ AC16. Mỗi lần xuất ghi một dòng log INFO `report-export` có `report`, `actorId`, `tenantId`, `rows` (kiểm bằng test bắt log).
- ✅ AC17. `GET /api/users/export` vẫn hoạt động như cũ (không hồi quy) và được liệt kê cùng nhóm báo cáo trong `docs/api/dashboard-reports.md`.

Phân quyền, module, cô lập tenant:
- ✅ AC18. Ma trận WebMvc (bật `lms.security.permission.enabled=true`, `@MockitoBean PermissionKeyRegistrar`) cho từng endpoint thống kê/báo cáo: không token 401; người dùng thường (độc giả) 403 `FORBIDDEN`; role có khóa thì 200; tenant admin 200; token không có `tenantId` (không phải platform admin) 403 `TENANT_REQUIRED`; token thiếu module 403 `MODULE_NOT_ENABLED` (kể cả `admin=true`).
- ✅ AC19. Role chỉ có khóa thống kê mượn trả (không có khóa sales): `summary` trả 200 với `lending` có số liệu, `sales = null`, `forbiddenSections` chứa `sales`; role không có khóa nào gọi `summary` nhận 403 (cả ba nguồn đều 403).
- ✅ AC20. Khóa xem dashboard và khóa xuất báo cáo tách nhau: role có khóa xem nhưng không có khóa xuất gọi `/api/reports/*` nhận 403 `FORBIDDEN` (IT/WebMvc).
- ✅ AC21. Cô lập: tenant A và B cùng có dữ liệu; `summary` và các báo cáo của người dùng tenant A không chứa dữ liệu của B (IT chạy bằng vai trò app `<svc>_app` theo mẫu `AppRoleDatabase`, đặt `TenantContext`/JWT có `tenantId`).
- ✅ AC22. Platform admin: `GET /api/dashboard/summary` có `X-Tenant-Id=<T>` trả 200 với số liệu của T (bằng AC1) và `tenantId = T`; không có header trả 403 `TENANT_REQUIRED`; header sai định dạng trả 400 `INVALID_TENANT_ID`; admin tenant A gửi header tenant B vẫn chỉ nhận số của A (header bị bỏ qua). Tenant đích không bật SALES: phần `sales = null` dù platform admin bỏ qua kiểm module ở service.
- ✅ AC23. `GET /api/platform/dashboard/overview` với `TOKEN_PA` trả 200 và có `tenants.total >= 1`, `tenants.byStatus`, `modules.LENDING >= 1`, `modules.SALES >= 1` (T bật cả hai), và hợp đồng của T trong `contracts.expiringSoon` khi `withinDays=30`; với token tenant admin trả 403 `FORBIDDEN`; không token 401.
- ✅ AC24. Tenant tạm ngưng (SUSPENDED): đối chiếu hành vi hiện có của hệ thống (đăng nhập/token) và ghi lại trong tài liệu API, không thêm cơ chế riêng cho thống kê.

Seed:
- ✅ AC25. Máy dev sạch (`docker compose down -v`, `scripts\dev-start.ps1`, chạy seed bằng một lệnh): đăng nhập `demo.admin` rồi gọi `summary` cho kết quả như AC1; thời gian chạy seed in ra màn hình tóm tắt số bản ghi.
- ✅ AC26. Chạy seed LẦN HAI: kết thúc không lỗi, số bản ghi của tenant demo ở mọi bảng nghiệp vụ (users, titles, copies, borrowings, fines, orders, stock items) trước và sau BẰNG NHAU (kiểm bằng đếm qua API/SQL).
- ✅ AC27. Production: khởi chạy với profile `prod` cùng với `demo-seed` thì seed từ chối và không ghi gì (test); `rg -i "demo" services/system-service/src/main/resources/db/migration` không có dữ liệu seed demo (không có INSERT demo trong migration); không có endpoint HTTP nào kích hoạt seed (ArchUnit/`rg` trong controller).
- ✅ AC28. RLS: seed chạy bằng vai trò `<svc>_app`; sau seed, người dùng tenant khác (hoặc tenant tạo mới) gọi `summary` thấy toàn số 0, không thấy dữ liệu demo (IT, mẫu `AppRoleDatabase`).
- ✅ AC29. Bất biến của dữ liệu seed: với phiếu `ACTIVE`, số bản sao `LOANED` khớp; sổ cái tồn kho của mỗi mặt hàng cộng ra đúng `onHand`; tổng khoản hoàn `REFUNDED` không vượt tổng đơn tương ứng (script/IT kiểm sau seed).

Tài liệu và kiến trúc:
- ✅ AC30. `mvn test` toàn bộ xanh (gồm ArchUnit `TenantArchitectureRulesTest`, luật tầng, luật module); IT mới chạy xanh bằng `-Dtest=<TênIT> -DargLine=-Dapi.version=1.44`; IT hiện có không hồi quy.
- ✅ AC31. `docs/api/dashboard-reports.md` ghi đủ đường dẫn, tham số, mẫu JSON, mã lỗi, khóa quyền, header `X-Tenant-Id`, định nghĩa chỉ số (5.2) và cách chạy seed; mục lục `docs/api/README.md` được bổ sung dòng tương ứng.

## 12. Ràng buộc và việc cho `sa`

1. ⚠️ Đặt thống kê ở đâu trong từng service (context mới `stats` hay mở rộng context hiện có), tuân ArchUnit; cổng truy vấn riêng cho đọc-tổng-hợp, không làm bẩn repository của aggregate.
2. ⚠️ Truy vấn đếm/nhóm theo ngày giờ VN trên PostgreSQL (`AT TIME ZONE 'Asia/Ho_Chi_Minh'`) và dùng chỉ mục; truy vấn native phải nằm trong danh sách đã rà của `TenantArchitectureRules.noUnreviewedNativeQueries` (cập nhật danh sách có chủ đích) và vẫn chịu RLS.
3. ⚠️ Nguồn "ngày thanh toán", "ngày trả bản sao" (mục 9); chỉ số tiền cần chốt từ trường nào của đơn (`totalAmount` đã gồm VAT/ship/giảm giá).
4. ⚠️ Cách `lms-api` đọc module của tenant đích khi platform admin gửi `X-Tenant-Id` (mục 6.3) và cách truyền mốc "hôm nay" thống nhất xuống các service.
5. ⚠️ Cơ chế seed: dự kiến runner `@Profile("demo-seed")` trong từng service sở hữu dữ liệu (identity, catalog, circulation, sales) do một script điều phối; hoặc chương trình riêng. `sa` chọn và nêu cách bảo đảm chỉ chạy ở dev/demo, giữ ArchUnit (không nhúng bean seed vào tầng `domain`/`application`).
6. ⚠️ Số migration (chỉ mục, gán khóa quyền): kiểm số kế tiếp ngay trước khi tạo; không sửa file đã chạy.
7. ⚠️ Khóa quyền mới chưa gán role thì tenant admin không phải `is_admin` bị 403 (ADR-0003): cần migration gán khóa như mục 6.1, và đúng danh sách khóa theo tên controller/method thật.
8. ⚠️ Profile dev tắt kiểm khóa/module: test WebMvc phải bật lại và `@MockitoBean PermissionKeyRegistrar`.

## 13. Giả định (chọn mặc định hợp lý, chờ người dùng duyệt ở mục 14)

- ℹ️ A1. Một đề bài, nhưng thực hiện theo 3 đợt độc lập: (1) số liệu + biểu đồ + tổng quan platform, (2) báo cáo CSV, (3) seed. Seed làm cuối vì cần dữ liệu để nghiệm thu (1) và (2), nhưng có thể làm song song nếu chốt khung số liệu ở 8.1. Lý do: giảm rủi ro, mỗi đợt kiểm riêng được.
- ℹ️ A2. Chỉ CSV (không Excel). Lý do: không thêm phụ thuộc, BOM UTF-8 cho phép Excel mở đúng tiếng Việt; đủ cho trình diễn.
- ℹ️ A3. Dashboard nghiệp vụ (mượn trả, bán hàng) chỉ theo MỘT tenant; platform admin chọn tenant bằng `X-Tenant-Id` (ADR-0016). Số liệu nghiệp vụ "mọi tenant cộng dồn" bị loại. Lý do: cách đọc `ALL` (ADR-0008) chưa được kiểm chứng cho truy vấn tổng hợp (rủi ro `isRoot` Hibernate), và cộng số giữa các tenant ít có ý nghĩa nghiệp vụ; tổng quan platform dựa hoàn toàn vào dữ liệu toàn cục của identity nên chắc chắn đúng.
- ℹ️ A4. Doanh thu hiển thị mặc định là doanh thu thuần theo ngày thanh toán/ngày hoàn, gồm VAT và phí vận chuyển (tiền thực thu). Lý do: đúng với "tiền vào quầy" khi demo; có đủ `gross/refunds/net` để FE chọn hiển thị.
- ℹ️ A5. "Sắp hết tồn" dùng lại ngưỡng sẵn có (theo mặt hàng, hoặc chung của tenant), không tạo ngưỡng mới; "quá hạn" dùng lại định nghĩa của `borrowings/search`. Lý do: một định nghĩa duy nhất, số trên dashboard khớp danh sách người dùng bấm vào.
- ℹ️ A6. Số "quá hạn" đếm theo PHIẾU ở dashboard và theo BẢN SAO ở báo cáo CSV (mỗi dòng một bản sao); cả hai đều được cung cấp (`overdueBorrowings`, `overdueCopies`).
- ℹ️ A7. `readerName`, `titleName`, `isbn` trong báo cáo/biểu đồ do `lms-api` bổ sung từ identity/catalog (lời gọi lô); lỗi tra cứu thì để trống thay vì hỏng cả báo cáo. Lý do: không join chéo DB.
- ℹ️ A8. Báo cáo đơn hàng không xuất thông tin liên hệ khách. Lý do: đồng bộ với chính sách che dữ liệu cá nhân của `sales.md`; xuất thông tin liên hệ cần khóa riêng, ngoài phạm vi.
- ℹ️ A9. Không cache/bảng tổng hợp; truy vấn trực tiếp. Lý do: quy mô demo nhỏ; chỉ thêm khi đo thấy chậm (khi đó thêm cache ngắn 30 giây trong bộ nhớ từng service là hướng đầu tiên, không đổi hợp đồng API).
- ℹ️ A10. Trần báo cáo CSV 10000 dòng, cắt kèm header `X-Report-Truncated: true` (nhất quán với `users/export` đang cắt ở 5000). Lý do: chặn tải nặng, không thêm phân trang cho CSV.
- ℹ️ A11. Seed tạo đúng MỘT tenant demo (bật cả hai module) như người dùng nêu; tổng quan platform sẽ chỉ có số liệu của tenant demo và các tenant sẵn có trên máy.
- ℹ️ A12. Mật khẩu tài khoản demo cố định cho dev (ghi trong README, đổi được bằng biến môi trường), tài khoản chỉ tồn tại khi seed chạy.
- ℹ️ A13. Việc quá hạn của dữ liệu seed theo thời gian: dữ liệu sinh tương đối so với ngày chạy; để làm mới thì chạy lại chế độ làm mới (nếu được duyệt) hoặc dựng lại môi trường.
- ℹ️ A14. Giao diện FE (trang dashboard, nút xuất) là việc ngoài repo BE; BE cung cấp API và tài liệu.

## 14. Câu hỏi mở (chờ người dùng duyệt)

1. ⏳ Q1. Platform admin có cần số liệu NGHIỆP VỤ cộng dồn mọi tenant (tổng mượn, tổng doanh thu toàn hệ thống) không? Mặc định (A3): KHÔNG, chỉ chọn từng tenant qua `X-Tenant-Id` và có tổng quan nền tảng (tenant, hợp đồng, module). Nếu cần: phải đổi sang đọc `ALL` (ADR-0008) và kiểm chứng `isRoot` với truy vấn tổng hợp trước.
2. ⏳ Q2. Định nghĩa doanh thu (A4): có bao gồm phí vận chuyển và VAT, và trừ khoản hoàn theo ngày hoàn không? Mặc định: có gồm, có trừ. Nếu muốn "doanh thu trước thuế/không phí ship" hoặc "không trừ hoàn", cần nói để sửa mục 5.2.
3. ⏳ Q3. Quyền xuất báo cáo: mặc định chỉ tenant admin và role được gán khóa riêng (tách khóa xem/xuất). Có muốn thủ thư/quản lý cửa hàng mặc định được xuất ngay (gán sẵn khóa cho role mẫu) hay chỉ tenant admin cho tới khi gán tay?
4. ⏳ Q4. Có cần thêm một tenant demo thứ hai chỉ bật LENDING (để trình diễn việc ẩn phần SALES và tổng quan platform đa dạng hơn)? Mặc định: không, đúng một tenant demo.
5. ⏳ Q5. Chế độ "làm mới" seed (xóa và tạo lại chỉ dữ liệu tenant demo để dịch ngày) có cần không? Mặc định: có nhưng tắt mặc định và chỉ ở dev. Cân nhắc rủi ro: sổ cái tồn kho và phiếu bất biến có thể cản việc xóa; nếu quá phức tạp thì thay bằng "dựng lại môi trường (`docker compose down -v`) rồi seed lại".
6. ⏳ Q6. Báo cáo mượn quá hạn gồm tên độc giả và (nếu có) email/điện thoại để thủ thư liên hệ không? Mặc định: chỉ `userId` và `readerName`, không có email/điện thoại (giảm rò dữ liệu cá nhân); muốn thêm thì cần khóa quyền riêng.
7. ⏳ Q7. Nơi trình diễn là `/api/**` qua nginx 8080 với FE admin (repo FE riêng): đã có kế hoạch cho FE làm trang dashboard song song chưa? BE chỉ bảo đảm API và tài liệu; chưa có thì demo bằng bộ lệnh `curl` trong `docs/api/dashboard-reports.md`.
