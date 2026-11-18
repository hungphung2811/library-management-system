# Hóa đơn VAT nội bộ (ADR-0030 gói A): `/api/sales/invoices/**`, `/api/sales/orders/{id}/invoice(-request)`, `/api/sales/invoice-settings`

Nền: ADR-0030 (mục D1, D2, Phụ lục A, B), ADR-0010 (tiền, thuế), ADR-0003 (khóa quyền). Service: sales-service, context `lms.sales.invoicing`; BFF: `lms-api` (`SalesInvoiceController`). Tiền là số nguyên đồng (VND, `long`).

> ⚠️ **Hóa đơn nội bộ CHƯA có giá trị pháp lý của hóa đơn điện tử** theo Nghị định 123/2020/NĐ-CP và Thông tư 78/2021/TT-BTC cho tới khi tích hợp nhà cung cấp hóa đơn điện tử (HĐĐT) được cơ quan thuế chấp nhận. Mọi phản hồi hóa đơn có trường `notice` ("Hóa đơn nội bộ, không thay thế hóa đơn điện tử") và bản in luôn có dòng đó: FE phải hiển thị. Cần tư vấn pháp lý/kế toán trước khi dùng cho mục đích thuế (xem "Giả định cần xác nhận").

## Cách hoạt động

- `lms-api` chuyển 1-1 sang `/internal/sales/**` của sales-service (giữ `Authorization`, `X-Correlation-Id`). Kiểm quyền, module, tenant ở sales-service: mọi controller `@RequiresModule(SALES)`; thiếu module `SALES` thì `403 MODULE_NOT_ENABLED` (kể cả admin), thiếu `tenantId` thì `403 TENANT_REQUIRED`. Lỗi 4xx chuyển nguyên.
- Lỗi nghiệp vụ: `{code, message, details[], timestamp}` (như `sales.md`). Validate body: `400 VALIDATION_FAILED`.
- Tiền, số hóa đơn, số tiền bằng chữ do server tính; FE không gửi.
- Một hóa đơn gồm: người bán (snapshot cấu hình lúc phát hành), người mua (snapshot yêu cầu xuất hóa đơn), dòng hàng, tổng. **Bất biến sau `ISSUED`** (domain không có setter; DB có trigger chặn sửa/xóa): chỉ chuyển `ISSUED` sang `CANCELLED` (kèm lý do) được. Sửa thông tin = hủy và lập hóa đơn thay thế.

## Trạng thái và loại

| `status` | Ý nghĩa |
|---|---|
| `DRAFT` | Chưa có số. API hiện tại không tạo nháp lâu dài (lập là phát hành ngay); DB có sẵn trạng thái cho nhà cung cấp HĐĐT bất đồng bộ sau này. |
| `ISSUED` | Đã có số, bất biến. |
| `CANCELLED` | Đã hủy (số và tiền giữ nguyên để đối chiếu). |

| `kind` | Ý nghĩa |
|---|---|
| `ORIGINAL` | Hóa đơn gốc của đơn. |
| `REPLACEMENT` | Thay thế hóa đơn đã hủy (`originalInvoiceId` = hóa đơn bị thay). |
| `ADJUSTMENT_DECREASE` | Điều chỉnh giảm, tự sinh khi trả hàng (`originalInvoiceId` + `returnId`). Điều chỉnh tăng: ngoài phạm vi. |

## Endpoint và khóa quyền

Khóa = `TênController/tênMethod` của sales-service (đúng Phụ lục A ADR-0030, đã seed ở `identity/V40`; đổi tên là đổi khóa). Cột "Mẫu" là role mẫu được gán sẵn (chỉ bảng toàn cục `role_template_permissions`; tenant đang chạy phải gán tay): c1 Thu ngân, c3 Quản lý cửa hàng, c4 Khách hàng.

