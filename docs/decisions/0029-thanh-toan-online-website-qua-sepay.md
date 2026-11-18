# ADR-0029: Thanh toán online cho website khách qua SePay theo mô hình POS hai bước

- Trạng thái: 🔄 Đang cài đặt (19/11/2026): người dùng đã chốt 4 câu hỏi mục 6; S1 (sales), C1 (customer-api) đang viết song song,
  N1 (nginx + tài liệu) đã viết nhưng chưa kiểm chứng; S2, T1, P1 chưa làm. Chỉ dev/test vì SePay đang ở Test mode
- Liên quan: ADR-0011 (customer-api), ADR-0013 (CORS storefront), ADR-0020 (QR sandbox, AWAITING_PAYMENT), ADR-0021 (POS hai
  bước), ADR-0025 (MoMo sandbox), ADR-0028 (SePay Test mode, thăm dò), ADR-0003 (phân quyền động), ADR-0006 (multi-tenant)

## 1. Bối cảnh và mục tiêu

Người dùng muốn website khách (FE `ui-java-1` → nginx `/public/t/{code}/**` → customer-api → sales `/internal/sales/shop/**`
bằng token của khách) thanh toán giống POS trong CMS. Đã chốt với người dùng: cổng duy nhất cho website là **SEPAY**
(ADR-0028); giữ nguyên COD, CASH, BANK_TRANSFER thủ công.

Hiện trạng đã đọc trong code (19/11/2026):

| | Hiện trạng |
|---|---|
| ℹ️ POS | `CommitPosPendingOrderUseCase`: đơn POS `AWAITING_PAYMENT` + `UNPAID`, giữ tồn (`reserveAll`), một `PaymentIntent` PENDING (`SEPAY`: `qrPayload` VietQR, `reference` `LMS…`, `expiresAt` = `app.payments.sandbox.intent-ttl-minutes`, mặc định 10). FE thăm dò `GET /internal/payments/intents/{id}`; `SyncGatewayPaymentUseCase.refresh` gọi `SepayPaymentGateway.queryPayment(reference, amount, createdAt)` (một lời gọi HTTP ra SePay mỗi lần thăm dò lệnh PENDING), khớp thì `ProcessPaymentWebhookUseCase` → lệnh PAID, đơn `COMPLETED`+`PAID`, `fulfilAll`. Hủy/hết hạn (`PaymentIntentUseCases.cancel/expireOne`, `ExpirePaymentIntentsJob`) → đơn `CANCELLED`, `releaseAll`. |
| ℹ️ Online | `PlaceOnlineOrderUseCase` → `CommitOnlineOrderUseCase`: đơn `ONLINE` `PENDING_CONFIRMATION` + `UNPAID`, giữ tồn có hạn (`reservationExpiresAt` theo chính sách tenant), khóa theo khách, chống lạm dụng (`countPendingOnline` chỉ đếm `PENDING_CONFIRMATION`). `SalesOrder.placeOnline` từ chối mọi `isGateway()`. |
| ℹ️ Domain chặn ONLINE | `SalesOrder.requireAwaitingPayment()` đòi `channel == POS`; `settleAwaitingPayment` luôn chuyển `COMPLETED`; `confirm()` tính lại `grandTotal = phí ship + dòng` (phí ship do nhân viên đặt khi xác nhận, mặc định `fixedShippingFee` của tenant khi giao hàng). |
| ℹ️ customer-api | `ShopDtos.PaymentMethod` chỉ `BANK_TRANSFER, COD, CASH`; `OrderView.cancellable` = `status == PENDING_CONFIRMATION` (tính ở `HttpShopClient`). `PublicOrderController` (`/api/public/v1/orders`, ADR-0027) dùng CHUNG `ShopDtos.PlaceOrderRequest`. |
| ℹ️ Quyền | Role Khách hàng (mẫu `c4`) có 4 khóa `ShopController/{placeOrder,myOrders,myOrder,cancelMyOrder}` (identity V20); allowlist token storefront mặc định `ShopController/*,…` (`AuthBeanConfiguration`). `GET /orders/count` là `@AuthenticatedOnly`. `@AuthenticatedOnly` trên controller có `@RequiresModule(SALES)` vẫn đòi tenant và module (`PermissionInterceptor` dòng 112-126). |
| ℹ️ nginx | `location ~ ^/public/t/[^/]+/orders…` chỉ có `public_order` (khóa theo IP, chỉ tính POST) và `public_tenant` (50r/s theo mã tenant). Location regex này thắng `location /public/` nên **GET dưới `/orders` không bị `public_read` theo IP**. |
| ℹ️ SePay | Giới hạn 3 yêu cầu/giây theo IP (ADR-0028); tài khoản Test mode là MỘT tài khoản cấu hình toàn hệ thống (`app.payments.sepay.*`), không theo tenant. |

