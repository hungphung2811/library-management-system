# Nhập mặt hàng và nhà cung cấp từ Excel (lms-api -> sales-service)

Hai loại của cụm nhập master data hàng loạt (ADR-0024, `docs/decisions/0024-nhap-master-data-tu-excel-preview-confirm.md`) thuộc module `sales`: `stock-items` (mặt hàng kèm tồn đầu kỳ) và `suppliers` (nhà cung cấp). Luồng giống `identity-import.md`: tải mẫu `.xlsx`, điền, upload, xem **preview từng dòng** (dữ liệu nằm trong Redis, chưa ghi DB), bấm Confirm mới ghi (chạy nền), thăm dò tiến độ. FE gọi `/api/sales/imports/{type}/**`; `lms-api` chuyển nguyên sang `/internal/imports/{type}/**` của sales-service (byte và mã HTTP giữ nguyên). `{type}` chỉ nhận `stock-items` hoặc `suppliers`, loại khác trả `404`. Nginx cho phép thân tối đa 6 MB ở nhóm đường dẫn `/api/*/imports`.

Giới hạn: tối đa **5.000 dòng** dữ liệu mỗi file, **5 MB** mỗi file, phiên preview sống **30 phút**, tối đa **3 phiên đang mở** mỗi người dùng trong tenant. Tenant phải bật module `SALES` (nếu không: `403 MODULE_NOT_ENABLED`).

## 1. Endpoint (áp dụng cho cả hai loại)

| # | Method + đường dẫn | Quyền | Trả về |
|---|---|---|---|
| 1 | `GET /api/sales/imports/{type}/template` | chỉ đăng nhập | `200` file `.xlsx` (`Content-Disposition: attachment; filename="mau-<type>.xlsx"`, no-store) |
| 2 | `POST /api/sales/imports/{type}` | khóa `upload` của loại (mục 2) | `201` `ImportSessionView` (`VALIDATED`). `multipart/form-data`, part tên **`file`** |
| 3 | `GET /api/sales/imports/{type}/{importId}` | chỉ đăng nhập + chủ phiên | `200` `ImportSessionView` (thăm dò 1-2 giây/lần khi `COMMITTING`) |
| 4 | `GET /api/sales/imports/{type}/{importId}/rows?status=&sheet=&page=&size=` | chỉ đăng nhập + chủ phiên | `200` `RowsPage` |
| 5 | `POST /api/sales/imports/{type}/{importId}/confirm` | khóa `confirm` của loại + chủ phiên | `202` `ImportSessionView` (đang ghi); `200` nếu phiên đã xong. Không body. Bấm lặp lại không lỗi, không ghi lại |
| 6 | `DELETE /api/sales/imports/{type}/{importId}` | chỉ đăng nhập + chủ phiên | `204` |
| 7 | `GET /api/sales/imports/{type}/{importId}/error-file` | chỉ đăng nhập + chủ phiên | `200` `.xlsx` gồm dòng `INVALID` và `DUPLICATE` (giữ giá trị đã nhập, thêm cột "Trạng thái", "Lỗi"), upload lại được sau khi sửa |

`rows` nhận query record `status` (`VALID|INVALID|DUPLICATE`), `sheet`, `page` (từ 0), `size` (mặc định 50, tối đa 200); giá trị có ký tự lạ trả `400 INVALID_QUERY`. Dạng `ImportSessionView`, `RowsPage` và các mã lỗi HTTP chung (`IMPORT_FILE_REQUIRED`, `IMPORT_FILE_TOO_LARGE`, `IMPORT_UNSUPPORTED_FILE`, `IMPORT_FILE_UNSAFE`, `IMPORT_FILE_CORRUPT`, `IMPORT_TEMPLATE_MISMATCH`, `IMPORT_EMPTY`, `IMPORT_TOO_MANY_ROWS`, `IMPORT_NOT_FOUND`, `IMPORT_INVALID_STATE`, `IMPORT_NOTHING_TO_COMMIT`, `IMPORT_SESSION_LIMIT`, `IMPORT_BUSY`, `IMPORT_STORAGE_UNAVAILABLE`) giống `identity-import.md` mục 5 (không có `credentials`/`410` ở sales).

