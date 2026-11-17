# Bán hàng (SALES): `/api/sales/**` (lms-api), `/internal/sales/shop/**` (khách, qua customer-api) (pha 1 và pha 2)

Nền: ADR-0010, `docs/architecture/sales-module-design.md` mục 5.1, 5.2. Tiền là số nguyên đồng (VND, `long`), không có phần lẻ.

> ℹ️ Hợp đồng dưới đây khớp code sales-service sau B1.3 (controller, record request/response, ExceptionHandler). Phần "Khác biệt so với B1.5" cuối tài liệu liệt kê chỗ DTO `lms-api` (`lms-api/src/main/java/lms/api/sales/dto/SalesDtos.java`, B1.5 viết theo thiết kế) lệch với service và cách xử lý.

## Cách hoạt động

- `lms-api` chuyển 1-1 `/api/sales/**` sang `/internal/sales/**` của sales-service (URL `SALES_SERVICE_URL`, mặc định `http://localhost:8087`), giữ `Authorization: Bearer` và `X-Correlation-Id`.
- BFF không kiểm quyền, module, tenant: sales-service kiểm (`@RequiresModule(SALES)` trên mọi controller; token thiếu module `SALES` bị `403 MODULE_NOT_ENABLED` kể cả admin; không có `tenantId` thì `403 TENANT_REQUIRED`). Lỗi 4xx của sales-service được chuyển nguyên mã và nội dung; 5xx thành `502 DEPENDENCY_ERROR`; không gọi được thành `503 DEPENDENCY_UNAVAILABLE`.
- Lỗi nghiệp vụ trả `ErrorResponse` `{code, message, details[], timestamp}`: `code` là tên mã lỗi, `details` là danh sách chuỗi `"khóa=giá trị"` chỉ gồm id/số (ví dụ `INSUFFICIENT_STOCK`: `["available=1", "titleId=<uuid>"]`).
- Phân trang: query `page` (bắt đầu từ **0**, mặc định 0) và `size` (mặc định 20, tối đa 100); kết quả `{items, total, page, size}`.
- Endpoint tạo trả `201` + `Location` (`/internal/sales/<tài nguyên>/{id}`, BFF đổi sang `/api/sales/...`). Gửi lại cùng `clientRequestId` trả chứng từ đã tạo (cũng `201`), không trừ/cộng tồn lần hai.
- `clientRequestId` là chuỗi không rỗng tối đa 100 ký tự. UUID hợp lệ được giữ nguyên; chuỗi khác được đổi thành UUID xác định theo nội dung (cùng chuỗi, cùng UUID) nên vẫn idempotent.
- Validate body (`400 VALIDATION_FAILED`, `details` là `"trường: lý do"`): trường bắt buộc thiếu, `quantity >= 1`, `unitCost >= 0`, `lines` không rỗng (tối đa 200), `paymentMethod` hoặc `discount.type` ngoài tập giá trị, `reason` rỗng, JSON hỏng, id/enum trong đường dẫn/query sai kiểu. Quy tắc nghiệp vụ trả mã riêng (bảng mã lỗi).

## Endpoint và khóa quyền

Khóa quyền = `TênController/tênMethod` của sales-service (không đổi tên khi chưa báo; 23 khóa pha 1, thêm 21 khóa pha 2 ở mục Pha 2: tổng 44). Mọi endpoint cần token có `tenantId` và module `SALES`.

| Method, đường dẫn (BFF) | Khóa quyền | Body / query | Kết quả |
|---|---|---|---|
| GET `/api/sales/settings` | `SalesSettingsController/get` | | `{vatRateBps, pricesIncludeVat, returnWindowDays, fixedShippingFee, lowStockThreshold, walkInPiiRetentionMonths, currency, version, updatedAt}`; chưa lưu thì trả mặc định với `version` 0, `updatedAt` null |
| PUT `/api/sales/settings` | `SalesSettingsController/update` | `{vatRateBps 0..10000, pricesIncludeVat, returnWindowDays 0..365, fixedShippingFee 0..100000000, lowStockThreshold 0..100000, walkInPiiRetentionMonths 1..120, version}` (mọi trường bắt buộc; `version` = giá trị đã đọc, 0 nếu chưa từng lưu) | cấu hình mới (version tăng); `409 SETTINGS_VERSION_CONFLICT`, `400 INVALID_SETTINGS` |
| POST `/api/sales/stock-items` | `StockItemController/register` | `{titleId, sellingPrice?, lowStockThreshold?}` | `201` mặt hàng tồn 0; `409 STOCK_ITEM_EXISTS`, `409 TITLE_NOT_AVAILABLE` (catalog không có đầu sách), `503 CATALOG_UNAVAILABLE` |
| GET `/api/sales/stock-items` | `StockItemController/search` | `lowStockOnly?, page?, size?` (`keyword` nhận nhưng chưa dùng: tên đầu sách nằm ở catalog) | trang mặt hàng (xem dưới) |
| GET `/api/sales/stock-items/{titleId}` | `StockItemController/get` | | một mặt hàng; `404 STOCK_ITEM_NOT_FOUND` |
| PUT `/api/sales/stock-items/{titleId}/price` | `StockItemController/updatePrice` | `{sellingPrice}`; `null` hoặc bỏ trống = quay về giá bìa | mặt hàng |
| GET `/api/sales/stock-items/{titleId}/ledger` | `StockItemController/ledger` | `page?, size?` | trang dòng sổ cái, mới nhất trước (xem dưới); `404 STOCK_ITEM_NOT_FOUND` |
| POST `/api/sales/stock-adjustments` | `StockAdjustmentController/adjust` | `{titleId, delta != 0, reason}` | mặt hàng sau điều chỉnh; `409 INSUFFICIENT_STOCK` nếu làm tồn khả dụng âm, `409 STOCK_ITEM_NOT_REGISTERED`, `400 INVALID_QUANTITY` (delta 0) |
| POST `/api/sales/stocktakes` | `StockAdjustmentController/stocktake` | `{titleId, countedOnHand, reason}` (`>= 0` và `>= reserved`) | mặt hàng; sổ cái ghi `STOCKTAKE` với chênh lệch; `404 STOCK_ITEM_NOT_FOUND`, `400 INVALID_QUANTITY` |
| GET `/api/sales/suppliers` | `SupplierController/search` | `keyword?` (theo tên), `page?, size?` | trang nhà cung cấp |
| POST `/api/sales/suppliers` | `SupplierController/create` | `{name, phone?, email?, address?, note?}` | `201` `{id, name, phone, email, address, note, active}`; `409 SUPPLIER_EXISTS` (trùng tên không phân biệt hoa thường) |
| PUT `/api/sales/suppliers/{id}` | `SupplierController/update` | như trên + `active?` (bỏ trống = giữ nguyên) | nhà cung cấp; `404 SUPPLIER_NOT_FOUND`, `409 SUPPLIER_EXISTS` |
| POST `/api/sales/goods-receipts` | `GoodsReceiptController/create` | `{supplierId, lines[{titleId, quantity, unitCost}], clientRequestId}` | `201` phiếu `DRAFT`; `404 SUPPLIER_NOT_FOUND`, `409 STOCK_ITEM_NOT_REGISTERED` |
| GET `/api/sales/goods-receipts` | `GoodsReceiptController/search` | `status? (DRAFT|CONFIRMED|CANCELLED), supplierId?, page?, size?` | trang phiếu |
| GET `/api/sales/goods-receipts/{id}` | `GoodsReceiptController/get` | | phiếu; `404 RECEIPT_NOT_FOUND` |
| POST `/api/sales/goods-receipts/{id}/confirmation` | `GoodsReceiptController/confirm` | không body | phiếu `CONFIRMED`, cộng tồn và ghi sổ cái `RECEIPT`; `409 RECEIPT_STATE_CONFLICT` |
| POST `/api/sales/goods-receipts/{id}/cancellation` | `GoodsReceiptController/cancel` | không body | phiếu `CANCELLED`, chỉ từ `DRAFT`; `409 RECEIPT_STATE_CONFLICT` |
| POST `/api/sales/pos-orders` | `PosOrderController/create` | body POS, KHÔNG có giảm giá | `201` đơn `COMPLETED`, `PAID`; có `discount` hoặc `orderDiscount` thì `400 DISCOUNT_NOT_ALLOWED` |
| POST `/api/sales/pos-orders/discounted` | `PosOrderController/createWithDiscount` | như trên, có thể kèm giảm giá | `201`; `400 DISCOUNT_EXCEEDS_AMOUNT` |
| GET `/api/sales/orders` | `SalesOrderController/search` | `status?, paymentStatus?, channel?, from? (ISO-8601, bao gồm), to? (loại trừ), keyword? (khớp số đơn), page?, size?` | trang đơn, mới nhất trước, liên hệ khách che một phần |
| GET `/api/sales/orders/{id}` | `SalesOrderController/get` | | đơn đầy đủ dòng, liên hệ che một phần; `404 ORDER_NOT_FOUND` |
| GET `/api/sales/orders/{id}/contact` | `SalesOrderController/getContact` | | `{name, phone, address, piiAnonymizedAt}` đầy đủ (khóa riêng); đơn đã ẩn danh trả trường null |
| GET `/api/sales/orders/{id}/receipt` | `SalesOrderController/receipt` | | phiếu in (xem dưới), không chứa dữ liệu cá nhân |

