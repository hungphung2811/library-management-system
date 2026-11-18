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
| GET `/api/sales/settings/order-abuse-policy` | `OrderAbusePolicyController/get` | | Chính sách chống lạm dụng đặt hàng online của tenant: `{reservationMinutes, maxPendingOrders, maxOrdersPerDay, maxQuantityPerLine, maxTotalQuantity, ordersPerMinutePerUser, ordersPerMinuteTenant, version, updatedAt}`; chưa lưu thì trả mặc định (`lms.sales.abuse.defaults.*`: 15 phút, 5 đơn chờ, 20 đơn/24 giờ, 50 mỗi dòng, 100 mỗi đơn, 5 đơn/phút/khách, 300 đơn/phút/tenant) với `version` 0. Không cache |
| PUT `/api/sales/settings/order-abuse-policy` | `OrderAbusePolicyController/update` | Mọi trường bắt buộc kèm `version` đã đọc. `reservationMinutes` 0..10080 (0 = đơn chờ xác nhận không tự hết hạn), `maxPendingOrders` 1..1000, `maxOrdersPerDay` 1..10000, `maxQuantityPerLine` 1..10000, `maxTotalQuantity` 1..100000, `ordersPerMinutePerUser` 1..600, `ordersPerMinuteTenant` 1..100000 | chính sách mới (version tăng), audit `SETTINGS_CHANGED`; `409 SETTINGS_VERSION_CONFLICT`, `400 INVALID_SETTINGS` |
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
| POST `/internal/sales/shop/orders` | `ShopController/placeOrder` | `{lines[{titleId, quantity}], fulfilment (PICKUP/DELIVERY), paymentMethod, recipient{name, phone, address?}, clientRequestId}`; `address` bắt buộc khi `DELIVERY` | `201` + `Location`, `ShopOrderView`; `409 INSUFFICIENT_STOCK`, `409 STOCK_ITEM_NOT_REGISTERED`, `409 TITLE_NOT_AVAILABLE`, `400 INVALID_REQUEST` (gồm `clientRequestId` không phải UUID), `409 CLIENT_REQUEST_CONFLICT`, `422 IDEMPOTENCY_KEY_REUSED` (cùng khóa, cùng khách, khác nội dung), `429 ORDER_LIMIT_EXCEEDED` (vượt trần đơn chờ hoặc đơn trong 24 giờ), `429 ORDER_RATE_LIMITED`, `400 INVALID_QUANTITY` (vượt trần số lượng của tenant). Đơn mới có hạn giữ hàng `reservationMinutes` (mặc định 15 phút): quá hạn mà nhân viên chưa xác nhận thì job hủy đơn ("Hết hạn giữ hàng") và nhả tồn; đơn đã xác nhận không bị hết hạn |
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
| GET `/internal/sales/storefront/items` | `StorefrontController/list` | query `{keyword?, page?, size?}` (size tối đa 50, mặc định 20); `{items[{titleId, name, author, price, inStock, hasPreview, subtitle, publicationYear, edition, language, pageCount, format, translator, series, seriesNumber, coverImageUrl, tags}], total, page, size}`; một truy vấn trang + một lời gọi lô tới catalog |
| GET `/internal/sales/storefront/items/{titleId}` | `StorefrontController/get` | như mục danh sách + `description` (null nếu chưa nhập); không có `previewText`. Các trường bổ sung lấy từ catalog (`docs/api/catalog-title-extra-fields.md`), null/`tags` rỗng nếu chưa nhập |
| GET `/internal/sales/storefront/items/{titleId}/preview` | `StorefrontController/preview` | `{titleId, name, previewText}` |
| GET `/internal/sales/storefront/top-sold` | `StorefrontTopSoldController/topSold` | query `{days?, limit?}` (days mặc định 90, chặn 1..365; limit mặc định 100, chặn 1..100; giá trị lạ được chặn, không lỗi); mảng `[{titleId, quantity}]` sắp quantity giảm dần rồi titleId tăng dần |

`top-sold` (customer-api ghép "tác giả nổi bật"): số lượng bán theo đầu sách của đơn đã thanh toán (`paid_at`) trong `days` ngày gần nhất tính cả hôm nay giờ VN, trừ phần đã có phiếu trả hàng (cùng truy vấn với `GET /internal/sales/reporting/top-titles`). Chỉ trả `titleId` và `quantity` (không tên sách, doanh thu, tenant). Controller nằm ở context reporting (không phải `StorefrontController`) để tránh vòng phụ thuộc slice stock/reporting, nên khóa quyền của người dùng thường là `StorefrontTopSoldController/topSold` (chưa seed vào identity; token dịch vụ storefront không cần khóa).

Chỉ mặt hàng `active`; `price` = `sellingPrice` hoặc giá bìa làm tròn đồng; `inStock = available > 0`. KHÔNG bao giờ trả `onHand`, `reserved`, `available`, giá nhập, ngưỡng tồn, `tenant_id`, ISBN. Mặt hàng không có/không active: `404 STOCK_ITEM_NOT_FOUND`. `keyword` lọc theo tên/tác giả bằng `GET /internal/books/lookup?keyword=` của catalog (tối đa 200 đầu sách) rồi giao với mặt hàng đang bán. Lỗi catalog: `503 CATALOG_UNAVAILABLE`. Không có endpoint BFF `lms-api` cho nhóm này: `customer-api` gọi thẳng.