Mã riêng của sales khi upload mặt hàng: `503 CATALOG_UNAVAILABLE` nếu sales không gọi được catalog để tra ISBN (kể cả người dùng thiếu quyền tra ISBN, xem mục 2); FE báo "Không tra được danh mục sách, thử lại sau". Không ghi gì khi lỗi này.

Chủ phiên = chính người đã upload (cùng tenant); phiên của người khác, hết hạn, đã hủy hoặc tenant khác đều `404 IMPORT_NOT_FOUND`.

## 2. Khóa quyền (động, ADR-0003)

Khóa sinh từ tên lớp controller của sales-service, KHÔNG đổi tên:

| Loại | Upload | Confirm |
|---|---|---|
| `stock-items` | `StockItemImportController/upload` | `StockItemImportController/confirm` |
| `suppliers` | `SupplierImportController/upload` | `SupplierImportController/confirm` |

Hai khóa của một loại luôn gán cùng nhau (role mẫu: STOCK_KEEPER, STORE_MANAGER; migration identity V31 do việc identity làm). Người dùng nhập `stock-items` cần thêm khóa catalog `TitleController/lookupByIsbn` vì sales tra ISBN bằng token của chính họ; thiếu khóa này thì upload trả `503 CATALOG_UNAVAILABLE`. Tải mẫu, xem phiên, xem dòng, hủy, tải file lỗi không cần khóa riêng (chỉ đăng nhập + chủ phiên), nhưng vẫn cần module `SALES`.

## 3. Cột của file

Nhãn tiếng Việt ở dòng 1 (có thể dùng `key` kỹ thuật làm tiêu đề); cột bắt buộc có dấu `*`. Số tiền là **số nguyên đồng** (không phần lẻ, không âm). Mọi ô chỉ là dữ liệu (chống công thức). Cuộc gọi `GET .../{importId}` trả `columns[]` `{key, label, required}` theo sheet để FE dựng bảng.

### 3.1. `stock-items`, sheet `MatHang`

| `key` | Nhãn | Bắt buộc | Quy tắc |
|---|---|---|---|
| `isbn` | ISBN đầu sách | có | tối đa 20. Phải khớp đúng chuỗi ISBN của một đầu sách đã có trong danh mục (nhập đầu sách trước). Không thấy: `INVALID` `TITLE_NOT_FOUND`. Đầu sách đã là mặt hàng: `DUPLICATE` `STOCK_ITEM_EXISTS` |
| `sellingPrice` | Giá bán (VND) | không | số nguyên đồng >= 0; bỏ trống thì dùng giá bìa |
| `lowStockThreshold` | Ngưỡng tồn thấp | không | số nguyên >= 0; bỏ trống thì dùng ngưỡng của cửa hàng |
| `sku` | SKU | không | tối đa 40, tự đổi sang CHỮ HOA. Đã thuộc mặt hàng khác: `DUPLICATE` `SKU_EXISTS`. Trùng dòng khác trong file: `INVALID` `DUPLICATE_IN_FILE` |
| `supplier` | Nhà cung cấp | không | văn bản tự do tối đa 255 (không liên kết tới danh sách nhà cung cấp) |
| `costPrice` | Giá nhập (VND) | không | số nguyên đồng >= 0; dữ liệu nội bộ |
| `binLocation` | Vị trí kho | không | tối đa 64 |
| `reorderQuantity` | SL đặt lại | không | số nguyên >= 0 |
| `note` | Ghi chú nội bộ | không | tối đa 500 |
| `openingQuantity` | Tồn đầu kỳ | không | số nguyên >= 0. Lớn hơn 0 thì khi confirm ghi **một biến động `ADJUSTMENT`** (+số lượng) vào sổ cái tồn kho với lý do "Tồn đầu kỳ nhập từ Excel <importId>" (xem được ở API sổ cái); bằng 0 hoặc trống thì mặt hàng có tồn 0 và không có biến động |

