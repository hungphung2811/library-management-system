# ADR-0020: Thanh toán QR bằng cổng giả lập nội bộ (sandbox) cho POS

- Trạng thái: đã cài đặt ở sales-service và lms-api (xem mục 7 về phần chưa kiểm chứng). ADR-0021 mở rộng khung này cho mọi hình thức thanh toán (provider `MANUAL`); những chỗ dưới đây nói "CASH/CARD/BANK_TRANSFER giữ nguyên" đã bị ADR-0021 thay thế.
- Liên quan: ADR-0003 (khóa `Controller/method`, `@PublicEndpoint`), ADR-0006 (multi-tenant, RLS fail-closed), ADR-0008 (phạm vi đọc tất cả tenant), ADR-0010 (module SALES, tồn kho giữ/xuất), ADR-0004 (migration tập trung ở system-service).
- Hợp đồng cho FE: `docs/api/sales.md`, mục "Thanh toán QR cho POS bằng cổng giả lập".

## 1. Bối cảnh

POS (`PosOrderController`, `CommitPosOrderUseCase`) coi tiền đã thu xong ngoài hệ thống: đơn ghi `COMPLETED` + `PAID` ngay với CASH, BANK_TRANSFER, CARD. Chưa có trạng thái "chờ thanh toán", mã QR hay đường nhận kết quả từ cổng thanh toán. Cần demo luồng thu tiền QR đầy đủ (tạo mã, chờ, nhận kết quả, hết hạn, hủy) mà chưa có hợp đồng với cổng thật (VNPay, PayOS...). Quyết định: dựng cổng GIẢ LẬP nội bộ sau một port, để sau này thay bằng cổng thật chỉ đổi adapter và lớp nhận webhook.

## 2. Quyết định

### 2.1. Mô hình

- `PaymentMethod.QR` (chỉ POS; đơn online từ chối QR, `markPaid` thủ công từ chối QR).
- Đơn POS QR: trạng thái mới `OrderStatus.AWAITING_PAYMENT` + `PaymentStatus.UNPAID`, không có `PaymentRecord`. Máy trạng thái nằm trong `SalesOrder` (`createPosAwaitingPayment`, `settleQrPayment`, `cancelAwaitingPayment`). `createPos` thường từ chối QR để không bao giờ ghi nhầm một đơn QR thành PAID ngay.
- `PaymentIntent` (aggregate riêng, bảng `payment_intents`): `id, orderId, amount, reference, status (PENDING|PAID|EXPIRED|CANCELLED), provider ("SIMULATED"), providerTxnId, qrPayload, expiresAt, paidAt, createdAt, version`. `reference` = "LMS" + 10 ký tự in hoa/số bỏ ký tự dễ nhầm (`SecureRandom`, khoảng 2^50 giá trị), duy nhất toàn hệ thống (UNIQUE không kèm tenant vì webhook tra theo mã này). Hạn mặc định 10 phút (`app.payments.sandbox.intent-ttl-minutes`).
- Tồn kho dùng đúng cơ chế đơn online: tạo đơn thì GIỮ (`reserveAll`); thanh toán xong thì xuất thật (`fulfilAll`, onHand và reserved cùng giảm); hết hạn hoặc hủy thì nhả (`releaseAll`), đơn thành `CANCELLED` (không có khoản hoàn vì chưa thu). Mọi thao tác ghi một DB (sales_db) trong một transaction (đơn + lệnh + tồn + sổ cái + audit).

### 2.2. Port và adapter

`PaymentGatewayPort` (application): `provider()` và `createQr(reference, amount, expiresAt)`. `SimulatedPaymentGateway` (infrastructure/client) sinh chuỗi VietQR/EMVCo (`VietQrPayload`): TLV `00 01`, `01 12` (QR động), `38` (AID NAPAS `A000000727`, BIN + số tài khoản, dịch vụ `QRIBFTTA`), `53 704`, `54 <số tiền>`, `58 VN`, `59 <tên bỏ dấu>`, `62/08 <reference>` (nội dung chuyển khoản), `63 04 <CRC16/CCITT-FALSE>`. Tài khoản/ngân hàng demo lấy từ `app.payments.sandbox.{bank-bin, account-no, account-name}`. Phần nhận kết quả (xác thực chữ ký, đọc thân) là của từng cổng ở tầng web (`SimulatedWebhookProcessor`); use case `ProcessPaymentWebhookUseCase` không biết cổng nào.

### 2.3. Webhook công khai và tenant (điểm rủi ro chính)

`POST /internal/payments/webhook/simulated` là `@PublicEndpoint` và `permitAll` trong `SecurityConfiguration` của sales (đúng một đường dẫn, đúng POST), vì cổng không có JWT. Danh tính duy nhất là chữ ký `X-Signature` = HMAC-SHA256 trên thân thô với `app.payments.sandbox.webhook-secret`, so sánh hằng thời gian (`MessageDigest.isEqual`).

