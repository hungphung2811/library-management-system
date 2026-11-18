# Exporter dữ liệu tenant (catalog, circulation, support): `GET /internal/tenant-data/export`

Nền: ADR-0031 (D1, D2, Phụ lục A). Gói F1 (phần catalog, circulation, support). Exporter của sales-service ghi ở
`tenant-data-exporters-sales.md`; phần của identity do điều phối (F2) tự xuất trong tiến trình.

> ℹ️ Đây là hợp đồng **nội bộ** giữa điều phối (identity `tenantdata`, gói F2) và exporter, không phải API cho FE và không
> đi qua `lms-api`. Không đổi hợp đồng `/api/**`, không cần đồng bộ FE.

## Endpoint (giống nhau ở catalog 8082, circulation 8083, support 8084)

| | |
|---|---|
| Đường dẫn | `GET /internal/tenant-data/export` |
| Chú thích | `@TenantDataAccess` trên `TenantDataExportController/export` (package `<root>.tenantdata.infrastructure.web`) |
| Xác thực | `Authorization: Bearer <token svc=tenant-data>` do identity ký (`aud = lms-tenant-data`, có `tenantId`, TTL 10 phút) |
| Tham số | Không có. Tenant lấy **duy nhất** từ claim `tenantId`; `?tenantId=` hoặc `X-Tenant-Id` bị bỏ qua |
| Thành công | `200`, `Content-Type: application/zip`, `Cache-Control: no-store`, ghi theo luồng (không `Content-Length`) |
| Từ chối | Mọi token khác (người dùng kể cả admin, platform admin kể cả `X-Tenant-Id`, integration, storefront, token tenant-data sai `aud`) `403 FORBIDDEN`; token tenant-data thiếu `tenantId` `403 TENANT_REQUIRED`; không token `401` |
| Khóa quyền | Không có: nhánh tenant-data không đăng ký/kiểm khóa `Controller/method`, không kiểm module |
| Lỗi giữa chừng | ZIP cụt, **không** có `_meta.json` (điều phối coi là nguồn lỗi, `SOURCE_TRUNCATED`) |

## Cấu trúc ZIP (theo Phụ lục A)

Entry ở gốc, theo thứ tự bảng bên dưới, `_meta.json` **cuối cùng**.

- `<bảng>.csv`: UTF-8 **có BOM**, dấu phẩy, RFC 4180 (bao nháy kép khi có dấu phẩy, nháy kép, xuống dòng; nháy kép nhân
  đôi), xuống dòng `\r\n`, dòng đầu là tên cột, null là ô rỗng, boolean `true/false`, số thực không dùng ký hiệu khoa học
  (`125000.50`), thời điểm ISO-8601 UTC (`2026-11-19T10:15:30Z`), ngày ISO, mảng và JSONB ghi thành chuỗi JSON
  (`["thieu nhi","x"]`). Bảng rỗng vẫn có file (chỉ dòng tiêu đề).
- Cột `tenant_id` **không** xuất (mỗi gói chỉ của một tenant; truy vấn vẫn lọc `tenant_id = ?` tường minh và RLS lọc thêm).
- Chống công thức bảng tính (cùng quy ước với exporter sales): ô **văn bản** bắt đầu bằng `=`, `+`, `-`, `@`, TAB hoặc CR được
  thêm dấu nháy đơn `'` phía trước; số/ngày/boolean/thời điểm không bị đụng (số âm giữ `-5`). Bên nhập lại dữ liệu phải
  bỏ tiền tố này. ADR-0031 chưa nêu quy tắc này; nếu muốn xuất nguyên văn thì đổi ở một chỗ
  (`JdbcTenantTableReader.neutralizeFormula` trong `tenant-common`).
- `_meta.json` (UTF-8 không BOM): `{"service","schemaVersion":1,"tables":[{"name","file","rows","columns":[...]}],"excluded":[{"table","reason"}]}`.
  `rows` là số dòng dữ liệu (không tính dòng tiêu đề); `excluded.table` là tên bảng bị loại hoặc `bảng.cột` bị loại.
- Đọc theo lô keyset (mặc định 500 dòng, đổi bằng `lms.tenant-data.export.batch-size`): mỗi lô một câu
  `SELECT <cột> FROM <bảng> WHERE tenant_id = ? AND <khóa> > ? ORDER BY <khóa> LIMIT ?`, nên số câu SQL = số dòng / lô, không N+1,
  RAM giới hạn bởi kích thước lô. Toàn bộ chạy trong **một transaction chỉ đọc REPEATABLE READ** nên các bảng của một service
  nhất quán với nhau (giữ một kết nối trong suốt thời gian xuất).
- `borrowing_items` (circulation) không có khóa duy nhất: đọc theo `(borrowing_id, book_copy_id)`, mỗi nhóm dòng trùng khóa
  được đọc nguyên vẹn trong cùng một truy vấn (không mất/lặp dòng).

## Bảng được xuất

Danh sách cố định trong mã (`*TenantDataTables` ở `<root>.tenantdata.application`), khớp migration; IT so với schema thật:
mọi bảng có `tenant_id` phải được xuất hoặc nằm trong `excluded`, và cột xuất phải khớp cột thật của bảng.

