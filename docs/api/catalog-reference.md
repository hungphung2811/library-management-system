# Danh mục chuẩn của catalog (tác giả, nhà xuất bản, thể loại, vị trí kệ)

Migration: `services/system-service/.../db/migration/catalog/V5__reference_catalogs.sql`. Dữ liệu cũ không backfill; mọi tham chiếu mới đều tùy chọn, các trường chuỗi cũ (`author` ...) giữ nguyên.

Tất cả đi qua `lms-api` (`/api/**`), quyền do catalog-service kiểm theo khóa `TênController/tênMethod` (admin gán role cho khóa; khóa tự đăng ký lần đầu được gọi).

## Danh mục: `/api/authors`, `/api/publishers`, `/api/categories`

| Method | Đường dẫn | Body / query | Response | Khóa quyền (ví dụ AuthorController) |
|---|---|---|---|---|
| GET | `/api/{danh-mục}` | `keyword`, `active`, `parentId` (chỉ categories), `page` (từ 0), `size` (1..100, mặc định 20) | `{items:[ReferenceView], total, page, size}` | `AuthorController/search` |
| GET | `/api/{danh-mục}/options` | `keyword` | `[{id, name, parentId}]` (chỉ mục active, tối đa 200, theo tên) | `AuthorController/options` |
| GET | `/api/{danh-mục}/{id}` | | `ReferenceView` | `AuthorController/get` |
| POST | `/api/{danh-mục}` | `{name (bắt buộc, <=255), description (<=500), parentId (chỉ categories)}` | 201 `ReferenceView` | `AuthorController/create` |
| PUT | `/api/{danh-mục}/{id}` | như POST (thay toàn bộ) | `ReferenceView` | `AuthorController/update` |
| PUT | `/api/{danh-mục}/{id}/status` | `{active: true/false}` | `ReferenceView` | `AuthorController/setStatus` |

Controller: `AuthorController`, `PublisherController`, `CategoryController` (cùng 6 method search, options, get, create, update, setStatus).

`ReferenceView`: `{id, name, description, parentId, parentName, active, tenantId}` (`parentId`/`parentName` chỉ có ở thể loại).

Quy tắc: tên được chuẩn hóa (NFC, bỏ khoảng trắng thừa), duy nhất theo tenant không phân biệt hoa thường. Ngừng dùng (`active=false`) thay cho xóa cứng; không có endpoint xóa. Thể loại tối đa 2 cấp.

## Vị trí kệ: `/api/shelf-locations` (module LENDING)

Giống bảng trên nhưng trường là `code` (<=64) thay cho `name`:
- Body POST/PUT: `{code, description}`.
- Response: `{id, code, description, active, tenantId}`; trang: `{items, total, page, size}`; options: `[{id, code}]`.
- Khóa: `ShelfLocationController/search|options|get|create|update|setStatus`.

## Mã lỗi

| HTTP | code | Khi |
|---|---|---|
| 404 | `AUTHOR_NOT_FOUND`, `PUBLISHER_NOT_FOUND`, `CATEGORY_NOT_FOUND`, `SHELF_LOCATION_NOT_FOUND` | id không có (cũng khi gán id không tồn tại vào sách/bản sao) |
| 409 | `DUPLICATE_AUTHOR_NAME`, `DUPLICATE_PUBLISHER_NAME`, `DUPLICATE_CATEGORY_NAME`, `DUPLICATE_SHELF_LOCATION_CODE` | trùng tên/mã |
| 422 | `INVALID_AUTHOR` / `INVALID_PUBLISHER` / `INVALID_CATEGORY` / `INVALID_SHELF_LOCATION` | tên rỗng/quá dài, mô tả quá dài, cha cho loại không phải thể loại, quá 20 tác giả/sách |
| 422 | `CATEGORY_CYCLE`, `CATEGORY_TOO_DEEP` | vòng cha-con; quá 2 cấp |
| 422 | `AUTHOR_INACTIVE`, `PUBLISHER_INACTIVE`, `CATEGORY_INACTIVE`, `SHELF_LOCATION_INACTIVE` | gán mục đã ngừng dùng (giá trị đang có sẵn trên sách/bản sao vẫn được giữ) |
| 400 | `VALIDATION_FAILED` | body sai |

## Thay đổi của `/api/books`

- `POST /api/books`, `PUT /api/books/{id}`: thêm trường tùy chọn `publisherId`, `categoryId`, `authorIds` (mảng UUID, tối đa 20, thứ tự giữ nguyên). Chuỗi `author` vẫn bắt buộc. PUT thay toàn bộ: không gửi các trường này nghĩa là gỡ phân loại chuẩn.
- Response (`TitleView`) thêm: `publisherId`, `publisherName`, `categoryId`, `categoryName`, `authors: [{id, name}]` (null / rỗng nếu chưa gán).
- `GET /api/books` thêm query `categoryId`, `publisherId` (khớp đúng id, không gồm thể loại con).

## Thay đổi của `/api/book-copies`

- `POST /api/book-copies`: thêm `shelfLocationId` (tùy chọn).
- `CopyView` (list/tạo/đổi trạng thái) và `CopyDetailsView` (tra theo id/mã vạch) thêm `shelfLocationId`, `shelfLocationCode`.
- Mới: `PUT /api/book-copies/{id}/shelf-location` body `{shelfLocationId}` (null = gỡ) trả `CopyView`; khóa `BookCopyController/moveShelfLocation`.