⚠️ Đề xuất, chưa có code (ADR-0019, hợp đồng ở `storefront-kham-pha.md`): `items` thêm `sort` (`newest|price_asc|price_desc`) và `inStock`; thêm `GET /internal/sales/storefront/suggestions` (`StorefrontController/suggestions`), `GET /internal/sales/storefront/banners` (`StorefrontBannerController/list`) và nhóm quản trị banner `/internal/sales/banners/**` ↔ `/api/sales/banners/**` (`SalesBannerController/{list,get,create,update,delete}`, không `@StorefrontAccessible`). Migration sales `V6` (snapshot giá bìa để sắp theo giá), `V7` (`storefront_banners`), identity `V26` (seed khóa).

### Storefront gộp biến thể theo tác phẩm (ADR-0026, pha 2b; migration sales `V14`)

Chỉ THÊM, tương thích ngược: mọi trường mới tùy chọn ở phía đọc, mặc định `group=EDITION` giữ hành vi cũ. Khóa quyền không đổi (`StorefrontController/list|get`). Hợp đồng đặt hàng KHÔNG đổi: khách chọn một biến thể rồi đặt bằng `titleId` của biến thể đó (`lines[].titleId`). Tồn kho, giá, giỏ hàng vẫn theo ấn bản; `stock_items.title_id`, UNIQUE `(tenant_id, title_id)` không đổi.

`GET /internal/sales/storefront/items` thêm query `group`:

| `group` | Hành vi |
|---|---|
| vắng hoặc `EDITION` | Như cũ: mỗi ấn bản một thẻ |
| `WORK` | Gộp các ấn bản cùng tác phẩm thành một thẻ. Giá trị khác (kể cả rỗng, `work`, `WORK `) là `400 INVALID_REQUEST`, kiểm trước khi chạm DB. Phân biệt hoa thường |

`ItemView` thêm (luôn có khóa, có thể null): `workId` (tác phẩm của ấn bản, lấy từ catalog rồi snapshot), `workTitle`, `variantCount`, `priceFrom`. Ở `EDITION`: `variantCount` và `priceFrom` là `null`. Ở `WORK`:
- thẻ mang ấn bản đại diện của nhóm (còn hàng trước, rồi giá hiệu lực thấp nhất, rồi mới nhất): `titleId`, `name`, `price`, ảnh... là của đại diện; `inStock` là của CẢ NHÓM (có ít nhất một ấn bản còn hàng);
- `variantCount` = số ấn bản đang bán của nhóm, `priceFrom` = giá thấp nhất trong nhóm (VND; nếu snapshot giá chưa có thì bằng giá của đại diện);
- `sort=newest` theo ấn bản mới đưa vào bán nhất của nhóm; `price_asc|price_desc` theo `priceFrom`; `inStock=true` giữ nhóm có ít nhất một ấn bản còn hàng; `total` đếm nhóm, không đếm ấn bản;
- có `keyword`: nhóm gộp trong bộ nhớ từ kết quả tìm của catalog, nên `variantCount`/`priceFrom` chỉ tính các ấn bản KHỚP từ khóa.
- Số câu SQL không tăng theo số dòng: 1 câu đếm, 1 câu gộp trang, 1 câu nạp ấn bản của các nhóm trong trang, cộng đúng 1 lời gọi lô `POST /internal/books/lookup` cho các ấn bản đại diện.

`GET /internal/sales/storefront/items/{titleId}`: `ItemDetailView` thêm `workId`, `workTitle` và `variants[]`, mỗi phần tử `{titleId, format, language, translator, edition, publicationYear, partNumber, partLabel, coverImageUrl, price, inStock}`. `variants` gồm cả chính ấn bản đang xem, chỉ mặt hàng đang bán có giá hiệu lực, sắp theo `partNumber` (null cuối), `publicationYear`, `language`, `format`, `titleId`. Ấn bản chưa gắn tác phẩm: `workId = null`, `variants` chỉ có chính nó. Phần của tập là nhãn trên ấn bản (`partNumber`, `partLabel`); bộ hộp là một ấn bản riêng.

Snapshot `stock_items.work_id` (V14, cùng khuôn `cover_price_snapshot` của ADR-0019): chỉ để gộp ở DB, không hiển thị hay dùng để bán. Ghi lúc đưa vào bán (`POST /internal/sales/stock-items` và import Excel `stock-items`, lấy từ `workId` của catalog), và tự sửa (tối đa 50 dòng mỗi lần xem) khi catalog trả `workId` khác, ví dụ sau `merge` tác phẩm. Snapshot lệch làm một ấn bản tạm hiện thành thẻ riêng ở `group=WORK` cho tới lần xem kế tiếp của chính nó hoặc của chi tiết ấn bản đó. Không cache tồn kho. Mặt hàng chưa có snapshot (`work_id` NULL) được coi là nhóm riêng (`COALESCE(work_id, title_id)`).

Migration `V14__stock_items_work_id.sql`: thêm cột `work_id UUID NULL`; backfill `md5('lms-work:' || title_id::text)::uuid` (đúng công thức catalog `V10`) lặp theo tenant với `app.tenant_id` vì `stock_items` FORCE RLS; index `idx_stock_items_work (tenant_id, work_id) WHERE active AND work_id IS NOT NULL`. Số `V13` đã bị `order_abuse_guard` chiếm nên dùng `V14`.

⚠️ Đổi mặc định sang `WORK` chỉ làm sau khi FE storefront chuyển xong (ADR-0026 mục 5).

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

## Chi tiết quản lý mặt hàng và ghi chú đơn (migration sales `V8`, identity `V28`)