| Method, đường dẫn (BFF) | Khóa quyền | Mẫu | Body / query | Kết quả |
|---|---|---|---|---|
| GET `/api/sales/invoices` | `InvoiceController/search` | c1, c3 | query record `status?, kind?, from?, to?, keyword?, page?, size?` (`from` bao gồm, `to` loại trừ, theo thời điểm tạo, ISO-8601; `keyword` khớp số đơn, tên/đơn vị/MST người mua, số hóa đơn; `page` từ 0, `size` tối đa 100) | `{items:[InvoiceSummary], total, page, size}` mới nhất trước |
| GET `/api/sales/invoices/{id}` | `InvoiceController/get` | c1, c3 | | `InvoiceView` đầy đủ; `404 INVOICE_NOT_FOUND` |
| GET `/api/sales/invoices/{id}/print` | `InvoiceController/print` | c1, c3 | | `200 text/html;charset=UTF-8` (bản in); `404 INVOICE_NOT_FOUND`; hóa đơn nháp `409 INVOICE_STATE_CONFLICT` |
| POST `/api/sales/orders/{orderId}/invoice` | `InvoiceController/issue` | c1, c3 | `{clientRequestId}` (bắt buộc, <=100 ký tự) | `201` + `Location` `InvoiceView` (hóa đơn `ORIGINAL` ISSUED) |
| POST `/api/sales/invoices/{id}/cancellation` | `InvoiceController/cancel` | c3 | `{reason}` (bắt buộc, <=500) | `InvoiceView` (`CANCELLED`) |
| POST `/api/sales/invoices/{id}/replacement` | `InvoiceController/replace` | c3 | `{reason, buyer?{name?, company?, taxCode?, address?, email?}, clientRequestId}` | `201` + `Location` `InvoiceView` (`REPLACEMENT` ISSUED; bản gốc thành `CANCELLED`) |
| GET `/api/sales/orders/{orderId}/invoice-request` | `InvoiceRequestController/get` | c1, c3 | | `InvoiceRequestView`; `404 ORDER_NOT_FOUND`, `404 INVOICE_NOT_FOUND` (đơn chưa có yêu cầu) |
| PUT `/api/sales/orders/{orderId}/invoice-request` | `InvoiceRequestController/save` | c1, c3 | `{buyer:{name?, company?, taxCode?, address?, email?}}` | `InvoiceRequestView` (tạo hoặc thay thế khi còn `OPEN`) |
| GET `/api/sales/invoice-settings` | `InvoiceSettingsController/get` | c3 | | `InvoiceSettingsView`; chưa lưu thì `version` 0, `complete` false |
| PUT `/api/sales/invoice-settings` | `InvoiceSettingsController/update` | c3 | `{sellerLegalName, sellerTaxCode, sellerAddress, sellerPhone?, sellerEmail?, templateCode, series, autoIssueOnCompletion, version}` | `InvoiceSettingsView` (version tăng) |
| PUT `/public/t/{code}/orders/{orderId}/invoice-request` (customer-api) | `ShopInvoiceController/saveRequest` | c4 | xem "Yêu cầu của khách" | `{orderId, status}` |

Khóa quyền không có `@RequirePermission`; ngoài khóa trên, không có endpoint hóa đơn nào cho token integration (`@IntegrationScope`).

### InvoiceView (nhân viên)

