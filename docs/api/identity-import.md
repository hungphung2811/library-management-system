# Nhập người dùng/độc giả từ Excel (lms-api -> identity-service)

Loại `users` của cụm nhập master data hàng loạt (ADR-0024, `docs/decisions/0024-nhap-master-data-tu-excel-preview-confirm.md`): tải file mẫu `.xlsx`, điền, upload, xem **preview từng dòng** (dữ liệu nằm trong Redis, chưa ghi DB), bấm Confirm mới ghi, rồi tải **một lần** file mật khẩu tạm. FE gọi `/api/identity/imports/users/**`; `lms-api` chuyển nguyên sang `/internal/imports/users/**` của identity-service (byte và mã HTTP giữ nguyên). Nginx cho phép thân tối đa 6 MB, timeout 60 giây ở nhóm đường dẫn này.

Endpoint JSON cũ `POST /api/users/imports` (500 dòng, `dryRun`) **deprecated** nhưng còn chạy tới pha dọn dẹp; xem `identity-account-admin.md`.

Giới hạn: tối đa **5.000 dòng** dữ liệu mỗi file, **5 MB** mỗi file, phiên preview sống **30 phút**, tối đa **3 phiên đang mở** mỗi người dùng trong tenant.

## 1. Endpoint

| # | Method + đường dẫn | Quyền | Trả về |
|---|---|---|---|
| 1 | `GET /api/identity/imports/users/template` | chỉ đăng nhập | `200` file `.xlsx` (`Content-Disposition: attachment; filename="mau-users.xlsx"`, no-store) |
| 2 | `POST /api/identity/imports/users` | khóa `UserImportController/upload` | `201` `ImportSessionView` (trạng thái `VALIDATED`). `multipart/form-data`, part tên **`file`** |
| 3 | `GET /api/identity/imports/users/{importId}` | chỉ đăng nhập + chủ phiên | `200` `ImportSessionView` (thăm dò 1-2 giây/lần khi `COMMITTING`) |
| 4 | `GET /api/identity/imports/users/{importId}/rows?status=&sheet=&page=&size=` | chỉ đăng nhập + chủ phiên | `200` `RowsPage` |
| 5 | `POST /api/identity/imports/users/{importId}/confirm` | khóa `UserImportController/confirm` + chủ phiên | `202` `ImportSessionView` (đang ghi); `200` nếu phiên đã xong. Không body. Bấm lặp lại không lỗi và không ghi lại |
| 6 | `DELETE /api/identity/imports/users/{importId}` | chỉ đăng nhập + chủ phiên | `204`; xóa phiên |
| 7 | `GET /api/identity/imports/users/{importId}/error-file` | chỉ đăng nhập + chủ phiên | `200` `.xlsx` gồm dòng `INVALID` và `DUPLICATE` (giữ giá trị đã nhập, thêm cột "Trạng thái", "Lỗi"), upload lại được sau khi sửa |
| 8 | `GET /api/identity/imports/users/{importId}/credentials` | khóa `UserImportController/credentials` + chủ phiên | `200` `.xlsx` mật khẩu tạm (no-store), **tải được đúng một lần**, chỉ sau `COMMITTED` |

`rows` nhận query record `status` (`VALID|INVALID|DUPLICATE`), `sheet`, `page` (từ 0, mặc định 0), `size` (mặc định 50, tối đa 200); tham số khác bị bỏ qua, giá trị có ký tự lạ trả `400`.

Chủ phiên = chính người đã upload (cùng tenant). Phiên của người khác, đã hết hạn, đã hủy hoặc tenant khác đều trả `404 IMPORT_NOT_FOUND` (cùng một mã, không lộ phiên người khác).

## 2. Luồng dùng

