# ADR-0025: Thanh toán MoMo sandbox cho POS

- Trạng thái: Chấp nhận (dev/test), chưa dùng cho production
- Liên quan: ADR-0020 (QR sandbox nội bộ), ADR-0021 (POS hai bước)

## Bối cảnh

Cổng QR giả lập (ADR-0020) không quét được bằng app thật. Cần một cổng sandbox có app điện thoại để thử luồng quét mã
như thanh toán thật mà không dùng tiền thật.

## Quyết định

- Thêm phương thức POS `MOMO` (migration sales `V12` mở các CHECK `payment_method`/`method`) và provider `MOMO` của
  `payment_intents`. Cổng `MomoPaymentGateway` cài `PaymentGatewayPort`, gọi `https://test-payment.momo.vn`:
  - Tạo: `POST /v2/gateway/api/create`, `requestType=captureWallet`; `qrCodeUrl` trả về là nội dung mã QR để app MoMo
    test quét. `orderId` = mã tham chiếu của lệnh (duy nhất toàn hệ thống).
  - Chữ ký HMAC-SHA256 hex trên chuỗi `key=value` nối `&` theo thứ tự chữ cái (create: accessKey, amount, extraData,
    ipnUrl, orderId, orderInfo, partnerCode, redirectUrl, requestId, requestType; query: accessKey, orderId,
    partnerCode, requestId), theo mã mẫu chính thức `github.com/momo-wallet/payment`. Đã gọi thử create/query với khóa
    test công khai: create `resultCode 0`, query chưa thanh toán `resultCode 1000`.
- Không dùng IPN: máy dev không có địa chỉ công khai. Khi FE thăm dò `GET /api/sales/payments/intents/{id}` mà lệnh còn
  PENDING thuộc cổng MoMo, `SyncGatewayPaymentUseCase` gọi `/v2/gateway/api/query`; `resultCode 0` thì ghi nhận bằng
  đúng luồng webhook (`ProcessPaymentWebhookUseCase`, idempotent theo mã giao dịch `MOMO-<transId>`, kiểm số tiền).
  Lỗi mạng/cổng thì giữ nguyên trạng thái. Hết hạn/hủy dùng luồng chung (job hết hạn, nhả tồn).
- Nút "Giả lập đã thanh toán" và webhook giả lập chỉ cho lệnh SIMULATED (MOMO trả 409 `PAYMENT_NOT_SIMULATED`).

## Cấu hình

`app.payments.momo.*` (env `PAYMENTS_MOMO_ENABLED|ENDPOINT|PARTNER_CODE|ACCESS_KEY|SECRET_KEY|REDIRECT_URL|IPN_URL`).
Mặc định tắt; profile `dev` bật với khóa test công khai trong mã mẫu của MoMo (không phải bí mật). Prod phải tắt hoặc
dùng khóa riêng qua biến môi trường.

## Rủi ro và việc chưa kiểm chứng

- WAF của MoMo trả 403 cho User-Agent của curl; client Java (`HttpClient`) vào được.
- `resultCode 0` của query được coi là đã thanh toán; chưa thử một giao dịch thanh toán thật bằng app MoMo test.
- `ipnUrl`/`redirectUrl` đang là địa chỉ giữ chỗ (`https://example.com/...`); MoMo vẫn gọi IPN tới đó nhưng không ảnh hưởng
  kết quả vì ta truy vấn chủ động.
- Query mỗi lần thăm dò (2,5 giây) sinh một lời gọi ra MoMo cho mỗi lệnh đang chờ; chấp nhận cho sandbox, cần giới hạn
  hoặc dùng IPN khi lên production.