```json
{"id":"…","kind":"ORIGINAL|REPLACEMENT|ADJUSTMENT_DECREASE","status":"ISSUED|CANCELLED|DRAFT","orderId":"…","orderNo":"DH-000012",
 "returnId":null,"originalInvoiceId":null,"originalInvoiceNumber":null,"templateCode":"1","series":"C26TAA","fiscalYear":2026,
 "invoiceNo":12,"invoiceNumber":"00000012","issuedAt":"…","issuedBy":"…","cancelledAt":null,"cancelledBy":null,"cancelReason":null,
 "seller":{"legalName":"…","taxCode":"0123456789","address":"…","phone":null,"email":null},
 "buyer":{"name":null,"company":"Công ty A","taxCode":"0123456789-001","address":"…","email":null},
 "currency":"VND","pricesIncludeVat":true,"paymentMethod":"CASH","totalBeforeVat":0,"vatTotal":0,"grandTotal":0,
 "amountInWords":"Một trăm linh năm nghìn đồng","issuer":"INTERNAL","externalLookupCode":null,"createdAt":"…","version":0,
 "lines":[{"lineNo":1,"itemName":"…","isbn":"…","unit":"cuốn","quantity":1,"unitPrice":0,"discount":0,"amountBeforeVat":0,
           "vatRateBps":500,"vatAmount":0,"lineTotal":0}],
 "adjustments":[{"id":"…","invoiceNumber":"00000013","grandTotal":0,"issuedAt":"…","returnId":"…"}],
 "notice":"Hóa đơn nội bộ, không thay thế hóa đơn điện tử"}
```

- `buyer` khách lẻ không yêu cầu hóa đơn: `{"name":"Người mua không lấy hóa đơn", ...}` (không MST). ⚠️ Cách ghi cần kế toán xác nhận.
- Dòng phí ship: `itemName "Phí vận chuyển"`, `unit "lần"`, `quantity 1`, `vatRateBps null` (không chịu thuế), `vatAmount 0`.
- `vatRateBps` lưu theo dòng, sao từ thuế suất của đơn (hiện một thuế suất cho cả tenant). `amountBeforeVat = lineTotal - vatAmount` ở cả hai chế độ giá. `discount` = giảm giá dòng + phần giảm giá đơn phân bổ về dòng. `grandTotal` = tổng đơn; `vatTotal` = tổng thuế các dòng = thuế của đơn (không tính lại từ tổng, nên không lệch 1 đồng do làm tròn).
- `invoiceNumber` đệm 8 chữ số; `invoiceNo` là số nguyên. `adjustments` chỉ có ở hóa đơn gốc/thay thế (các điều chỉnh còn hiệu lực).
- `buyer` là dữ liệu cá nhân: FE chỉ hiển thị cho người có khóa `InvoiceController/get`; không ghi log phía FE.

### InvoiceSummary (danh sách)

`{id, kind, status, orderId, orderNo, originalInvoiceId, templateCode, series, fiscalYear, invoiceNo, invoiceNumber, buyerName, buyerCompany, buyerTaxCode, grandTotal, issuedAt, createdAt}` (không dòng hàng, không địa chỉ/email người mua).

### InvoiceRequestView / InvoiceSettingsView

- `InvoiceRequestView`: `{id, orderId, buyerName, buyerCompany, buyerTaxCode, buyerAddress, buyerEmail, source (POS|ONLINE), status (OPEN|FULFILLED|WITHDRAWN), createdAt, updatedAt, version}`.
- `InvoiceSettingsView`: `{sellerLegalName, sellerTaxCode, sellerAddress, sellerPhone, sellerEmail, templateCode, series, autoIssueOnCompletion, complete, version, updatedAt}`. `complete` = đủ tên pháp lý, MST, địa chỉ, mẫu số, ký hiệu để phát hành.

## Quy tắc nghiệp vụ

