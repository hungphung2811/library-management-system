# API đầu sách: thông tin bổ sung (10 trường mới)

Cập nhật 2026-11-18. Hợp đồng cho FE (qua nginx 8080 -> `lms-api` 8085 -> `catalog-service` `/internal/books`). Mọi trường đều TÙY CHỌN (null/vắng = chưa nhập), không backfill dữ liệu cũ. Không đổi đường dẫn, không đổi khóa quyền (`TitleController/create|update|get|search`).

Migration: `services/system-service/.../db/migration/catalog/V7__titles_extra_fields.sql` (thêm 11 cột vào `titles`, có CHECK tương ứng).

## Các trường

| Trường (JSON) | Kiểu | Ràng buộc |
|---|---|---|
| `subtitle` | string | tối đa 255 |
| `publicationYear` | integer | 1000..(năm hiện tại + 1) |
| `edition` | string | tối đa 50 (ví dụ `"Tái bản lần thứ 3"`) |
| `language` | string | 2..8 chữ cái thường a-z (`vi`, `en`, `fr`, `ja`...); lms-api từ chối chữ hoa (400) |
| `pageCount` | integer | 1..20000 |
| `format` | string (enum) | `PAPERBACK` \| `HARDCOVER` \| `EBOOK` |
| `translator` | string | tối đa 255 |
| `series` | string | tối đa 255 |
| `seriesNumber` | integer | >= 1, chỉ hợp lệ khi có `series` (không thì 422 `INVALID_TITLE`) |
| `coverImageUrl` | string | tối đa 500, chỉ `http://` hoặc `https://` |
| `tags` | array of string | tối đa 10 nhãn, mỗi nhãn tối đa 50 ký tự; catalog cắt khoảng trắng và bỏ nhãn trùng (không phân biệt hoa thường), giữ nhãn xuất hiện trước |

Chuỗi rỗng/chỉ khoảng trắng được coi là chưa nhập (lưu null).

## Request (POST /api/books, PUT /api/books/{id})

Thêm các trường trên vào body hiện có (`name`, `author`, `isbn`, `coverPrice`, `publisherId`, `categoryId`, `authorIds`). Body cũ không có trường mới vẫn hợp lệ. LƯU Ý: PUT vẫn là thay toàn bộ, nên PUT thiếu trường mới nghĩa là XÓA giá trị đã có; FE sửa đầu sách phải gửi lại đủ các trường mới đang hiển thị.

Sai ràng buộc cú pháp (độ dài, khoảng giá trị, enum, `language`, `coverImageUrl`): `400 VALIDATION_FAILED`. Sai quy tắc nghiệp vụ (ví dụ `seriesNumber` không có `series`, URL không phải http/https): `422 INVALID_TITLE`.

## Response (GET /api/books, GET /api/books/{id}, POST, PUT): `TitleView`

Tên trường JSON thêm vào cuối `TitleView` (sau `authors`):
`subtitle, publicationYear, edition, language, pageCount, format, translator, series, seriesNumber, coverImageUrl, tags`.
Chưa nhập thì là `null`, riêng `tags` là mảng rỗng `[]`.

Ví dụ một phần tử của `GET /api/books`:

```json
{"id":"...","name":"Harry Potter và Hòn đá Phù thủy","author":"J.K. Rowling","isbn":"9786041000011","coverPrice":120000,
 "availableCopies":2,"tenantId":"...","publisherId":"...","publisherName":"NXB Trẻ","categoryId":"...","categoryName":"Văn học",
 "authors":[{"id":"...","name":"J.K. Rowling"}],
 "subtitle":null,"publicationYear":2017,"edition":"Tái bản lần thứ 8","language":"vi","pageCount":368,"format":"PAPERBACK",
 "translator":"Lý Lan","series":"Harry Potter","seriesNumber":1,"coverImageUrl":"https://picsum.photos/seed/lms-demo-21/300/450",
 "tags":["giả tưởng","phép thuật","thiếu niên"]}
```

## Storefront công khai (customer-api qua sales)

`GET /public/t/{code}/books` (mỗi phần tử) và `GET /public/t/{code}/books/{bookRef}` trả thêm cùng 11 tên trường trên (`subtitle, publicationYear, edition, language, pageCount, format, translator, series, seriesNumber, coverImageUrl, tags`); `tags` là `[]` khi chưa có. Vẫn KHÔNG có ISBN, tồn kho, giá nhập, `tenantId`. Gợi ý tìm kiếm (`suggestions`) và preview không đổi. Chi tiết: `docs/api/customer-api.md` mục "Danh mục sách" và `docs/api/sales.md` (`StorefrontController`).

Đường sales nhận dữ liệu từ catalog qua `POST|GET /internal/books/lookup` (`TitleLookupView`, thêm cùng 11 trường; không đổi khóa quyền `TitleController/lookup|lookupByKeyword`).

## Dữ liệu demo (ADR-0018)

64 đầu sách demo được thay bằng sách thật (tên, tác giả, nhà xuất bản, năm 2005-2024, số trang 120-700, dịch giả, bộ sách, nhãn, `language` vi/en). Tenant demo đã seed từ bản cũ: chạy lại `scripts\seed-demo.ps1 -Only catalog` sẽ làm mới đúng các đầu sách demo chưa có thông tin bổ sung (tên, tác giả, nhà xuất bản, thông tin bổ sung; giữ id, ISBN, giá bìa); đầu sách đã có thông tin bổ sung thì bỏ qua. Tác giả giả cũ (`Nguyễn Văn An`...) còn lại trong danh mục tác giả, không bị xóa.
