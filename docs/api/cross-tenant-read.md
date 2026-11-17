# Đọc xuyên tenant cho platform admin (query `tenantId`)

Nền: ADR-0008. Chỉ thêm tham số và trường, không phá vỡ FE hiện có. Chỉ áp dụng cho GET danh sách ở màn hình quản lý; mọi thao tác ghi vẫn theo tenant trong token.

## Đường dẫn trong phạm vi

`tenantId` là query tùy chọn (UUID dạng chuỗi). `lms-api` không kiểm claim và không kiểm định dạng, chỉ chuyển nguyên giá trị xuống service (chỉ khi có giá trị).

| Đường dẫn `/api/**` | Query khác | Phần tử có thêm `tenantId` |
|---|---|---|
| `GET /api/users` | `keyword`, `departmentId`, `status`, `page`, `size` | `items[]` (UserView) |
| `GET /api/departments` | `status` | DepartmentView |
| `GET /api/departments/tree` | `includeInactive` | DepartmentNodeView, mọi nút kể cả `children` |
| `GET /api/roles` | không | RoleView |
| `GET /api/permissions` | không | không đổi (dữ liệu toàn cục) |
| `GET /api/books` | `keyword`, `available` | TitleView |
| `GET /api/book-copies` | `titleId` (bắt buộc) | CopyView |
| `GET /api/borrowings` | `readerId` (bắt buộc) | BorrowingView; `fines[]` bên trong là FineView cũng có `tenantId` |
| `GET /api/fines` | `readerId` (bắt buộc) | `fines[]` (FineView); `totalUnpaid` ở chế độ mọi tenant là tổng qua mọi tenant |
| `GET /api/audit-logs` | `aggregateId`, `eventType`, `page`, `size` | `items[]` (AuditLogView) |

Không áp dụng: GET theo id, nhóm "của tôi" (`/api/borrowings/mine`, `/api/fines/mine`, `/api/reservations`, `/api/notifications/**`), `/api/platform/**` (đã xuyên tenant sẵn), mọi endpoint ghi. `tenantId` ở các endpoint đó không được chuyển xuống.

## Hành vi (do service quyết định theo token)

| Người gọi | Kết quả |
|---|---|
| Platform admin, token không có `tenantId` | Không truyền `tenantId`: dữ liệu mọi tenant. Truyền: chỉ tenant đó. Tenant không tồn tại: 200, danh sách rỗng. Sai định dạng UUID: 400 `INVALID_TENANT_ID` (BFF chuyển nguyên mã và nội dung). |
| Mọi người khác (kể cả admin tenant, platform admin đã `switch-tenant`) | Tham số bị bỏ qua hoàn toàn (kể cả sai định dạng); hành vi và mã lỗi như hiện nay. |

## Trường mới trong response

Mỗi phần tử danh sách ở bảng trên có thêm `tenantId` (UUID, luôn có kể cả khi đọc một tenant; `null` nếu service cũ chưa trả). Không có `tenantName`.

## Lưu ý cho FE

- Khóa quyền `Controller/method` KHÔNG đổi. Platform admin không cần khóa cho các endpoint này; người dùng thường vẫn cần khóa như cũ.
- FE tự ánh xạ `tenantId` sang tên tenant từ danh sách tenant đã tải cho bộ chọn tenant (`GET /api/platform/tenants`).
- Ở chế độ mọi tenant, `/api/users` có thể trả cùng một người nhiều lần (mỗi dòng là một cặp người dùng và tenant); `/api/fines` có `totalUnpaid` cộng qua mọi tenant, nên hiển thị kèm ghi chú hoặc khuyến khích chọn tenant.
- Danh sách không phân trang (books, departments, roles, book-copies, borrowings/fines) trả mọi tenant khi không truyền `tenantId`; có thể lớn.