1. **Điều kiện lập**: đơn `COMPLETED` và `PAID`. Đơn đã có phiếu trả trước khi lập hóa đơn: `409 ORDER_NOT_INVOICEABLE` (chưa hỗ trợ lập hóa đơn cho đơn đã trả một phần). Mỗi đơn tối đa một hóa đơn `ORIGINAL|REPLACEMENT` còn hiệu lực (chỉ mục duy nhất một phần ở DB): lập thêm `409 INVOICE_ALREADY_EXISTS`. Gửi lại cùng `clientRequestId` trả hóa đơn đã lập (cùng khóa, đơn khác: `409 CLIENT_REQUEST_CONFLICT`).
2. **Số hóa đơn**: tuần tự theo (tenant, mẫu số, ký hiệu, năm theo giờ Việt Nam), bắt đầu từ 1 mỗi năm; bộ đếm `sales_counters` tên `INVOICE:<mẫu số>:<ký hiệu>:<năm>` (<= 30 ký tự: mẫu số <= 6, ký hiệu <= 8 ký tự `[0-9A-Za-z/]`) cùng transaction với việc lưu hóa đơn: rollback không để lỗ hổng, phát hành song song không trùng số. Phát hành qua `InvoiceIssuerPort` (mặc định `InternalInvoiceIssuer`, chỉ cấp số nội bộ, không gửi nhà cung cấp HĐĐT).
3. **Cấu hình người bán**: tên pháp lý, MST, địa chỉ, mẫu số, ký hiệu bắt buộc; chưa đủ thì phát hành `409 INVOICE_SETTINGS_INCOMPLETE`. MST người bán: 10 chữ số hoặc `10-3` (ví dụ `0123456789-001`); sai `400 INVALID_TAX_CODE`. Chỉ kiểm định dạng, không kiểm tồn tại với cơ quan thuế.
4. **Người mua**: không bắt buộc. Có `taxCode` (10 chữ số, `10-3` hoặc 12 chữ số) thì bắt buộc `company` và `address` (`400 VALIDATION_FAILED`); sai định dạng `400 INVALID_TAX_CODE`. Yêu cầu xuất hóa đơn cần ít nhất `name` hoặc `company`. `email` đúng định dạng.
5. **Hủy** (`cancellation`): chỉ từ `ISSUED` (còn lại `409 INVOICE_STATE_CONFLICT`), `reason` bắt buộc (`400 CANCEL_REASON_REQUIRED`/`VALIDATION_FAILED`). Hóa đơn điều chỉnh không hủy được. Hóa đơn gốc đã có điều chỉnh còn hiệu lực: `409 INVOICE_HAS_ADJUSTMENTS` (khác ADR: ADR chỉ nêu "không thay thế được", hủy cũng bị chặn vì để lại điều chỉnh mồ côi). Hủy xong, đơn lập lại được hóa đơn mới (số mới).
6. **Thay thế** (`replacement`): hủy hóa đơn `ORIGINAL|REPLACEMENT` rồi lập `REPLACEMENT` ISSUED trong cùng transaction; dòng sao từ hóa đơn bị thay, `buyer` vắng thì giữ người mua cũ, người bán/mẫu số/ký hiệu theo cấu hình hiện tại (cần cấu hình đủ). Đã có điều chỉnh: `409 INVOICE_HAS_ADJUSTMENTS`.
7. **Điều chỉnh giảm khi trả hàng**: khi phiếu trả được lập (`POST /api/sales/returns[...]`), nếu đơn có hóa đơn còn hiệu lực thì cùng transaction lập `ADJUSTMENT_DECREASE` ISSUED: dòng = các dòng trả (số lượng trả, tiền = `refundAmount`), người bán/người mua/mẫu số/ký hiệu theo hóa đơn gốc, số lấy từ bộ đếm năm hiện tại. Thuế điều chỉnh phân bổ lũy kế theo thuế dòng gốc (`round(đã trả+hoàn, thuế/thành tiền) - round(đã trả, ...)`, HALF_UP): phiếu đầu đúng công thức `round(hoàn x thuếGốc / thànhTiềnGốc)` của ADR, trả hết dòng thì tổng thuế điều chỉnh đúng bằng thuế gốc. Tổng điều chỉnh không vượt hóa đơn gốc. Đơn không có hóa đơn: trả hàng không đổi gì. Một phiếu trả tối đa một hóa đơn điều chỉnh.
8. **Tự phát hành** (`autoIssueOnCompletion`, mặc định tắt): bật thì khi đơn vừa thành `COMPLETED + PAID` (hoàn tất đơn online; xác nhận thu tay POS; cổng thanh toán POS báo đã thu) mà đơn có yêu cầu xuất hóa đơn đang `OPEN` thì tự phát hành (người lập = người thao tác). Lưu yêu cầu SAU khi đơn đã hoàn tất cũng kích hoạt. Thiếu cấu hình người bán: bỏ qua và ghi log WARN, **không làm hỏng việc ghi nhận thanh toán**; nhân viên xuất tay sau.
9. **Yêu cầu xuất hóa đơn**: mỗi đơn một yêu cầu; còn `OPEN` thì ghi đè được; đã `FULFILLED` (đã có hóa đơn) thì `409 INVOICE_ALREADY_EXISTS` (dùng thay thế hóa đơn để đổi thông tin). Đơn đã hủy: `409 ORDER_NOT_INVOICEABLE`. Đơn đang chờ thu (POS `AWAITING_PAYMENT`) vẫn nhận yêu cầu.
10. **Audit** (`sales_audit_log`, cùng transaction, chỉ id/số/mã, không dữ liệu cá nhân): `INVOICE_ISSUED` (`invoiceId, invoiceNo, series, kind, orderId, grandTotal`), `INVOICE_CANCELLED`, `INVOICE_REPLACED` (`originalInvoiceId`), `INVOICE_ADJUSTED` (`originalInvoiceId, returnId`), `INVOICE_REQUEST_SAVED` (`orderId, source, hasTaxCode`), `INVOICE_SETTINGS_CHANGED` (`templateCode, series, autoIssueOnCompletion`). Sự kiện `sales.events` do `SalesAuditAdapter` phát (`InvoiceIssued`...); làm giàu `customerUserId` thuộc gói D.

