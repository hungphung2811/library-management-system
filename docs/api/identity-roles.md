# API role: sửa và xóa (lms-api -> identity-service)

FE gọi `/api/**` qua nginx (8080); `lms-api` chuyển tiếp 1-1 sang `/internal/roles/**` của identity-service, kèm Bearer và `X-Correlation-Id`. Các endpoint role sẵn có (`POST/GET /api/roles`, `GET /api/roles/{roleId}`, thêm/gỡ permission, gán/thu hồi role) không đổi.

## PUT /api/roles/{roleId}

Đổi tên và mô tả role trong tenant hiện tại. Giữ nguyên id, danh sách permission và cờ `isAdmin` (cờ này không đổi được qua API; body không có trường `isAdmin`).

Request:

```json
{ "name": "REVIEWER", "description": "Người duyệt" }
```

`name` theo quy tắc tên role (chữ HOA và dấu gạch dưới), `description` không trống. Response `200` là `RoleView` `{id, name, description, isAdmin, permissions[]}`.

## DELETE /api/roles/{roleId}

Xóa cứng role (các dòng `role_permissions` xóa theo). Response `204 No Content`.

## Lỗi (4xx chuyển nguyên `{code, message, details}` của identity; 5xx thành 502)

| HTTP | code | Khi |
|---|---|---|
| 400 | `VALIDATION_FAILED` | thiếu/rỗng `name` hoặc `description` |
| 401 | (Spring Security) | không có/hết hạn token |
| 403 | `FORBIDDEN` | thiếu khóa quyền; hoặc người không phải quản trị viên sửa/xóa role `isAdmin` |
| 404 | `NOT_FOUND` | role không tồn tại hoặc thuộc tenant khác |
| 409 | `DUPLICATE` | (PUT) tên trùng role khác trong tenant |
| 409 | `ROLE_IN_USE` | (DELETE) còn người dùng đang được gán role |
| 409 | `LAST_ADMIN_ROLE` | (DELETE) là role quản trị (`isAdmin`) duy nhất của tenant |
| 422 | `INVALID_ACCESS_CONTROL` | tên role sai quy tắc đặt tên |

Không có cột "role hệ thống" trong dữ liệu; role mẫu khi tạo tenant chỉ là bản sao, nên chỉ chặn theo `ROLE_IN_USE` và `LAST_ADMIN_ROLE`. Không phát event Kafka (các thao tác tạo/cấp quyền role hiện có cũng không phát; chỉ gán/thu hồi role cho user phát `RoleAssigned/RoleRevoked`).

## Hiệu lực với token đã phát

JWT chứa `permissions`/`admin` tính lúc đăng nhập hoặc refresh. Đổi tên role không ảnh hưởng quyền. Xóa role chỉ xảy ra khi không còn user nào được gán, nên không có người dùng bị mất quyền do xóa; token cũ của người từng có role (đã bị thu hồi trước đó) vẫn giữ quyền đến khi hết hạn hoặc refresh, giống hành vi thu hồi role hiện tại.

## Khóa quyền cho FE/quản trị

Khóa động `Controller/method` ở identity-service (gán cho role ở màn quản lý role; admin bỏ qua kiểm khóa; khóa chưa gán cho role nào thì người không phải admin nhận 403):

| Endpoint FE | Khóa |
|---|---|
| `PUT /api/roles/{roleId}` | `AccessControlController/updateRole` |
| `DELETE /api/roles/{roleId}` | `AccessControlController/deleteRole` |

Khóa tự đăng ký vào bảng `permissions` lần đầu endpoint được gọi. Không có migration.
