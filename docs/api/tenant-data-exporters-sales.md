# Exporter dữ liệu tenant của sales-service: `GET /internal/tenant-data/export`

Nền: ADR-0031 (D1, D2, Phụ lục A). Phần này của **sales-service** (gói F1); các service khác ghi ở `tenant-data-exporters.md` (F1a), gộp sau.

> ℹ️ Đây là hợp đồng **nội bộ** giữa điều phối (identity `tenantdata`, gói F2) và exporter, không phải API cho FE và không đi qua `lms-api`. Không đổi hợp đồng `/api/**` nên FE không phải đồng bộ.

## Endpoint

| | |
|---|---|
| Đường dẫn | `GET /internal/tenant-data/export` (sales-service, cổng 8087) |
| Chú thích | `@TenantDataAccess` (`TenantDataExportController`); controller vẫn mang `@RequiresModule(SALES)` chỉ để thỏa luật ArchUnit, token tenant-data bỏ qua kiểm module |
| Xác thực | `Authorization: Bearer <token svc=tenant-data>` của identity (`aud = lms-tenant-data`, có `tenantId`, TTL 10 phút) |
| Tham số | Không có. Tenant lấy **duy nhất** từ claim `tenantId` của token; `tenantId` ở query/header bị bỏ qua |
| Thành công | `200`, `Content-Type: application/zip`, `Cache-Control: no-store`, ghi theo luồng (không `Content-Length`) |
| Từ chối | Mọi token khác (người dùng kể cả admin, platform admin kể cả `X-Tenant-Id`, integration, storefront) `403 FORBIDDEN`; không token `401`; token tenant-data thiếu `tenantId` `403 TENANT_REQUIRED`; sai/thiếu `aud` `403 FORBIDDEN`. Token tenant-data ở endpoint khác của sales `403 FORBIDDEN` |
| Khóa quyền | Không có (không đăng ký khóa `Controller/method`) |

Lỗi giữa chừng khi đã ghi thân: ZIP cụt, **không** có `_meta.json` (điều phối coi là `SOURCE_TRUNCATED`).

## Cấu trúc ZIP

Entry ở gốc, theo thứ tự bảng bên dưới, `_meta.json` cuối cùng. Định dạng CSV theo Phụ lục A: UTF-8 có BOM, dấu phẩy, RFC 4180, `\r\n`, dòng đầu là tên cột, thời điểm ISO-8601 UTC (`2026-11-19T10:15:30.123456Z`), ngày `yyyy-MM-dd`, boolean `true/false`, null là ô rỗng, cột JSON ghi chuỗi JSON. Bảng rỗng vẫn có file (chỉ dòng tiêu đề).

- Chống công thức bảng tính: ô **văn bản** (tên, ghi chú, địa chỉ, số điện thoại, mã...) bắt đầu bằng `=`, `+`, `-`, `@`, TAB hoặc CR được thêm một dấu nháy đơn `'` phía trước (cùng `CsvWriter` của báo cáo CSV). Ô số, ngày, boolean, thời điểm không bị đụng (số âm giữ `-5`). Hệ quả: số điện thoại dạng `+84...` ra thành `'+84...`; bên nhập lại dữ liệu cần bỏ tiền tố này.
- Đọc theo lô keyset (mặc định 1000 dòng, `lms.tenant-data.export.batch-size`), mỗi lô một câu SELECT có `WHERE tenant_id = ? AND <khóa> > ? ORDER BY <khóa> LIMIT ?`, nên số câu SQL phụ thuộc số lô, không phụ thuộc số dòng; RLS (`app.tenant_id`) là lớp chặn thứ hai.
- Không có ảnh chụp nhất quán xuyên bảng: mỗi lô là một lần đọc riêng (đủ cho tenant đang ở chế độ chỉ đọc/sau hợp đồng; xuất khi tenant còn ghi dữ liệu có thể lệch nhẹ giữa các bảng).

`_meta.json` (UTF-8 không BOM): `{"service":"sales","schemaVersion":1,"tables":[{"name","file","rows","columns":[...]}],"excluded":[{"table","reason"}]}`. `table` trong `excluded` là `bảng` (loại cả bảng) hoặc `bảng.cột` (loại một cột).