## Bản in

`GET /api/sales/invoices/{id}/print` trả HTML in được (trình duyệt "In, Lưu PDF"; không PDF phía server). FE mở trong tab mới hoặc iframe `sandbox`, không chèn HTML vào DOM của ứng dụng. An toàn: mọi dữ liệu được thoát HTML (`& < > " '`), không có thẻ script/thuộc tính sự kiện/tài nguyên ngoài, có `<meta>` CSP và header `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'`, `X-Content-Type-Options: nosniff`, `Cache-Control: no-store` (lms-api luôn đặt lại các header này). Nội dung: tiêu đề theo loại ("HÓA ĐƠN GIÁ TRỊ GIA TĂNG", "HÓA ĐƠN THAY THẾ", "HÓA ĐƠN ĐIỀU CHỈNH GIẢM"), mẫu số/ký hiệu/số, ngày lập, người bán, người mua, bảng dòng (thuế suất `5%` hoặc `KCT`), tổng, số tiền bằng chữ, banner "HÓA ĐƠN ĐÃ HỦY" khi `CANCELLED`, và dòng "Hóa đơn nội bộ, không thay thế hóa đơn điện tử".

## Yêu cầu của khách (customer-api)

`PUT /public/t/{code}/orders/{orderId}/invoice-request` (cần đăng nhập storefront, xem `customer-api.md`). Body `{"buyer":{"name":"..","company":"..","taxCode":"..","address":"..","email":".."}}` (record allowlist, mọi trường tùy chọn nhưng cần `name` hoặc `company`; có `taxCode` thì cần `company` và `address`). Phản hồi `200 {"orderId":"…","status":"OPEN|FULFILLED"}`, `Cache-Control: no-store` (không lặp lại thông tin khách đã gửi). Sales kiểm sở hữu: chỉ đơn ONLINE của chính khách; đơn người khác, đơn POS, không tồn tại hoặc `orderId` sai định dạng đều `404 ORDER_NOT_FOUND` cùng một thân (IDOR). Lỗi khác: `400 VALIDATION_FAILED`, `400 INVALID_TAX_CODE`, `409 INVOICE_ALREADY_EXISTS` (đã có hóa đơn: liên hệ cửa hàng), `409 ORDER_NOT_INVOICEABLE` (đơn đã hủy).