Thứ tự bắt buộc trong `SimulatedWebhookProcessor`: (1) sandbox bật và có bí mật, không thì 404; (2) kiểm chữ ký TRƯỚC mọi truy cập DB (nên không dò được mã tham chiếu khi chưa có bí mật); (3) phân tích JSON; (4) tra tenant theo `reference`; (5) `TenantContext.callAs(tenantId, ...)` rồi mới gọi use case, nên transaction mở sau khi đã có tenant và RLS đúng.

Bước 4 là chỗ duy nhất đọc xuyên tenant: `PaymentTenantResolverAdapter` bật phạm vi đọc tất cả tenant (ADR-0008: Hibernate bỏ bộ lọc `@TenantId` khi đọc, policy `platform_read_all` của RLS cho phép SELECT) chỉ để lấy `tenant_id` bằng JPQL (không native query), rồi khôi phục phạm vi cũ. Phạm vi phải đặt trước khi transaction mở (kết nối lấy theo transaction mới mang biến phiên), nên adapter không transactional và gọi truy vấn qua bean riêng (`PaymentTenantQuery`). Toàn bộ đọc/ghi sau đó chạy trong tenant của lệnh; use case đọc lại lệnh bằng `(tenantId, reference)` nên không thể ghi chéo tenant dù `reference` đoán trúng.

### 2.4. Idempotent, số tiền, hạn, tranh chấp

- Cùng `providerTxnId` gửi lại: 200 `ALREADY_PROCESSED`, không thu hai lần, không trừ tồn lần hai. Mã giao dịch khác cho lệnh đã PAID: 409. UNIQUE `(tenant_id, provider_txn_id)` là lớp chặn cuối (mã đã dùng cho lệnh khác: 409 `PAYMENT_DUPLICATE_TRANSACTION`).
- Số tiền phải bằng đúng `amount`, lệch thì 400 và không đổi gì. Lệnh quá hạn (kể cả `PENDING` chưa được job dọn) thì 409 `PAYMENT_INTENT_EXPIRED`; lệnh hủy thì 409 `PAYMENT_INTENT_CANCELLED`. Tiền về sau khi hết hạn/hủy không được tự ghi nhận (cần xử lý tay ngoài hệ thống).
- Khóa lạc quan `@Version` trên lệnh: webhook trùng đồng thời, hoặc webhook đồng thời với thu ngân hủy, chỉ một bên thắng; bên thua nhận 409 và rollback (cả tồn). Cổng thật nên thử lại khi 409.

### 2.5. Endpoint cho FE và khóa quyền

`GET /internal/payments/intents/{id}`, `POST .../cancel`, `POST .../simulate-paid` (`PaymentIntentController`, `@RequiresModule(SALES)`, khóa `PaymentIntentController/get|cancel|simulatePaid`, không dùng `@RequirePermission`). `simulate-paid` chỉ có khi sandbox bật (tắt thì 404 `PAYMENT_SANDBOX_DISABLED`): nó tự dựng thân JSON, ký bằng cùng bí mật rồi gọi chính `SimulatedWebhookProcessor.process`, nên đi đúng luồng kiểm chữ ký, tra tenant, idempotent như webhook thật. POST tạo đơn POS trả thêm khối `paymentIntent` (`PosOrderView` dùng `@JsonUnwrapped` để giữ nguyên các trường đơn cũ). lms-api chuyển nguyên `/api/sales/payments/intents/**`; webhook không đi qua lms-api.

### 2.6. Job dọn

`ExpirePaymentIntentsJob` (`@Scheduled`, `fixedDelay` 60 giây): duyệt từng tenant trong danh bạ `sales_tenants`, mỗi tenant chạy trong `TenantContext.callAs` (cùng cách với `AnonymizeWalkInJob`); mỗi lệnh quá hạn là một transaction riêng nên một lệnh lỗi (vừa được thu cùng lúc) không kéo theo lệnh khác. Chỉ có khi sandbox bật và `expire-job-enabled` (profile `demo-seed` tắt). `@Scheduled` không chạy nếu bật `spring.main.lazy-initialization` (hiện không bật).

## 3. Đánh đổi

| Quyết định | Lợi | Giá phải trả |
|---|---|---|
| Trạng thái mới `AWAITING_PAYMENT` thay vì tái dùng `PENDING_CONFIRMATION` | Không lẫn với hàng đợi xác nhận đơn online (thống kê "đơn chờ xác nhận" đếm đúng) | FE phải biết thêm một giá trị `status` |
| Giữ tồn (`reserved`) lúc tạo đơn | Cùng cơ chế đơn online, không bán trùng trong lúc chờ quét | Hàng bị giữ tối đa 10 phút khi khách bỏ dở; job mỗi 60 giây nên độ trễ nhả tồn tới ~70 giây |
| `reference` UNIQUE toàn cục, tra tenant xuyên tenant bằng phạm vi ALL | Webhook không cần JWT/tenant | Một điểm đọc xuyên tenant có chủ đích; an toàn nhờ kiểm chữ ký trước và chỉ lấy `tenant_id` |
| Trạng thái hiệu lực `EXPIRED` tính ngay trong phản hồi `get` | FE thấy hết hạn đúng hạn dù job chưa chạy | Trong cửa sổ đó DB vẫn `PENDING` (tồn vẫn giữ) |
| Gate toàn bộ (tạo QR, webhook, nút giả lập) bằng `sandbox.enabled` | Prod không có đường nào tới cổng giả | Khi gắn cổng thật phải thêm cờ/adapter riêng (xem mục 5) |
| Tách `PlacePosOrderUseCase.createWithPayment` mà vẫn giữ `create` | Không phá test và hợp đồng cũ | Hai tên gần nghĩa trong một lớp |