Mọi trường mới đều tùy chọn (nullable), không backfill dữ liệu cũ; FE phải chịu được `null`. Tất cả chuỗi được cắt khoảng trắng đầu/cuối, chuỗi rỗng coi là `null`. Vi phạm giới hạn: `400` (`VALIDATION_FAILED` hoặc `INVALID_REQUEST`/`INVALID_PRICE`/`INVALID_QUANTITY`).

### Mặt hàng (`stock_items`)

| Trường JSON | Kiểu | Giới hạn / ý nghĩa |
|---|---|---|
| `sku` | string | tối đa 40; chuẩn hóa chữ HOA; duy nhất theo tenant khi có (trùng: `409 SKU_EXISTS`) |
| `supplier` | string | tối đa 255; tên nhà cung cấp/nhà phát hành (chuỗi tự do, không liên kết bảng nhà cung cấp của phiếu nhập) |
| `costPrice` | number (đồng) | `>= 0`; giá nhập. NỘI BỘ |
| `binLocation` | string | tối đa 64; vị trí trong kho/quầy, ví dụ `K1-A3` |
| `reorderQuantity` | integer | `>= 0`; số lượng đặt lại gợi ý |
| `lastRestockedAt` | ISO-8601 instant | tự cập nhật khi xác nhận phiếu nhập (`GoodsReceiptController/confirm`, ghi sổ cái `RECEIPT`, chỉ tăng); cũng nhận qua `details` |
| `note` | string | tối đa 500; ghi chú nội bộ. NỘI BỘ |

- `POST /api/sales/stock-items` (`StockItemController/register`): body thêm đủ 7 trường trên (tùy chọn). Phản hồi KHÔNG chứa `costPrice` và `note`.
- `GET /api/sales/stock-items` và `GET /api/sales/stock-items/{titleId}` (`search`, `get`; Thu ngân cũng có khóa này) và các phản hồi `updatePrice`, `adjust`, `stocktake` thêm `sku, supplier, binLocation, reorderQuantity, lastRestockedAt`. KHÔNG có `costPrice`, `note`.
- `GET /api/sales/stock-items/{titleId}/details` (khóa MỚI `StockItemController/getDetails`) trả `{titleId, sku, supplier, costPrice, binLocation, reorderQuantity, lastRestockedAt, note}`.
- `PUT /api/sales/stock-items/{titleId}/details` (khóa MỚI `StockItemController/updateDetails`): body `{sku?, supplier?, costPrice?, binLocation?, reorderQuantity?, lastRestockedAt?, note?}`, THAY TOÀN BỘ: trường vắng/`null` là xóa, riêng `lastRestockedAt` vắng thì giữ nguyên. Trả cùng dạng với GET details. `404 STOCK_ITEM_NOT_FOUND`, `409 SKU_EXISTS`.
- Storefront (`StorefrontController/*`) và `/public/**` KHÔNG trả bất kỳ trường nào ở trên.

### Đơn (`sales_orders`)

| Trường JSON | Kiểu | Ghi chú |
|---|---|---|
| `customerNote` | string, tối đa 500 | ghi chú của khách; đặt lúc tạo đơn (`POST /api/sales/pos-orders[/discounted]` body thêm `customerNote?`; đơn online `POST /internal/sales/shop/orders` thêm `customerNote?`), không sửa sau đó |
| `expectedDeliveryDate` | `yyyy-MM-dd` | ngày giao/nhận dự kiến; chỉ đơn `fulfilment` `DELIVERY` hoặc `PICKUP` (đơn `COUNTER`: `400 INVALID_REQUEST`) |
| `staffNote` | string, tối đa 500 | ghi chú nội bộ của nhân viên. NỘI BỘ |

- `SalesOrderView` (`search`, `get`, phản hồi POS và các thao tác đơn) thêm `customerNote`, `expectedDeliveryDate`. KHÔNG có `staffNote`. Phản hồi cho KHÁCH (`/internal/sales/shop/orders...`) thêm `customerNote`, `expectedDeliveryDate`, không có `staffNote`.
- `GET /api/sales/orders/{id}/notes` (khóa MỚI `SalesOrderController/getNotes`) trả `{id, customerNote, staffNote, expectedDeliveryDate, version}`.
- `PUT /api/sales/orders/{id}/notes` (khóa MỚI `SalesOrderController/updateNotes`): body `{staffNote?, expectedDeliveryDate?, version?}`, thay toàn bộ hai trường (vắng là xóa); `version` tùy chọn, lệch thì `409 ORDER_STATE_CONFLICT`. `customerNote` không đổi qua đây. Trả cùng dạng với GET notes. `404 ORDER_NOT_FOUND`.
- Ẩn danh khách vãng lai (B2.5) xóa luôn `customerNote` và `staffNote` của đơn đó (văn bản tự do có thể chứa dữ liệu cá nhân).

### Quyền và dữ liệu nhạy cảm

- Khóa mới: `StockItemController/getDetails`, `StockItemController/updateDetails`, `SalesOrderController/getNotes`, `SalesOrderController/updateNotes` (nâng tổng khóa sales lên 61, đối chiếu với code trong `SalesPermissionSeedMatchesCodeTest`). Migration identity `V28__sales_stock_order_details_permissions.sql` đăng ký khóa và gán cho mẫu role: `getDetails/updateDetails` cho STOCK_KEEPER và STORE_MANAGER, `getNotes/updateNotes` cho STORE_MANAGER; CASHIER và CUSTOMER không có. V28 KHÔNG backfill role của tenant đã tồn tại: tenant admin gán tay 4 khóa cho role cần dùng.
- `costPrice`, `note` (mặt hàng) và `staffNote` (đơn) chỉ đi qua các endpoint khóa riêng ở trên, không có ở danh sách/chi tiết thường, storefront hay `customer-api`.
- Ghi chú (`customerNote`, `staffNote`, `note`) và giá nhập không ghi vào log, audit hay event Kafka: audit `STOCK_ITEM_DETAILS_UPDATED` chỉ có tên trường đổi (`changedFields`), `ORDER_NOTES_UPDATED` chỉ có cờ `hasStaffNote`, `hasExpectedDeliveryDate`.
- Mã lỗi mới: `409 SKU_EXISTS`.