### catalog (10 bảng, khóa `id` trừ khi ghi khác; không có cột nhạy cảm, không bị loại bảng nào)

| Bảng | Ghi chú |
|---|---|
| `categories`, `authors`, `publishers`, `shelf_locations`, `series` | danh mục tham chiếu |
| `works` | tác phẩm (works/series/edition) |
| `work_authors` | khóa `(work_id, author_id)` |
| `titles` | đầu sách/ấn bản; `tags` (mảng) ghi dạng JSON |
| `title_authors` | khóa `(title_id, author_id)` |
| `book_copies` | bản sao: mã vạch, trạng thái, tình trạng, giá mua |

### circulation (11 bảng; bị loại: `known_tenants`)

`borrowings`, `borrowing_items` (khóa không duy nhất, xem trên), `reservations`, `fines`, `fine_adjustment_requests`,
`fine_refunds`, `circulation_policies`, `compensation_policies`, `damage_levels`, `borrow_rules` (khóa `reader_type`),
`loan_reminders`. `user_id`/`reader_id` là id người dùng (tên/email nằm ở phần identity của gói).
Loại có chủ đích: `known_tenants` (danh bạ tenant nội bộ của dịch vụ).

### support (6 bảng, 1 PostgreSQL AUDIT_DB; bị loại: `notification_tenants`, bản thô Mongo)

| Bảng | Ghi chú |
|---|---|
| `notifications` | thông báo trong ứng dụng |
| `notification_deliveries` | nhật ký gửi (email/...): **không** xuất `recipient_address` (địa chỉ đầy đủ) và `recipient_key` (băm địa chỉ, khóa khử trùng nội bộ); chỉ `recipient_address_masked`. `params` là tham số đã qua danh sách cho phép của từng loại (mã đơn, số tiền, ngày), không có token/liên kết |
| `notification_templates`, `notification_channel_settings` (khóa `(notification_type, channel)`), `notification_preferences` (khóa `(user_id, category, channel)`) | cấu hình thông báo |
| `audit_logs` | nhật ký audit; bảng lớn nên chia thành `audit_logs_001.csv`, `audit_logs_002.csv`, ... mỗi 500.000 dòng (mỗi file có tiêu đề; mỗi file là một mục riêng trong `_meta.json`, `name` = `audit_logs_001`...; tenant rỗng vẫn có `audit_logs_001.csv` chỉ tiêu đề) |

Loại có chủ đích: `notification_tenants` (danh bạ tenant nội bộ của bộ gửi), `mongo.raw_events` (bản thô sự kiện ở
MongoDB LOG_DB; bản chuẩn đã nằm trong `audit_logs`).

## Mã nguồn dùng chung

`shared-libraries/tenant-common`, package `lms.tenant.export`: `ExportTable` (khai báo bảng, từ chối cột có tên nhạy cảm như
password/token/secret/hash/signature và `tenant_id`), `JdbcTenantTableReader` (keyset JDBC, định dạng ô), `CsvTableWriter`,
`TenantDataExporter` (ghi ZIP + `_meta.json`, `TenantContext.callAs(tenantId)`). Mỗi service chỉ khai danh sách bảng, bean
`TenantDataExporter` trong `TenantDataBeanConfiguration` và `TenantDataExportController`.

## Kiểm chứng

- Đơn vị `tenant-common` (`TenantDataExporterTest`, `ExportTableTest`): BOM, escape (dấu phẩy, nháy kép, xuống dòng, tiếng Việt), bảng rỗng, chia file, `_meta.json` cuối, lỗi giữa chừng không có `_meta.json`, vô hiệu hóa công thức.
- Mỗi service: WebMvc phân quyền (`TenantDataExportControllerTest`: token tenant-data qua; admin, platform admin, integration, storefront, người dùng thường, sai `aud` 403; thiếu tenant 403; không token 401; tenant lấy từ claim, bỏ qua query/header), ArchUnit `TenantDataArchitectureRulesTest`, bảng/cột (`*TenantDataTablesTest`), `IntegrationTokenDenyMatrixTest` vẫn xanh.
- IT Postgres thật với vai trò `<svc>_app` (RLS): `CatalogTenantDataExportIT`, `CirculationTenantDataExportIT`, `SupportTenantDataExportIT` (hai tenant, lô nhỏ buộc phân trang, số dòng khớp `COUNT(*)`, so danh sách bảng/cột với schema thật; support còn kiểm địa chỉ đầy đủ/khóa khử trùng không xuất hiện ở file nào).

## Chưa kiểm chứng

- ⚠️ Chưa chạy qua điều phối identity (F2) và chưa chạy end-to-end bằng token thật do identity ký.
- ⚠️ Chưa đo hiệu năng với dữ liệu lớn (audit hàng triệu dòng); truy vấn keyset `tenant_id = ? AND id > ? ORDER BY id` dựa trên chỉ mục khóa chính/`(tenant_id, ...)` hiện có, chưa thêm chỉ mục riêng.
- ⚠️ Transaction REPEATABLE READ giữ một kết nối suốt thời gian xuất của service đó; chưa đo ảnh hưởng lên pool khi tenant lớn.