Ràng buộc: không atomicity xuyên DB (ở đây mọi ghi đều trong `sales_db`, ổn); lời gọi HTTP ra SePay không được giữ transaction
mở; khách chỉ thấy đơn của chính mình (404 cùng thân); quy tắc nghiệp vụ nằm ở `domain`/`application`.

## 2. Quyết định

### 2.1. Phạm vi phương thức

- Website nhận thêm `paymentMethod = SEPAY` (enum đã có, không đổi DB: V15 đã mở CHECK, `sales_orders.status` đã có
  `AWAITING_PAYMENT` từ V9, không có CHECK ràng buộc kênh với trạng thái). SEPAY đi được với cả `PICKUP` và `DELIVERY`.
- COD (chỉ DELIVERY), CASH (chỉ PICKUP), BANK_TRANSFER: giữ nguyên luồng cũ (`PENDING_CONFIRMATION`, nhân viên đánh dấu đã thu).
- ⏭️ Không làm cho website: `QR` (cổng giả lập: webhook nội bộ HMAC + nút simulate-paid, vô nghĩa với khách thật và sẽ phải mở
  thêm endpoint giả lập ra công khai), `MOMO` (người dùng đã chọn SePay; MoMo cần redirect/app MoMo test, thêm một nhánh UX).
  Domain cho phép cổng online bằng một danh sách tường minh (`PaymentMethod.allowedOnlineGateway()` = chỉ SEPAY), không
  dùng `isGateway()` cho kênh online, để QR/MOMO vẫn bị từ chối.
- Bật/tắt riêng cho website: cờ mới `app.payments.online.sepay-enabled` (env `PAYMENTS_ONLINE_SEPAY_ENABLED`, mặc định
  `false`), chỉ có hiệu lực khi bean SePay tồn tại (`app.payments.sepay.enabled=true`). Lý do: bật SePay cho POS không được
  tự động mở thanh toán bằng tài khoản Test mode dùng chung ra mọi website tenant. Tắt thì đặt đơn SEPAY trả
  `400 PAYMENT_NOT_ENABLED`.
- FE biết có SEPAY hay không qua endpoint mới `GET …/payment-options` (mục 3.3), không tự đoán.

Phương án đã loại cho "chọn cổng": thêm trường `provider` riêng trong body (thừa, ADR-0028 đã chọn `paymentMethod`); bật cổng
theo cài đặt từng tenant (cần lưu tài khoản SePay theo tenant, có secret; chưa cần ở Test mode, xem câu hỏi Q3).

### 2.2. Luồng đơn online qua cổng và trạng thái sau khi PAID

So sánh trạng thái sau khi cổng báo đã nhận tiền:

| Phương án | Ưu | Nhược |
|---|---|---|
| A. `PENDING_CONFIRMATION` + `PAID` (chọn) | Khớp máy trạng thái online sẵn có (xác nhận → chuẩn bị/giao → hoàn tất; `complete()` đòi PAID đã thỏa); nhân viên vẫn kiểm địa chỉ, giao hàng; thống kê "đơn chờ xác nhận" tự đúng | Khách đã trả tiền nhưng còn chờ nhân viên |
| B. `CONFIRMED` + `PAID` luôn | Ít bước | Bỏ qua bước nhân viên nhận đơn (`confirmedBy` null, phí ship không ai đặt); lệch với COD/BANK_TRANSFER; đơn "tự xác nhận" khó giải thích ở CMS |
| C. `COMPLETED` + `PAID` như POS | Tái dùng nguyên `settleAwaitingPayment` | Sai nghĩa với giao hàng/nhận tại cửa hàng; `fulfilAll` trừ kho thật trước khi hàng rời kho |

Chọn A. Luồng:

```
Khách POST /orders {paymentMethod: SEPAY}
  → sales: SalesOrder.placeOnlineAwaitingPayment → ONLINE, AWAITING_PAYMENT, UNPAID, giữ tồn (reserveAll),
           PaymentIntent PENDING (provider SEPAY, qrPayload VietQR, reference, expiresAt = now + online TTL)
           reservationExpiresAt = null (hạn giữ do lệnh thanh toán quyết định), requestHash lưu như đơn online
  → FE hiện QR + mã tham chiếu + đếm ngược, thăm dò GET /orders/{id}/payment mỗi 3 giây
  → sales: lệnh PENDING + SEPAY → (qua bộ hãm, mục 2.4) SepayPaymentGateway.queryPayment
       khớp → ProcessPaymentWebhookUseCase: lệnh PAID; đơn ONLINE → PENDING_CONFIRMATION + PAID (paidAt, PaymentRecord SEPAY),
              KHÔNG fulfilAll (tồn vẫn reserved tới markShipping/complete như online thường), audit ORDER_PAID
  → nhân viên xác nhận/giao như đơn online thường
Hết hạn (ExpirePaymentIntentsJob) / khách hủy khi còn AWAITING_PAYMENT
  → lệnh EXPIRED/CANCELLED, đơn CANCELLED, releaseAll (PaymentIntentUseCases.release, mở cho ONLINE)
```

