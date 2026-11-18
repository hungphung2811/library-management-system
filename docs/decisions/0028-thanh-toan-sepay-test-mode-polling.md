# ADR-0028: Thanh toán SePay Test mode cho POS bằng thăm dò (polling)

- Trạng thái: Chấp nhận (dev/test), chưa dùng cho production
- Liên quan: ADR-0020 (QR sandbox nội bộ), ADR-0021 (POS hai bước), ADR-0025 (MoMo sandbox, cùng kiểu thăm dò)

## Bối cảnh

Cần một cách "đặt trạng thái đã thanh toán" từ bên ngoài bằng điện thoại, không có địa chỉ công khai để nhận webhook.
SePay có Test mode (sandbox) trong my.sepay.vn: tạo tài khoản ngân hàng thử, token API riêng, và nút "Mô phỏng giao dịch"
tạo giao dịch tiền vào.

## Phương án

- Webhook của SePay: bị loại vì SePay phải gọi được máy này (cần địa chỉ công khai hoặc tunnel).
- Thăm dò (chọn): BE chủ động đọc sổ giao dịch bằng token Test mode, theo cách ADR-0025 đã dùng cho MoMo.

## Quyết định

- Phương thức POS mới `SEPAY` và provider `SEPAY` (migration sales `V15` mở các CHECK `payment_method`/`method`, như V12).
  Chọn cổng bằng `paymentMethod` (không thêm trường `provider`).
- `SepayPaymentGateway` (`PaymentGatewayPort`):
  - `createQr`: chuỗi VietQR dựng bằng `VietQrPayload` với BIN, số tài khoản, tên lấy từ cấu hình, nội dung chuyển khoản =
    `reference` (`LMS` + 10 ký tự, 13 ký tự). Không dùng cơ chế payment code/tiền tố của SePay: ta tự khớp theo nội
    dung giao dịch, nên không cần cấu hình "Cấu trúc mã thanh toán".
  - `queryPayment(reference, amount, notBefore)` (phương thức mới của port, mặc định gọi bản cũ nên MoMo/Simulated không đổi):
    `GET {endpoint}/v2/transactions?transfer_type=in&amount_in_min=A&amount_in_max=A&transaction_content=<reference>&transaction_date_sort=desc&per_page=100[&bank_account_id=...]`
    với `Authorization: Bearer <token>`, timeout 5 giây kết nối/10 giây yêu cầu, chỉ GET, không log token. Sau khi lọc ở
    SePay còn kiểm lại phía ta từng giao dịch: `transfer_type = in`, `amount_in` đúng số tiền, `transaction_content` chứa
    `reference` (không phân biệt hoa thường), `transaction_date` (giờ Việt Nam, `yyyy-MM-dd HH:mm:ss`) không trước thời điểm
    tạo lệnh trừ 10 giây (dung sai đồng hồ). Khớp thì trả `providerTxnId = "SEPAY-" + id` (UUID giao dịch).
  - 401/403/429/5xx, mất kết nối, timeout: ném `SalesException(PAYMENT_GATEWAY_ERROR)`; `SyncGatewayPaymentUseCase` bắt và giữ
    PENDING, FE thăm dò lại.
- `SyncGatewayPaymentUseCase.refresh` truyền số tiền và `createdAt` của lệnh; ghi nhận PAID qua đúng
  `ProcessPaymentWebhookUseCase` (kiểm số tiền, idempotent theo `providerTxnId`, `UNIQUE (tenant_id, provider_txn_id)`).
- Cấu hình `app.payments.sepay.*` (env `PAYMENTS_SEPAY_ENABLED|ENDPOINT|TOKEN|BANK_BIN|ACCOUNT_NO|ACCOUNT_NAME|BANK_ACCOUNT_ID`).
  Mặc định tắt, không có token hay tài khoản mặc định (kể cả profile dev); bật thì thiếu token/BIN/số tài khoản là lỗi khởi
  động. Prod phải tắt.

## Nguồn tài liệu đã đọc (18/11/2026)

- https://developer.sepay.vn/en/sepay-api/v2/sandbox : base URL sandbox `https://userapi-sandbox.sepay.vn/v2`, token Test
  mode chỉ chạy trên base URL này, header `Authorization: Bearer`, ví dụ `GET /v2/transactions`.
