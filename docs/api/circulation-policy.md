# Chính sách mượn trả theo đơn vị (tenant)

Hợp đồng `lms-api`. Quyền do circulation-service kiểm theo khóa `CirculationPolicyController/get` và `CirculationPolicyController/update`
(gán cho role như các khóa khác; admin bỏ qua kiểm khóa). Thuộc module LENDING.

## GET /api/circulation/policy
Trả chính sách của đơn vị hiện tại. Chưa cấu hình: giá trị mặc định, `custom=false`, `version=0`, `updatedAt=null`.

```json
{ "loanDays": 14, "maxRenewals": 2, "renewalDays": 14, "maxActiveLoansPerReader": 5,
  "finePerDay": 5000, "graceDays": 0, "reservationHoldDays": 3,
  "custom": false, "version": 0, "updatedAt": null }
```

## PUT /api/circulation/policy
Body: 7 trường như trên (bắt buộc) + `version` (giá trị đọc từ GET; thiếu coi như 0). Trả cùng dạng như GET (`custom=true`, version mới).

| Trường | Khoảng |
|---|---|
| loanDays | 1..365 |
| maxRenewals | 0..20 |
| renewalDays | 1..365 |
| maxActiveLoansPerReader | 1..100 |
| finePerDay (số nguyên, VND) | 0..10.000.000 |
| graceDays | 0..90 |
| reservationHoldDays | 1..30 |

Lỗi: 400 `VALIDATION_FAILED` (thiếu trường), 422 `INVALID_CIRCULATION_POLICY` (ngoài khoảng, thông điệp tiếng Việt),
409 `POLICY_VERSION_CONFLICT` (version cũ, tải lại rồi sửa), 401/403 (thiếu đăng nhập/quyền).

## Hành vi
- Đơn vị đã lưu chính sách (`custom=true`): mượn dùng `loanDays` và `maxActiveLoansPerReader` cho mọi loại độc giả
  (thay quy định theo loại độc giả `borrow_rules`); gia hạn dùng `maxRenewals`/`renewalDays`; phạt trễ dùng `finePerDay`
  sau khi trừ `graceDays`; giữ chỗ đặt trước dùng `reservationHoldDays`.
- Chưa cấu hình: hành vi cũ không đổi (borrow_rules theo loại độc giả, 14 ngày/2 lần, 5.000đ/ngày, 3 ngày).
- Chỉ áp cho thao tác mới: phiếu đã lập, khoản phạt đã lập, đặt trước đã giữ bản sao không bị tính lại.