Quy tắc domain (đặt trong `SalesOrder`, không ở controller):

- `placeOnlineAwaitingPayment(...)`: như `placeOnline` nhưng trạng thái `AWAITING_PAYMENT`, chỉ nhận `SEPAY`; với `DELIVERY`
  nhận **phí ship đã tính sẵn** (use case truyền `settings.fixedShippingMoney()`), `grandTotal` gồm phí ship; `PICKUP` phí 0.
  Lý do: khách trả trước nên số tiền phải chốt lúc đặt; phương án "chỉ cho SEPAY với PICKUP" bị loại vì cắt mất giao hàng,
  "thu phí ship sau khi giao" bị loại vì đơn PAID mà còn nợ là trạng thái không mô hình hóa được (xem Q1).
- `requireAwaitingPayment()` chấp nhận cả ONLINE. Thanh toán xong: POS → `COMPLETED` (như cũ), ONLINE → `PENDING_CONFIRMATION`
  + `PAID`; phương thức settle trả về hiệu ứng tồn (POS `FULFIL`, ONLINE không đổi tồn) để use case không tự suy theo kênh.
- `confirm(fee, …)` trên đơn ONLINE đã `PAID`: phí ship bị khóa; `fee` gửi lên khác phí đang có thì `409 ORDER_STATE_CONFLICT`
  ("Đơn đã thanh toán, không đổi phí ship"); `OrderWorkflowUseCases.confirm` không áp phí cố định lần nữa cho đơn đã PAID.
  `applyDiscount` đã đòi `UNPAID` nên không đổi.
- `cancel(byCustomer=true)`: khách hủy được `AWAITING_PAYMENT` (đi luồng hủy lệnh, mục 2.3) và `PENDING_CONFIRMATION` khi
  `UNPAID`; đơn đã `PAID` khách **không tự hủy** (`409 ORDER_STATE_CONFLICT`, liên hệ cửa hàng; nhân viên hủy thì
  `cancelLoaded` đã tạo `Refund` như hiện nay). Xem Q2.
- `markPaid` thủ công vẫn từ chối `isGateway()` (giữ nguyên).

### 2.3. Hạn giữ tồn, hủy, bỏ dở, idempotency

- TTL lệnh online: cấu hình mới `app.payments.online.intent-ttl-minutes` (env `PAYMENTS_ONLINE_INTENT_TTL_MINUTES`, mặc
  định **15**), tách khỏi TTL quầy (10) vì khách cần mở app ngân hàng. Tồn giữ đúng bằng thời gian này; `reservationExpiresAt`
  của đơn để null (job `ExpireOnlineReservations` không đụng đơn AWAITING_PAYMENT vì nó chỉ xét `PENDING_CONFIRMATION`; đơn đã
  PAID cũng có hạn null nên không bị tự hủy).
- Khách bỏ dở (đóng tab): không cần làm gì, `ExpirePaymentIntentsJob` (60 giây/lần) hủy đơn và nhả tồn. Khách quay lại trang
  đơn: `GET /orders/{id}/payment` trả lại QR nếu còn hạn.
- Khách hủy chủ động: dùng lại `POST /orders/{id}/cancellation` (khóa `ShopController/cancelMyOrder` đã có). Đơn
  `AWAITING_PAYMENT` → use case mới **không transactional** gọi đồng bộ cổng một lần (bỏ qua bộ hãm theo lệnh, vẫn qua hạn mức
  toàn cục) trước khi hủy; cổng báo đã nhận tiền thì ghi PAID và trả `409 ORDER_STATE_CONFLICT` (FE đọc lại đơn), không thì
  gọi `PaymentIntentUseCases.cancel` (transaction riêng). Mục đích: giảm ca khách vừa chuyển tiền rồi bấm hủy.
- Hết hạn: `ExpirePaymentIntentsUseCase` với lệnh SEPAY kiểm cổng lần cuối (cùng cách trên, best-effort; cổng lỗi/hết hạn mức
  thì vẫn hết hạn) trước `expireOne`. Đề xuất thêm (P1, cùng đợt): `GatewayPayment` mang `paidAt` (từ `transaction_date`) và
  `PaymentIntent.markPaid` chấp nhận giao dịch có `paidAt < expiresAt` khi lệnh vẫn còn PENDING trong DB dù đồng hồ đã qua hạn
  (khách chuyển phút chót, lần thăm dò sau mới thấy). Áp cả POS; cần sửa `PaymentIntentDomainTest`.
