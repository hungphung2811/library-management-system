# Module theo tenant (platform): `/api/platform/tenants/{id}/modules`

Nền: ADR-0009. Chỉ dành cho platform admin (claim `platformAdmin=true`, token không cần `tenantId`). Không dùng khóa `Controller/method`: quản trị viên tenant và người dùng thường nhận 403 `FORBIDDEN`, không token nhận 401. `lms-api` chuyển nguyên `/api/platform/**` xuống identity nên không có route riêng ở BFF.

Module hiện có: `LENDING` (mượn trả), `SALES` (bán hàng). Mã không phân biệt hoa thường ở đường dẫn và body, response luôn viết hoa. Tenant luôn có ít nhất một module bật.

## Endpoint mới

| Method | Đường dẫn | Kết quả |
|---|---|---|
| GET | `/api/platform/tenants/{id}/modules` | `200` mảng mọi module của hệ thống kèm trạng thái |
| PUT | `/api/platform/tenants/{id}/modules/{module}` | Bật module, idempotent: `200` |
| DELETE | `/api/platform/tenants/{id}/modules/{module}` | Tắt module: `200`; tắt module bật cuối cùng: `409` |

Phần tử response (cả ba endpoint):

```json
{ "module": "SALES", "enabled": true, "updatedAt": "2026-11-16T01:00:00Z", "updatedBy": "<uuid người thao tác>" }
```

- `GET` luôn trả đủ mọi module theo thứ tự `LENDING`, `SALES`. Module chưa từng bật: `enabled=false`, `updatedAt` và `updatedBy` là `null`.
- Bật module đang bật, tắt module đang tắt: `200`, trả trạng thái hiện tại, không đổi `updatedAt`, không phát event, không đụng role.

### Tác động phụ

- Bật: trong một transaction, ghi trạng thái và chép role mẫu của module thành role của tenant. Role đã có cùng tên thì bỏ qua (không nhân bản, không ghi đè chỉnh sửa của quản trị tenant). Phát event `TenantModuleEnabled` và bản ghi audit `TENANT_MODULE_ENABLED` (Kafka best-effort).
- Tắt: chỉ đổi `enabled=false`, giữ dòng; KHÔNG xóa role, dữ liệu nghiệp vụ hay menu. Phát `TenantModuleDisabled` và audit `TENANT_MODULE_DISABLED`.
- Mã module trong event: `tenantId`, `module`, `actorId`. Không service nào phải nghe event để hoạt động đúng.

### Mã lỗi

| HTTP | `code` | Khi |
|---|---|---|
| 400 | `UNKNOWN_MODULE` | `{module}` không thuộc `LENDING`, `SALES` |
| 404 | `TENANT_NOT_FOUND` | tenant không tồn tại |
| 409 | `TENANT_MODULE_REQUIRED` | tắt module bật cuối cùng (muốn ngưng tenant thì `POST /api/platform/tenants/{id}/suspend`) |
| 401 / 403 | | không token / không phải platform admin |

## Thay đổi hợp đồng sẵn có (chỉ thêm trường, không phá)

| Đường dẫn | Thay đổi |
|---|---|
| `POST /api/platform/tenants` | Body thêm `modules` (mảng chuỗi, tùy chọn). Bỏ trống hoặc `null`: `["LENDING"]`. Mảng rỗng: `400 INVALID_MODULES`. Mã lạ: `400 UNKNOWN_MODULE`. Mã trùng được gộp. Role mẫu được chép: mẫu lõi (quản trị) cộng mẫu của các module đã chọn. |
| `POST /api/platform/tenants`, `GET /api/platform/tenants`, `PUT /api/platform/tenants/{id}`, `POST .../suspend`, `POST .../activate` | `TenantResponse` thêm `modules`: mảng mã module đang bật. |
| `GET /api/platform/tenants/{id}` | `TenantDetailResponse` thêm `modules`. |
| `GET /api/me/tenants` và `availableTenants[]` trong response đăng nhập, làm mới, đổi tenant, đăng nhập ngoài | `TenantSummary` thêm `modules`: module đang bật của từng tenant, CHỈ để hiển thị bộ chọn tenant. Quyết định chặn dựa vào claim token. |

## Claim `modules` trong access token

- Mảng chuỗi, ví dụ `["LENDING","SALES"]`, có ở mọi access token có `tenantId` (đăng nhập, làm mới, đổi tenant, đăng nhập ngoài), kể cả token tài khoản phải đổi mật khẩu.
- Token platform admin không có `tenantId` thì không có claim `modules`. Platform admin đã `switch-tenant` thì nhận `modules` của tenant đó.
- Thời điểm hiệu lực giống quyền: bật module có hiệu lực ở lần đăng nhập, làm mới, đổi tenant kế tiếp; tắt module vẫn dùng được tới khi access token hết hạn (tối đa 15 phút). Cần dừng ngay thì suspend tenant.
- Token phát trước khi triển khai (thiếu hẳn claim) được các service coi là `["LENDING"]` trong thời gian chuyển tiếp (cờ `lms.security.permission.legacy-modules-claim-fallback`, mặc định bật).

## Lưu ý cho FE

- Khóa quyền `Controller/method` của các endpoint hiện có KHÔNG đổi; endpoint mới này không có khóa động.
- Màn hình quản lý tenant của platform admin: hiển thị `modules` ở danh sách/chi tiết, công tắc bật/tắt gọi PUT/DELETE, xử lý 409 `TENANT_MODULE_REQUIRED` (không cho tắt module cuối).
- Migration `V16` backfill `LENDING` cho mọi tenant hiện có nên tenant cũ giữ nguyên hành vi.

## Nguyên nhân tạm ngưng tenant (ADR-0012)

`TenantResponse` và `TenantDetailResponse` (các endpoint `/api/platform/tenants...`) thêm `suspensionCause`: `null` khi `ACTIVE`; khi `SUSPENDED` là `MANUAL` (platform admin suspend tay), `CONTRACT_EXPIRED` (job hết hạn hợp đồng) hoặc `CONTRACT_CANCELLED` (hủy hợp đồng). Chỉ thêm trường, `POST .../suspend` và `.../activate` giữ nguyên đường dẫn, không body. Suspend tay luôn ghi `MANUAL`; activate tay xóa nguyên nhân. Chỉ `CONTRACT_EXPIRED` tự kích hoạt lại khi hợp đồng mới được áp dụng. Bật/tắt module tay được ghi vào lịch sử hợp đồng như "ngoại lệ ngoài hợp đồng" (xem `docs/api/platform-contracts.md`).