- https://developer.sepay.vn/en/sepay-api/v2/giao-dich/danh-sach : tham số `transfer_type`, `amount_in_min/max`,
  `transaction_content` (LIKE), `transaction_date_from/to`, `bank_account_id`, `since_id`, `transaction_date_sort`, `per_page` (tối
  đa 100); trường `id`, `transaction_date`, `amount_in`, `transaction_content`, `transfer_type`, `reference_number`.
- https://developer.sepay.vn/en/sepay-api/v2/xac-thuc : giới hạn 3 yêu cầu/giây theo IP, 429 kèm `Retry-After`, 401 có
  `error_code: unauthorized`.
- https://developer.sepay.vn/en/tien-ich-khac/test-mode và các trang con `mo-phong-giao-dich`, `api-access`, `cau-hinh`,
  `mo-phong-tao-ma-vietqr`: nội dung mô phỏng tối đa 200 ký tự, số tiền 1.000 đến 499.000.000, thời gian giao dịch là giờ
  hiện tại (UTC+7), tối đa 500 giao dịch mô phỏng/ngày, token Test mode tạo ở Test mode > API Access.
- Gọi thử `GET https://userapi-sandbox.sepay.vn/v2/transactions` không token: 401 `unauthorized`, tức endpoint tồn tại ở sandbox.

## Chưa xác minh (không bịa)

- Chưa gọi SePay thật bằng token Test mode: chưa thấy tận mắt dạng JSON của `GET /v2/transactions` ở sandbox. Tên trường dựa
  trên trang "danh sách giao dịch" (trang này ghi host production `userapi.sepay.vn`; trang sandbox xác nhận cùng đường dẫn ở
  host sandbox). Trang `api-access` chỉ thử `GET /v2/bank-accounts`, không xác nhận riêng transactions ở sandbox.
- Chưa rõ giao dịch mô phỏng có nội dung đúng như nhập hay bị SePay đổi/cắt; ta khớp kiểu "chứa" không phân biệt hoa thường.
- Chưa rõ `transaction_content` có được SePay chuẩn hóa dấu cách hay không (mã tham chiếu không có dấu cách nên ít ảnh hưởng).
- Định dạng `transaction_date` suy ra từ ví dụ `"2025-02-20 14:15:00"`; không đọc được thì giao dịch bị bỏ qua (an toàn).
- QR sinh ra mang BIN/số tài khoản Test mode nên app ngân hàng thật không chuyển được; tiền chỉ "về" qua Mô phỏng giao dịch.

## Rủi ro

- Giới hạn tần suất: 3 yêu cầu/giây theo IP, mỗi lần FE thăm dò (khoảng 2,5 giây) một lệnh PENDING sinh một lời gọi; nhiều quầy cùng
  lúc hoặc dùng chung IP với dịch vụ khác có thể gặp 429 (giữ PENDING, thử lại). Production cần webhook hoặc gom truy vấn.
- Khớp nhầm: hai lệnh cùng số tiền không lẫn vì nội dung chứa mã khác nhau; một giao dịch chỉ thanh toán một lệnh nhờ
  `UNIQUE (tenant_id, provider_txn_id)`; giao dịch cũ hơn lệnh bị loại. Người mô phỏng cố ý ghi cùng `reference` hai lần chỉ
  ra hai giao dịch, lần đầu khớp đã dùng.
- Lệch đồng hồ giữa máy chạy BE và SePay vượt 10 giây có thể loại nhầm giao dịch hợp lệ (lệnh vẫn PENDING rồi hết hạn);
  người thao tác quét/mô phỏng thường sau lúc tạo lệnh nhiều giây nên hiếm gặp.
- Test mode giới hạn 500 giao dịch mô phỏng mỗi ngày.

## Lên production sau này

Test mode không dùng cho production. Production dùng token Live (`https://userapi.sepay.vn`, tài khoản thật) và nên: thêm
webhook SePay (xác thực chữ ký/Api key, idempotent theo mã giao dịch, đi qua `ProcessPaymentWebhookUseCase`), giữ thăm dò làm
đối soát bù (dùng `since_id` + `transaction_date_sort=asc`), job đối soát định kỳ giao dịch tiền vào chưa khớp lệnh nào,
và giới hạn tần suất/hàng đợi gọi SePay.
