# Hợp đồng thuê dịch vụ của tenant (platform): `/api/platform/...contracts`

Nền: ADR-0012 (`docs/decisions/0012-hop-dong-thue-dich-vu-theo-tenant.md`), đề bài `docs/requirements/2026-11-17-hop-dong-thue-dich-vu.md`. Chỉ dành cho **platform admin** (claim `platformAdmin=true`, token không cần `tenantId`); mọi platform admin xem/ghi được giá, thanh toán, ghi chú nội bộ. Không dùng khóa `Controller/method`: quản trị viên tenant và người dùng thường nhận `403 FORBIDDEN`, không token nhận `401`. `lms-api` chuyển nguyên `/api/platform/**` xuống identity nên không có route riêng ở BFF. Không có `DELETE`: hủy dùng `POST .../cancel`; thanh toán không có sửa/xóa.

## Khái niệm

- Hợp đồng gắn một tenant, có các module (`LENDING`, `SALES`), thời hạn, **tổng tiền** (VND, số nguyên, không giá riêng từng module), chu kỳ thanh toán tham khảo, ghi chú nội bộ. Hợp đồng là nguồn quyết định bật/tắt module ở các **điểm chuyển** (tới ngày bắt đầu, hết ân hạn, hủy); `tenant_modules` vẫn là trạng thái chạy (claim `modules` của token).
- Trạng thái lưu: `DRAFT` (nháp) → `ACTIVE` (hiệu lực) → `EXPIRED` (hết hạn); `DRAFT`/`ACTIVE` → `CANCELLED` (đã hủy). Trường `phase` chỉ có khi `ACTIVE`: `UPCOMING` (chưa tới ngày bắt đầu), `IN_EFFECT`, `IN_GRACE` (đã quá ngày kết thúc, còn trong ân hạn, module vẫn bật; `inGrace=true`).
- Ngày (`startDate`, `endDate`, `graceEndsDate`, `paidOn`) là `YYYY-MM-DD` theo giờ Việt Nam (`Asia/Ho_Chi_Minh`), hợp đồng còn hiệu lực hết ngày `endDate`. Mốc thời gian (`createdAt`...) là ISO-8601 UTC.
- Ân hạn mặc định 7 ngày (cấu hình `lms.contract.grace-period`), job chạy mỗi giờ (phút 7): module bị tắt tối đa ~1 giờ sau hết ân hạn cộng 15 phút TTL token (token cũ còn dùng module tới hết TTL; refresh nhận `modules` mới). Cần dừng ngay thì suspend tenant.
- Hợp đồng tạo qua API **bắt buộc có `endDate`**. Hợp đồng `legacy=true` do migration tạo cho tenant có sẵn (tổng 0, không ngày kết thúc, không hết hạn, không nhắc hạn); khi kích hoạt hợp đồng mới chồng lấn, hợp đồng legacy bị cắt tại `startDate` của hợp đồng mới (vào ân hạn từ đó).
- Tenant tạo mới **không bắt buộc** có hợp đồng (`POST /api/platform/tenants` không đổi): tenant chưa có hợp đồng không bị job động tới; FE nên cảnh báo "chưa có hợp đồng".

## Endpoint

| Method | Đường dẫn | Body / query | Kết quả |
|---|---|---|---|
| POST | `/api/platform/tenants/{tenantId}/contracts` | `ContractRequest` | `201 ContractResponse` (`DRAFT`) |
| GET | `/api/platform/tenants/{tenantId}/contracts` | | `200 {contracts[], enabledModules[], modulesOutsideContract[]}`, mới nhất trước |
| GET | `/api/platform/contracts/expiring` | `withinDays` (mặc định 30, 1 đến 365) | `200 ExpiringContract[]` |
| GET | `/api/platform/contracts/{id}` | | `200 ContractDetailResponse` (= `ContractResponse` + `payments[]`) |
| PUT | `/api/platform/contracts/{id}` | `ContractRequest` + `version` | `200 ContractResponse` |
| POST | `/api/platform/contracts/{id}/activate` | không body | `200 ContractResponse` |
| POST | `/api/platform/contracts/{id}/extend` | `{endDate, version}` | `200 ContractResponse` |
| POST | `/api/platform/contracts/{id}/cancel` | `{reason}` | `200 ContractResponse` |
| POST | `/api/platform/contracts/{id}/renew` | `{startDate?, endDate, totalAmount, billingCycle?, name?, internalNote?}` | `201 ContractResponse` (`DRAFT` mới) |
| POST | `/api/platform/contracts/{id}/payments` | `{paidOn, amount, reference?, note?}` | `201 PaymentResponse` |
| POST | `/api/platform/contracts/{id}/payments/{paymentId}/reverse` | `{note?}` | `201 PaymentResponse` (dòng đảo) |
| GET | `/api/platform/contracts/{id}/history` | | `200 HistoryEntry[]`, mới nhất trước |

