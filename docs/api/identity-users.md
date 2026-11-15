# API người dùng: đặt lại mật khẩu (lms-api -> identity-service)

FE gọi `/api/**` qua nginx (8080); `lms-api` chuyển tiếp 1-1 sang `/internal/users/**` của identity-service, kèm Bearer và `X-Correlation-Id`.

## POST /api/users/{id}/password-reset

Quản trị viên tenant đặt mật khẩu tạm cho một người dùng. Hệ quả bên identity: người dùng buộc đổi mật khẩu ở lần đăng nhập sau (`mustChangePassword`), mọi phiên hiện có bị thu hồi.

Chuyển tiếp tới `POST /internal/users/{id}/password-reset`.

Request:

```json
{ "temporaryPassword": "chuỗi 8-128 ký tự" }
```

Response: `204 No Content`, không có thân. Mật khẩu tạm không bao giờ được trả lại; `lms-api` không log thân yêu cầu.

Lỗi (4xx chuyển nguyên mã và thân `{code, message, details}` của identity; 5xx thành 502):

| HTTP | code | Khi |
|---|---|---|
| 400 | `VALIDATION_FAILED` | thiếu/rỗng hoặc độ dài ngoài 8-128 |
| 401 | (Spring Security) | không có/hết hạn token |
| 403 | `FORBIDDEN` | thiếu khóa quyền; hoặc `USER_OPERATION_NOT_ALLOWED` khi đích là platform admin/tài khoản thuộc tenant khác |
| 404 | `USER_NOT_FOUND` | người dùng không thuộc tenant hiện tại |
| 422 | `INVALID_USER` | người dùng chưa có tài khoản đăng nhập |

### Khóa quyền cho FE/quản trị

`lms-api` không kiểm quyền (ADR-0005); identity-service kiểm trên token được chuyển tiếp. Khóa là của controller bên identity (đã đối chiếu code), không phải của controller lms-api:

| Hành động | Khóa |
|---|---|
| Đặt lại mật khẩu người dùng | `UserController/resetPassword` |

Khóa chỉ xuất hiện trong danh mục permissions sau khi endpoint được gọi lần đầu. Quản trị viên (`admin=true`) dùng được ngay; role khác cần được gán khóa trên và đăng nhập/refresh lại để có hiệu lực. Không đổi tên controller/method `resetPassword` của identity (đổi là mất quyền đã gán).