Body POS (cả hai endpoint):

```json
{
  "lines": [{ "titleId": "<uuid>", "quantity": 2, "discount": { "type": "PERCENT", "value": 1000 } }],
  "paymentMethod": "CASH",
  "customerUserId": null,
  "walkIn": { "name": "An", "phone": "0900000000" },
  "orderDiscount": { "type": "AMOUNT", "value": 5000 },
  "clientRequestId": "<chuỗi duy nhất mỗi lần bấm thanh toán>"
}
```

- `discount` và `orderDiscount` chỉ dùng ở `/pos-orders/discounted`; `type` `AMOUNT` (số đồng) hoặc `PERCENT` (`value` là điểm cơ bản, 1000 = 10%, tối đa 10000). `paymentMethod` thuộc `CASH|BANK_TRANSFER|CARD` (`COD` bị `400 INVALID_REQUEST`).
- `customerUserId` và `walkIn` đều tùy chọn; `walkIn.phone` tối đa 30 ký tự. Tên và điện thoại khách vãng lai không bao giờ ghi vào log, audit hay event Kafka.
- Đơn trả về (`OrderView`): `{id, orderNo, channel, status, paymentStatus, paymentMethod, fulfilment, customerUserId, contact{name, phone, address, piiAnonymizedAt}, vatRateBps, pricesIncludeVat, currency, subtotal, discountTotal, vatTotal, shippingFee, grandTotal, createdAt, completedAt, version, lines[{id, lineNo, titleId, titleName, isbn, quantity, unitPrice, lineDiscount, allocatedOrderDiscount, amount, vatAmount, lineTotal}]}`. `contact` luôn che một phần (tên giữ ký tự đầu mỗi từ, điện thoại giữ 3 số cuối), `contact.address` null với đơn POS. Đơn POS: `channel=POS`, `fulfilment=COUNTER`, `status=COMPLETED`, `paymentStatus=PAID`. `orderNo` dạng `DH-000001` theo tenant.

Mặt hàng (`StockItemResponse`): `{titleId, titleName, onHand, reserved, available, sellingPrice, effectivePrice, lowStock, active, lowStockThreshold}`. `sellingPrice` null = dùng giá bìa; `effectivePrice` = `sellingPrice` hoặc giá bìa làm tròn đồng (null nếu catalog không trả đầu sách); `titleName` tra từ catalog. `lowStock`: `available <= ngưỡng` (ngưỡng mặt hàng, nếu null thì ngưỡng tenant).

Dòng sổ cái: `{id, titleId, type, deltaOnHand, deltaReserved, refType, refId, reason, actorId, occurredAt}` (`type`: `RECEIPT, SALE, RESERVE, RELEASE, FULFIL, RETURN, WRITE_OFF, ADJUSTMENT, STOCKTAKE, CANCEL_RESTOCK`; `refType` `GOODS_RECEIPT`, `ORDER`, `ADJUSTMENT`, `STOCKTAKE`).

Phiếu nhập: `{id, receiptNo (PN-000001), supplierId, status, lines[{titleId, quantity, unitCost, lineCost}], totalCost, createdAt, createdBy, confirmedAt, confirmedBy, version}`.

Phiếu in (`ReceiptResponse`): `{orderId, orderNo, issuedAt, currency, vatRateBps, pricesIncludeVat, lines[{lineNo, titleName, isbn, quantity, unitPrice, discount, amount, vatAmount, lineTotal}], subtotal, lineDiscountTotal, orderDiscountTotal, discountTotal, taxableAmount, vatTotal, shippingFee, grandTotal, paymentMethod}`. `discount` của dòng = giảm giá dòng + phần giảm giá đơn phân bổ; phí ship tách khỏi phần chịu thuế.

## Mã lỗi (chuyển nguyên từ sales-service)

