# Nhập đầu sách, bản sao và danh mục từ Excel (lms-api -> catalog-service)

Năm loại của cụm nhập master data hàng loạt (ADR-0024, `docs/decisions/0024-nhap-master-data-tu-excel-preview-confirm.md`) thuộc catalog: `titles` (đầu sách + bản sao, hai sheet), `authors`, `publishers`, `categories`, `shelf-locations`. Luồng giống `identity-import.md` và `sales-import.md`: tải mẫu `.xlsx`, điền, upload, xem **preview từng dòng** (dữ liệu nằm trong Redis, chưa ghi DB), bấm Confirm mới ghi (chạy nền, theo chunk), thăm dò tiến độ.

FE gọi `/api/catalog/imports/{type}/**`; `lms-api` chuyển nguyên sang `/internal/imports/{type}/**` của catalog-service (byte và mã HTTP giữ nguyên). `{type}` chỉ nhận `titles | authors | publishers | categories | shelf-locations`, loại khác trả `404 IMPORT_TYPE_NOT_FOUND` (không chuyển tiếp). Nginx cho phép thân tối đa 6 MB ở nhóm đường dẫn `/api/*/imports`.

Giới hạn: tối đa **5.000 dòng** dữ liệu mỗi file (cộng cả hai sheet của `titles`), **5 MB** mỗi file, phiên preview sống **30 phút**, tối đa **3 phiên đang mở** mỗi người dùng trong tenant.

## 1. Endpoint (áp dụng cho cả năm loại)

| # | Method + đường dẫn | Quyền | Trả về |
|---|---|---|---|
| 1 | `GET /api/catalog/imports/{type}/template` | chỉ đăng nhập | `200` file `.xlsx` (`Content-Disposition: attachment; filename="mau-<type>.xlsx"`, no-store) |
| 2 | `POST /api/catalog/imports/{type}` | khóa `upload` của loại (mục 2) | `201` `ImportSessionView` (`VALIDATED`). `multipart/form-data`, part tên **`file`**; thiếu file `400 IMPORT_FILE_REQUIRED` |
| 3 | `GET /api/catalog/imports/{type}/{importId}` | chỉ đăng nhập + chủ phiên | `200` `ImportSessionView` (thăm dò 1-2 giây/lần khi `COMMITTING`) |
| 4 | `GET /api/catalog/imports/{type}/{importId}/rows?status=&sheet=&page=&size=` | chỉ đăng nhập + chủ phiên | `200` `RowsPage` |
| 5 | `POST /api/catalog/imports/{type}/{importId}/confirm` | khóa `confirm` của loại + chủ phiên | `202` `ImportSessionView` (đang ghi); `200` nếu phiên đã xong. Không body. Bấm lặp lại không lỗi, không ghi lại |
| 6 | `DELETE /api/catalog/imports/{type}/{importId}` | chỉ đăng nhập + chủ phiên | `204`; `409 IMPORT_INVALID_STATE` khi đang `COMMITTING` |
| 7 | `GET /api/catalog/imports/{type}/{importId}/error-file` | chỉ đăng nhập + chủ phiên | `200` `.xlsx` gồm dòng `INVALID` và `DUPLICATE` (giữ giá trị đã nhập, thêm cột "Trạng thái", "Lỗi"), upload lại được sau khi sửa |

`rows` nhận query record `status` (`VALID|INVALID|DUPLICATE`), `sheet` (`DauSach|BanSao` cho `titles`), `page` (từ 0), `size` (mặc định 50, tối đa 200); giá trị có ký tự lạ (ngoài chữ, số, `_`, `-`) trả `400 INVALID_QUERY`. Dạng `ImportSessionView`, `RowsPage` và các mã lỗi HTTP chung (`IMPORT_FILE_REQUIRED`, `IMPORT_FILE_TOO_LARGE`, `IMPORT_UNSUPPORTED_FILE`, `IMPORT_FILE_UNSAFE`, `IMPORT_FILE_CORRUPT`, `IMPORT_TEMPLATE_MISMATCH`, `IMPORT_EMPTY`, `IMPORT_TOO_MANY_ROWS`, `IMPORT_NOT_FOUND`, `IMPORT_INVALID_STATE`, `IMPORT_NOTHING_TO_COMMIT`, `IMPORT_SESSION_LIMIT`, `IMPORT_BUSY`, `IMPORT_STORAGE_UNAVAILABLE`) giống `identity-import.md` mục 5 (catalog không có `credentials`/`410`). Lỗi do BFF tự phát hiện: `404 IMPORT_TYPE_NOT_FOUND`, `400 IMPORT_FILE_REQUIRED`, `400 INVALID_QUERY`; `413 IMPORT_FILE_TOO_LARGE` khi vượt 5 MB (nginx trả `413` dạng HTML khi vượt 6 MB: FE dựa vào mã HTTP).