## Triển khai

- `app.services.sales-url` (`SALES_SERVICE_URL`, mặc định `http://localhost:8087`) trong `lms-api`; `docker-compose.yml` đặt `SALES_SERVICE_URL=http://sales-service:8087` cho lms-api.
- sales-service gọi `POST /internal/books/lookup` của catalog bằng token người dùng chuyển tiếp (`CATALOG_SERVICE_URL`, `lms.sales.catalog.base-url`); vai trò cần khóa `TitleController/lookup` (xem `docs/api/catalog-lookup.md`).
- Tắt phát Kafka bằng `lms.sales.events.enabled=false`.
- Che dữ liệu cá nhân là cấu hình theo môi trường: `lms.privacy.mask-personal-data` (env `LMS_PRIVACY_MASK_PERSONAL_DATA`, boolean, mặc định `true`). Giá trị `false` (đặt sẵn trong `application-dev.yml`; prod giữ `true`) khiến API nhân viên trả giá trị đầy đủ ở: `OrderView.contact.{name, phone, address}` (đơn POS và đơn online), `ShopContactSummaryView/DetailView.contact.{name, email, phone}`. Hợp đồng JSON không đổi (tên trường và cấu trúc giữ nguyên, kể cả các tên `*Masked` ở lớp ứng dụng); khi tắt che các trường này chứa giá trị đầy đủ nên FE không được giả định chúng luôn che. Không ảnh hưởng ẩn danh theo thời hạn (`piiAnonymizedAt`, dữ liệu đã xóa vẫn null), audit, log, Kafka (không bao giờ chứa dữ liệu cá nhân). Đề xuất (chưa làm): thêm cờ `piiMasked` vào phản hồi nếu FE cần biết đang che hay không.

## Thanh toán QR cho POS bằng cổng giả lập (ADR-0020, migration sales `V9`)

POS có thêm phương thức `paymentMethod = "QR"`. Đơn QR **không** thu ngay: tạo ở `status = AWAITING_PAYMENT`, `paymentStatus = UNPAID`, hàng được giữ (`reserved`, như đơn online), kèm một lệnh thanh toán (`paymentIntent`). Khi cổng báo đã nhận tiền, đơn thành `COMPLETED` + `PAID`, ghi bản ghi thu (`method = QR`, `reference` = mã tham chiếu) và hàng giữ chuyển thành xuất thật (FULFIL). Hết hạn hoặc hủy thì đơn thành `CANCELLED` và hàng giữ được nhả. CASH, BANK_TRANSFER, CARD cũng đi hai bước từ ADR-0021 (mục "POS hai bước" bên dưới, **thay đổi phá vỡ**). Cổng là **giả lập nội bộ (sandbox), không dùng cho production**: chỉ chạy khi `app.payments.sandbox.enabled=true` (dev bật, prod tắt).

### Thay đổi hợp đồng (BFF `lms-api`)

- `POST /api/sales/pos-orders` và `/api/sales/pos-orders/discounted`: `paymentMethod` nhận thêm `QR`. Với `QR` response `201` có thêm khối `paymentIntent`; các phương thức khác không có trường này (không thay đổi gì với FE cũ). Đơn QR không đi với `exchangeOfReturnId` (400) và tổng tiền phải lớn hơn 0.
- `SalesOrderView.status` có thêm giá trị `AWAITING_PAYMENT` (chỉ đơn POS QR); `paymentMethod` có thêm `QR`. FE phải coi `AWAITING_PAYMENT` là "chờ thanh toán", không phải đơn đã bán.
- Sandbox tắt: tạo đơn `QR` trả `400 PAYMENT_NOT_ENABLED`.

```json
{
  "id": "…", "orderNo": "O-000123", "status": "AWAITING_PAYMENT", "paymentStatus": "UNPAID",
  "paymentMethod": "QR", "grandTotal": 215000,
  "paymentIntent": {
    "id": "6b1d…", "orderId": "…", "reference": "LMS7K2M9QXPAB", "amount": 215000,
    "status": "PENDING", "provider": "SIMULATED",
    "qrPayload": "00020101021238…6304ABCD",
    "expiresAt": "2026-11-18T03:10:00Z", "paidAt": null, "createdAt": "2026-11-18T03:00:00Z"
  }
}
```

`qrPayload` là chuỗi VietQR/EMVCo (TLV, CRC16/CCITT-FALSE, số tiền, nội dung chuyển khoản = `reference`) để FE vẽ mã QR. Tài khoản/ngân hàng in vào mã là tài khoản demo trong cấu hình.

### Endpoint cho FE và khóa quyền (mới)