| HTTP | `code` |
|---|---|
| 400 | `VALIDATION_FAILED`, `INVALID_REQUEST`, `INVALID_QUANTITY`, `INVALID_PRICE`, `INVALID_SETTINGS`, `REASON_REQUIRED`, `DISCOUNT_NOT_ALLOWED`, `DISCOUNT_EXCEEDS_AMOUNT` |
| 403 | `FORBIDDEN` (thiếu khóa), `MODULE_NOT_ENABLED`, `TENANT_REQUIRED` |
| 404 | `ORDER_NOT_FOUND`, `STOCK_ITEM_NOT_FOUND`, `SUPPLIER_NOT_FOUND`, `RECEIPT_NOT_FOUND` |
| 409 | `INSUFFICIENT_STOCK` (details `titleId`, `available`), `TITLE_NOT_AVAILABLE`, `STOCK_ITEM_EXISTS`, `STOCK_ITEM_NOT_REGISTERED`, `SUPPLIER_EXISTS`, `RECEIPT_STATE_CONFLICT`, `ORDER_STATE_CONFLICT`, `SETTINGS_VERSION_CONFLICT`, `DUPLICATE_REQUEST` (hiếm: hai request cùng `clientRequestId` chạy đồng thời mà không bắt được) |
| 503 | `CATALOG_UNAVAILABLE` (không tra được catalog khi bán/liệt kê; gồm cả catalog từ chối) |

Đơn POS vượt tồn trả `409 INSUFFICIENT_STOCK` và rollback toàn bộ (không đơn một phần, không dòng sổ cái nào).

## Sự kiện Kafka (best-effort, không dùng để trừ tồn hay ghi tiền)

Sau commit, mỗi dòng audit phát một bản tóm tắt lên topic `sales.events` (EventEnvelope, `tenantId` trong envelope): `eventType` `OrderCompleted` (đơn POS), `GoodsReceiptCreated|Confirmed|Cancelled`, `StockAdjusted`, `StocktakeRecorded`, `StockItemRegistered`, `PriceChanged`, `SettingsChanged`, `SupplierCreated|Updated`, `OrderDiscountApplied`; payload `{eventId, action, aggregateType, actorId, data}`, `data` chỉ gồm id/số tiền/số lượng/mã trạng thái, không có dữ liệu cá nhân. Nguồn chuẩn là bảng `sales_audit_log` (cột `published_at` giữ NULL cho tới khi có relay Outbox).

## Khác biệt so với DTO lms-api của B1.5 (đã xử lý ở B1.3 hoặc để bước sau)

Đã sửa trực tiếp trong `lms-api/.../SalesDtos.java` (lệch tên trường nhỏ):

1. `LedgerEntryView`: service trả `deltaOnHand, deltaReserved, actorId, occurredAt` và `titleId`, không có `delta`, `onHandAfter`, `createdBy`, `createdAt` -> DTO đổi theo service.
2. `SupplierRequest`/`SupplierView`: service dùng `note` (không có `contactName`) -> đổi tên; `phone` tối đa 30 (cột DB).
3. `ReceiptLineView`: thêm `lineNo`, `lineDiscount` đổi thành `discount` (đã gồm phần giảm giá đơn phân bổ).
4. Biên kiểm tra cấu hình: `returnWindowDays` tối đa 365 (DTO cũ 3650), `walkInPiiRetentionMonths` 1..120 (DTO cũ 1..600); `WalkInRequest.phone` tối đa 30.

Để bước sau (chưa sửa, cần quyết định hoặc code thêm):

5. `SupplierSearchQuery.activeOnly`: sales-service chưa lọc theo `active` (tham số bị bỏ qua); `SupplierView.version` luôn 0 (bảng nhà cung cấp không có version).
6. `GoodsReceiptView.supplierName` và `GoodsReceiptLineView.titleName`: service không trả (null); FE cần tự ghép từ danh sách nhà cung cấp/mặt hàng, hoặc bổ sung tra tên ở service.
7. `StockItemView.effectivePrice` là `long` ở DTO nhưng service có thể trả null khi catalog không có đầu sách (Jackson mặc định đọc thành 0): nên đổi DTO sang `Long`.
8. `keyword` ở `GET /stock-items` chưa có tác dụng.
9. Mã lỗi `SUPPLIER_EXISTS`, `DUPLICATE_REQUEST`, `INVALID_REQUEST` có ở service nhưng chưa có trong thiết kế mục 5.6.
10. `details` của `ErrorResponse` là danh sách chuỗi `"khóa=giá trị"`, không phải đối tượng.
11. `clientRequestId` không phải UUID được chấp nhận (băm thành UUID); thiết kế gốc nói UUID do FE sinh.

## Pha 2 (B2.2, B2.5): đơn online, khách, trả hàng, hoàn tiền, nhật ký, ẩn danh

Hợp đồng khớp code sales-service sau B2.2/B2.5. Thiết kế gốc: `docs/architecture/sales-module-design.md` mục 4, 5.2 đến 5.4; ADR-0010 mục 2.7, 2.8; ADR-0011 mục 2.6. Khóa quyền cuối cùng của pha 2 ở bảng "Danh sách khóa pha 2" bên dưới (B2.3 seed theo đúng danh sách này).

### Máy trạng thái đơn online

```
PENDING_CONFIRMATION --confirm--> CONFIRMED --markReadyForPickup--> READY_FOR_PICKUP --complete (cần PAID)--> COMPLETED
                                           \--markShipping (FULFIL tồn)--> SHIPPING --complete (cần PAID)--> COMPLETED
cancel: PENDING_CONFIRMATION, CONFIRMED, READY_FOR_PICKUP (nhả hàng giữ) | SHIPPING (giao thất bại, nhập lại tồn)
```