Mã lỗi HTTP khác theo catalog: `403 TENANT_REQUIRED`, `403 MODULE_NOT_ENABLED` (chỉ `shelf-locations`, mục 2), thiếu khóa `403`.

Chủ phiên = chính người đã upload (cùng tenant); phiên của người khác, hết hạn, đã hủy hoặc tenant khác đều `404 IMPORT_NOT_FOUND`.

`ImportSessionView.sheets[]` có `columns[] {key, label, required}` theo sheet để FE dựng bảng chung. Sau confirm có `progress {processed, total}` và `result {created, skippedDuplicate, failed}`; kết quả cuối có thể khác preview (dữ liệu đổi trong 30 phút, dòng vừa bị người khác tạo thành bỏ qua), FE hiển thị số liệu của `result`.

## 2. Khóa quyền (động, ADR-0003) và module

Khóa sinh từ tên lớp controller của catalog-service, KHÔNG đổi tên (đổi tên đổi khóa, role đã gán mất quyền):

| Loại | Upload | Confirm | Module |
|---|---|---|---|
| `titles` | `TitleImportController/upload` | `TitleImportController/confirm` | lõi (dòng `BanSao` cần `LENDING`, xem 3.1) |
| `authors` | `AuthorImportController/upload` | `AuthorImportController/confirm` | lõi |
| `publishers` | `PublisherImportController/upload` | `PublisherImportController/confirm` | lõi |
| `categories` | `CategoryImportController/upload` | `CategoryImportController/confirm` | lõi |
| `shelf-locations` | `ShelfLocationImportController/upload` | `ShelfLocationImportController/confirm` | `LENDING` |

Khóa tự đăng ký về identity lần đầu endpoint được gọi; admin bỏ qua kiểm khóa; role khác phải được gán cả hai khóa của loại (hiệu lực khi đăng nhập/refresh token kế tiếp; role mẫu và migration do việc identity làm, V31). Tải mẫu, xem phiên, xem dòng, hủy, tải file lỗi không cần khóa riêng (chỉ đăng nhập + chủ phiên). `shelf-locations` thuộc module `LENDING` cho cả endpoint không cần khóa (tenant chưa bật: `403 MODULE_NOT_ENABLED`).

## 3. Cột của file

File mẫu có sheet hướng dẫn `HuongDan`, sheet dữ liệu (dòng 1 là tiêu đề, cột bắt buộc có dấu `*`) và sheet ẩn `_meta`. Tiêu đề so theo nhãn tiếng Việt hoặc theo `key` kỹ thuật. Mọi ô chỉ là dữ liệu (chống công thức). Danh sách nhiều giá trị trong một ô cách nhau bằng `;`. Ngày: ô ngày của Excel, `dd/MM/yyyy` hoặc `yyyy-MM-dd`.

**Thứ tự nhập khuyến nghị:** tham chiếu (`authors`, `publishers`, `categories`, `shelf-locations`) trước, rồi `titles`. Tham chiếu (tác giả, nhà xuất bản, thể loại, vị trí kệ) chưa có trong thư viện thì dòng đầu sách/bản sao bị `INVALID` (`AUTHOR_NOT_FOUND`...), hệ thống **không tự tạo**. Dòng trùng khóa với dữ liệu đã có là `DUPLICATE` (bỏ qua khi confirm, không lỗi); trùng trong cùng file là `INVALID` `DUPLICATE_IN_FILE`; chỉ dòng `VALID` được ghi.

### 3.1. `titles`: hai sheet `DauSach` và `BanSao`

File có thể chỉ có một trong hai sheet (nhập riêng bản sao cho đầu sách đã có: bỏ sheet `DauSach`, hoặc để trống). Một confirm ghi đầu sách và bản sao của nó trong cùng một giao dịch chunk.

Sheet `DauSach` (đầu sách):

