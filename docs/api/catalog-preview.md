# API catalog: mô tả và đoạn đọc thử của đầu sách (ADR-0011 B-C6)

Nguồn: ADR-0011 mục 2.6, 2.8, 2.12; đề bài `docs/requirements/2026-11-16-customer-api.md` 4.7, 6.5, 8. Endpoint nội bộ của catalog-service (`/internal/books`). Đường cho FE nhân viên (`/api/books/{id}/preview`) do lms-api ánh xạ 1-1 (B-C6L).

## Dữ liệu

Hai cột tùy chọn trong `titles` (migration `catalog/V4__titles_description_preview.sql`): `description` (tối đa 500 ký tự), `preview_text` (tối đa 2000 ký tự). Văn bản thuần, trả nguyên văn (không escape HTML ở server). FE hiển thị như text (không `innerHTML`), xuống dòng bằng CSS `white-space: pre-wrap` (thống nhất với `catalog-books.md`; giữ cả xuống dòng và khoảng trắng liên tiếp).

Chuẩn hóa ở domain `Title.withPreview` (không ở controller): Unicode NFC, `\r\n` và `\r` thành `\n`, bỏ ký tự điều khiển trừ `\n` và `\t`, cắt khoảng trắng đầu cuối, chuỗi rỗng hoặc chỉ khoảng trắng thành `null`. Độ dài đếm theo code point (emoji tính 1), sau khi chuẩn hóa. `hasPreview = previewText != null`.

## GET /internal/books/{id}/preview

Khóa động `TitleController/preview`, có `@StorefrontAccessible` (token dịch vụ storefront vào được, chỉ khi có `tenantId`; người dùng vẫn cần khóa). Không `@RequiresModule` (catalog lõi).

Response 200:

```json
{ "id": "<uuid>", "name": "Clean Code", "description": "..." , "previewText": "..." }
```

`description` và `previewText` là `null` nếu chưa nhập. Không có `isbn`, `coverPrice`, `tenantId`. `404 TITLE_NOT_FOUND` khi không có hoặc thuộc tenant khác (RLS).

## PUT /internal/books/{id}/preview

Khóa động `TitleController/updatePreview`, **không** `@StorefrontAccessible` (token dịch vụ nhận 403). Cần gán cho role nhân viên được nhập preview (cùng `TitleController/preview` để mở được màn hình sửa).

Request (thay cả hai trường; thiếu, `null` hoặc rỗng = xóa):

```json
{ "description": "mô tả ngắn", "previewText": "đoạn đọc thử" }
```

Response 200: như GET. Ghi cuối thắng (không có version).

| Mã HTTP | `code` | Khi nào |
|---|---|---|
| 400 | `TITLE_PREVIEW_TOO_LONG` | sau chuẩn hóa, `description` quá 500 hoặc `previewText` quá 2000 code point (không lưu gì) |
| 400 | `VALIDATION_FAILED` | chặn thô thân quá lớn: `description` quá 2000 hoặc `previewText` quá 8000 đơn vị UTF-16 (cố ý không đặt `@Size` 500/2000 vì đếm UTF-16 sai với emoji) |
| 401 | | không token |
| 403 | | thiếu khóa, token dịch vụ, hoặc `TENANT_REQUIRED` |
| 404 | `TITLE_NOT_FOUND` | không có hoặc thuộc tenant khác |

## Thay đổi hợp đồng đã có (cộng thêm, không phá)

`POST /internal/books/lookup` (`TitleController/lookup`) nay có `@StorefrontAccessible`; phần tử trả thêm `description` (string hoặc null) và `hasPreview` (boolean). Không có `previewText`. Vẫn trả `isbn` cho người gọi nội bộ (sales); sales không được chuyển `isbn` ra ngoài (ADR-0011 2.6, 2.8).

```json
[ { "id": "<uuid>", "name": "...", "author": "...", "isbn": "...", "coverPrice": 250000,
    "description": null, "hasPreview": false } ]
```

## GET /internal/books/lookup?keyword=

Tra theo từ khóa (không phân biệt hoa thường, trên tên và tác giả), sắp theo tên, **tối đa 200 phần tử**, cùng dạng phần tử với `POST /lookup`. Không có `keyword` hoặc rỗng thì lấy 200 đầu sách đầu tiên theo tên. Khóa động `TitleController/lookupByKeyword`, `@StorefrontAccessible`, chỉ đọc. Sales dùng để lấy giao với mặt hàng đang bán (ADR-0011 2.6). Tên method chốt, không đổi (là khóa quyền).

## Khóa `@StorefrontAccessible` của catalog (danh sách đóng, ADR-0011 2.1)

`TitleController/lookup`, `TitleController/lookupByKeyword`, `TitleController/preview`. Mọi endpoint khác của catalog (gồm `updatePreview`, `get`, `search`) trả 403 cho token `svc`.

## Cho người gán quyền (B-C10)

Role cần khóa: `TitleController/preview`, `TitleController/updatePreview` (nhân viên nhập preview), `TitleController/lookup` và `TitleController/lookupByKeyword` (sales gọi bằng token người dùng khi nhân viên tìm kiếm mặt hàng). Không gán `updatePreview` cho mẫu Khách hàng. Quyền mới chỉ hiệu lực ở lần đăng nhập hoặc refresh kế tiếp.

## Triển khai

Chạy migration `catalog/V4` (system-service) trước khi khởi động catalog-service mới (catalog chạy `validate`, thiếu cột thì không lên).