| Phương thức, đường dẫn (lms-api) | Service | Khóa quyền | Ghi chú |
|---|---|---|---|
| `GET /api/sales/payments/intents/{id}` | `GET /internal/payments/intents/{id}` | `PaymentIntentController/get` | FE thăm dò trạng thái (2 đến 3 giây/lần). `status` báo `EXPIRED` ngay khi quá `expiresAt` dù job dọn chưa chạy. |
| `POST /api/sales/payments/intents/{id}/cancel` | `POST /internal/payments/intents/{id}/cancel` | `PaymentIntentController/cancel` | Chỉ lệnh `PENDING`; hủy lại lệnh đã hủy trả nguyên trạng (200); lệnh đã `PAID`/`EXPIRED` thì `409 PAYMENT_INTENT_STATE_CONFLICT`. Đơn thành `CANCELLED`, tồn được nhả. |
| `POST /api/sales/payments/intents/{id}/simulate-paid` | `POST /internal/payments/intents/{id}/simulate-paid` | `PaymentIntentController/simulatePaid` | Nút "giả lập đã thanh toán": tự dựng thân webhook có ký rồi đi đúng luồng webhook. Sandbox tắt thì `404 PAYMENT_SANDBOX_DISABLED`. |
| (không qua lms-api) | `POST /internal/payments/webhook/simulated` | không có (`@PublicEndpoint`) | Webhook của cổng; chỉ xác thực bằng chữ ký HMAC. |

Cần `PaymentIntentController/*` trong role (admin tenant bỏ qua kiểm khóa). Migration identity `V29__payment_intent_permissions.sql` gán `get`, `cancel`, `confirm` cho mẫu Thu ngân và Quản lý cửa hàng (role còn nguyên mẫu được backfill; role đã chỉnh cần gán tay); `simulatePaid` chỉ đăng ký, không gán.

### Webhook `POST /internal/payments/webhook/simulated`

Header `X-Signature` = hex thường của `HMAC-SHA256(thân thô, app.payments.sandbox.webhook-secret)` (chấp nhận tiền tố `sha256=`); thân JSON `{"reference","amount","providerTxnId","status"}` (`status`: `PAID` hoặc `SUCCESS` là đã nhận tiền, giá trị khác như `FAILED` chỉ được ghi nhận, không đổi gì). Không cần JWT. Không đi qua lms-api/nginx của FE (cổng gọi thẳng sales-service; nếu muốn mở ra ngoài phải thêm location riêng vào nginx và giới hạn tần suất).

| Tình huống | HTTP | `code` |
|---|---|---|
| Sandbox tắt hoặc chưa đặt bí mật | 404 | `PAYMENT_SANDBOX_DISABLED` |
| Thiếu/sai chữ ký | 401 | `INVALID_WEBHOOK_SIGNATURE` |
| Thân không phải JSON hoặc thiếu trường | 400 | `INVALID_REQUEST` |
| Không có lệnh với `reference` | 404 | `PAYMENT_INTENT_NOT_FOUND` |
| Lệch số tiền | 400 | `PAYMENT_AMOUNT_MISMATCH` (không đổi gì) |
| Lệnh đã hết hạn | 409 | `PAYMENT_INTENT_EXPIRED` |
| Lệnh đã hủy | 409 | `PAYMENT_INTENT_CANCELLED` |
| Đã thanh toán bằng mã giao dịch khác, hoặc hai tiến trình tranh nhau | 409 | `PAYMENT_INTENT_STATE_CONFLICT` |
| Mã giao dịch đã dùng cho lệnh khác | 409 | `PAYMENT_DUPLICATE_TRANSACTION` |
| Thành công | 200 | `{"result":"PROCESSED","reference","intentStatus":"PAID"}` |
| Gửi lại cùng `providerTxnId` (idempotent) | 200 | `{"result":"ALREADY_PROCESSED",…}` |
| `status` khác `PAID`/`SUCCESS` | 200 | `{"result":"IGNORED",…}` |

### Sơ đồ trạng thái

```
Lệnh thanh toán:   PENDING --webhook đúng tiền, chưa hết hạn--> PAID
                   PENDING --quá expiresAt (job mỗi 60 giây)--> EXPIRED
                   PENDING --thu ngân hủy-------------------> CANCELLED
Đơn POS QR:        AWAITING_PAYMENT --PAID--> COMPLETED + PAID (ghi PaymentRecord QR, FULFIL tồn)
                   AWAITING_PAYMENT --EXPIRED/CANCELLED--> CANCELLED + UNPAID (RELEASE tồn, không có khoản hoàn)
```

`expiresAt` mặc định `createdAt + 10 phút` (`app.payments.sandbox.intent-ttl-minutes`). Job dọn chạy mỗi 60 giây (`expire-delay-ms`).

### Dữ liệu và cấu hình

- Migration sales `V9__payment_intents.sql`: bảng `payment_intents` (RLS ENABLE + FORCE, `platform_read_all`, `UNIQUE(reference)`, `UNIQUE(tenant_id, provider_txn_id)`, chỉ mục `(status, expires_at)`); thêm `QR` vào CHECK `payment_method` (đơn, bản ghi thu, khoản hoàn) và `AWAITING_PAYMENT` vào CHECK `status` của đơn.
- Cấu hình `app.payments.sandbox.*`: `enabled`, `webhook-secret` (biến `PAYMENTS_SANDBOX_WEBHOOK_SECRET`; dev có giá trị mặc định chỉ để chạy máy cá nhân), `bank-bin`, `account-no`, `account-name`, `intent-ttl-minutes`, `expire-delay-ms`, `expire-job-enabled`. Prod luôn `enabled=false`.

## POS hai bước cho mọi hình thức thanh toán (ADR-0021, migration sales `V10`) — THAY ĐỔI PHÁ VỠ HỢP ĐỒNG