- Đặt hàng giữ tồn ngay (`reserved += q`, sổ cái `RESERVE`) trong một transaction; hết hàng thì `409 INSUFFICIENT_STOCK`, không tạo đơn một phần. Hủy nhả hàng giữ (`RELEASE`). `markReadyForPickup` giữ nguyên `reserved`, `complete` mới `FULFIL` (`onHand` và `reserved` cùng giảm). `markShipping` `FULFIL` ngay lúc hàng rời cửa hàng; hủy ở `SHIPPING` ghi `CANCEL_RESTOCK` (`onHand += q`).
- Thanh toán là trục riêng: `UNPAID` tới khi nhân viên có khóa `markPaid` đánh dấu (chuyển khoản/COD/thẻ chưa bao giờ tự `PAID`); chỉ đánh dấu được ở `CONFIRMED`, `READY_FOR_PICKUP`, `SHIPPING`, một lần (lần hai, hoặc hai người cùng lúc: một thành công, người kia `409 ORDER_STATE_CONFLICT`). `complete` khi chưa thu: `409 ORDER_NOT_PAID`. Số tiền thu = `grandTotal` tại thời điểm đánh dấu. Audit `ORDER_PAID` có người (`actorId`), thời điểm (`occurredAt`), số tiền, phương thức, tham chiếu.
- Phí ship: nhập khi xác nhận (`shippingFee`), không gửi thì dùng phí cố định của tenant khi `DELIVERY` và 0 khi `PICKUP`; `PICKUP` mà `shippingFee > 0` là `400 INVALID_REQUEST`. Phí ship không chịu VAT (`vatTotal` không đổi khi xác nhận).
- Hủy đơn đã `PAID` sinh khoản hoàn `PENDING` (số tiền = `grandTotal`, gồm phí ship) trong cùng transaction; đơn chưa thu thì không có hoàn tiền. Đánh dấu khoản hoàn `REFUNDED` đổi `paymentStatus` của đơn (`REFUNDED` khi tổng đã hoàn >= tổng tiền hàng, ngược lại `PARTIALLY_REFUNDED`).
- Đơn chuyển khoản quá hạn: KHÔNG có job tự hủy. Nhân viên lọc `GET /api/sales/orders?paymentStatus=UNPAID&to=<thời điểm>` rồi hủy tay (`cancel`, lý do bắt buộc).
- Khách chỉ hủy được khi `PENDING_CONFIRMATION`; nhân viên hủy được trước `COMPLETED`.
- Đồng thời: `@Version` trên đơn; `version` (tùy chọn) trong body lệch với đơn hiện tại cũng `409 ORDER_STATE_CONFLICT`. Thua cuộc thì rollback cả tồn, sổ cái, audit.
- Phương thức thanh toán đơn online: `BANK_TRANSFER`, `COD` (chỉ với `DELIVERY`), `CASH` (chỉ với `PICKUP`); `CARD` không đặt online được (`400 INVALID_REQUEST`).

### Endpoint nhân viên (đơn)

| Method, đường dẫn (BFF) | Khóa quyền | Body | Kết quả, lỗi |
|---|---|---|---|
| POST `/api/sales/orders/{id}/confirmation` | `SalesOrderController/confirm` | tùy chọn `{shippingFee?, version?}` | đơn `CONFIRMED` |
| POST `/api/sales/orders/{id}/discount` | `SalesOrderController/applyDiscount` | `{orderDiscount?{type, value}, lineDiscounts?[{lineNo, discount{type, value}}], version?}`; mỗi lần gọi thay toàn bộ giảm giá, bỏ trống cả hai là gỡ | đơn tính lại mọi dòng (chỉ đơn online `UNPAID` ở `PENDING_CONFIRMATION`/`CONFIRMED`), `400 DISCOUNT_EXCEEDS_AMOUNT`, `409 ORDER_STATE_CONFLICT` |
| POST `/api/sales/orders/{id}/payment` | `SalesOrderController/markPaid` | `{method, reference?, version?}` | đơn `PAID`; `409 ORDER_STATE_CONFLICT` |
| POST `/api/sales/orders/{id}/ready-for-pickup` | `SalesOrderController/markReadyForPickup` | tùy chọn `{version?}` | `READY_FOR_PICKUP` (chỉ `PICKUP`) |
| POST `/api/sales/orders/{id}/shipping` | `SalesOrderController/markShipping` | tùy chọn `{version?}` | `SHIPPING` (chỉ `DELIVERY`), `FULFIL` tồn |
| POST `/api/sales/orders/{id}/completion` | `SalesOrderController/complete` | tùy chọn `{version?}` | `COMPLETED`; `409 ORDER_NOT_PAID` |
| POST `/api/sales/orders/{id}/cancellation` | `SalesOrderController/cancel` | `{reason, version?}` (`reason` rỗng: `400 VALIDATION_FAILED`) | `CANCELLED`, hoàn tồn, khoản hoàn nếu đã thu |
| GET `/api/sales/orders/{id}/contact` | `SalesOrderController/getContact` (pha 1) | | đơn online trả người nhận đầy đủ `{name, phone, address}`; đơn POS trả khách vãng lai (`address` null); đã ẩn danh trả `null` kèm `piiAnonymizedAt` |

`OrderView` (pha 1) có thêm: `confirmedAt, paidAt, cancelledAt, cancelReason, exchangeOfReturnId`; `contact` của đơn online là người nhận, che một phần (tên giữ ký tự đầu mỗi từ, điện thoại giữ 3 số cuối, địa chỉ chỉ giữ quận/huyện và tỉnh/thành từ hai đoạn cuối, ít hơn ba đoạn thì `***`). `fulfilment`: `COUNTER` (POS), `PICKUP`, `DELIVERY`.

### Endpoint khách (đã đăng nhập, role Khách hàng)

> ⚠️ Thay đổi hợp đồng (rà soát phân quyền dữ liệu, `docs/architecture/customer-api-data-authorization.md` A2 đến A5). Khách KHÔNG đi qua `lms-api`: ADR-0011 mục 2.4/2.5 cho `customer-api` (`/public/t/{code}/orders/**`) chuyển tiếp token người dùng thẳng tới `/internal/sales/shop/**` của sales-service; `lms-api` KHÔNG có `/api/shop/**` (nhân viên dùng `/api/sales/orders/**`).

`userId` lấy từ token, không nhận từ body/query. Sở hữu nằm trong điều kiện truy vấn (`tenant_id` và `customer_user_id` từ token và `channel = ONLINE`), không đọc rồi so sánh: đơn của người khác, của tenant khác, đơn POS (kể cả gắn tài khoản của chính khách) đều `404 ORDER_NOT_FOUND` cùng thân. Khách thấy đầy đủ thông tin người nhận của chính mình.

| Method, đường dẫn (service) | Khóa quyền | Body / query | Kết quả |
|---|---|---|---|
| POST `/internal/sales/shop/orders` | `ShopController/placeOrder` | `{lines[{titleId, quantity}], fulfilment (PICKUP/DELIVERY), paymentMethod, recipient{name, phone, address?}, clientRequestId}`; `address` bắt buộc khi `DELIVERY` | `201` + `Location`, `ShopOrderView`; `409 INSUFFICIENT_STOCK`, `409 STOCK_ITEM_NOT_REGISTERED`, `409 TITLE_NOT_AVAILABLE`, `400 INVALID_REQUEST` (gồm `clientRequestId` không phải UUID), `409 CLIENT_REQUEST_CONFLICT` |
| GET `/internal/sales/shop/orders` | `ShopController/myOrders` | `page?, size?` (size mặc định 20, **tối đa 50**) | trang `ShopOrderView` của chính mình |
| GET `/internal/sales/shop/orders/{id}` | `ShopController/myOrder` | | một đơn của mình; `404 ORDER_NOT_FOUND` |
| POST `/internal/sales/shop/orders/{id}/cancellation` | `ShopController/cancelMyOrder` | tùy chọn `{reason?}` | `CANCELLED` nếu còn `PENDING_CONFIRMATION`, ngược lại `409 ORDER_STATE_CONFLICT` |