`ContractRequest`: `{ name, modules: ["LENDING","SALES"], startDate, endDate, totalAmount, billingCycle?: {unit: "DAY|MONTH|YEAR", value}, internalNote?, version? }` (`version` chỉ bắt buộc ở PUT).

### Quy tắc theo trạng thái

| Thao tác | Điều kiện | Tác động |
|---|---|---|
| PUT | `DRAFT`: mọi trường. `ACTIVE`: **chỉ** `internalNote` (các trường khác phải giữ nguyên, đổi khác thì `409 CONTRACT_NOT_EDITABLE`). `EXPIRED`/`CANCELLED`: không sửa | `version` cũ thì `409 CONTRACT_VERSION_CONFLICT` (tải lại) |
| activate | `DRAFT`; không chồng hợp đồng `ACTIVE` khác (trừ legacy bị cắt) | `ACTIVE`. Nếu `startDate` đã tới (kể cả quá khứ): áp dụng module ngay. Ngày bắt đầu ở tương lai: chờ job tới ngày |
| extend | `ACTIVE`, không legacy, `endDate` mới > hiện tại, không chồng | đổi ngày kết thúc (ân hạn quay về `IN_EFFECT`); mốc nhắc hạn mở lại. Không rút ngắn (dùng cancel) |
| cancel | `DRAFT` hoặc `ACTIVE`, bắt buộc `reason` | `DRAFT`/chưa áp dụng: chỉ đổi trạng thái. Đã áp dụng: thu hồi module ngay, không ân hạn |
| renew | nguồn `ACTIVE` hoặc `EXPIRED` | tạo `DRAFT` mới chép module; `startDate` mặc định = `endDate` nguồn + 1 (nguồn legacy: bắt buộc nhập); không tự kích hoạt |
| payments | hợp đồng `ACTIVE`/`EXPIRED`/`CANCELLED` | `DRAFT` thì `409 PAYMENT_NOT_ALLOWED` |

### Áp dụng và thu hồi module

- **Áp dụng** (tới `startDate`): bật từng module của hợp đồng (chép role mẫu, không nhân bản/ghi đè), tắt module "giữ lại" của hợp đồng cũ nếu không còn được bao phủ, **tự kích hoạt lại tenant nếu đang `SUSPENDED` do `CONTRACT_EXPIRED`**, ghi lịch sử `MODULES_APPLIED` (`tenantReactivated`). Module bị platform admin tắt tay trước đó được bật lại ở điểm này.
- **Thu hồi** (hết ân hạn do job, hoặc `cancel`): chỉ tắt module của hợp đồng đó không được hợp đồng khác bao phủ; module platform admin bật tay ngoài mọi hợp đồng **không bị đụng** (`modulesOutsideContract`). Nếu tenant sẽ không còn module nào: **giữ lại một module** (`retainedModule`, bất biến "còn ít nhất một module" không bị phá, không có `409 TENANT_MODULE_REQUIRED`) và **suspend tenant**. Không xóa role, dữ liệu hay menu.
- Nguyên nhân tạm ngưng (`suspensionCause` của tenant): `MANUAL` (platform admin suspend tay, kể cả tenant đã bị suspend do hợp đồng mà platform admin suspend thêm), `CONTRACT_EXPIRED` (job hết hạn), `CONTRACT_CANCELLED` (hủy hợp đồng). **Chỉ `CONTRACT_EXPIRED` tự kích hoạt lại** khi hợp đồng mới được áp dụng; `MANUAL` và `CONTRACT_CANCELLED` phải `POST /api/platform/tenants/{id}/activate` tay. Tenant đang `SUSPENDED` trước khi triển khai được coi là `MANUAL`.
- Thao tác tay `PUT/DELETE /api/platform/tenants/{id}/modules/{module}` ghi lịch sử `MANUAL_MODULE_ENABLED/DISABLED` (`data.outsideContract`); module tắt tay không bị job bật lại.

## Response

`ContractResponse`:

```json
{
  "id": "uuid", "tenantId": "uuid", "name": "Gói A",
  "status": "ACTIVE", "phase": "IN_EFFECT", "inGrace": false, "legacy": false,
  "modules": ["LENDING", "SALES"],
  "startDate": "2026-12-01", "endDate": "2026-12-31", "graceEndsDate": "2027-01-07",
  "totalAmount": 1000000, "paidAmount": 400000, "outstandingAmount": 600000,
  "billingCycle": { "unit": "MONTH", "value": 12 },
  "internalNote": "…", "modulesAppliedAt": "2026-12-01T00:00:00Z", "retainedModule": null, "cancelReason": null,
  "createdAt": "…", "createdBy": "uuid", "updatedAt": "…", "updatedBy": "uuid", "version": 3
}
```