- Chống lạm dụng: `countPendingOnline` đếm cả `AWAITING_PAYMENT` (nếu không, khách tạo hàng loạt đơn SEPAY khóa tồn 15 phút
  mỗi đơn mà không chạm trần đơn chờ). `ShopOrderUseCases.myOrderCounts.pending` dùng chung số này (huy hiệu hiện "đang chờ"
  gồm cả chờ thanh toán); thống kê CMS `countPendingOnlineOrders` GIỮ chỉ `PENDING_CONFIRMATION` (ADR-0020 đã tách
  AWAITING_PAYMENT). Đơn hết hạn thanh toán tính vào `countCancelledOnlineSince` như đơn hủy khác (đã đúng).
- Idempotency: không đổi cơ chế. Cùng `clientRequestId` + cùng nội dung (dấu vân tay đã gồm `paymentMethod`, `fulfilment`)
  trả lại đơn cũ ở MỌI trạng thái (kể cả đã hết hạn/hủy); khác nội dung `422 IDEMPOTENCY_KEY_REUSED`. FE muốn "đặt lại" sau
  khi hết hạn phải sinh `clientRequestId` mới. Không có "tạo lại QR cho đơn cũ" (⏭️, tránh nhiều lệnh/đơn và đua với job).
- Đơn tổng tiền 0 không xảy ra ở online (không giảm giá lúc đặt); domain vẫn từ chối lệnh 0 đồng (`PaymentIntent.create`).

### 2.4. Hãm lời gọi SePay khi thăm dò

Mỗi lần thăm dò một lệnh PENDING hiện sinh một lời gọi SePay; SePay giới hạn 3 yêu cầu/giây theo IP cho cả máy chủ. Nhiều
khách cùng chờ thanh toán sẽ chạm 429 và làm hỏng cả POS.

| Phương án | Đánh giá |
|---|---|
| Chỉ dựa nginx | Không đủ: nginx giới hạn theo IP khách, còn hạn mức SePay là của máy chủ |
| `RateLimiter` Redis (cache-common) | Fail-open và bị tắt khi `LMS_CACHE_ENABLED=false` (mặc định dev) nên đúng lúc thử thì không có tác dụng |
| Bộ hãm trong tiến trình bọc cổng (chọn) | Đơn giản, không phụ thuộc Redis; giới hạn theo instance (sales hiện một instance) |

Quyết định: decorator `ThrottledPaymentGateway implements PaymentGatewayPort` (tầng `infrastructure/client`) bọc
`SepayPaymentGateway` trong `PaymentsBeanConfiguration`:
- Theo lệnh: mỗi `reference` hỏi SePay tối đa 1 lần / `app.payments.sepay.min-query-interval-ms` (mặc định 5000); trong
  khoảng đó trả `Optional.empty()` (lệnh giữ PENDING, FE vẫn nhận phản hồi ngay từ DB).
- Toàn cục: token bucket `app.payments.sepay.max-queries-per-second` (mặc định 2, chừa 1 cho dư địa); hết token thì trả rỗng.
- Kênh hủy/hết hạn (mục 2.3) bỏ qua giới hạn theo lệnh nhưng vẫn qua giới hạn toàn cục.
- Không đổi `SyncGatewayPaymentUseCase` (vẫn bắt lỗi, giữ PENDING). Map theo `reference` dọn mục cũ (Caffeine nếu sales đã có,
  không thì map có giới hạn kích thước): ⚠️ be-dev kiểm phụ thuộc hiện có.

Lớp ngoài: nginx thêm vùng `public_poll` (mục 3.4); FE thăm dò 3 giây, dừng khi trạng thái cuối, khi quá `expiresAt` hoặc
tab bị ẩn (FE repo, ngoài phạm vi ADR này).

### 2.5. Khóa quyền: `@AuthenticatedOnly` cho endpoint đọc mới, tái dùng khóa cũ cho ghi

| Phương án | Ưu | Nhược |
|---|---|---|
| A. Khóa mới `ShopController/myOrderPayment` + migration identity `V39` (permissions, mẫu `c4`, backfill role "còn nguyên mẫu" như V22) | Tenant admin thu hồi được theo role | Tenant nào đã chỉnh role Khách hàng chỉ nhận WARNING, khách của họ bị 403 khi thanh toán (đã trả tiền mà không thấy kết quả); thêm migration, sửa `SalesPermissionSeedMatchesCodeTest`; quyền mới chỉ có khi khách đăng nhập lại |
| B. `@AuthenticatedOnly` + kiểm sở hữu trong use case (chọn) | Không migration, không đổi allowlist; cùng mẫu `GET /orders/count`; vẫn đòi tenant + module SALES; chỉ trả dữ liệu đơn của chính `sub` | Không thu hồi được theo role (không có nhu cầu: đã đặt được đơn thì phải xem được thanh toán của đơn đó) |