## Bảng được xuất (27 bảng)

Khóa phân trang ở cột `id` trừ khi ghi khác. Tất cả có cột `tenant_id` (xuất kèm).

| Nhóm | Bảng (`<bảng>.csv`) |
|---|---|
| Cấu hình | `sales_settings` (khóa `tenant_id`), `order_abuse_policies` (khóa `tenant_id`), `invoice_settings` (khóa `tenant_id`), `cashier_shift_settings` (khóa `tenant_id`), `sales_counters` (khóa `name`) |
| Mặt hàng, tồn kho, nhập hàng | `stock_items`, `stock_ledger`, `suppliers`, `goods_receipts`, `goods_receipt_lines` |
| Đơn, thanh toán, hoàn, trả | `sales_orders`, `sales_order_lines`, `payment_records`, `payment_intents`, `refunds`, `sales_returns`, `sales_return_lines` |
| Hóa đơn VAT | `invoices`, `invoice_lines`, `invoice_requests` |
| Ca thu ngân | `cashier_shifts`, `cashier_shift_events` |
| Cửa hàng trực tuyến | `shop_contact_requests`, `storefront_banners` |
| Nhật ký | `sales_audit_log` (không chứa PII) |

Danh sách cột cố định trong code (`lms.sales.tenantdata.domain.SalesExportCatalog`), không dump schema thô: cột mới của DB không tự ra gói xuất. IT `TenantDataExportIT` đối chiếu `information_schema` để mọi cột/bảng của sales_db phải thuộc danh sách xuất hoặc loại.

## Dữ liệu cá nhân và chứng từ

Tenant là chủ dữ liệu (ADR-0031 D2) nên **xuất đầy đủ**, đúng như đang lưu: tên/điện thoại khách lẻ (`walk_in_*`), người nhận (`recipient_*`), ghi chú khách/nhân viên, thông tin người mua trên hóa đơn và yêu cầu hóa đơn (tên, đơn vị, mã số thuế, địa chỉ, email), nội dung và liên hệ của `shop_contact_requests`. Hóa đơn và dòng hóa đơn xuất toàn bộ chứng từ (kể cả trạng thái `CANCELLED`, số hóa đơn, bằng chữ, thông tin tích hợp bên ngoài). Dòng đã bị ẩn danh theo ADR-0010 2.8 xuất đúng giá trị đã ẩn danh (rỗng) cùng `pii_anonymized_at`; không có bản gốc để xuất.

## Bị loại có chủ đích (`excluded` trong `_meta.json`)

| Mục | Lý do |
|---|---|
| `sales_tenants` (bảng) | Danh bạ tenant toàn cục của job ẩn danh, không RLS, không phải dữ liệu của tenant |
| `payment_intents.qr_payload` | Nội dung mã QR thanh toán, dữ liệu của cổng thanh toán (ADR-0031 D2: `qr_payload`) |
| `sales_orders.request_hash` | Băm kỹ thuật chống đặt trùng, không phải dữ liệu nghiệp vụ |

Không có bí mật webhook/khóa cổng thanh toán trong DB sales (cấu hình qua biến môi trường), nên không cột nào khác bị loại. `payment_intents.provider_txn_id` và `transaction_ref` (mã giao dịch phục vụ đối soát) được xuất.

## Cấu hình

| Thuộc tính | Mặc định | Ý nghĩa |
|---|---|---|
| `lms.tenant-data.export.batch-size` | `1000` | Số dòng mỗi lô đọc (Phụ lục A: 500 đến 1000) |

Nguồn ở identity: `lms.tenant-data.export.sources.sales.base-url` = `http://sales-service:8087` (local `http://localhost:8087`).

## Kiểm thử

`ExportSalesTenantDataUseCaseTest` (phân trang con trỏ, BOM, escape, công thức, kiểu ô, `_meta.json`), `SalesExportCatalogTest`, `TenantDataArchitectureTest` (luật `TenantDataArchitectureRules`), `TenantDataExportWebTest` (ma trận token), `TenantDataExportIT` (Postgres thật, vai trò `sales_app`, hai tenant: `-Dtest=TenantDataExportIT -DargLine=-Dapi.version=1.44`).