- Request đặt hàng là allowlist: không có trường giá, giảm giá, phí ship, trạng thái, `customerUserId`, `tenantId`, `channel`; trường thừa trong JSON bị bỏ qua. Giá lấy từ `sellingPrice` (hoặc giá bìa) phía server, phí ship tính khi nhân viên xác nhận, kênh/trạng thái do server đặt.
- `clientRequestId` của đơn online BẮT BUỘC là UUID hợp lệ (không băm chuỗi tự do như POS): sai định dạng là `400 INVALID_REQUEST`. Gửi lại cùng khóa của CHÍNH khách trả đơn cũ (`201`, không giữ tồn lần hai). Khóa đã thuộc khách khác hoặc một đơn POS: `409 CLIENT_REQUEST_CONFLICT` (mã mới, thay `DUPLICATE_REQUEST` ở đường khách), thân chỉ có `code`/`message`, không có dữ liệu đơn.
- Phản hồi dùng `ShopOrderView` riêng (allowlist, KHÔNG dùng `OrderView` của nhân viên): `{id, orderNo, status, paymentStatus, paymentMethod, fulfilment, recipient{name, phone, address}, currency, subtotal, discountTotal, vatTotal, shippingFee, grandTotal, createdAt, confirmedAt, paidAt, completedAt, cancelledAt, lines[{titleId, titleName, quantity, unitPrice, lineTotal}]}`. Không có: `customerUserId`, `version`, `tenantId`, `channel`, `contact`, `cancelReason` (nhân viên có thể ghi nội dung nội bộ), `isbn`, id dòng, giảm giá từng dòng, người thao tác. Khác bản cũ: trường `contact` đổi thành `recipient`, bỏ `customerUserId`/`version`/`isbn`/`channel`/`cancelReason`/`vatRateBps`/`pricesIncludeVat`/`exchangeOfReturnId`. Test so tập tên trường (`SalesPhase2WebTest`) nên thêm trường phải sửa test.
- Khóa quyền không đổi (4 khóa `ShopController/*`).

Không có `ShopController/listItems` (thiết kế gốc 5.3): danh mục sách đọc qua `StorefrontController` (công khai, ADR-0011). Liên hệ cửa hàng chưa có trong thiết kế nên chưa làm.

### Danh mục công khai (storefront, `@StorefrontAccessible`)

Đường dẫn service `/internal/sales/storefront/**`; customer-api gọi bằng token dịch vụ (kiểm tenant và module SALES, không kiểm khóa); người dùng thường vẫn cần khóa `StorefrontController/*`. Chỉ đọc, không dùng `CurrentUser`.

| Method, đường dẫn (service) | Khóa | Kết quả |
|---|---|---|
| GET `/internal/sales/storefront/items` | `StorefrontController/list` | query `{keyword?, page?, size?}` (size tối đa 50, mặc định 20); `{items[{titleId, name, author, price, inStock, hasPreview}], total, page, size}`; một truy vấn trang + một lời gọi lô tới catalog |
| GET `/internal/sales/storefront/items/{titleId}` | `StorefrontController/get` | như mục danh sách + `description` (null nếu chưa nhập); không có `previewText` |
| GET `/internal/sales/storefront/items/{titleId}/preview` | `StorefrontController/preview` | `{titleId, name, previewText}` |

Chỉ mặt hàng `active`; `price` = `sellingPrice` hoặc giá bìa làm tròn đồng; `inStock = available > 0`. KHÔNG bao giờ trả `onHand`, `reserved`, `available`, giá nhập, ngưỡng tồn, `tenant_id`, ISBN. Mặt hàng không có/không active: `404 STOCK_ITEM_NOT_FOUND`. `keyword` lọc theo tên/tác giả bằng `GET /internal/books/lookup?keyword=` của catalog (tối đa 200 đầu sách) rồi giao với mặt hàng đang bán. Lỗi catalog: `503 CATALOG_UNAVAILABLE`. Không có endpoint BFF `lms-api` cho nhóm này: `customer-api` gọi thẳng.

⚠️ Đề xuất, chưa có code (ADR-0019, hợp đồng ở `storefront-kham-pha.md`): `items` thêm `sort` (`newest|price_asc|price_desc`) và `inStock`; thêm `GET /internal/sales/storefront/suggestions` (`StorefrontController/suggestions`), `GET /internal/sales/storefront/banners` (`StorefrontBannerController/list`) và nhóm quản trị banner `/internal/sales/banners/**` ↔ `/api/sales/banners/**` (`SalesBannerController/{list,get,create,update,delete}`, không `@StorefrontAccessible`). Migration sales `V6` (snapshot giá bìa để sắp theo giá), `V7` (`storefront_banners`), identity `V26` (seed khóa).

### Trả hàng, đổi hàng, hoàn tiền

| Method, đường dẫn (BFF) | Khóa quyền | Body / query | Kết quả, lỗi |
|---|---|---|---|
| POST `/api/sales/returns` | `SalesReturnController/create` | `{orderId, lines[{orderLineId, quantity, disposition (RESTOCK/WRITE_OFF)}], reason, clientRequestId}` | `201` phiếu trả (xem dưới) + khoản hoàn `PENDING`; quá N ngày: `409 RETURN_WINDOW_EXPIRED` (details `windowDays`, `daysOverdue`) |
| POST `/api/sales/returns/late` | `SalesReturnController/createLate` | như trên + `lateReason` bắt buộc | `201`, `late=true`; thiếu/rỗng `lateReason`: `400 LATE_REASON_REQUIRED`; audit `RETURN_LATE_APPROVED` (người, thời điểm, `daysOverdue`, lý do). Khóa riêng, chỉ mẫu Quản lý cửa hàng |
| GET `/api/sales/returns/{id}` | `SalesReturnController/get` | | phiếu; `404 RETURN_NOT_FOUND` |
| GET `/api/sales/orders/{id}/returns` | `SalesReturnController/listByOrder` | | danh sách phiếu của đơn; `404 ORDER_NOT_FOUND` |
| GET `/api/sales/refunds` | `RefundController/search` | query `{status? (PENDING/REFUNDED), page?, size?}` | trang khoản hoàn |
| POST `/api/sales/refunds/{id}/completion` | `RefundController/markRefunded` | `{method, reference?, version?}` | khoản hoàn `REFUNDED`; đã hoàn rồi: `409 ORDER_STATE_CONFLICT`; `404 REFUND_NOT_FOUND` |
| GET `/api/sales/audit-log` | `SalesAuditController/search` | query `{action?, aggregateType?, aggregateId?, from?, to?, page?, size?}` (`from` bao gồm, `to` loại trừ, ISO-8601) | trang `{id, action, aggregateType, aggregateId, actorId, occurredAt, data}`, mới nhất trước; `data` không có dữ liệu cá nhân |

