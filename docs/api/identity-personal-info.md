# Thông tin cá nhân của người dùng và độc giả (lms-api -> identity-service)

Mở rộng hồ sơ `users` và `reader_profiles` bằng các trường **tùy chọn** (nullable, không backfill: dữ liệu cũ trả `null`). FE gọi `/api/**`; `lms-api` chuyển tiếp 1-1 sang `/internal/users/**` của identity-service. Migration `V27__user_reader_personal_info.sql` (thư mục `identity` của system-service).

Dữ liệu cá nhân (điện thoại, ngày sinh, địa chỉ) **không** được ghi vào log, audit hay event Kafka.

## Trường cho người dùng (`UserView`)

Có trong response của `GET /api/users` (mỗi phần tử `items[]`), `GET /api/users/{id}`, `GET /api/users/me`, `POST /api/users`, `PUT /api/users/{id}`, `PUT /api/users/{id}/status`, `POST /api/users/{id}/unlock`. Các trường cũ (`id, username, fullName, email, departmentId, status, tenantId, locked, lockedUntil`) không đổi.

| Trường | Kiểu | Quy tắc |
|---|---|---|
| `phone` | string | tối đa 20 ký tự, chỉ chữ số, `+`, `-`, khoảng trắng |
| `dateOfBirth` | `yyyy-MM-dd` | không ở tương lai, tuổi tối đa 120 |
| `gender` | `MALE` \| `FEMALE` \| `OTHER` | |
| `address` | string | tối đa 500 |
| `avatarUrl` | string | tối đa 500, bắt đầu `http://` hoặc `https://` |
| `jobTitle` | string | tối đa 100 |
| `employeeCode` | string | tối đa 32 |

## Trường cho độc giả (`ReaderProfileView`)

Có trong `GET /api/users/me/reader-profile`, `GET|POST /api/users/{userId}/reader-profile`, `PUT /api/users/{userId}/reader-profile[...]`. Các trường cũ (`userId, cardNo, fullName, email, readerType, cardExpiryDate, status, cardValid`) không đổi.

| Trường | Kiểu | Quy tắc |
|---|---|---|
| `phone` | string | như trên |
| `dateOfBirth` | `yyyy-MM-dd` | như trên |
| `gender` | `MALE` \| `FEMALE` \| `OTHER` | |
| `address` | string | tối đa 500 |
| `faculty` | string | tối đa 150, khoa/đơn vị |
| `className` | string | tối đa 50, lớp/ngành |
| `memberCode` | string | tối đa 32, mã sinh viên/mã cán bộ |
| `cardIssuedDate` | `yyyy-MM-dd` | không sau `cardExpiryDate` |

## Request

- `POST /api/users` (`CreateUserRequest`): thêm các trường người dùng ở trên (đều tùy chọn).
- `PUT /api/users/{id}` (`UpdateUserRequest`): thêm các trường người dùng ở trên. **Trường vắng hoặc `null` nghĩa là giữ nguyên giá trị hiện có**, có giá trị thì ghi đè (chuỗi rỗng bị coi như vắng; chưa có thao tác xóa một trường). Đổi thông tin cá nhân của tài khoản khác chịu cùng ràng buộc như đổi họ tên/email (tài khoản chỉ thuộc tenant hiện tại và không phải platform admin; chính người dùng luôn được).
- `POST /api/users/{userId}/reader-profile` (`IssueReaderCardRequest`): thêm các trường độc giả ở trên (đều tùy chọn). Khi cấp kèm tài khoản online (`temporaryPassword`), `phone`, `dateOfBirth`, `gender`, `address` cũng được ghi vào hồ sơ người dùng.
- **Mới** `PUT /api/users/{userId}/reader-profile` (`UpdateReaderProfileRequest`): body gồm các trường độc giả ở trên, cùng quy tắc "vắng/`null` giữ nguyên". Trả `200 ReaderProfileView`. Khóa quyền tự sinh `ReaderProfileController/update` (gán cho role cần sửa hồ sơ độc giả; admin tự bỏ qua).

## Lỗi

`400` (validation Bean: sai định dạng, quá dài, `gender` không hợp lệ, `dateOfBirth` không ở quá khứ) hoặc `422 INVALID_USER` / `422 INVALID_READER` (ví dụ tuổi quá 120, `cardIssuedDate` sau hạn thẻ). Thân lỗi `{code, message, details}` như các API khác.

## Dữ liệu demo

`scripts\seed-demo.ps1` (tenant `demo`) điền tên người Việt, điện thoại giả, ngày sinh, giới tính, địa chỉ nhiều tỉnh/thành, khoa/lớp/mã sinh viên (sinh viên) hoặc đơn vị/mã cán bộ (giảng viên) cho `demo.admin`, `demo.thuthu`, `demo.cuahang`, `demo.docgia01..24`. Họ tên các tài khoản này đã đổi so với bản seed cũ (chỉ áp dụng cho DB seed mới; DB đã seed giữ tên cũ, chỉ được điền các trường cá nhân còn trống).
