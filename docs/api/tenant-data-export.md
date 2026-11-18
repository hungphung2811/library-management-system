# Xuất trọn dữ liệu tenant (data portability)

Nền: ADR-0031 (D1 đến D3, Phụ lục A). Gói F2 (điều phối ở identity-service + lms-api). Exporter của từng service ghi ở
`tenant-data-exporters.md` và `tenant-data-exporters-sales.md` (nội bộ, không dành cho FE).

> ℹ️ Đây là **hợp đồng mới cho FE** (`/api/tenant-data/exports/**`). FE cần trang "Dữ liệu của tenant": yêu cầu xuất, tiến độ,
> tải, lịch sử. Gói chỉ sống 72 giờ.

## Điều kiện bật

- identity cần biến môi trường `TENANT_DATA_ENCRYPTION_KEY` = base64 của đúng 32 byte (ví dụ `openssl rand -base64 32`). Thiếu
  khóa thì tính năng tắt: `POST` trả `503 EXPORT_NOT_CONFIGURED`. Khóa sai định dạng thì identity không khởi động.
- Thư mục lưu gói: `TENANT_DATA_EXPORT_DIR` (mặc định `./data/tenant-exports`; production nên mount volume riêng, chỉ đúng khi
  identity chạy một instance).
- Địa chỉ exporter của catalog/circulation/support/sales: `TENANT_DATA_<TÊN>_URL` (mặc định lấy `CATALOG_SERVICE_URL`,
  `CIRCULATION_SERVICE_URL`, `SUPPORT_SERVICE_URL`, `SALES_SERVICE_URL`). Nguồn không khai thì bị bỏ qua và ghi vào manifest
  (`notConfigured`).

## Quyền

| Endpoint | Khóa động (ADR-0003) | Ai có |
|---|---|---|
| `POST /api/tenant-data/exports` | `TenantDataExportController/request` | Chỉ role `is_admin` của tenant (khóa không gán mẫu vai trò nào, seed ở identity V40); tenant ở chế độ `READ_ONLY` vẫn gọi được (`@AllowedWhenReadOnly`) |
| `GET /api/tenant-data/exports` | `TenantDataExportController/list` | như trên |
| `GET /api/tenant-data/exports/{id}` | `TenantDataExportController/get` | như trên |
| `GET /api/tenant-data/exports/{id}/download` | `TenantDataExportController/download` | như trên |
| `/api/platform/tenants/{tenantId}/data-exports/**` | không dùng khóa (`@PlatformAdminOnly`) | Platform admin, xuất thay |

Tenant luôn lấy từ token; không có tham số tenant. `id` thuộc tenant khác và `id` không tồn tại cùng trả `404 EXPORT_NOT_FOUND` với
cùng thân (chỉ khác dấu thời gian), FE không phân biệt được.

## Đối tượng `ExportView`

```json
{
  "id": "uuid", "status": "PENDING|RUNNING|READY|FAILED|EXPIRED",
  "requestedBy": "uuid", "requestedByPlatform": false, "reason": "string|null",
  "createdAt": "ISO-8601", "startedAt": null, "finishedAt": null, "expiresAt": null,
  "partsTotal": 5, "partsDone": 2, "sizeBytes": 0, "sha256": "hex|null",
  "errorCode": "null|SOURCE_FAILED|SIZE_LIMIT|STORAGE_ERROR|STALE|INTERNAL",
  "downloadCount": 0, "lastDownloadedAt": null,
  "parts": [{ "service": "identity", "status": "PENDING|DONE|FAILED", "attempts": 1, "rows": 1234,
              "errorCode": "null|SOURCE_HTTP_503|SOURCE_UNREACHABLE|SOURCE_TRUNCATED|SOURCE_INVALID|SOURCE_ERROR",
              "finishedAt": null }]
}
```

- Tiến độ hiển thị = `partsDone / partsTotal`. `parts` cho biết từng nguồn (identity, catalog, circulation, support, sales).
- `sizeBytes`, `sha256` là của file ZIP tải về (chỉ có khi `READY`). `expiresAt` có khi `READY`.
- `errorCode`/`parts[].errorCode` chỉ là mã chung; thông điệp lỗi nội bộ không bao giờ trả ra. Gói `FAILED` thì tenant được yêu cầu lại
  ngay (không tính vào giới hạn 24 giờ).
- Không có đường dẫn lưu trữ trong phản hồi.