- Điều kiện: đơn `COMPLETED` và đã thu (`PAID`/`PARTIALLY_REFUNDED`; đơn chưa thu `409 ORDER_NOT_PAID`, chưa hoàn tất `409 ORDER_STATE_CONFLICT`); trong N ngày (`returnWindowDays` của tenant lúc trả) tính từ `completedAt`; mỗi dòng đơn chỉ xuất hiện một lần trong phiếu; số lượng không vượt "đã mua trừ đã trả" (tính cả phiếu trước): `409 RETURN_QUANTITY_EXCEEDED` (details `orderLineId`, `remaining`). Hai phiếu trả đồng thời trên cùng đơn được tuần tự hóa (khóa `PESSIMISTIC_WRITE` dòng đơn).
- Tồn kho: `RESTOCK` ghi `RETURN +q`; `WRITE_OFF` ghi `RETURN +q` rồi `WRITE_OFF -q` (lý do "Trả hàng hỏng: ..."), tồn không đổi.
- Tiền hoàn của dòng = `round(lineTotal x q / Q)` HALF_UP; lần trả cuối của dòng nhận phần còn lại nên tổng hoàn không vượt `lineTotal`. Phí ship không được hoàn khi trả hàng (chỉ khi hủy đơn).
- Phiếu trả: `{id, returnNo (TH-000001), orderId, reason, late, lateReason, daysOverdue, refundTotal, refundId, createdBy, createdAt, lines[{id, orderLineId, titleId, quantity, disposition, refundAmount}]}`. Khoản hoàn: `{id, orderId, source (CANCELLATION/RETURN), returnId, amount, status, method, reference, createdAt, refundedBy, refundedAt, version}`.
- Đổi hàng = phiếu trả + đơn POS mới: `POST /api/sales/pos-orders` (hoặc `/discounted`) nhận thêm `exchangeOfReturnId` (id phiếu trả). Phiếu trả phải tồn tại (`404 RETURN_NOT_FOUND`) và mỗi phiếu chỉ đổi một lần (`409 ORDER_STATE_CONFLICT`). Chênh lệch tiền xử lý ngoài hệ thống: khoản hoàn của phiếu trả và thanh toán của đơn mới là hai bản ghi riêng.

### Ẩn danh dữ liệu khách vãng lai (B2.5)

- `walkInPiiRetentionMonths` (mặc định 12, 1..120) trong `GET/PUT /api/sales/settings`. Job `@Scheduled` hằng ngày (`lms.sales.anonymize.cron`, mặc định `0 30 2 * * *`; tắt bằng `lms.sales.anonymize.enabled=false`; cỡ lô `lms.sales.anonymize.batch-size`, mặc định 500) duyệt từng tenant trong `sales_tenants` (ghi trong transaction tạo đơn, bảng không RLS) trong `TenantContext.callAs`.
- Chỉ đơn đã kết thúc (`COMPLETED` quá hạn tính từ `completedAt`, hoặc `CANCELLED` từ `cancelledAt`) bị xóa `walk_in_name`, `walk_in_phone` và đặt `piiAnonymizedAt`; đơn, dòng, tiền, sổ cái, audit giữ nguyên. Người nhận của đơn online (khách có tài khoản) không bị ẩn danh tự động. Chạy lại không đổi gì. Một dòng audit `WALK_IN_PII_ANONYMIZED` mỗi lô (`orders`, `cutoff`, `actorId` null, không có PII).

### Liên hệ của khách gửi cửa hàng (ADR-0015)

Khách đã đăng nhập storefront gửi yêu cầu liên hệ tới cửa hàng; nhân viên xử lý ở `/api/sales/contact-requests/**`. Context `lms.sales.contact` trong sales-service, bảng `shop_contact_requests` (migration sales `V4`, RLS ENABLE + FORCE, chỉ policy `tenant_isolation`, không `platform_read_all`). Nhân viên liên hệ lại khách NGOÀI hệ thống (email/điện thoại khách để lại); chưa có hội thoại, ghi chú, gán người xử lý, thông báo nhân viên, `orderId`.

| Khóa | Service nhận | lms-api (nhân viên) | Vào | Ra |
|---|---|---|---|---|
| `ShopContactController/create` | `POST /internal/sales/shop/contact-requests` | không đi qua lms-api: khách gọi `POST /public/t/{code}/contact-requests` (customer-api, xem `customer-api.md`) | `{topic: ORDER/PRODUCT/PAYMENT/OTHER, fullName 1..100, email <=254, phone? <=20, message 1..2000, consent: true}` (độ dài theo code point sau chuẩn hóa NFC, CRLF thành LF, cắt khoảng trắng; ký tự điều khiển trừ LF và tab ở `message` bị từ chối) | `201` không thân, `Cache-Control: no-store` (kể cả khi trùng nội dung) |
| `SalesContactController/search` | `GET /internal/sales/contact-requests` | `GET /api/sales/contact-requests?status&topic&from&to&page&size` (`from` bao gồm, `to` loại trừ, theo `createdAt`; `size` tối đa 100; mới nhất trước) | | `{items:[ShopContactSummaryView], total, page, size}` |
| `SalesContactController/get` | `GET /internal/sales/contact-requests/{id}` | `GET /api/sales/contact-requests/{id}` | | `ShopContactDetailView`; `404 SHOP_CONTACT_NOT_FOUND` |
| `SalesContactController/getContact` | `GET .../{id}/contact` | `GET /api/sales/contact-requests/{id}/contact` | | `{fullName, email, phone, piiAnonymizedAt}` đầy đủ (đã ẩn danh: các trường `null`); không ghi audit (như `SalesOrderController/getContact`) |
| `SalesContactController/updateStatus` | `PUT .../{id}/status` | `PUT /api/sales/contact-requests/{id}/status` | `{status: NEW/IN_PROGRESS/CLOSED, version}` (cả hai bắt buộc) | `ShopContactDetailView`; `409 SHOP_CONTACT_STATE_CONFLICT` |
| `SalesContactController/anonymize` | `POST .../{id}/anonymization` | `POST /api/sales/contact-requests/{id}/anonymization` | không thân | `ShopContactDetailView` đã ẩn danh; gọi lại là no-op `200` |

