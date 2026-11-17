# Hợp đồng của tôi (tenant admin, chỉ đọc): `/api/contracts`

Nền: ADR-0012 mục 2.5.2. Cho quản trị viên tenant xem hợp đồng thuê dịch vụ của **chính tenant mình**. FE gọi `/api/contracts...` (lms-api chuyển xuống identity `/internal/contracts...`, giữ nguyên Bearer token). Chỉ có `GET`; không có API ghi (tạo/sửa/thanh toán thuộc platform admin, xem `docs/api/platform-contracts.md`).

| Method | Đường dẫn | Khóa quyền (động) | Kết quả |
|---|---|---|---|
| GET | `/api/contracts` | `TenantContractController/list` | `200 TenantContractView[]`, mới nhất trước, **không gồm `DRAFT`** |
| GET | `/api/contracts/current` | `TenantContractController/current` | `200 TenantContractView` hợp đồng đang `IN_EFFECT` hoặc `IN_GRACE`; không có: `404 CONTRACT_NOT_FOUND` |

## Phân quyền (ADR-0003)

- Khóa động `Controller/method`, không hằng số: role có cờ `is_admin` của tenant đi qua sẵn (`admin=true`). Tenant có thể gán hai khóa này cho role khác (ví dụ kế toán của thư viện) mà không đổi code; khóa được đăng ký vào bảng quyền ở lần endpoint được gọi đầu tiên. Người dùng thường chưa được gán khóa: `403 FORBIDDEN`. Không token: `401`.
- Token không có `tenantId` (platform admin chưa chọn tenant): `403 TENANT_REQUIRED`.
- Đổi tên lớp/method đổi khóa, role đã gán mất quyền (cần báo trước nếu đổi).
- Không cần module nào bật (không `@RequiresModule`): vẫn xem được hợp đồng khi module bị tắt. FE ẩn mục menu khi nhận `403`.
- Endpoint **không nhận id nào**: tenant lấy từ token và lọc tường minh theo `tenant_id`, nên không có đường đọc hợp đồng của tenant khác. Hiện không có `/api/contracts/{id}`; nếu sau này thêm thì hợp đồng của tenant khác phải trả `404 CONTRACT_NOT_FOUND` (không lộ sự tồn tại).

## `TenantContractView`

```json
{
  "id": "uuid", "name": "Gói A", "status": "ACTIVE", "phase": "IN_GRACE", "inGrace": true,
  "graceEndsDate": "2027-01-07", "modules": ["LENDING"],
  "startDate": "2026-12-01", "endDate": "2026-12-31",
  "totalAmount": 1000000, "paidAmount": 400000, "outstandingAmount": 600000,
  "billingCycle": { "unit": "MONTH", "value": 12 }
}
```

- `status`: `ACTIVE` | `EXPIRED` | `CANCELLED`. `phase` (chỉ khi `ACTIVE`): `UPCOMING` | `IN_EFFECT` | `IN_GRACE`. `inGrace=true` thì module vẫn bật tới `graceEndsDate`; sau đó module của hợp đồng bị tắt (xem `platform-contracts.md`). Ngày theo giờ Việt Nam.
- Tiền là số nguyên VND, chỉ **tổng** phải trả (`totalAmount`), tổng đã thu (`paidAmount`) và còn nợ (`outstandingAmount`, có thể âm nếu thu thừa). **Không có** giá riêng từng module, chi tiết thanh toán (mã tham chiếu, ghi chú, người ghi), ghi chú nội bộ, lịch sử, lý do hủy, `retainedModule`, `modulesOutsideContract`, `legacy`.
- Ngày kết thúc: hợp đồng chuyển tiếp (legacy) chưa bị cắt có `endDate` và `graceEndsDate` là `null`.

## Lỗi

| HTTP | `code` | Khi |
|---|---|---|
| 401 | | không token / token hết hạn |
| 403 | `FORBIDDEN` | chưa được gán khóa quyền |
| 403 | `TENANT_REQUIRED` | token không có `tenantId` |
| 404 | `CONTRACT_NOT_FOUND` | `/current`: tenant chưa có hợp đồng đang hiệu lực |

## Ghi chú cho FE

- Thẻ "Hợp đồng hiện tại": `GET /api/contracts/current` (`404` = chưa có hợp đồng, hiển thị trạng thái rỗng, không phải lỗi); danh sách: `GET /api/contracts`. Hiển thị cờ ân hạn và số còn nợ.
- Nhắc sắp hết hạn đến qua thông báo `CONTRACT_EXPIRING` (`docs/api/notifications.md`), không qua API này.