## Endpoint

### `POST /api/tenant-data/exports`

Body tùy chọn: `{"reason": "string tối đa 500 ký tự"}` (cũng chấp nhận không body hoặc `{}`).

| Mã | Ý nghĩa |
|---|---|
| `202` | `ExportView` với `status = PENDING`. Công việc chạy nền (worker lấy trong tối đa ~10 giây) |
| `400 VALIDATION_FAILED` | `reason` quá 500 ký tự hoặc JSON hỏng |
| `409 EXPORT_IN_PROGRESS` | Tenant đã có một yêu cầu `PENDING`/`RUNNING` (mỗi tenant tối đa một) |
| `429 EXPORT_RATE_LIMITED` | Đã có một lần xuất thành công trong 24 giờ gần nhất (tính cả gói đã hết hạn) |
| `503 EXPORT_NOT_CONFIGURED` | Chưa cấu hình khóa mã hóa |
| `403` | Thiếu khóa quyền, hoặc `TENANT_REQUIRED` |

### `GET /api/tenant-data/exports`

Danh sách tối đa 20 yêu cầu gần nhất của tenant, mới nhất trước, kèm `parts`. Phản hồi là mảng `ExportView`.

### `GET /api/tenant-data/exports/{id}`

`ExportView` (kèm `parts`). FE nên thăm dò mỗi 3 đến 5 giây khi `PENDING`/`RUNNING`. `404 EXPORT_NOT_FOUND` như trên.

### `GET /api/tenant-data/exports/{id}/download`

Tải gói ZIP đã giải mã, **phát theo luồng** (identity giải mã từng khung 64 KB; lms-api chuyển tiếp từng khối, không nạp cả gói vào
bộ nhớ). Gọi bằng `fetch` kèm `Authorization` rồi lưu thành blob/stream; không dùng thẻ `<a href>` vì cần Bearer token.

Phản hồi `200`:

| Header | Giá trị |
|---|---|
| `Content-Type` | `application/zip` |
| `Content-Length` | kích thước ZIP (= `sizeBytes`) |
| `Content-Disposition` | `attachment; filename="tenant-data-<exportId>.zip"` |
| `Cache-Control` | `no-store` |
| `X-Content-Type-Options` | `nosniff` |

Lỗi: `404 EXPORT_NOT_FOUND`; `409 EXPORT_NOT_READY` (chưa xong hoặc `FAILED`); `410 EXPORT_EXPIRED` (quá hạn 72 giờ hoặc đã dọn);
`503 EXPORT_NOT_CONFIGURED`. Tải nhiều lần được trong hạn (đứt mạng thì tải lại); mỗi lần ghi `downloadCount`, `lastDownloadedAt` và audit
(`TENANT_DATA_EXPORT_DOWNLOADED`, ai và lúc nào). ⚠️ Read-timeout của lms-api (mặc định 5 giây) áp cho từng lần đọc socket, không
phải cả lần tải; gói lớn tải được miễn dữ liệu đến đều. ⚠️ nginx cần `proxy_buffering off` và `proxy_read_timeout` đủ lớn cho
`/api/tenant-data/exports/*/download` nếu gói lớn (chưa kiểm cấu hình nginx trong repo, xem mục chưa kiểm chứng).

## Platform admin xuất thay

Đường dẫn identity (qua `/api/platform/**` của lms-api, chuyển nguyên trừ `download` được stream riêng):

| Method, đường dẫn | Ghi chú |
|---|---|
| `POST /api/platform/tenants/{tenantId}/data-exports` | Body như trên. `404 TENANT_NOT_FOUND` nếu tenant không tồn tại. Bỏ qua giới hạn 24 giờ; vẫn mỗi tenant một yêu cầu đang chạy. Audit + log `platform-audit` |
| `GET /api/platform/tenants/{tenantId}/data-exports` | Danh sách |
| `GET /api/platform/tenants/{tenantId}/data-exports/{id}` | Chi tiết |
| `GET /api/platform/tenants/{tenantId}/data-exports/{id}/download` | Tải ZIP (stream) |

Không cần header `X-Tenant-Id` (tenant nằm trong đường dẫn). Gói do platform admin yêu cầu có `requestedByPlatform = true`.

## Nội dung gói ZIP