Chọn B cho hai endpoint ĐỌC mới (`orders/{id}/payment`, `payment-options`). Ghi thì không thêm endpoint: đặt đơn dùng
`ShopController/placeOrder`, hủy dùng `ShopController/cancelMyOrder` (đã có trong role và allowlist). Không thêm migration
identity. Quy tắc chọn cho sau này: endpoint chỉ đọc dữ liệu của chính người gọi và là hệ quả tất yếu của một quyền đã có thì
`@AuthenticatedOnly`; hành động có thể muốn cấm theo role thì khóa mới + migration.

⚠️ Chưa kiểm chứng: token của tài khoản `mustChangePassword` có tenant/module nhưng không có `permissions`; với
`@AuthenticatedOnly` nó vẫn xem được thanh toán của đơn của chính mình (rủi ro thấp, không đặt được đơn mới).

## 3. Thiết kế hợp đồng

### 3.1. sales-service (nội bộ, gọi bằng token khách)

| Endpoint | Quyền | Vào | Ra / lỗi |
|---|---|---|---|
| `POST /internal/sales/shop/orders` (có) | `ShopController/placeOrder` | thêm giá trị `paymentMethod: SEPAY` | `201 ShopOrderView` (`status=AWAITING_PAYMENT`, `paymentMethod=SEPAY`). `400 PAYMENT_NOT_ENABLED` khi tắt; `400 INVALID_REQUEST` với QR/MOMO/CARD |
| `GET /internal/sales/shop/orders/{id}/payment` (mới, `ShopController/myOrderPayment`) | `@AuthenticatedOnly` | id đơn | `200 ShopPaymentView`; đơn không phải của người gọi hoặc không tồn tại: `404 ORDER_NOT_FOUND` (cùng thân) |
| `POST /internal/sales/shop/orders/{id}/cancellation` (có) | `ShopController/cancelMyOrder` | lý do tùy chọn | đơn `AWAITING_PAYMENT` → hủy lệnh + đơn, nhả tồn; vừa được trả tiền → `409 ORDER_STATE_CONFLICT`; đơn `PAID` → `409` |
| `GET /internal/sales/shop/payment-options` (mới, `ShopController/paymentOptions`) | `@AuthenticatedOnly` | | `200 {"methods":[{"method":"COD","fulfilments":["DELIVERY"]},{"method":"CASH","fulfilments":["PICKUP"]},{"method":"BANK_TRANSFER","fulfilments":["PICKUP","DELIVERY"]},{"method":"SEPAY","fulfilments":["PICKUP","DELIVERY"],"intentTtlMinutes":15}],"deliveryFee":<fixedShippingFee>}` (SEPAY chỉ có khi bật) |

`ShopPaymentView` (allowlist, record riêng ở `infrastructure/web`, test so tập tên trường như `ShopOrderViewFieldsTest`):

```
{ orderId, orderNo, orderStatus, paymentStatus, paymentMethod,
  intent: null | { status: PENDING|PAID|EXPIRED|CANCELLED (trạng thái hiệu lực), provider: "SEPAY",
                   qrPayload, amount, currency: "VND", reference, expiresAt, paidAt } }
```

KHÔNG có: `tenantId`, `intent.id`, `providerTxnId`, `transactionRef`, `tenderedAmount`, `changeDue`, `createdAt` của lệnh,
`version`. `intent` null với đơn không qua cổng. `qrPayload` chỉ có khi lệnh `PENDING` (trạng thái khác trả null, tránh
khách quét mã đã hết hạn); `reference` luôn trả (khách cần mã này khi khiếu nại với cửa hàng).

`ShopOrderView` và `OrderView` (nhân viên): **không đổi tập trường** (giá trị mới: `status=AWAITING_PAYMENT`,
`paymentMethod=SEPAY`); `ShopOrderViewFieldsTest` giữ nguyên. Không nhúng lệnh vào danh sách đơn (tránh N+1 và giữ hợp đồng).

Use case mới `ShopPaymentUseCases` (application, không transactional): `myOrderPayment` = `findByIdAndCustomer` (404 nếu
không) → `intents.findByOrderId` → nếu PENDING thì `SyncGatewayPaymentUseCase.refresh` → nếu vừa PAID đọc lại đơn; `cancelAwaiting`
như mục 2.3; `paymentOptions` đọc cờ cấu hình + `GetSalesSettingsUseCase` (bean gốc, không cache).

### 3.2. Ghi nhận thanh toán cho ONLINE

`ProcessPaymentWebhookUseCase.execute`: sau `intent.markPaid`, gọi settle của đơn; hiệu ứng tồn `FULFIL` (POS) thì
`fulfilAll`, ONLINE thì không. `PaymentIntentUseCases.release` dùng `cancelAwaitingPayment` đã mở cho ONLINE. `confirm`
(MANUAL) chỉ POS, giữ nguyên. Audit ghi thêm `channel`.

### 3.3. customer-api