Trước: `POST /api/sales/pos-orders[/discounted]` với CASH/CARD/BANK_TRANSFER trả đơn `COMPLETED` + `PAID` ngay. **Nay mọi phương thức đều hai bước**: tạo đơn ra `status = AWAITING_PAYMENT`, `paymentStatus = UNPAID`, giữ tồn, kèm `paymentIntent`; thu ngân bấm xác nhận thu (hoặc cổng QR báo) thì đơn mới `COMPLETED` + `PAID` và hàng mới xuất thật. Đơn đổi hàng (`exchangeOfReturnId`) cũng hai bước. Ngoại lệ: tổng tiền 0 (giảm giá hết) thu ngay, `paymentIntent` không có.

### FE biết lệnh là MANUAL hay QR bằng `paymentIntent.provider`

| `provider` | Phương thức đơn | `qrPayload` | Hành động của thu ngân |
|---|---|---|---|
| `MANUAL` | CASH, CARD, BANK_TRANSFER | `null` | Form xác nhận thu: CASH nhập tiền khách đưa (tùy chọn), CARD/BANK_TRANSFER nhập mã giao dịch (bắt buộc), rồi gọi `confirm` |
| `SIMULATED` | QR | chuỗi VietQR | Hiện mã QR, thăm dò `GET`; không có `confirm` (409 `PAYMENT_NOT_MANUAL`); dev có nút `simulate-paid` |
| `MOMO` | MOMO | URL `qrCodeUrl` của MoMo | Hiện mã QR, thăm dò `GET` (BE hỏi MoMo mỗi lần thăm dò), không có nút giả lập |
| `SEPAY` | SEPAY | chuỗi VietQR | Như `SIMULATED` nhưng không có nút `simulate-paid`: tiền "về" khi mô phỏng giao dịch ở my.sepay.vn (xem dưới) |

Phương thức của đơn có sẵn ở `paymentMethod` của đơn; `provider` quyết định hiển thị form hay mã QR.

### Endpoint mới

`POST /api/sales/payments/intents/{id}/confirm` (service `POST /internal/payments/intents/{id}/confirm`, khóa `PaymentIntentController/confirm`). Thân tùy chọn `ConfirmPaymentRequest`:

```json
{ "tenderedAmount": 500000, "transactionRef": "POS-778899" }
```

- `tenderedAmount` (số đồng, chỉ CASH, lớn hơn hoặc bằng `amount`; vắng thì bằng đúng `amount`). Gửi cho CARD/BANK_TRANSFER thì 400.
- `transactionRef` (tối đa 64 ký tự): bắt buộc với CARD/BANK_TRANSFER, tùy chọn với CASH. Lưu làm `reference` của bản ghi thu (không có thì dùng `paymentIntent.reference`).
- Response 200: `PaymentIntentView` với `status = "PAID"`, `paidAt`, và (nếu có) `tenderedAmount`, `changeDue` (tiền thối = tendered trừ amount), `transactionRef`. Xác nhận lại lệnh đã PAID: 200 nguyên trạng.

`PaymentIntentView` (lms-api) có thêm 3 trường nullable: `tenderedAmount`, `changeDue`, `transactionRef`.

| Tình huống | HTTP | `code` |
|---|---|---|
| Tiền khách đưa chưa đủ | 400 | `PAYMENT_AMOUNT_MISMATCH` |
| Thiếu mã giao dịch (thẻ/chuyển khoản), `tenderedAmount` cho thẻ/chuyển khoản, mã quá 64 ký tự | 400 | `INVALID_REQUEST` |
| Lệnh hết hạn, đã hủy | 409 | `PAYMENT_INTENT_STATE_CONFLICT` |
| Lệnh QR (SIMULATED) | 409 | `PAYMENT_NOT_MANUAL` |
| `simulate-paid` hoặc webhook cho lệnh MANUAL | 409 | `PAYMENT_NOT_SIMULATED` |
| Xung đột (hai người bấm cùng lúc, hoặc cùng lúc với hủy) | 409 | `PAYMENT_INTENT_STATE_CONFLICT` (bên thua thử lại và thấy 200 nguyên trạng hoặc 409) |

### Thay đổi khác

- Thời hạn lệnh MANUAL: `app.payments.manual.intent-ttl-minutes` (mặc định 10). Job dọn xử lý mọi lệnh quá hạn (hủy đơn, nhả tồn); không còn phụ thuộc sandbox: `app.payments.expire-job-enabled`.
- `GET /api/sales/orders/{id}/receipt` với đơn POS chưa thu (`AWAITING_PAYMENT` hoặc đã hủy chưa thu): 409 `ORDER_NOT_PAID` (chưa có phiếu thu).
- Doanh thu, top bán, thống kê theo `paid_at` nên chỉ tính từ lúc xác nhận thu. Danh sách đơn hiện đơn chờ với `status = AWAITING_PAYMENT`.
- Đơn đổi hàng chờ thanh toán bị hủy thì phiếu trả được đổi lại (chỉ mục duy nhất bỏ qua đơn `CANCELLED`).
- Audit: `ORDER_CREATED` (kèm `paymentIntentId`, `provider`), `ORDER_PAID` (kèm người bấm, `changeDue`), `ORDER_CANCELLED` khi hủy/hết hạn.
- Khóa mới `PaymentIntentController/confirm` (FE phải đồng bộ danh sách quyền màn POS); migration identity V29 đã gán cho Thu ngân và Quản lý cửa hàng.

## Thanh toán SePay Test mode cho POS (ADR-0028, migration sales `V15`)

