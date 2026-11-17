# Danh sách phiếu mượn có phân trang: `GET /api/borrowings/search`

Màn hình quản lý (thủ thư/admin). Chỉ thêm endpoint mới, không phá vỡ FE hiện có. Khóa quyền (động, ADR-0003): `BorrowingController/search`; role cần được gán khóa này, admin bỏ qua kiểm khóa.

## Query (đều tùy chọn)

| Tham số | Ý nghĩa |
|---|---|
| `page` | Trang, tính từ 0, mặc định 0. Âm: 400 |
| `size` | Mặc định 20, từ 1 đến 100. Ngoài khoảng: 400 (không tự kẹp) |
| `status` | `ACTIVE` (còn bản sao chưa trả) hoặc `RETURNED` (đã trả hết). Giá trị khác: 400 |
| `overdue` | `true`: phiếu còn bản sao chưa trả và `dueDate` trước hôm nay. Kết hợp `status=RETURNED` cho kết quả rỗng |
| `readerId` | Chỉ phiếu của độc giả này (cùng tên tham số với `GET /api/borrowings`, trường kết quả vẫn là `userId`) |
| `tenantId` | Chỉ có tác dụng với platform admin không tenant (xem `cross-tenant-read.md`); người khác bị bỏ qua |

## Response 200

```json
{ "items": [BorrowingView], "total": 42, "page": 0, "size": 20 }
```

`BorrowingView` như `GET /api/borrowings` (có `tenantId`); `items[].fines` luôn rỗng ở danh sách này. Sắp xếp `borrowDate` giảm dần, rồi `id` tăng dần. `total` là tổng số phiếu khớp bộ lọc.

Lỗi tham số: 400 `VALIDATION_FAILED` (BFF chuyển nguyên). Không token: 401; thiếu khóa quyền: 403.
