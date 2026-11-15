# API đầu sách: sửa đầu sách (UC11)

Hợp đồng cho FE (qua nginx 8080 -> `lms-api` 8085, BFF chuyển tiếp sang `catalog-service` `/internal/**`).

## PUT /api/books/{id}

Sửa thông tin đầu sách. Thay toàn bộ 4 trường (không phải PATCH). Không đụng bản sao (`book-copies`) và số bản sao.

Request body (JSON):

| Trường | Kiểu | Ràng buộc |
|---|---|---|
| `name` | string | bắt buộc, không rỗng, tối đa 255 |
| `author` | string | bắt buộc, không rỗng, tối đa 255 |
| `isbn` | string hoặc null | tối đa 20; null/rỗng = xóa ISBN; duy nhất trong tenant |
| `coverPrice` | number | bắt buộc, >= 0 (cơ sở tính phí bồi thường) |

Response 200: cùng dạng `TitleView` như `GET /api/books/{id}`:
`{ id, name, author, isbn, coverPrice, availableCopies }`.

Lỗi:

| Mã HTTP | `code` | Khi nào |
|---|---|---|
| 400 | `VALIDATION_FAILED` | body sai ràng buộc, hoặc `id` không phải UUID |
| 401 | | không có token |
| 403 | | thiếu khóa quyền (kèm `TENANT_REQUIRED` nếu token không có tenant) |
| 404 | `TITLE_NOT_FOUND` | không có đầu sách (hoặc thuộc tenant khác) |
| 409 | `DUPLICATE_ISBN` | ISBN đã thuộc đầu sách khác |
| 422 | `INVALID_TITLE` | vi phạm quy tắc domain |

Khóa quyền (phân quyền động, ADR-0003): `TitleController/update`. Khóa tự đăng ký lần đầu endpoint được gọi; admin bỏ qua kiểm khóa, role khác phải được gán khóa này (hiệu lực khi đăng nhập/refresh token kế tiếp).

Lưu ý: chưa có optimistic lock (bảng `titles` không có cột version); hai người sửa cùng lúc thì bản ghi sau thắng.

Các khóa quyền khác của nhóm này: `TitleController/create` (POST /api/books), `TitleController/search` (GET /api/books), `TitleController/get` (GET /api/books/{id}).