| `key` | Nhãn | Bắt buộc | Quy tắc |
|---|---|---|---|
| `isbn` | ISBN | không | trim; đã có trong tenant: `DUPLICATE` `ALREADY_EXISTS`; trùng dòng trước trong file: `INVALID` `DUPLICATE_IN_FILE`; **để trống** thì dòng vẫn `VALID` kèm cảnh báo `NO_DEDUPE_KEY` (nhập lại file sẽ tạo bản trùng, và không gắn được bản sao qua file) |
| `name` | Tên sách | có | không trống |
| `author` | Tác giả (hiển thị) | có | chuỗi hiển thị trên đầu sách |
| `coverPrice` | Giá bìa (VND) | có | số >= 0 |
| `authors` | Tác giả chuẩn | không | tên các tác giả cách nhau `;`, tối đa 20; so theo tên (không phân biệt hoa thường/khoảng trắng); phải có và đang hoạt động |
| `publisher` | Nhà xuất bản | không | tên, phải có và đang hoạt động |
| `category` | Thể loại | không | tên, phải có và đang hoạt động |
| `subtitle` | Phụ đề | không | tối đa 255 |
| `publicationYear` | Năm xuất bản | không | từ 1000 đến năm hiện tại + 1 |
| `edition` | Lần tái bản | không | tối đa 50 |
| `language` | Ngôn ngữ | không | 2-8 chữ cái thường (`vi`, `en`) |
| `pageCount` | Số trang | không | 1 đến 20000 |
| `format` | Định dạng | không | `PAPERBACK`, `HARDCOVER`, `EBOOK` (dropdown) |
| `translator` | Dịch giả | không | tối đa 255 |
| `series` | Bộ sách | không | tối đa 255 |
| `seriesNumber` | Tập | không | > 0, chỉ khi có bộ sách |
| `coverImageUrl` | Ảnh bìa (URL) | không | http/https, tối đa 500 |
| `tags` | Nhãn | không | cách nhau `;`, tối đa 10 nhãn, mỗi nhãn tối đa 50 ký tự |
| `description` | Mô tả | không | tối đa 500 ký tự |

Sheet `BanSao` (bản sao; trạng thái luôn `AVAILABLE`, mỗi bản sao mới phát một sự kiện bản sao sẵn sàng sau khi chunk commit):

| `key` | Nhãn | Bắt buộc | Quy tắc |
|---|---|---|---|
| `isbn` | ISBN đầu sách | có | khớp đầu sách đã có trong tenant **hoặc** dòng `DauSach` hợp lệ của cùng file. Không có: `INVALID` `TITLE_NOT_FOUND`. Dòng `DauSach` cùng ISBN bị `INVALID` (và đầu sách chưa có trong DB): `INVALID` `TITLE_ROW_INVALID`. Đầu sách không có ISBN không gắn được bản sao qua file |
| `barcode` | Mã vạch | có | tối đa 64; đã có: `DUPLICATE` `ALREADY_EXISTS`; trùng trong file: `INVALID` `DUPLICATE_IN_FILE` |
| `shelfLocationCode` | Mã vị trí kệ | không | mã đã có và đang hoạt động |
| `acquiredDate` | Ngày nhập | không | không ở tương lai |
| `acquisitionSource` | Nguồn nhập | không | `PURCHASE`, `DONATION`, `EXCHANGE`, `OTHER` |
| `purchasePrice` | Giá mua (VND) | không | số nguyên >= 0, chỉ khi nguồn là `PURCHASE` |
| `conditionGrade` | Tình trạng | không | `NEW`, `GOOD`, `FAIR`, `WORN` |
| `conditionNote` | Ghi chú tình trạng | không | tối đa 300 |
| `lastInventoryDate` | Ngày kiểm kê | không | không ở tương lai, không trước ngày nhập |

Module: dòng `BanSao` cần module `LENDING`; tenant chưa bật thì mọi dòng `BanSao` là `INVALID` `MODULE_NOT_ENABLED` (dòng `DauSach` vẫn nhập được).

### 3.2. `authors`, sheet `TacGia`

| `key` | Nhãn | Bắt buộc | Quy tắc |
|---|---|---|---|
| `name` | Tên tác giả | có | tối đa 255; khóa trùng theo tên chuẩn hóa (NFC, gộp khoảng trắng, không phân biệt hoa thường) |
| `description` | Mô tả | không | tối đa 500 |
| `birthYear` | Năm sinh | không | từ 1 đến năm hiện tại |
| `deathYear` | Năm mất | không | từ năm sinh đến năm hiện tại |
| `nationality` | Quốc tịch | không | tối đa 100 |
| `photoUrl` | Ảnh (URL) | không | http/https, tối đa 500 |
| `website` | Website | không | http/https, tối đa 500 |

### 3.3. `publishers`, sheet `NhaXuatBan`

`name` (Tên nhà xuất bản, bắt buộc, tối đa 255), `description` (tối đa 500), `country` (Quốc gia, tối đa 100), `foundedYear` (Năm thành lập, 1400 đến năm hiện tại), `website` (http/https, tối đa 500), `address` (tối đa 500). Khóa trùng: `name` chuẩn hóa.

### 3.4. `categories`, sheet `TheLoai`

| `key` | Nhãn | Bắt buộc | Quy tắc |
|---|---|---|---|
| `name` | Tên thể loại | có | tối đa 255; khóa trùng theo tên chuẩn hóa |
| `description` | Mô tả | không | tối đa 500 |
| `parentName` | Thể loại cha | không | tên thể loại cha: đã có trong thư viện (đang hoạt động, là thể loại gốc) hoặc là một dòng gốc hợp lệ khác của cùng file (có thể đứng sau dòng con). Cây tối đa 2 cấp. Khi confirm, cha được ghi trước con |