Mỗi đầu sách chỉ là một mặt hàng; trùng ISBN trong file: dòng sau `INVALID` `DUPLICATE_IN_FILE`. Dòng `DUPLICATE` bị bỏ qua khi confirm (không báo lỗi), dòng `INVALID` không bao giờ được ghi.

### 3.2. `suppliers`, sheet `NhaCungCap`

| `key` | Nhãn | Bắt buộc | Quy tắc |
|---|---|---|---|
| `name` | Tên nhà cung cấp | có | tối đa 200; trùng không phân biệt hoa thường/khoảng trắng đầu cuối. Đã có: `DUPLICATE` `SUPPLIER_EXISTS`; trùng dòng khác trong file: `INVALID` `DUPLICATE_IN_FILE` |
| `phone` | Điện thoại | không | tối đa 30 |
| `email` | Email | không | tối đa 200 |
| `address` | Địa chỉ | không | tối đa 500 |
| `note` | Ghi chú | không | tối đa 1000 |

Nhà cung cấp mới luôn `active = true`.

## 4. Mã lỗi dòng (`issues[].code`)

| Mã | Mức / trạng thái dòng | Ý nghĩa |
|---|---|---|
| `REQUIRED`, `TOO_LONG`, `INVALID_NUMBER`, `CELL_TOO_LONG` | ERROR | Kiểm cú pháp chung |
| `TITLE_NOT_FOUND` | ERROR (`INVALID`) | `isbn` không có trong danh mục catalog của tenant |
| `INVALID_PRICE` | ERROR (`INVALID`) | `sellingPrice`/`costPrice` không phải số nguyên đồng không âm |
| `INVALID_QUANTITY` | ERROR (`INVALID`) | `lowStockThreshold`/`reorderQuantity`/`openingQuantity` không phải số nguyên không âm |
| `INVALID_DETAILS` | ERROR (`INVALID`) | Vi phạm quy tắc chi tiết mặt hàng (độ dài SKU/nhà cung cấp/vị trí/ghi chú) |
| `DUPLICATE_IN_FILE` | ERROR (`INVALID`) | Trùng ISBN, SKU (mặt hàng) hoặc tên (nhà cung cấp) với dòng trước trong file |
| `STOCK_ITEM_EXISTS` | `DUPLICATE` | Đầu sách đã là mặt hàng |
| `SKU_EXISTS` | `DUPLICATE` | SKU đã thuộc mặt hàng khác |
| `SUPPLIER_EXISTS` | `DUPLICATE` | Nhà cung cấp cùng tên đã có |

Trong `result` (sau confirm) `skippedDuplicate` đếm dòng bị bỏ qua vì người khác vừa tạo trùng giữa preview và confirm (cùng các mã trên); `failed` là dòng lỗi lúc ghi (mã lỗi nghiệp vụ của sales).

## 5. Hành vi phía sau (để FE hiểu số liệu)

- Upload chỉ đọc: tra ISBN một lần theo lô ở catalog, kiểm trùng bằng một truy vấn mỗi loại, **chưa ghi DB**. `titleId` do hệ thống giữ trong phiên, không có trong `rows`.
- Confirm ghi theo chunk 200 dòng, mỗi chunk một transaction; sổ cái tồn kho khớp bộ đếm. Sau confirm `stock-items` có thể xem bằng `/api/sales/stock-items`; `suppliers` hiện ngay ở `/api/sales/suppliers` (cache 30 giây của danh sách đã được xóa).
- Audit: một bản ghi tóm tắt mỗi lần confirm (`STOCK_ITEMS_IMPORTED` hoặc `SUPPLIERS_IMPORTED`: số tạo/bỏ qua/lỗi, không chứa giá nhập hay ghi chú), không audit từng dòng.
- Phát hiện đổi hợp đồng: không đổi endpoint cũ. Khóa quyền mới nêu ở mục 2.