- `phase` và `graceEndsDate`: `phase` null khi không `ACTIVE`; `endDate`/`graceEndsDate` null với hợp đồng legacy chưa bị cắt. `outstandingAmount` = `totalAmount` - `paidAmount` (có thể âm nếu thu thừa).
- `PaymentResponse`: `{ id, paidOn, amount, reference, note, reversesPaymentId, reversedByPaymentId, recordedBy, recordedAt }`. Thanh toán **bất biến**: sửa sai = đảo **toàn phần** (`amount` dòng đảo luôn `-gốc`, body `reverse` không có `amount`) rồi ghi dòng đúng; mỗi dòng gốc đảo một lần; không đảo dòng đảo.
- `HistoryEntry`: `{ id, type, contractId, actorType: "USER|SYSTEM", actorId, occurredAt, data }`; job ghi `actorType=SYSTEM`, `actorId=null`. Các `type`: `CREATED`, `UPDATED`, `ACTIVATED`, `MODULES_APPLIED`, `EXTENDED`, `CANCELLED`, `EXPIRED`, `LEGACY_SUPERSEDED`, `LEGACY_BACKFILLED`, `PAYMENT_RECORDED`, `PAYMENT_REVERSED`, `REMINDER_SENT`, `MANUAL_MODULE_ENABLED`, `MANUAL_MODULE_DISABLED`. Lịch sử chỉ thêm (DB chặn sửa/xóa).
- `ExpiringContract`: `{ contractId, tenantId, tenantCode, tenantName, name, endDate, daysLeft, inGrace, graceEndsDate, outstandingAmount }`: hợp đồng `ACTIVE` không legacy có hạn trong `withinDays` ngày hoặc đang ân hạn, sớm nhất trước.
- `TenantResponse` / `TenantDetailResponse` (`/api/platform/tenants...`) thêm `suspensionCause` (`null` khi `ACTIVE`); `POST .../suspend` và `.../activate` giữ nguyên (không body).

## Mã lỗi (`{ code, message }`)

| HTTP | `code` | Khi |
|---|---|---|
| 400 | `INVALID_MODULES` / `UNKNOWN_MODULE` | module rỗng / mã lạ |
| 400 | `INVALID_CONTRACT` | thiếu `endDate`, `endDate` < `startDate`, `totalAmount` < 0, chu kỳ sai, thiếu `version`/`reason`, `extend` không dài hơn, `withinDays` ngoài 1..365, `renew` legacy thiếu `startDate` |
| 400 | `INVALID_PAYMENT` | `amount` <= 0, thiếu `paidOn` |
| 404 | `TENANT_NOT_FOUND` / `CONTRACT_NOT_FOUND` / `PAYMENT_NOT_FOUND` | không tồn tại (thanh toán không thuộc hợp đồng trong đường dẫn cũng 404) |
| 409 | `CONTRACT_OVERLAP` | kích hoạt/kéo dài chồng hợp đồng `ACTIVE` khác (không phải legacy), hoặc hợp đồng mới không bắt đầu sau hợp đồng legacy |
| 409 | `CONTRACT_NOT_EDITABLE` | sửa trường không được phép theo trạng thái, hoặc thao tác sai trạng thái (kích hoạt không phải `DRAFT`, hủy `EXPIRED`/`CANCELLED`...) |
| 409 | `CONTRACT_VERSION_CONFLICT` | `version` cũ: tải lại rồi thử lại |
| 409 | `PAYMENT_NOT_ALLOWED` | ghi thanh toán vào `DRAFT` |
| 409 | `PAYMENT_ALREADY_REVERSED` / `PAYMENT_NOT_REVERSIBLE` | đảo lần hai / đảo một dòng đảo |
| 401 / 403 | `FORBIDDEN` | không token / không phải platform admin |

## Khóa quyền

Không có khóa động: `PlatformContractController` và `PlatformContractPaymentController` đều `@PlatformAdminOnly`. Đổi tên controller/method không ảnh hưởng quyền. (Tách quyền xem tiền cho vai trò kế toán là ngoài phạm vi, sẽ có ADR riêng; controller thanh toán đã tách sẵn.)

## Ghi chú cho FE

- Danh sách/chi tiết tenant: hiển thị `suspensionCause` ("Tạm ngưng tay", "Tạm ngưng do hết hạn hợp đồng, tự mở khi hợp đồng mới có hiệu lực", "Tạm ngưng do hủy hợp đồng"); cảnh báo tenant chưa có hợp đồng; cảnh báo `modulesOutsideContract` và `retainedModule`; cảnh báo khi kích hoạt hợp đồng thiếu module đang bật (module legacy không có trong hợp đồng mới bị tắt sau ân hạn kể từ `startDate`).
- Xử lý `409 CONTRACT_OVERLAP`, `CONTRACT_NOT_EDITABLE`, `CONTRACT_VERSION_CONFLICT` (tải lại).
- Thông báo `CONTRACT_EXPIRING` đến mọi quản trị viên (`is_admin`) đang hoạt động của tenant qua `docs/api/notifications.md`; không có API mới.
