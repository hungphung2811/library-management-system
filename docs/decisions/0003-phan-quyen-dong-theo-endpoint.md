# ADR-0003: Phân quyền động theo endpoint, khóa quyền tự sinh `Controller/method`

- Trạng thái: chấp nhận
- Bối cảnh: trước đây mỗi service khai báo cứng "endpoint nào cần permission nào" (`requestMatchers(...).hasAuthority(Permissions.X)`)
  cùng một class hằng số `Permissions`. Thêm endpoint phải sửa code ở nhiều nơi, danh mục permission trong DB và hằng số trong code
  có thể lệch nhau, và quản trị viên không biết hệ thống đang có những khóa quyền nào.
- Quyết định:
  - Middleware `PermissionInterceptor` (security-common) xác định khóa quyền cho mỗi controller method: mặc định
    `TênController/tênPhươngThức`; `@RequirePermission("KEY")` chỉ khi BE chủ đích đặt khóa riêng; `@AuthenticatedOnly` và
    `@PublicEndpoint` cho endpoint không cần quyền.
  - Khi endpoint được gọi lần đầu (sau xác thực), khóa được đăng ký vào bảng `permissions` của identity-service: identity ghi
    trực tiếp, các service khác gọi REST nội bộ có khóa chia sẻ (vì mỗi service có DB riêng). Chèn bằng
    `INSERT ... ON CONFLICT`, idempotent, không ghi đè khóa đã có.
  - Quyết định cho phép vẫn dựa trên claim `permissions` trong JWT (sinh từ DB lúc đăng nhập), không truy vấn DB mỗi request.
  - Mặc định từ chối: khóa chưa gán cho role nào thì người không phải admin bị 403, nhưng khóa vẫn được đăng ký để quản trị viên thấy và gán.
  - Không hardcode tên role quản trị: role có cờ `is_admin` (ô tích khi tạo role). Người dùng có ít nhất một role như vậy mang claim
    `admin=true` và được bỏ qua kiểm tra khóa. Chỉ quản trị viên mới tạo/gán/thu hồi role `isAdmin`. Token của tài khoản phải đổi mật khẩu
    không bao giờ mang claim admin.
  - Xóa class `Permissions` và các quy tắc `hasAuthority`; migration V9 đổi các mã seed cũ (`BORROW_BOOK`...) sang khóa endpoint
    tương ứng, giữ nguyên quyền hiệu lực của các role.
- Đánh đổi:
  - Khóa chỉ xuất hiện trong DB sau khi endpoint được gọi ít nhất một lần; muốn thấy sớm cần thêm bước quét lúc khởi động (chưa làm).
  - Đổi tên controller hoặc method làm đổi khóa quyền, role đã gán khóa cũ mất quyền: cần dùng `@RequirePermission` để cố định khóa khi
    muốn đổi tên an toàn, hoặc migrate dữ liệu.
  - Thay đổi quyền của role chỉ có hiệu lực ở lần đăng nhập/làm mới token kế tiếp; chưa có event buộc làm mới khi đổi permission của role.
  - Đăng ký khóa là best-effort và bất đồng bộ (cooldown 30 giây khi lỗi); identity-service chết thì khóa mới chưa vào được DB.