| Công khai | Gọi sales | Ghi chú |
|---|---|---|
| `POST /public/t/{code}/orders` (có) | như cũ | `ShopDtos.PaymentMethod` thêm `SEPAY` |
| `GET /public/t/{code}/orders/{orderId}/payment` (mới) | `GET …/shop/orders/{id}/payment` | `no-store`; orderId không phải UUID → 404 không gọi sales; DTO `OrderPaymentView` riêng (cùng allowlist 3.1) |
| `POST /public/t/{code}/orders/{orderId}/cancellation` (có) | như cũ | `cancellable` = `AWAITING_PAYMENT` hoặc (`PENDING_CONFIRMATION` và `UNPAID`) |
| `GET /public/t/{code}/payment-options` (mới) | `GET …/shop/payment-options` | cần đăng nhập (trang thanh toán đã đăng nhập) |

- `SecurityConfiguration`: thêm 2 matcher GET vào danh sách đường dẫn cần JWT khách (cạnh dòng 68-71).
- `PublicExceptionHandler.FORWARDED_CODES`: thêm `PAYMENT_NOT_ENABLED` ("Phương thức thanh toán này hiện không khả dụng");
  `CODE_ALIASES`: `PAYMENT_INTENT_NOT_FOUND → ORDER_NOT_FOUND`. `PAYMENT_GATEWAY_ERROR` không chuyển (sync đã nuốt; nếu lọt thì
  503 chung).
- `/api/public/v1/orders` (ADR-0027) dùng chung `PlaceOrderRequest`: ⏭️ chưa hỗ trợ SEPAY ở v1, `PublicOrderController` từ chối
  `SEPAY` bằng `400 PAYMENT_NOT_ENABLED` (nếu không, đối tác tạo được đơn chờ thanh toán mà không có endpoint xem QR).

### 3.4. nginx

Thêm trước location `/orders` hiện có (regex xét theo thứ tự khai báo):

```
limit_req_zone $binary_remote_addr zone=public_poll:10m rate=2r/s;   # khối http, cạnh public_read
location ~ "^/public/t/[^/]+/orders/[^/]+/payment(?:[;].*)?$" {
    limit_req zone=public_poll burst=10 nodelay;
    limit_req zone=public_tenant burst=100 nodelay;
    # security-headers, proxy tới customer_api như location /orders
}
```

2r/s/IP cho phép khoảng 6 khách sau cùng một NAT thăm dò 3 giây/lần. `payment-options` đi qua `location /public/` (đã có
`public_read`). ⚠️ Số liệu đề xuất, chưa đo.

### 3.5. Cấu hình

| Biến | Mặc định | Ghi chú |
|---|---|---|
| `PAYMENTS_SEPAY_ENABLED` | false | bật bean cổng (ADR-0028) |
| `PAYMENTS_SEPAY_TOKEN` | rỗng | token Test mode (my.sepay.vn > Test mode > API Access); bắt buộc khi bật; không commit |
| `PAYMENTS_SEPAY_BANK_BIN`, `PAYMENTS_SEPAY_ACCOUNT_NO` | rỗng | tài khoản ngân hàng Test mode; bắt buộc khi bật |
| `PAYMENTS_SEPAY_ACCOUNT_NAME` | `LMS SEPAY TEST` | in vào VietQR |
| `PAYMENTS_SEPAY_BANK_ACCOUNT_ID`, `PAYMENTS_SEPAY_ENDPOINT` | rỗng / sandbox | như ADR-0028 |
| `PAYMENTS_ONLINE_SEPAY_ENABLED` (mới) | false | mở SEPAY cho website |
| `PAYMENTS_ONLINE_INTENT_TTL_MINUTES` (mới) | 15 | hạn lệnh + giữ tồn của đơn online |
| `PAYMENTS_SEPAY_MIN_QUERY_INTERVAL_MS` (mới) | 5000 | hãm theo lệnh |
| `PAYMENTS_SEPAY_MAX_QPS` (mới) | 2 | hãm toàn cục |

Khối mới đặt dưới `app.payments` sẵn có (không tạo khối cấp cao thứ hai). `dev-start.ps1`/compose: be-dev chỉ chuyển tiếp biến
nếu chúng đã chuyển `PAYMENTS_SEPAY_*`; ⚠️ chưa kiểm, không sửa `.env`.

Cách thử (dev/khách thử): bật biến trên, đăng nhập website, chọn "Chuyển khoản qua SePay", đặt đơn → trang hiện QR và mã
tham chiếu `LMS…`; vào my.sepay.vn (Test mode) > Mô phỏng giao dịch: chọn đúng tài khoản Test mode, loại tiền vào, số tiền
ĐÚNG `amount`, nội dung CHỨA mã tham chiếu → trong vòng 3-8 giây (thăm dò 3 giây + hãm 5 giây) trang chuyển "Đã thanh toán", đơn
thành `PENDING_CONFIRMATION` + `PAID` ở CMS. Sai số tiền hoặc thiếu mã thì lệnh giữ PENDING rồi hết hạn. Quét QR bằng app ngân
hàng thật KHÔNG chuyển được (tài khoản Test mode).

