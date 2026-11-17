# API tài khoản: quên mật khẩu, mở khóa, thu hồi phiên, nhập/xuất người dùng (lms-api -> identity-service)

FE gọi `/api/**` qua nginx (8080); `lms-api` chuyển tiếp sang `/internal/**` của identity-service. Lỗi 4xx giữ nguyên `{code, message, details}` của identity; 5xx thành 502.

## 1. Quên mật khẩu (công khai, không cần token)

### POST /api/auth/password-reset-requests
Body `{ "username": "..." }`. **Luôn `202 Accepted`, không thân**, dù tài khoản có tồn tại hay không (chống dò tài khoản); vượt giới hạn tần suất theo tài khoản (3 yêu cầu / 15 phút) cũng vẫn 202 nhưng không tạo token. nginx giới hạn thêm 5 lần/phút/IP (429). Lỗi: `400 VALIDATION_FAILED` (thiếu username).

Token ngẫu nhiên dùng một lần, hạn 30 phút, chỉ lưu hash. Chưa có dịch vụ gửi thư: identity phát event `PasswordResetRequested` (token thô) lên topic `auth.password-reset`; ở profile dev token được ghi log INFO của identity (`lms.identity.password-reset.log-token`, mặc định tắt, KHÔNG bật ở production).

### POST /api/auth/password-reset-confirmations
Body `{ "token": "...", "newPassword": "8-72 ký tự" }`. `204 No Content`. Hệ quả: đổi mật khẩu, bỏ cờ `mustChangePassword`, xóa khóa đăng nhập sai, thu hồi mọi refresh token (phải đăng nhập lại).

| HTTP | code | Khi |
|---|---|---|
| 400 | `INVALID_RESET_TOKEN` | token sai, hết hạn hoặc đã dùng (một thông điệp chung) |
| 400 | `VALIDATION_FAILED` | thiếu trường / độ dài ngoài 8-72 |
| 422 | `INVALID_REQUEST` | mật khẩu dưới 8 ký tự (token chưa bị tiêu thụ, thử lại được) |

## 2. Mở khóa tài khoản

`POST /api/users/{id}/unlock` (không body). Xóa số lần đăng nhập sai và thời điểm khóa (khóa tự động 15 phút sau 5 lần sai). Trả `200` hồ sơ người dùng với `locked=false`. Lỗi: `404 USER_NOT_FOUND` (không thuộc tenant), `403 USER_OPERATION_NOT_ALLOWED` (tài khoản thuộc nhiều tenant hoặc platform admin), `422 INVALID_USER` (chưa có tài khoản đăng nhập). Phát event `AccountUnlockedEvent` lên `auth.events` (audit).

Mọi response hồ sơ người dùng (`GET /api/users`, `GET /api/users/{id}`, `GET /api/users/me`, `PUT /api/users/{id}`, `PUT /api/users/{id}/status`) có thêm `locked` (boolean) và `lockedUntil` (ISO-8601 UTC hoặc `null`). Chỉ khi khóa còn hiệu lực mới `locked=true`.

## 3. Thu hồi phiên

`POST /api/users/{id}/sessions/revocation` (không body) -> `200 { "revokedSessions": n }`. Thu hồi mọi refresh token của người dùng **trong tenant hiện tại** (phiên tenant khác và phiên nền tảng giữ nguyên). Access token đã phát còn dùng được tối đa đến hết TTL (15 phút); làm mới bị từ chối ngay. Người dùng bị vô hiệu hóa (`status=DISABLED`) cũng không làm mới được. Lỗi: `404 USER_NOT_FOUND`.

## 4. Nhập / xuất người dùng

### POST /api/users/imports
```json
{ "dryRun": true,
  "rows": [ { "username": "alice", "fullName": "Nguyễn A", "email": "a@x.vn",
              "departmentCode": "KHOA-CNTT", "temporaryPassword": null, "reader": true } ] }
```
Tối đa 500 dòng (`400 VALIDATION_FAILED` nếu rỗng hoặc vượt). Response `200` (Cache-Control no-store):
```json
{ "total": 1, "created": 0, "failed": 0,
  "results": [ { "row": 1, "username": "alice", "status": "VALID", "errorCode": null, "message": null, "temporaryPassword": null } ] }
```
`status`: `VALID` (dryRun hợp lệ) | `CREATED` | `FAILED`. `dryRun=false` tạo từng dòng hợp lệ trong transaction riêng; dòng lỗi không ảnh hưởng dòng khác. Tài khoản tạo ra luôn `mustChangePassword=true`. Thiếu `temporaryPassword` thì hệ thống sinh ngẫu nhiên 14 ký tự và trả **một lần** ở `temporaryPassword` của dòng `CREATED` (không log, không lưu). `reader=true` tạo thêm hồ sơ độc giả: số thẻ = username (tối đa 32 ký tự), loại `STUDENT`, hạn thẻ 1 năm.

`errorCode` theo dòng: `INVALID_USERNAME`, `INVALID_FULL_NAME`, `INVALID_EMAIL`, `DEPARTMENT_NOT_FOUND` (mã phòng ban không có/không hoạt động), `WEAK_PASSWORD` (dài 8-72, không trùng username), `DUPLICATE_IN_FILE`, `DUPLICATE_USER` (đã có ở bất kỳ tenant; thông điệp chung), `DUPLICATE_READER`, `INVALID_ROW`, `INTERNAL_ERROR`.

### GET /api/users/export
Query (cùng bộ lọc tìm kiếm): `keyword`, `departmentId`, `status`. Trả `200 text/csv; charset=UTF-8` (có BOM, `Content-Disposition: attachment; filename="users.csv"`, no-store), tối đa 5000 dòng. Cột: `username,fullName,email,department,status,roles` (department là **mã** phòng ban; roles phân tách bằng `;`). Ô bắt đầu bằng `=` `+` `-` `@` được thêm dấu nháy đơn phía trước (chống CSV injection).

## 5. Khóa quyền (identity kiểm trên token; lms-api không kiểm)

| Hành động | Khóa |
|---|---|
| Mở khóa | `UserController/unlock` |
| Thu hồi phiên | `UserController/revokeSessions` |
| Nhập | `UserController/importUsers` |
| Xuất | `UserController/exportUsers` |

Quên mật khẩu là `@PublicEndpoint` (không có khóa). Khóa chỉ xuất hiện trong danh mục permissions sau lần gọi đầu; admin dùng được ngay, role khác cần được gán khóa và đăng nhập/refresh lại. Không đổi tên các method trên.
