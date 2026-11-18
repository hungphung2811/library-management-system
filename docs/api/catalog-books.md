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

Từ 2026-11-18 đầu sách có thêm 10 trường bổ sung tùy chọn trong body và response (`subtitle`, `publicationYear`, `edition`, `language`, `pageCount`, `format`, `translator`, `series`, `seriesNumber`, `coverImageUrl`, `tags`): xem `docs/api/catalog-title-extra-fields.md`. PUT thiếu các trường này nghĩa là xóa chúng.

Các khóa quyền khác của nhóm này: `TitleController/create` (POST /api/books), `TitleController/search` (GET /api/books), `TitleController/get` (GET /api/books/{id}).

## GET /api/books/{id}/preview và PUT /api/books/{id}/preview (mô tả ngắn và preview)

Cập nhật 2026-11-17 (ADR-0011 mục 2.12, O11). Nhân viên tenant nhập mô tả ngắn và đoạn preview cho đầu sách; storefront công khai đọc qua customer-api (không đi đường này). BFF chỉ ánh xạ 1-1 sang `catalog-service` `GET|PUT /internal/books/{id}/preview`.

✅ Đã đối chiếu với code catalog thật (B-C6): đường dẫn `/internal/books/{id}/preview`, tên trường `{id, name, description, previewText}`, PUT trả 200 kèm thân giống GET đều khớp. Lỗi bổ sung từ catalog: `400 VALIDATION_FAILED` khi `description` quá 2000 hoặc `previewText` quá 8000 đơn vị UTF-16 (chặn thô), còn giới hạn nghiệp vụ 500/2000 ký tự (code point, sau chuẩn hóa) trả `400 TITLE_PREVIEW_TOO_LONG`. Chi tiết phía catalog: `docs/api/catalog-preview.md`. ⚠️ Chưa gọi thử lms-api sang catalog thật (chỉ đọc code và test mock).

### GET /api/books/{id}/preview

Response 200: `{ id, name, description, previewText }`. `description` và `previewText` là string hoặc `null` (chưa nhập). Không có ISBN, giá bìa, `tenantId`.

### PUT /api/books/{id}/preview

Thay đúng hai trường (không phải PATCH): gửi thiếu hoặc `null` hoặc chuỗi rỗng/khoảng trắng = xóa trường đó. Body bắt buộc có (có thể là `{}` để xóa cả hai).

Request body (JSON):

| Trường | Kiểu | Ràng buộc |
|---|---|---|
| `description` | string hoặc null | tùy chọn; tối đa 500 ký tự (theo ADR, do catalog kiểm); null/rỗng = xóa |
| `previewText` | string hoặc null | tùy chọn; tối đa 2000 ký tự (theo ADR, do catalog kiểm); null/rỗng = xóa |

`lms-api` không kiểm độ dài; catalog quyết và trả lỗi. Catalog cắt khoảng trắng đầu/cuối; chuỗi rỗng coi là chưa có.

Response 200: như GET (giá trị sau chuẩn hóa).

Lỗi (chuyển nguyên từ catalog):

| Mã HTTP | `code` | Khi nào |
|---|---|---|
| 400 | `TITLE_PREVIEW_TOO_LONG` | vượt giới hạn độ dài (PUT) |
| 400 | `VALIDATION_FAILED` | `id` không phải UUID, hoặc thiếu body JSON (PUT) |
| 401 | | không có token |
| 403 | | thiếu khóa quyền (kèm `TENANT_REQUIRED` nếu token không có tenant) |
| 404 | `TITLE_NOT_FOUND` | không có đầu sách (hoặc thuộc tenant khác) |

Khóa quyền (phân quyền động, ADR-0003, do catalog kiểm): GET `TitleController/preview`, PUT `TitleController/updatePreview`. Hai khóa tách riêng với `TitleController/update`; role nhân viên được nhập preview phải được gán tay (hiệu lực khi đăng nhập/refresh token kế tiếp). Admin bỏ qua kiểm khóa.

Lưu ý cho FE: cả hai trường là **văn bản thuần**. Hiển thị như text (ví dụ `textContent`, `{{ }}` của Angular), không dùng `innerHTML`/`bypassSecurityTrust*`; xuống dòng hiển thị bằng CSS `white-space: pre-wrap`. Ký tự `<`, `>` được lưu và trả nguyên văn, không bị mã hóa.