1. Tải mẫu (#1), điền sheet `NguoiDung` (sheet `HuongDan` mô tả cột, không sửa tên cột; cột bắt buộc có dấu `*`).
2. Upload (#2). Nhận `importId`, tóm tắt (`totals`, `sheets[]`) và `columns[]` để dựng bảng. Xem từng dòng ở #4. Dòng `INVALID` kèm `issues[]` (cột + mã + thông điệp); dòng `DUPLICATE` (tên đăng nhập hoặc email đã có ở bất kỳ tenant nào, thông điệp chung không nêu tenant) sẽ **bị bỏ qua** khi confirm.
3. Sửa file và upload lại nếu cần (tải #7 để lấy các dòng lỗi), hoặc Confirm (#5). Chỉ dòng `VALID` được ghi.
4. Thăm dò #3 cho tới `status = COMMITTED` (hoặc `COMMIT_FAILED`/`INTERRUPTED`; `INTERRUPTED` bấm Confirm lại để tiếp tục). `progress {processed, total}`, `result {created, skippedDuplicate, failed}` (có thể khác preview vì bị bỏ qua nếu người khác vừa tạo trùng).
5. Khi `COMMITTED` và `result.created > 0`, tải mật khẩu tạm (#8) **ngay**: chỉ tải được một lần. Lần hai trả `410 IMPORT_CREDENTIALS_GONE` (cũng trả khi quá hạn 30 phút kể từ lúc xong). File này là cách duy nhất để lấy mật khẩu; hệ thống không lưu, không log.

## 3. Cột của file (sheet `NguoiDung`)

Nhãn tiếng Việt ở dòng 1 (có thể dùng `key` kỹ thuật làm tiêu đề). Ngày nhận ô kiểu ngày của Excel, `dd/MM/yyyy` hoặc `yyyy-MM-dd`. Boolean nhận `Có/Không`, `x`, `true/false`. Mọi ô chỉ là **dữ liệu**: ô bắt đầu bằng `=`, `+`, `-`, `@` ở file tải về được chuẩn hóa chống công thức.

| `key` | Nhãn | Bắt buộc | Quy tắc |
|---|---|---|---|
| `username` | Tên đăng nhập | có | `^[a-z0-9._-]{3,100}$`, tự đưa về chữ thường; độc giả tối đa 32 ký tự (dùng làm số thẻ). Đã có ở bất kỳ tenant nào: `DUPLICATE` |
| `fullName` | Họ tên | có | tối đa 255 |
| `email` | Email | có | dạng email, tối đa 255. Đã có (không phân biệt hoa thường): `DUPLICATE` |
| `phone` | Số điện thoại | không | tối đa 20, chỉ chữ số, `+`, `-`, khoảng trắng |
| `dateOfBirth` | Ngày sinh | không | không ở tương lai, tuổi tối đa 120 |
| `gender` | Giới tính | không | `MALE` \| `FEMALE` \| `OTHER` |
| `address` | Địa chỉ | không | tối đa 500 |
| `jobTitle` | Chức danh | không | tối đa 100 |
| `employeeCode` | Mã nhân viên | không | tối đa 32 |
| `isReader` | Là độc giả | không | mặc định Không. Có thì tạo thêm hồ sơ độc giả, số thẻ = `username` |
| `readerType` | Loại độc giả | không | `STUDENT` (mặc định) \| `LECTURER`; chỉ có nghĩa khi là độc giả |
| `cardExpiryDate` | Hạn thẻ | không | phải sau hôm nay; mặc định hôm nay + 1 năm |
| `faculty` | Khoa/đơn vị | không | tối đa 150 |
| `className` | Lớp/ngành | không | tối đa 50 |
| `memberCode` | Mã sinh viên/cán bộ | không | tối đa 32 |
| `cardIssuedDate` | Ngày cấp thẻ | không | không sau hạn thẻ |

Không có cột **mật khẩu**, **role** và **phòng ban**: mật khẩu do hệ thống tự sinh khi confirm (14 ký tự, mọi tài khoản `mustChangePassword = true`, người dùng bị buộc đổi ở lần đăng nhập đầu); gán role và phòng ban làm sau bằng chức năng quản lý người dùng. Cột thông tin độc giả (`readerType`, `cardExpiryDate`, `faculty`, `className`, `memberCode`, `cardIssuedDate`) trên dòng không phải độc giả chỉ có cảnh báo `READER_COLUMN_IGNORED` và bị bỏ qua.

## 4. Mã lỗi dòng (`issues[].code`)

| Mã | Mức | Ý nghĩa |
|---|---|---|
| `REQUIRED`, `TOO_LONG`, `INVALID_NUMBER`, `INVALID_DATE`, `INVALID_ENUM`, `INVALID_BOOLEAN`, `CELL_TOO_LONG` | ERROR | Kiểm cú pháp chung (thư viện) |
| `INVALID_USER` | ERROR | Vi phạm luật hồ sơ người dùng (tên đăng nhập, họ tên, email, thông tin cá nhân); `column` chỉ cột lỗi |
| `INVALID_READER` | ERROR | Vi phạm luật độc giả (tên đăng nhập quá 32 ký tự, hạn thẻ, ngày cấp thẻ, khoa/lớp/mã) |
| `DUPLICATE_IN_FILE` | ERROR | Trùng tên đăng nhập hoặc email với dòng khác trong cùng file |
| `ALREADY_EXISTS` | `DUPLICATE` | Tên đăng nhập hoặc email đã có (thông điệp chung) |
| `CARD_ALREADY_EXISTS` | `DUPLICATE` | Số thẻ độc giả đã có trong tenant |
| `READER_COLUMN_IGNORED` | WARNING | Có cột độc giả nhưng dòng không phải độc giả |
| `INTERNAL_ERROR` | khi ghi | Lỗi hệ thống ở dòng đó (chỉ có trong `result.failed`) |

## 5. Mã lỗi HTTP (thân là `ErrorResponse` chuẩn: `code`, `message`)

| HTTP | `code` | Khi nào |
|---|---|---|
| 400 | `IMPORT_FILE_REQUIRED` | Thiếu part `file` hoặc file rỗng |
| 413 | `IMPORT_FILE_TOO_LARGE` | File vượt 5 MB (nginx trả `413` HTML khi cả request vượt 6 MB: xử lý theo mã HTTP) |
| 415 | `IMPORT_UNSUPPORTED_FILE` | Không phải `.xlsx` thường (sai chữ ký, `.xls`, `.xlsm`/macro, file mã hóa, `.xlsb`) |
| 422 | `IMPORT_FILE_UNSAFE`, `IMPORT_FILE_CORRUPT` | File không an toàn / không đọc được cấu trúc |
| 422 | `IMPORT_TEMPLATE_MISMATCH` | Thiếu sheet `NguoiDung` hoặc cột bắt buộc ở dòng tiêu đề, hoặc file của loại khác; `details` liệt kê cột thiếu |
| 422 | `IMPORT_EMPTY` | Không có dòng dữ liệu |
| 422 | `IMPORT_TOO_MANY_ROWS` | Hơn 5.000 dòng |
| 404 | `IMPORT_NOT_FOUND` | Phiên không có, hết hạn, đã hủy hoặc không phải của người gọi |
| 409 | `IMPORT_INVALID_STATE` | Hủy khi đang `COMMITTING`; tải `credentials` trước `COMMITTED` |
| 409 | `IMPORT_NOTHING_TO_COMMIT` | Confirm khi không có dòng `VALID` |
| 410 | `IMPORT_CREDENTIALS_GONE` | File mật khẩu tạm đã tải hoặc hết hạn |
| 429 | `IMPORT_SESSION_LIMIT` | Đã có 3 phiên đang mở |
| 429 | `IMPORT_BUSY` | Hệ thống đang bận; `details.retryAfterSeconds` (header `Retry-After` bị `lms-api` bỏ, dùng trường này) |
| 503 | `IMPORT_STORAGE_UNAVAILABLE` | Redis (kho phiên) không dùng được |
| 403 | `TENANT_REQUIRED`, `MODULE_NOT_ENABLED`, thiếu khóa | Như mọi API khác |

## 6. Hành vi khi ghi

- Mỗi dòng `VALID` tạo trong một transaction riêng (dùng chung luật của `POST /api/users` và cấp thẻ độc giả): hồ sơ, credential `mustChangePassword`, membership của tenant hiện tại, và (nếu độc giả) thẻ. Trùng phát sinh giữa lúc preview và confirm (người khác vừa tạo) làm dòng đó bị bỏ qua (`skippedDuplicate`), không làm hỏng dòng khác. Tạo 5.000 người chạy nền, mật khẩu bcrypt nên có thể mất vài phút.
- Một bản ghi audit tóm tắt mỗi lần confirm (`UsersImportedEvent`: `importId`, người bấm, số tạo/bỏ qua/lỗi); **không** có tên đăng nhập, email hay mật khẩu.
- Quyền mới của role chỉ có hiệu lực ở lần đăng nhập hoặc làm mới token kế tiếp.

## 7. Khóa quyền (identity kiểm trên token; `lms-api` không kiểm)

Khóa động theo ADR-0003, **tên lớp con** `UserImportController`; không đổi tên lớp/method (đổi khóa, role đã gán mất quyền). Hai khóa `upload` và `confirm` luôn gán cùng nhau.

| Hành động | Khóa | Method + đường dẫn (FE) |
|---|---|---|
| Upload | `UserImportController/upload` | `POST /api/identity/imports/users` |
| Xác nhận | `UserImportController/confirm` | `POST /api/identity/imports/users/{importId}/confirm` |
| Tải mật khẩu tạm | `UserImportController/credentials` | `GET /api/identity/imports/users/{importId}/credentials` |

Migration `V31__import_permissions.sql` (thư mục `identity` của system-service) seed các khóa này vào vai trò mẫu `LIBRARY_ADMIN` (cả ba khóa). **Không backfill** role của tenant đang chạy: tenant admin gán tay ba khóa cho role cần dùng. Role có `is_admin` bỏ qua kiểm khóa. Template, `get`, `rows`, `cancel`, `error-file` không có khóa riêng (chỉ đăng nhập và kiểm chủ phiên).