Mã lỗi riêng của thể loại cha: `CATEGORY_NOT_FOUND`, `CATEGORY_TOO_DEEP`, `CATEGORY_CYCLE` (tự làm cha của chính mình), `CATEGORY_INACTIVE`.

### 3.5. `shelf-locations`, sheet `ViTriKe`

`code` (Mã vị trí kệ, bắt buộc, tối đa 64; khóa trùng theo mã chuẩn hóa), `description` (tối đa 500), `floor` (Tầng, tối đa 20), `zone` (Khu, tối đa 50), `capacity` (Sức chứa, 1 đến 100000).

## 4. Mã lỗi dòng (`issues[].code`)

| Mã | Mức / trạng thái dòng | Ý nghĩa |
|---|---|---|
| `REQUIRED`, `TOO_LONG`, `INVALID_NUMBER`, `INVALID_DATE`, `INVALID_ENUM`, `CELL_TOO_LONG` | ERROR (`INVALID`) | Kiểm cú pháp chung |
| `INVALID_TITLE` | ERROR (`INVALID`) | Vi phạm quy tắc đầu sách/thông tin bổ sung (giá bìa âm, năm xuất bản, số trang...); `message` nói rõ trường |
| `TITLE_PREVIEW_TOO_LONG` | ERROR (`INVALID`) | Mô tả quá 500 ký tự |
| `TOO_MANY_AUTHORS` | ERROR (`INVALID`) | Quá 20 tác giả chuẩn |
| `INVALID_AUTHOR`, `INVALID_PUBLISHER`, `INVALID_CATEGORY`, `INVALID_SHELF_LOCATION` | ERROR (`INVALID`) | Vi phạm quy tắc danh mục (độ dài, năm, URL, sức chứa...); `message` nói rõ trường |
| `AUTHOR_NOT_FOUND`, `PUBLISHER_NOT_FOUND`, `CATEGORY_NOT_FOUND`, `SHELF_LOCATION_NOT_FOUND` | ERROR (`INVALID`) | Tham chiếu chưa có trong thư viện (không tự tạo) |
| `AUTHOR_INACTIVE`, `PUBLISHER_INACTIVE`, `CATEGORY_INACTIVE`, `SHELF_LOCATION_INACTIVE` | ERROR (`INVALID`) | Tham chiếu đã ngừng sử dụng |
| `TITLE_NOT_FOUND`, `TITLE_ROW_INVALID` | ERROR (`INVALID`) | Bản sao không gắn được vào đầu sách (mục 3.1) |
| `INVALID_COPY` | ERROR (`INVALID`) | Vi phạm quy tắc bản sao (ngày tương lai, giá mua khi không phải `PURCHASE`...) |
| `MODULE_NOT_ENABLED` | ERROR (`INVALID`) | Tenant chưa bật module `LENDING` (dòng `BanSao`) |
| `DUPLICATE_IN_FILE` | ERROR (`INVALID`) | Trùng khóa với dòng trước trong file |
| `ALREADY_EXISTS` | `DUPLICATE` | Khóa (tên, mã, ISBN, mã vạch) đã có trong tenant, bỏ qua khi confirm |
| `NO_DEDUPE_KEY` | WARNING (dòng vẫn `VALID`) | Đầu sách không có ISBN |
| `WRITE_CONFLICT` | kết quả ghi `FAILED` | Ghi bị xung đột do thay đổi đồng thời ngay cả sau khi chạy lại từng dòng |

## 5. Hành vi ghi, cache và vết

- Confirm kiểm lại với DB tại thời điểm ghi (theo lô); dòng vừa bị người khác tạo trong 30 phút qua thành bỏ qua (`skippedDuplicate`), tham chiếu vừa ngừng dùng thành `failed`.
- Ghi theo chunk 200 dòng, mỗi chunk một giao dịch (đầu sách đi cùng bản sao của nó; thể loại cha trước con). Không có giao dịch bao cả file: lỗi giữa chừng để lại phần đã ghi (xem `status` `COMMIT_FAILED`, `result`).
- Cache danh mục (`authors|publishers|categories|shelf-locations`, TTL 60 giây) được xóa sau khi nhập; danh sách tham chiếu mới hiện ngay.
- Mỗi lần confirm xong ghi MỘT bản ghi tóm tắt (importId, loại, số tạo/bỏ qua/lỗi) vào log `import-audit` của catalog-service; catalog chưa có kho audit riêng nên chưa xem được ở màn hình audit.