Thêm phương thức POS `paymentMethod = "SEPAY"` (POST `/api/sales/pos-orders[/discounted]`), tương thích ngược: FE cũ không bị ảnh hưởng. Chọn cổng bằng đúng trường `paymentMethod` như `MOMO` (không có trường `provider` ở request). Response `201` có `paymentIntent` với `provider = "SEPAY"` và `qrPayload` là **chuỗi VietQR/EMVCo** (không phải URL; FE dựng thành ảnh QR như với `SIMULATED`), số tiền và nội dung chuyển khoản = `reference` (`LMS` + 10 ký tự). Đơn và lệnh theo cùng máy trạng thái QR (`AWAITING_PAYMENT` đến `COMPLETED` + `PAID`, hết hạn mặc định 10 phút). Tắt cổng thì `400 PAYMENT_NOT_ENABLED` ("Thanh toán SePay chưa được bật").

Không webhook, không cần địa chỉ công khai: mỗi lần FE gọi `GET /api/sales/payments/intents/{id}` mà lệnh còn PENDING, BE gọi `GET https://userapi-sandbox.sepay.vn/v2/transactions` tìm giao dịch tiền vào có nội dung chứa `reference`, đúng số tiền, xảy ra sau lúc tạo lệnh (dung sai 10 giây), rồi ghi nhận PAID. FE chỉ cần thăm dò như với QR/MoMo (khuyến nghị cách nhau hơn 3 giây; SePay giới hạn 3 yêu cầu/giây theo IP, vượt thì BE giữ PENDING và FE thăm dò lại). Nút/endpoint `simulate-paid` chỉ cho lệnh `SIMULATED` (SEPAY trả 409 `PAYMENT_NOT_SIMULATED`).

### Cách thử bằng "Mô phỏng giao dịch"

1. my.sepay.vn: bật **Test mode**, tạo tài khoản ngân hàng Test mode, và **API Access** (token Test mode, chỉ hiện một lần).
2. Đặt biến môi trường cho sales-service rồi khởi động lại: `PAYMENTS_SEPAY_ENABLED=true`, `PAYMENTS_SEPAY_TOKEN` (token Test mode), `PAYMENTS_SEPAY_BANK_BIN` (BIN 6 số của ngân hàng tài khoản test, in vào QR), `PAYMENTS_SEPAY_ACCOUNT_NO` (số tài khoản test), tùy chọn `PAYMENTS_SEPAY_ACCOUNT_NAME`, `PAYMENTS_SEPAY_BANK_ACCOUNT_ID` (UUID tài khoản Test mode để chỉ đọc giao dịch của tài khoản đó), `PAYMENTS_SEPAY_ENDPOINT` (mặc định `https://userapi-sandbox.sepay.vn`). Không có giá trị mặc định thật; prod phải tắt.
3. POS tạo đơn thanh toán SEPAY, ghi lại mã `reference` hiện trên màn hình.
4. my.sepay.vn (Test mode) > Mô phỏng giao dịch: chọn tài khoản test, **Tiền vào**, số tiền **đúng bằng** số tiền đơn (tối thiểu 1.000), **nội dung chứa `reference`** (không phân biệt hoa thường). Lần thăm dò kế tiếp đơn thành `COMPLETED` + `PAID`.

Không khớp thì lệnh giữ PENDING: sai số tiền, nội dung không chứa `reference`, hoặc giao dịch tạo trước lúc tạo lệnh. Một giao dịch SePay chỉ thanh toán một lệnh (mã `SEPAY-<id giao dịch>`, duy nhất theo tenant).

## Thanh toán online cho website qua SePay (ADR-0029)

⚠️ Đang cài đặt (S1 sales, C1 customer-api), chưa kiểm chứng bằng test chạy. Không đổi DB, không migration (V9/V15 đã mở `AWAITING_PAYMENT` và `SEPAY`), không khóa quyền mới.

**Đặt đơn.** `POST /internal/sales/shop/orders` (khóa `ShopController/placeOrder`) nhận thêm `paymentMethod = "SEPAY"` (với `PICKUP` và `DELIVERY`). Đơn `ONLINE` được tạo ở `AWAITING_PAYMENT` + `UNPAID`, giữ tồn, kèm một lệnh `PaymentIntent` PENDING (provider `SEPAY`, `qrPayload` VietQR, `reference` `LMS…`, hạn `app.payments.online.intent-ttl-minutes`, mặc định 15 phút). Đơn giao hàng: phí ship cố định của tenant chốt ngay lúc đặt và nằm trong `grandTotal` (quyết định Q1). Tắt cờ website: `400 PAYMENT_NOT_ENABLED`; QR/MOMO/CARD vẫn `400 INVALID_REQUEST`. `ShopOrderView` KHÔNG đổi tập trường (chỉ có giá trị mới `status=AWAITING_PAYMENT`, `paymentMethod=SEPAY`).

**`GET /internal/sales/shop/orders/{id}/payment`** (`@AuthenticatedOnly`, khóa dự kiến `ShopController/myOrderPayment` nhưng không kiểm khóa): trả `ShopPaymentView` (allowlist, record riêng):

```
{ orderId, orderNo, orderStatus, paymentStatus, paymentMethod,
  intent: null | { status: PENDING|PAID|EXPIRED|CANCELLED, provider: "SEPAY",
                   qrPayload, amount, currency: "VND", reference, expiresAt, paidAt } }
```

`qrPayload` chỉ có khi lệnh `PENDING`; `reference` luôn có; KHÔNG có `tenantId`, id lệnh, `providerTxnId`, `transactionRef`, `version`. Đơn của người khác hoặc không tồn tại: `404 ORDER_NOT_FOUND` cùng thân. Khi lệnh còn PENDING, lời gọi này làm sales hỏi SePay (qua bộ hãm: mỗi `reference` tối đa 1 lần/5 giây, toàn cục tối đa 2 lần/giây); khớp giao dịch thì lệnh `PAID`.