- `ShopContactSummaryView`: `{id, topic, status, createdAt, closedAt, contact{name, email, phone} (che), piiAnonymizedAt, version}`; không có `message`, `customerUserId`, `tenantId`. `ShopContactDetailView`: như trên + `message`, `customerUserId` (liên hệ vẫn che). Che: tên giữ ký tự đầu mỗi từ, email `b***@example.vn`, điện thoại 3 số cuối. Liên hệ đầy đủ chỉ qua `getContact`.
- Trạng thái (R6): `NEW` sang `IN_PROGRESS` sang `CLOSED`, cho phép `NEW` sang `CLOSED`; không mở lại `CLOSED`; `closedAt` đặt khi `CLOSED`. Chuyển sai hoặc `version` lệch: `409 SHOP_CONTACT_STATE_CONFLICT`.
- Chống spam ở sales (cấu hình nền tảng `lms.sales.contact.max-per-user-per-day: 5`, `lms.sales.contact.duplicate-window: PT10M`): cùng người, cùng `topic` và `message` (sau chuẩn hóa) trong 10 phút thì trả `201` nhưng KHÔNG ghi dòng mới; tối đa 5 yêu cầu/người trong 24 giờ trượt, vượt thì `429 SHOP_CONTACT_LIMIT_EXCEEDED` (xét trùng trước giới hạn). Gửi song song có thể vượt nhẹ giới hạn (không khóa phân tán).
- Lưu giữ: dữ liệu cá nhân giữ theo `walkInPiiRetentionMonths` của tenant (CÙNG cấu hình với khách vãng lai, mặc định 12, 1..120; không đổi hợp đồng `PUT /api/sales/settings`), tính từ `createdAt`, mọi trạng thái. Hết hạn thì ẩn danh: `contact_name`, `contact_email`, `contact_phone`, `message`, `customer_user_id` thành `NULL` và đặt `piiAnonymizedAt`; giữ dòng, `topic`, `status`, mốc thời gian. Job `ShopContactAnonymizationJob` dùng chung `lms.sales.anonymize.enabled/cron` và danh bạ `sales_tenants` (nay cũng được ghi khi tạo liên hệ đầu tiên của tenant). Nhân viên ẩn danh ngay bằng `anonymize`.
- Audit không PII (`sales_audit_log` và `sales.events`): `SHOP_CONTACT_CREATED` (actor `null`, `{topic}`), `SHOP_CONTACT_STATUS_CHANGED` (`{from, to}`), `SHOP_CONTACT_ANONYMIZED` (`{}`), `SHOP_CONTACT_PII_ANONYMIZED` (lô của job: `{requests, cutoff}`).
- Mã lỗi mới: `404 SHOP_CONTACT_NOT_FOUND` (id không có hoặc thuộc tenant khác, cùng thân), `409 SHOP_CONTACT_STATE_CONFLICT`, `429 SHOP_CONTACT_LIMIT_EXCEEDED`, `400 CONSENT_REQUIRED` (`consent` khác `true`), `401 USER_REQUIRED` (không có người dùng trong token, chỉ gặp ở profile dev), `400 INVALID_REQUEST` (độ dài, định dạng, `topic` lạ, ký tự điều khiển), `400 VALIDATION_FAILED` (JSON/kiểu sai).
- Quyền: khách (role mẫu `CUSTOMER`) chỉ có `ShopContactController/create`; `STORE_MANAGER` có 5 khóa `SalesContactController/*`; `CASHIER`, `STOCK_KEEPER` không có (tenant admin gán thêm). Platform admin không đọc được (sales không có `@CrossTenantReadable`). Token dịch vụ `svc` của customer-api bị `403` (không `@StorefrontAccessible`). Seed ở `identity/V22__sales_shop_contact_permissions.sql` (6 khóa; backfill chỉ role còn nguyên mẫu, role đã chỉnh giữ nguyên và `WARNING` để gán tay). Quyền mới có hiệu lực ở lần đăng nhập/refresh kế tiếp.

### Danh sách khóa pha 2 (B2.3 seed)

Tổng 44 khóa với pha 1 (23). Mới ở pha 2 (21):

| Nhóm | Khóa |
|---|---|
| Thao tác đơn (nhân viên) | `SalesOrderController/confirm`, `SalesOrderController/applyDiscount`, `SalesOrderController/markPaid`, `SalesOrderController/markReadyForPickup`, `SalesOrderController/markShipping`, `SalesOrderController/complete`, `SalesOrderController/cancel` |
| Khách | `ShopController/placeOrder`, `ShopController/myOrders`, `ShopController/myOrder`, `ShopController/cancelMyOrder` |
| Danh mục công khai | `StorefrontController/list`, `StorefrontController/get`, `StorefrontController/preview` |
| Trả hàng | `SalesReturnController/create`, `SalesReturnController/createLate` (chỉ Quản lý cửa hàng), `SalesReturnController/get`, `SalesReturnController/listByOrder` |
| Hoàn tiền | `RefundController/search`, `RefundController/markRefunded` |
| Nhật ký | `SalesAuditController/search` |

Đường dẫn khớp (service `/internal/sales/...` ↔ BFF `/api/sales/...`; khách chỉ ở service `/internal/sales/shop/...` qua customer-api, không có `/api/shop/...`; storefront chỉ ở service). Role Khách hàng cần thêm `TitleController/lookup` của catalog (sales gọi catalog bằng token khách khi đặt hàng). `StorefrontController/*` seed cho role nhân viên nào muốn xem danh mục bằng token người dùng; token dịch vụ storefront không cần khóa.

Mã lỗi mới ở pha 2 (cộng vào bảng trên): `400 LATE_REASON_REQUIRED`; `404 RETURN_NOT_FOUND`, `REFUND_NOT_FOUND`; `409 RETURN_WINDOW_EXPIRED`, `RETURN_QUANTITY_EXCEEDED`, `ORDER_NOT_PAID`, `CLIENT_REQUEST_CONFLICT` (đường khách). `ORDER_STATE_CONFLICT` còn dùng cho khoản hoàn đã xử lý, phiếu trả đã đổi hàng, xung đột phiên bản.

### `lms-api` đã khớp pha 2 (B2.4)