## Bảng mã lỗi (hóa đơn)

| HTTP | `code` | Khi nào |
|---|---|---|
| 404 | `INVOICE_NOT_FOUND` | không có hóa đơn (hoặc của tenant khác); đơn chưa có yêu cầu xuất hóa đơn |
| 404 | `ORDER_NOT_FOUND` | đơn không tồn tại/không thuộc tenant; (khách) đơn không phải của mình |
| 409 | `ORDER_NOT_INVOICEABLE` | đơn chưa `COMPLETED + PAID`, đã có phiếu trả, hoặc (yêu cầu) đơn đã hủy |
| 409 | `INVOICE_ALREADY_EXISTS` | đơn đã có hóa đơn hiệu lực; yêu cầu đã `FULFILLED` |
| 409 | `INVOICE_STATE_CONFLICT` | thao tác không hợp lệ ở trạng thái hiện tại (hủy hai lần, hủy điều chỉnh, in nháp, điều chỉnh vượt gốc, xung đột phiên bản) |
| 409 | `INVOICE_HAS_ADJUSTMENTS` | hủy/thay thế hóa đơn đã có điều chỉnh |
| 409 | `INVOICE_SETTINGS_INCOMPLETE` | chưa cấu hình người bán/mẫu số/ký hiệu |
| 409 | `CLIENT_REQUEST_CONFLICT` | `clientRequestId` đã dùng cho thao tác khác |
| 409 | `SETTINGS_VERSION_CONFLICT` | `version` cấu hình lệch |
| 400 | `INVALID_TAX_CODE` | MST sai định dạng |
| 400 | `CANCEL_REASON_REQUIRED` | thiếu lý do hủy |
| 400 | `INVALID_SETTINGS` | cấu hình sai (mẫu số/ký hiệu/email/độ dài) |
| 400 | `VALIDATION_FAILED` | body/query sai (thiếu trường, quá dài, enum lạ) |

## Dữ liệu

Migration `sales/V16__invoicing.sql` (system-service): `invoice_settings` (PK `tenant_id`), `invoice_requests`, `invoices`, `invoice_lines`; RLS ENABLE + FORCE (`tenant_isolation`, `platform_read_all`), entity `@TenantId` (trừ `invoice_settings` lưu bằng JDBC như `sales_settings`); chỉ mục duy nhất (`uq_invoices_number`, `uq_invoices_order_effective`, `uq_invoices_return`, `uq_invoices_client_request`) và chỉ mục danh sách có `tenant_id` đứng đầu; trigger bất biến `trg_invoices_immutable`, `trg_invoice_lines_immutable`. Bảng cột `invoice_requests.pii_anonymized_at` có sẵn nhưng chưa có job ẩn danh hóa (thông tin người mua nằm trong yêu cầu và hóa đơn, cần chính sách lưu giữ riêng).

## Giả định cần xác nhận (pháp lý/kế toán)

1. ⚠️ Hóa đơn nội bộ không phải HĐĐT: chưa dùng được cho kê khai thuế khi chưa tích hợp nhà cung cấp.
2. ⚠️ Số hóa đơn bắt đầu lại từ 1 mỗi năm theo (mẫu số, ký hiệu) (theo cách hiểu Thông tư 78); ký hiệu có thể phải đổi mỗi năm (`C26...`).
3. ⚠️ Cách ghi khách lẻ ("Người mua không lấy hóa đơn"), phí ship là dòng không chịu thuế, sách "không chịu thuế" so với 5%.
4. ⚠️ MST người mua 12 chữ số được chấp nhận (số định danh cá nhân) ngoài 10 và 10-3 chữ số; chỉ kiểm định dạng.
5. ⚠️ Hóa đơn điều chỉnh giảm tự sinh ISSUED, thuế phân bổ lũy kế theo dòng gốc.
6. Hủy/thay thế chỉ quản lý cửa hàng (c3); thu ngân xuất và in được.