```
manifest.json            schemaVersion, exportId, tenantId, tenantCode, requestedBy, requestedAt, generatedAt,
                         sections[{service, schemaVersion, files[{path, table, rows, bytes, sha256, columns}]}],
                         excluded[{service, table, reason}], notConfigured[], appVersion
manifest.json.sig        JWS compact tách rời (RFC 7515 phụ lục F), RS256, header có kid (khóa ký JWT hiện hành của identity)
README.txt               hướng dẫn đọc (tiếng Việt)
identity/*.csv           users, tenant_memberships, roles, role_permissions, user_roles, departments, reader_profiles,
                         applications, tenant_contracts
catalog/*.csv, circulation/*.csv, support/*.csv, sales/*.csv   do exporter từng service (xem tenant-data-exporters*.md)
```

- CSV: UTF-8 có BOM, RFC 4180, `\r\n`, dòng đầu là tên cột, thời điểm ISO-8601 UTC. Ô văn bản bắt đầu bằng `= + - @` được thêm dấu nháy đơn phía
  trước (chống công thức bảng tính); bên nhập lại phải bỏ tiền tố này.
- Kiểm toàn vẹn: SHA-256 từng file so với `manifest.json`; kiểm chữ ký bằng JWKS công khai (`GET /.well-known/jwks.json` của identity) theo `kid`:
  payload của JWS tách rời chính là các byte của `manifest.json`. ⚠️ Khóa đã xoay vòng và gỡ khỏi JWKS thì không kiểm lại được gói cũ.
- KHÔNG có trong gói (có chủ đích, ghi ở `excluded`): mật khẩu băm, refresh token, token đặt lại mật khẩu, mã xác minh email, vé/liên kết đăng nhập
  ngoài, secret của credential Application, site key, ghi chú nội bộ của hợp đồng, bảng toàn cục của nền tảng, và phần các service khác loại.
- Gói chứa dữ liệu cá nhân của độc giả/khách của chính tenant; vì vậy chỉ quản trị tenant tải được và mọi lần yêu cầu/tải đều ghi audit.
- Không đặt mật khẩu ZIP (bảo vệ bằng HTTPS, xác thực và hạn dùng). File lưu trên đĩa được mã hóa AES-256-GCM theo khung.

## Giới hạn và vòng đời

| | |
|---|---|
| Mỗi tenant | một yêu cầu `PENDING`/`RUNNING`; một lần thành công/24 giờ (platform admin được bỏ qua) |
| Kích thước | tối đa 2 GB dữ liệu thô (tổng byte CSV) trước khi nén; vượt thì `FAILED` `SIZE_LIMIT` |
| Thử lại | mỗi nguồn thử tối đa 3 lần; hết lượt thì phần đó `FAILED`, các nguồn còn lại vẫn được thử để báo đủ, yêu cầu `FAILED` với `errorCode = SOURCE_FAILED` |
| Hạn dùng | `READY` sống 72 giờ rồi job xóa file và chuyển `EXPIRED` |
| Kẹt | `RUNNING` quá 2 giờ thì `FAILED` `STALE`; sau khi identity khởi động lại, `RUNNING` sót lại được đưa về `PENDING` và chạy lại |

## Sự kiện và audit

- Khi `READY`, identity phát `TenantDataExportReady` lên topic `identity-events`: `{eventId, tenantId, exportId, requestedBy, recipientUserIds, expiresAt, occurredAt}`
  (chỉ id, không email; `requestedBy` rỗng khi platform yêu cầu). Gói C (support) sẽ gửi email; chưa có consumer ở repo này tại thời điểm F2.
- Audit qua `PlatformOperationPerformed`: `TENANT_DATA_EXPORT_REQUESTED`, `TENANT_DATA_EXPORT_DOWNLOADED`, `TENANT_DATA_EXPORT_FAILED`, `TENANT_DATA_EXPORT_EXPIRED`.

## Chưa kiểm chứng

- ⚠️ Chưa chạy end-to-end với exporter thật của catalog/circulation/support/sales (chỉ test điều phối với exporter giả và HTTP giả).
- ⚠️ nginx (`infrastructure/nginx/nginx.conf`) chưa được rà cho tải 2 GB: cần `proxy_buffering off` (hoặc `proxy_max_temp_file_size 0`) và `proxy_read_timeout` đủ lớn cho đường dẫn tải.
- ⚠️ Mỗi nguồn exporter nên cấu hình timeout đọc 120 giây giữa hai lần nhận dữ liệu (đã đặt ở identity), exporter nên flush đều.
