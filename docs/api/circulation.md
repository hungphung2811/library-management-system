# API circulation (lms-api -> circulation-service)

FE chỉ gọi `/api/**` qua nginx (8080); `lms-api` chuyển tiếp 1-1 sang `/internal/**` của circulation-service (8083), kèm Bearer và `X-Correlation-Id`. Lỗi 4xx của service giữ nguyên mã và body `{code, message, details}`; mất kết nối trả 503 `DEPENDENCY_UNAVAILABLE`.

## Endpoint mới (đợt này)

| Method | Đường dẫn FE | Chuyển tiếp tới | Body / query | Trả về |
|---|---|---|---|---|
| GET | `/api/borrowings/mine` | `/internal/borrowings/mine` | không | `BorrowingView[]` của người đăng nhập |
| GET | `/api/borrowings?readerId=` | `/internal/borrowings?readerId=` | `readerId` (UUID, bắt buộc; thiếu thì 400) | `BorrowingView[]` của độc giả |
| POST | `/api/borrowings/returns` | `/internal/borrowings/returns` | `{bookCopyId?, barcode?, condition?}` | `BorrowingView` |
| POST | `/api/borrowings/losses` | `/internal/borrowings/losses` | `{bookCopyId?, barcode?}` | `BorrowingView` |
| GET | `/api/fines/mine` | `/internal/fines/mine` | không | `ReaderFinesView` |
| GET | `/api/fines?readerId=` | `/internal/fines?readerId=` | `readerId` (UUID, bắt buộc) | `ReaderFinesView` |
| POST | `/api/fines/payments` | `/internal/fines/payments` | `{readerId}` (bắt buộc) | `CollectFinesResult` |

Giữ nguyên: `POST /api/borrowings`, `POST /api/borrowings/{id}/returns` (body `{bookCopyId}`), `POST /api/borrowings/{id}/renewals`, `GET /api/borrowings/{id}`. `/mine` và `/returns`, `/losses` là đường dẫn chữ nên được ưu tiên hơn `/{id}`.

Chỉ định bản sao: truyền `bookCopyId` hoặc `barcode` (ít nhất một; thiếu cả hai do service trả 422). `condition` của trả sách: `GOOD` (mặc định) hoặc `DAMAGED`; giá trị khác bị `lms-api` chặn 400 `VALIDATION_FAILED`.

## Thay đổi phản hồi `BorrowingView` (tương thích ngược, chỉ thêm trường)

```
BorrowingView { id, userId, status, borrowDate, dueDate, renewCount,
                items: [{bookCopyId, returnDate, condition}],   // condition: GOOD|DAMAGED|LOST|null (còn mượn)
                fines: FineView[] }                             // phạt vừa lập bởi trả/báo mất, còn lại []
FineView { id, readerId, borrowingId, bookCopyId, reason, amount, status, createdAt, paidAt }
ReaderFinesView { readerId, totalUnpaid, fines: FineView[] }
CollectFinesResult { readerId, collectedCount, totalCollected, fines: FineView[] }
```

Danh sách `/api/borrowings` và `/api/borrowings/mine` hiện không phân trang và không có tham số lọc ngoài `readerId` (đúng như use case của circulation-service).

## Thay đổi phá vỡ: xem phiếu mượn theo id (DA-3, vá IDOR)

Trước đây `GET /api/borrowings/{id}` trả phiếu của bất kỳ ai cho mọi người đăng nhập. Nay:

- `GET /api/borrowings/{id}` (khóa `BorrowingController/get` giữ nguyên, vẫn `@AuthenticatedOnly`): chỉ trả phiếu của chính người gọi (`userId` từ token, điều kiện sở hữu nằm trong truy vấn). Phiếu của người khác, của tenant khác hoặc không tồn tại đều trả cùng `404 BORROWING_NOT_FOUND` (không dùng 403 để không lộ tồn tại).
- `GET /api/borrowings/{id}/detail` (MỚI, khóa MỚI `BorrowingController/getAny`): nhân viên xem phiếu bất kỳ trong tenant; thiếu khóa thì 403. Cùng `BorrowingView`, không đổi DTO.
- Lựa chọn thiết kế: tài liệu đánh giá (customer-api-data-authorization A6/DA-3) đề xuất ngược lại (`get` thành khóa nhân viên, `mine/{id}` cho độc giả). Đã chọn giữ `get` cho "phiếu của tôi" để role độc giả hiện có không mất quyền và FE độc giả không phải đổi đường dẫn; đường mới chỉ cho nhân viên.
- FE phải đổi: màn hình nhân viên/thủ thư đang gọi `GET /api/borrowings/{id}` để xem phiếu của độc giả khác phải chuyển sang `/api/borrowings/{id}/detail` và admin gán khóa `BorrowingController/getAny` cho role thủ thư/nhân viên (admin `admin=true` bỏ qua kiểm khóa, hiệu lực sau đăng nhập/refresh). Màn hình độc giả xem phiếu của mình không đổi.
- Seed (migration identity V18): khóa `BorrowingController/getAny` vào `permissions` và vào mẫu role `SUPER_ADMIN`, `LIBRARY_ADMIN`, `LIBRARIAN` (không `DEPARTMENT_MANAGER`, `MEMBER`); tenant TẠO MỚI nhận. Tenant HIỆN CÓ: backfill chỉ thêm khóa vào role không phải admin đang giữ `BorrowingController/returnBook` (khôi phục năng lực xem phiếu cũ cho role nhân viên, bất kể tên role). Role nhân viên khác tenant đã tùy biến (không giữ `returnBook`) phải admin gán tay.
- Không đổi: `mine`, `byReader`, `search`, các khóa khác. `returnBook`/`renew` theo id vẫn cần khóa riêng như cũ.

## Mã lỗi thường gặp (từ circulation-service)

| HTTP | code | Khi |
|---|---|---|
| 400 | `VALIDATION_FAILED` | body/tham số sai (kể cả `condition` ngoài GOOD/DAMAGED, thiếu `readerId`) |
| 403 | (không body hoặc `DOWNSTREAM_REJECTED`) | thiếu khóa quyền |
| 404 | `BORROWING_NOT_FOUND`, mã NotFound khác | không có phiếu/bản sao |
| 409 | mã của `BorrowingRejectedException`, `BORROWING_LIMIT_EXCEEDED` | vi phạm quy tắc nghiệp vụ |
| 422 | `INVALID_BORROWING`, `INVALID_FINE` | dữ liệu không hợp lệ (vd thiếu bookCopyId lẫn barcode) |
| 503 | `DEPENDENCY_UNAVAILABLE` | service phụ thuộc hoặc circulation-service không sẵn sàng |

## Khóa quyền cho FE/quản trị

`lms-api` không kiểm quyền (ADR-0005); circulation-service kiểm trên token chuyển tiếp. Khóa là của controller bên circulation-service (đối chiếu code thật). Khóa chưa gán cho role nào thì người không phải admin bị 403; admin (`admin=true`) bỏ qua. Gán khóa cho role (thường là role thủ thư) tại quản lý role; hiệu lực sau lần đăng nhập/refresh kế tiếp.

| Endpoint FE | Khóa | Ai cần |
|---|---|---|
| `GET /api/borrowings/mine` | `BorrowingController/mine`, `@AuthenticatedOnly` | mọi người đăng nhập, không cần gán |
| `GET /api/borrowings/{id}` | `BorrowingController/get`, `@AuthenticatedOnly` | mọi người đăng nhập, nhưng chỉ xem được phiếu của chính mình (xem mục DA-3) |
| `GET /api/borrowings/{id}/detail` | `BorrowingController/getAny` (khóa MỚI) | nhân viên được gán khóa, xem phiếu bất kỳ trong tenant |
| `GET /api/borrowings?readerId=` | `BorrowingController/byReader` | role thủ thư/nhân viên |
| `POST /api/borrowings/returns` | `BorrowingController/returnCopy` | role thủ thư/nhân viên |
| `POST /api/borrowings/{id}/returns` | `BorrowingController/returnBook` | role thủ thư/nhân viên |
| `POST /api/borrowings/losses` | `BorrowingController/reportLost` | role thủ thư/nhân viên |
| `POST /api/borrowings` | `BorrowingController/borrow` | role thủ thư/nhân viên |
| `POST /api/borrowings/{id}/renewals` | `BorrowingController/renew` | role được gán |
| `GET /api/fines/mine` | `FineController/mine`, `@AuthenticatedOnly` | mọi người đăng nhập |
| `GET /api/fines?readerId=` | `FineController/byReader` | role thủ thư/nhân viên |
| `POST /api/fines/payments` | `FineController/collect` | role thủ thư/nhân viên |

Lưu ý: các khóa mới (`returnCopy`, `reportLost`, `byReader`, `FineController/*`) không có trong seed V9; chúng tự đăng ký vào bảng `permissions` lần đầu được gọi, sau đó mới gán được cho role. Tên role cụ thể là dữ liệu, không hardcode.