## 4. Bảo mật

- Webhook công khai nhưng chỉ chạy được khi có bí mật cấu hình: thiếu bí mật hoặc sandbox tắt thì 404 mọi đường (kể cả nút giả lập). Bí mật đọc từ biến môi trường `PAYMENTS_SANDBOX_WEBHOOK_SECRET`; chỉ profile dev có giá trị mặc định (`dev-only-sandbox-webhook-secret`, chỉ cho máy cá nhân). Prod đặt cứng `enabled=false` (không đọc từ biến môi trường để khỏi bật nhầm).
- Thân tối đa 4 KB; thân không phải JSON hoặc thiếu trường thì 400 sau khi chữ ký đúng; không log thân.
- Chữ ký không có nonce/timestamp: bản ghi lại của một webhook hợp lệ gửi lại chỉ cho kết quả idempotent (cùng `providerTxnId`) nên không thu hai lần, nhưng cổng thật nên thêm timestamp và cửa sổ hợp lệ.
- Audit dùng lại `ORDER_CREATED`, `ORDER_PAID`, `ORDER_CANCELLED` kèm `paymentIntentId`, `paymentReference`; không chứa dữ liệu cá nhân. Người thực hiện là `null` khi webhook/job.

## 5. Cách thay bằng cổng thật

1. Viết adapter `PaymentGatewayPort` mới (ví dụ `PayOsPaymentGateway`): `createQr` gọi API cổng, lưu `qrPayload`/URL thanh toán; đổi `provider()`.
2. Viết controller webhook riêng cho cổng đó (chữ ký, định dạng thân, mã trạng thái theo tài liệu cổng) gọi `ProcessPaymentWebhookUseCase` (không đổi); tra tenant bằng `PaymentTenantResolverPort` y như đây. Thêm timestamp/chống phát lại theo yêu cầu cổng, và location nginx riêng với giới hạn tần suất (webhook phải tới được sales-service từ Internet).
3. Bỏ `simulate-paid` và cờ sandbox cho cổng thật (hoặc giữ làm công cụ test, mặc định tắt). Thêm đối soát định kỳ (truy vấn trạng thái giao dịch ở cổng) cho lệnh `PENDING` quá hạn để bắt tiền về muộn.
4. Thêm migration nếu cần cột (ví dụ URL thanh toán, mã cổng).

## 6. Giới hạn đã biết

- Webhook là điểm công khai duy nhất của sales-service; bảo vệ chỉ bằng chữ ký và cờ cấu hình. Không dùng cho production.
- Tiền về sau hết hạn hoặc sau hủy không tự hoàn/ghi nhận (409); chưa có luồng "tiền thừa".
- Một lệnh cho một đơn; không thanh toán một phần, không đổi số tiền sau khi tạo; đơn QR không đi với đơn đổi hàng.
- Khóa quyền mới `PaymentIntentController/get|cancel|simulatePaid` chưa có migration identity gán cho mẫu role Thu ngân (c1) và Quản lý cửa hàng (c3): khóa tự đăng ký lần đầu được gọi, nhưng người không phải admin cần admin tenant gán. Nên thêm migration identity theo mẫu V26 (đăng ký khóa, gán mẫu, backfill role còn nguyên mẫu) và cập nhật `SalesPermissionSeedMatchesCodeTest` cùng lúc (test đó đang được phiên khác sửa nên chưa đụng tới).
- `reference` va chạm (xác suất rất nhỏ) báo 409 `PAYMENT_INTENT_STATE_CONFLICT`, gửi lại cùng `clientRequestId` sẽ tạo mã mới (transaction đã rollback).
- Doanh thu theo ngày (`SalesStatsQueryAdapter.grossByDay`) tính theo `paid_at IS NOT NULL` nên đơn `AWAITING_PAYMENT`/hủy chưa thu không vào doanh thu; danh sách đơn và báo cáo đơn theo kênh POS vẫn hiện chúng (lọc được theo `status`). Đơn QR đã hủy vì hết hạn có `paymentStatus = UNPAID`, không sinh khoản hoàn.

## 7. Kiểm chứng

- Test đơn vị/use case/WebMvc: `PaymentIntentDomainTest`, `QrPaymentUseCaseTest`, `VietQrPayloadTest`, `PaymentQrWebTest`, `PaymentSandboxDisabledWebTest` (sales-service); `SalesPaymentQrTest` (lms-api); ArchUnit của sales vẫn đạt.
- IT Testcontainers `PaymentQrIT` (vai trò `sales_app`, RLS thật): chạy bằng `-Dtest=PaymentQrIT` (cần Docker, Docker 29 thêm `-DargLine=-Dapi.version=1.44`).