## 4. Phân việc

Thứ tự phụ thuộc: S1 → (S2 ∥ C1 ∥ N1) → T1 → P1. Không có việc identity (không migration, không đổi allowlist).

| # | Ai | Phạm vi file | Việc |
|---|---|---|---|
| 🔄 S1 | be-dev (sales) | `ordering/domain/SalesOrder.java`, `PaymentMethod.java`, `PaymentIntent.java` (nếu làm P1 `paidAt`); `ordering/application/{CommitOnlineOrderUseCase, PlaceOnlineOrderUseCase, ProcessPaymentWebhookUseCase, PaymentIntentUseCases, ExpirePaymentIntentsUseCase, ShopOrderUseCases, OrderWorkflowUseCases(confirm), PaymentGatewayPort(GatewayPayment.paidAt)}`, mới `ShopPaymentUseCases`, `ShopPaymentResponse`; `ordering/infrastructure/client/ThrottledPaymentGateway` (mới), `SepayPaymentGateway` (trả paidAt); `infrastructure/config/{PaymentsBeanConfiguration, OrderingBeanConfiguration}`; `infrastructure/persistence/SalesOrderPersistenceAdapter.countPendingOnline`; `infrastructure/web/ShopController` (+2 method `@AuthenticatedOnly`), `ShopPaymentView` (mới), `PaymentOptionsView` (mới); `application.yml` (khối `app.payments.online`, `sepay.min-query-interval-ms`, `max-queries-per-second`) | Domain + use case + web theo mục 2, 3.1, 3.2. Commit online: tạo lệnh trong cùng transaction (createQr của SePay không gọi mạng). |
| ⏳ S2 | be-dev (sales, test) | `src/test/...` | Unit domain (`SalesOrder` ONLINE AWAITING_PAYMENT: settle → PENDING_CONFIRMATION+PAID, không fulfil; confirm khóa phí; khách không hủy PAID; QR/MOMO online bị từ chối); `QrPaymentUseCaseTest`/`SepaySyncUseCaseTest` thêm ca ONLINE; `ThrottledPaymentGateway` (theo lệnh, toàn cục, đồng hồ giả); WebMvc `ShopController` (bật `lms.security.permission.enabled`, `@MockitoBean PermissionKeyRegistrar`): 404 cùng thân cho đơn người khác, thiếu module 403, `ShopPaymentViewFieldsTest`; `SalesPermissionMatrixWebTest`/`SalesPermissionSeedMatchesCodeTest` vẫn xanh (endpoint `@AuthenticatedOnly`) |
| 🔄 C1 | be-dev (customer-api), song song S1 sau khi chốt DTO 3.1 | `shop/dto/ShopDtos.java`, `shop/client/ShopClient.java`, `shop/client/http/HttpShopClient.java` (`cancellable`, 2 method mới), `shop/web/OrderController.java`, controller mới cho `payment-options` (trong `shop/web`), `config/SecurityConfiguration.java`, `web/PublicExceptionHandler.java`, `publicv1/web/PublicOrderController.java` (chặn SEPAY) | Mục 3.3; WebMvc: allowlist tên trường `OrderPaymentView`, mã lỗi mới, 403 `SHOP_SESSION_MISMATCH`, `no-store`; sửa `PublicErrorRegressionTest`/`StorefrontRoutesWebTest` nếu chúng liệt kê đường dẫn |
| ⚠️ N1 | be-dev (hạ tầng + docs; đã viết `nginx.conf` + 3 file docs, chưa chạy `nginx -t`; README/compose/dev-start không có biến `PAYMENTS_*` nên không sửa) | `infrastructure/nginx/nginx.conf`, `docs/api/sales.md`, `docs/api/customer-api.md`, `docs/api/token-audience.md` (ghi rõ không đổi allowlist), README bảng biến môi trường nếu có | Mục 3.4, 3.5. Lưu ý working tree đang có thay đổi chưa commit ở `docs/api/{sales,customer-api}.md`: sửa bằng Edit có mục tiêu |
| ⏳ T1 | tester | | IT (Docker, `-DargLine=-Dapi.version=1.44`): sales IT đặt đơn SEPAY bằng vai trò `sales_app` + RLS, cổng SePay giả (WireMock hoặc port giả) → PAID → `stock.reserved` không đổi, `on_hand` không đổi; hết hạn → nhả tồn; hai tenant không thấy lệnh của nhau. E2E thủ công theo "Cách thử" 3.5 với Test mode thật (cần token). Hồi quy POS SEPAY/QR/MANUAL |
| ⏳ P1 | perf-tester | | 50-200 khách thăm dò đồng thời 3 giây/lần: số lời gọi SePay/giây ≤ cấu hình, không 429 từ SePay, p95 `GET …/payment` (phần lớn trả từ DB), nginx `public_poll` không chặn khách hợp lệ sau NAT |