`lms-api` giờ có đủ: 7 thao tác đơn (`SalesOrderController`: `confirmation`, `discount`, `payment`, `ready-for-pickup`, `shipping`, `completion`, `cancellation`), trả hàng/hoàn tiền/nhật ký (`SalesReturnController`: `POST /api/sales/returns`, `POST /api/sales/returns/late`, `GET /api/sales/returns/{id}`, `GET /api/sales/orders/{id}/returns`, `GET /api/sales/refunds`, `POST /api/sales/refunds/{id}/completion`, `GET /api/sales/audit-log`). Đường dẫn bằng đường dẫn service đổi `/internal/sales` thành `/api/sales`; BFF không kiểm quyền (sales-service kiểm), lỗi 4xx chuyển nguyên.

1. Body: `confirmation` nhận tùy chọn `{shippingFee?, version?}`; `ready-for-pickup`, `shipping`, `completion` nhận tùy chọn `{version?}` (không body vẫn chuyển được); `discount` `{orderDiscount?, lineDiscounts?[{lineNo, discount}], version?}`; `payment` `{method (CASH|BANK_TRANSFER|CARD|COD), reference?, version?}`; `cancellation` `{reason, version?}` (BFF chặn `reason` rỗng `400 VALIDATION_FAILED`).
2. `SalesOrderView` (lms-api) thêm `confirmedAt, paidAt, cancelledAt, cancelReason, exchangeOfReturnId`; `contact` của đơn online là người nhận (che một phần), của đơn POS là khách vãng lai. `GET /orders/{id}/contact` trả `address` cho đơn online.
3. `POST /api/sales/pos-orders` và `/discounted` nhận thêm `exchangeOfReturnId?` (id phiếu trả; `404 RETURN_NOT_FOUND`, `409 ORDER_STATE_CONFLICT` nếu đã đổi).
4. Trả hàng quá hạn là `POST /api/sales/returns/late` (không phải cờ trong body); `lateReason` không bị BFF chặn, thiếu thì sales-service trả `400 LATE_REASON_REQUIRED`. `disposition` `RESTOCK|WRITE_OFF`. `GET /orders/{id}/returns` trả mảng (không phân trang).
5. Query `GET /api/sales/refunds`: `{status? (PENDING|REFUNDED), page?, size?}`; `GET /api/sales/audit-log`: `{action?, aggregateType?, aggregateId?, from?, to?, page?, size?}`, `from`/`to` là chuỗi ISO-8601 chuyển nguyên.
6. Không có `/api/shop/**` ở `lms-api` (xem "Endpoint khách"); FE storefront gọi `customer-api`.
7. Thiết kế gốc 5.3 có `ShopController/listItems` (`GET /api/shop/items`): không cài, thay bằng `StorefrontController` (ADR-0011).

## Khóa theo role mẫu (migration identity V17, V20 và V22)

Role mẫu module SALES chép vào tenant khi bật SALES; tên role là dữ liệu, tenant admin sửa được. Tenant đã bật SALES trước V20: CASHIER và STORE_MANAGER chỉ nhận khóa pha 2 khi tập khóa còn TRÙNG ĐÚNG mẫu V17 (chưa bị chỉnh), role đã chỉnh giữ nguyên và log `WARNING` để gán tay; `CUSTOMER` được tạo (xem `identity-signup.md` về cờ `signup_default`).

| Nhóm khóa | CASHIER | STOCK_KEEPER | STORE_MANAGER | CUSTOMER |
|---|---|---|---|---|
| Bán POS: `PosOrderController/create` | có | không | có | không |
| `PosOrderController/createWithDiscount` | không | không | có | không |
| `SalesOrderController/search, get, receipt` | có | không | có | không |
| `SalesOrderController/getContact` | không | không | có | không |
| Thao tác đơn: `confirm, markPaid, markReadyForPickup, markShipping, complete, cancel` | có | không | có | không |
| `SalesOrderController/applyDiscount` | không | không | có (chỉ role này) | không |
| `SalesReturnController/create, get, listByOrder` (trả/đổi trong hạn) | có | không | có | không |
| `SalesReturnController/createLate` (trả quá hạn) | không | không | có (chỉ role này) | không |
| `RefundController/search, markRefunded` | không | không | có | không |
| `SalesAuditController/search` | không | không | có | không |
| `StorefrontController/list, get, preview` | không | không | có | không (đọc công khai dùng token dịch vụ) |
| `StockItemController/search, get` | có | có | có | không |
| `StockItemController/register, updatePrice, ledger`, `StockAdjustmentController/*`, `SupplierController/*`, `GoodsReceiptController/*` | không | có | có | không |
| `SalesSettingsController/get, update` | không | không | có | không |
| `ShopController/placeOrder, myOrders, myOrder, cancelMyOrder` | không | không | không | có |
| `TitleController/lookup` (catalog) | có | có | có | có |

Số khóa: CASHIER 16, STOCK_KEEPER 16 (không đổi), STORE_MANAGER 46 (41 + 5 khóa `SalesContactController/*` của V22), CUSTOMER 6 (5 + `ShopContactController/create` của V22; xem mục Liên hệ của khách). Phần suy ra (không có trong thiết kế gốc): Thu ngân thao tác đơn online tại quầy gồm cả `cancel` (đúng yêu cầu giao việc; lý do hủy vẫn bắt buộc); Quản lý cửa hàng nhận `StorefrontController/*` nhưng KHÔNG nhận `ShopController/*` (khóa "của tôi" của khách vô nghĩa với nhân viên, nguyên tắc tối thiểu); STOCK_KEEPER không có khóa đơn nào.

Danh sách 44 khóa (50 với 6 khóa liên hệ của V22) nay có đủ trong `permissions` (V17: 23 khóa pha 1 + `TitleController/lookup`; V20: 21 khóa pha 2). Đường dẫn của `ShopController/*` và `StorefrontController/*` ghi dạng service `/internal/sales/...` (không có đường BFF); test `SalesPermissionSeedMatchesCodeTest` ở sales-service so cả 50 khóa với code thật (3 file seed `V*__sales_*.sql`).

## Triển khai

- `app.services.sales-url` (`SALES_SERVICE_URL`, mặc định `http://localhost:8087`) trong `lms-api`; `docker-compose.yml` đặt `SALES_SERVICE_URL=http://sales-service:8087` cho lms-api.
- sales-service gọi `POST /internal/books/lookup` của catalog bằng token người dùng chuyển tiếp (`CATALOG_SERVICE_URL`, `lms.sales.catalog.base-url`); vai trò cần khóa `TitleController/lookup` (xem `docs/api/catalog-lookup.md`).
- Tắt phát Kafka bằng `lms.sales.events.enabled=false`.