**`GET /internal/sales/shop/payment-options`** (`@AuthenticatedOnly`): `{"methods":[{"method":"COD","fulfilments":["DELIVERY"]},{"method":"CASH","fulfilments":["PICKUP"]},{"method":"BANK_TRANSFER","fulfilments":["PICKUP","DELIVERY"]},{"method":"SEPAY","fulfilments":["PICKUP","DELIVERY"],"intentTtlMinutes":15}],"deliveryFee":<phí ship cố định>}`. `SEPAY` chỉ có khi `PAYMENTS_SEPAY_ENABLED` và `PAYMENTS_ONLINE_SEPAY_ENABLED` cùng bật. Không cache (đọc cài đặt tenant bằng bean gốc).

**Hủy.** `POST /internal/sales/shop/orders/{id}/cancellation` (`ShopController/cancelMyOrder`, có sẵn): đơn `AWAITING_PAYMENT` được hủy cùng lệnh và nhả tồn, sales kiểm cổng một lần trước khi hủy; nếu cổng báo vừa nhận tiền thì ghi PAID và trả `409 ORDER_STATE_CONFLICT`. Khách KHÔNG tự hủy đơn đã `PAID` (`409 ORDER_STATE_CONFLICT`, liên hệ cửa hàng; quyết định Q2). Nhân viên hủy đơn đã PAID thì hệ thống tạo `Refund` như hiện nay. Hết hạn thanh toán: `ExpirePaymentIntentsJob` (60 giây/lần) kiểm cổng lần cuối rồi đặt lệnh `EXPIRED`, đơn `CANCELLED`, nhả tồn.

**Trạng thái sau khi PAID.** Đơn online qua cổng thành `PENDING_CONFIRMATION` + `PAID` (KHÔNG `COMPLETED`, không trừ kho thật; tồn vẫn ở `reserved` tới lúc giao như đơn online thường), audit `ORDER_PAID`. Nhân viên xác nhận/giao tiếp như đơn online khác. `confirm` với đơn đã PAID: phí ship bị khóa, gửi phí khác phí hiện có thì `409 ORDER_STATE_CONFLICT`. Đơn `AWAITING_PAYMENT` đếm vào trần đơn chờ của khách (chống lạm dụng) nhưng KHÔNG vào thống kê CMS "đơn chờ xác nhận".

**Cấu hình môi trường của sales-service** (khối dưới `app.payments`, không đặt secret trong repo):

| Biến | Mặc định | Ghi chú |
|---|---|---|
| `PAYMENTS_SEPAY_ENABLED` | `false` | Bật bean cổng SePay (ADR-0028) |
| `PAYMENTS_SEPAY_TOKEN` | rỗng | Token Test mode (my.sepay.vn > Test mode > API Access), bắt buộc khi bật, không commit |
| `PAYMENTS_SEPAY_BANK_BIN`, `PAYMENTS_SEPAY_ACCOUNT_NO` | rỗng | Tài khoản ngân hàng Test mode, bắt buộc khi bật |
| `PAYMENTS_SEPAY_ACCOUNT_NAME` | `LMS SEPAY TEST` | In vào VietQR |
| `PAYMENTS_SEPAY_BANK_ACCOUNT_ID`, `PAYMENTS_SEPAY_ENDPOINT` | rỗng / sandbox | Như ADR-0028 |
| `PAYMENTS_ONLINE_SEPAY_ENABLED` (mới) | `false` | Mở SEPAY cho website; chỉ có tác dụng khi bean SePay đã bật |
| `PAYMENTS_ONLINE_INTENT_TTL_MINUTES` (mới) | `15` | Hạn lệnh và hạn giữ tồn của đơn online |
| `PAYMENTS_SEPAY_MIN_QUERY_INTERVAL_MS` (mới) | `5000` | Hãm hỏi SePay theo lệnh |
| `PAYMENTS_SEPAY_MAX_QPS` (mới) | `2` | Hãm hỏi SePay toàn cục (SePay giới hạn 3 yêu cầu/giây/IP) |

**Cách thử (Test mode).** Mọi tenant dùng chung MỘT tài khoản SePay Test mode cấu hình toàn hệ thống (quyết định Q3; chỉ dev/thử, không bật kiểu này ở production). (1) Bật 4 biến đầu và `PAYMENTS_ONLINE_SEPAY_ENABLED=true`, khởi động lại sales-service. (2) Đăng nhập website, chọn "Chuyển khoản qua SePay" (từ `payment-options`), đặt đơn: trang hiện QR và mã `LMS…`. (3) my.sepay.vn (Test mode) > Mô phỏng giao dịch: đúng tài khoản Test mode, **Tiền vào**, số tiền **đúng bằng** `amount`, nội dung **chứa** `reference`. (4) Trong khoảng 3-8 giây (thăm dò 3 giây cộng hãm 5 giây) trang chuyển "Đã thanh toán", ở CMS đơn là `PENDING_CONFIRMATION` + `PAID`. Sai số tiền hoặc thiếu mã thì lệnh giữ PENDING rồi hết hạn sau 15 phút. Quét QR bằng app ngân hàng thật KHÔNG chuyển được tiền (tài khoản Test mode).

⚠️ Giới hạn đã biết: tiền về sau khi lệnh đã hết hạn/hủy không được khớp tự động (cần job đối soát, chưa thiết kế); bộ hãm theo instance (hiện một instance sales).
