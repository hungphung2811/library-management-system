# API catalog: tra nhiều đầu sách một lần (B1.4, cho sales-service)

Endpoint nội bộ của catalog-service, không có đường `/api/**` ở lms-api (lms-api không cần ánh xạ; FE không gọi). Người gọi là sales-service (ADR-0010 mục 2.2), gửi kèm token người dùng chuyển tiếp.

## POST /internal/books/lookup

Khóa quyền động: `TitleController/lookup` (không `@AuthenticatedOnly`, không `@RequiresModule`: thuộc lõi dùng chung). Lý do: thiết kế ADR-0010 chọn "khóa động gán cho role" và đã dự kiến `TitleController/lookup` trong `role_template_permissions` của các role SALES cần tra đầu sách (Thu ngân, Thủ kho, Quản lý cửa hàng). Admin tenant bỏ qua kiểm khóa. Token phải có `tenantId`, nếu không 403 `TENANT_REQUIRED`.

Việc của agent khác: gán khóa này vào role template SALES (migration identity), role khách chỉ cần khóa nếu luồng của họ gọi catalog.

Request:

```json
{ "ids": ["<uuid>", "<uuid>"] }
```

| Trường | Kiểu | Ràng buộc |
|---|---|---|
| `ids` | mảng UUID | bắt buộc, 1 đến 100 phần tử, không có phần tử null |

Response 200, mảng theo thứ tự `ids` (id trùng chỉ trả một lần):

```json
[ { "id": "<uuid>", "name": "Clean Code", "author": "Robert Martin", "isbn": "9780132350884", "coverPrice": 250000 } ]
```

`isbn` có thể null. Không có `tenantId` và `availableCopies`.

Id không tồn tại hoặc thuộc tenant khác (lọc tenant của Hibernate/RLS) thì không có trong kết quả, không báo lỗi, không lộ. Người gọi tự so danh sách trả về với danh sách đã gửi (sales: thiếu thì `409 TITLE_NOT_AVAILABLE`).

| Mã HTTP | `code` | Khi nào |
|---|---|---|
| 400 | `VALIDATION_FAILED` | `ids` thiếu, rỗng, quá 100, có null (phần tử không phải UUID cũng 400 nhưng `code` do Spring quyết định, có thể khác) |
| 401 | | không có token |
| 403 | | thiếu khóa `TitleController/lookup` (hoặc `TENANT_REQUIRED`) |

## GET /internal/books/new-authors

Tác giả có sách mới cho storefront: customer-api ghép với top sách bán chạy của sales để dựng mục "tác giả nổi bật". Không có đường `/api/**` ở lms-api. Khóa `NewAuthorsController/list`; `@StorefrontAccessible` nên token dịch vụ storefront (`svc`) gọi được và không kiểm khóa (token người dùng vẫn cần khóa, admin tenant bỏ qua). Token integration (`aud lms-integration`) bị 403. Token phải có `tenantId`, nếu không 403 `TENANT_REQUIRED`.

Query (một record, ngoài khoảng thì chặn lại chứ không trả 400):

| Tham số | Mặc định | Giới hạn |
|---|---|---|
| `days` | 90 | 1..365 |
| `limit` | 200 | 1..200 |

Response 200, mảng chuỗi tên tác giả (không id, giá, ISBN):

```json
["Nguyễn Nhật Ánh", "Robert Martin"]
```

Quy tắc: lấy `titles.author` của đầu sách có `created_at` trong `days` ngày gần nhất của tenant (catalog không có khái niệm "đang bán", nên tính mọi đầu sách của tenant). Gộp tác giả không phân biệt hoa thường và khoảng trắng đầu/cuối; trả tên hiển thị gốc (đã cắt khoảng trắng đầu/cuối) của ấn bản mới nhất của họ; sắp theo thời điểm tạo mới nhất giảm dần. Một câu SQL, index `idx_titles_tenant_created_at` (migration catalog V11). Không cache.