FE (`ui-java-1`, ngoài repo này): trang chọn phương thức theo `payment-options`, trang thanh toán (QR từ `qrPayload`, mã tham
chiếu, số tiền, đếm ngược `expiresAt`, thăm dò 3 giây, dừng khi kết thúc/ẩn tab), nhãn `AWAITING_PAYMENT` "Chờ thanh toán",
nút hủy theo `cancellable`, "Đặt lại" sinh `clientRequestId` mới. CMS: nhãn `AWAITING_PAYMENT` cho đơn ONLINE.

## 5. Rủi ro và việc chưa kiểm chứng

- ⚠️ Tiền về sau khi lệnh đã EXPIRED/CANCELLED (khách chuyển phút chót, hoặc chuyển sau khi hủy): lệnh không còn PENDING nên
  không ai khớp, tiền nằm ở tài khoản mà đơn đã hủy. Giảm bằng kiểm cổng trước khi hủy/hết hạn và P1 `paidAt`; còn lại cần job
  đối soát giao dịch tiền vào không khớp lệnh (đã nêu ở ADR-0028 "Lên production"), chưa thiết kế ở đây.
- ⚠️ Bộ hãm theo instance: chạy nhiều instance sales thì tổng lời gọi SePay nhân lên; hiện một instance. Production cần webhook
  SePay thay thăm dò.
- ⚠️ Tài khoản SePay dùng chung toàn nền tảng: mọi tenant nhận tiền vào một tài khoản Test mode. Chấp nhận cho Test mode; KHÔNG
  được bật kiểu này ở production (xem Q3).
- ⚠️ Đua cổng báo PAID với khách hủy/job hết hạn: khóa lạc quan `version` của lệnh (bên thua 409, rollback) như POS; khách thấy
  409 thì FE đọc lại đơn. Chưa có IT riêng cho ONLINE.
- ⚠️ Giữ tồn 15 phút mỗi đơn chờ thanh toán: đối thủ có thể khóa hàng hiếm; giảm bằng trần đơn chờ (đếm cả AWAITING_PAYMENT),
  rate limit đặt đơn sẵn có. Số 15 phút chưa được người dùng xác nhận.
- ⚠️ Dạng JSON thật của SePay `/v2/transactions` ở sandbox và việc nội dung mô phỏng có bị sửa: vẫn chưa kiểm (kế thừa ADR-0028).
- ⚠️ Doanh thu tính theo `paid_at` nên đơn online SEPAY vào doanh thu lúc khách trả, trước khi giao; nhân viên hủy sau đó tạo
  Refund (trừ khi REFUNDED). Nhất quán với `markPaid` online hiện có nhưng khác thời điểm cũ (lúc nhân viên đánh dấu thu).
- ⚠️ `@AuthenticatedOnly` với token `mustChangePassword` (mục 2.5).
- ⚠️ Số nginx `public_poll` 2r/s burst 10 và hãm 2 QPS/5 giây là đề xuất, chưa đo.
- ℹ️ Không có thay đổi DB: đã kiểm V9/V15 (CHECK `status`, `payment_method`, `payment_records.method`) và `idx_payment_intents_order
  (tenant_id, order_id)` phục vụ `findByOrderId`. Không có migration identity.
- ℹ️ Production (cổng thật): token Live + tài khoản theo tenant, webhook SePay có xác thực (API key/chữ ký) đi qua
  `ProcessPaymentWebhookUseCase`, thăm dò chỉ còn là đối soát bù, `PAYMENTS_SANDBOX_ENABLED=false`, endpoint webhook giả lập
  không được mở ra nginx (hiện `/internal/` bị chặn ở nginx, giữ nguyên).

## 6. Quyết định đã chốt của người dùng

Cả bốn câu hỏi đã được người dùng chốt theo khuyến nghị:

- ✅ Q1. Phí ship: thu luôn phí ship cố định của tenant lúc đặt, số tiền chốt ngay khi đặt (SEPAY với cả `PICKUP` và `DELIVERY`).
- ✅ Q2. Khách KHÔNG tự hủy đơn đã thanh toán (`PENDING_CONFIRMATION` + `PAID`): khách liên hệ cửa hàng; nhân viên hủy thì hệ
  thống tạo khoản hoàn như hiện nay.
- ✅ Q3. Giai đoạn thử dùng chung MỘT tài khoản SePay Test mode cho mọi tenant; tài khoản theo tenant để ADR riêng khi lên production.
- ✅ Q4. Hạn thanh toán/giữ hàng cho đơn website: 15 phút (`PAYMENTS_ONLINE_INTENT_TTL_MINUTES`).

ℹ️ Đây là quyết định đã chốt, chưa phải kết quả đã kiểm chứng: rủi ro ở mục 5 về số 15 phút (chưa đo mức khóa hàng) vẫn mở.
