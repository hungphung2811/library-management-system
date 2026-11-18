# ADR-0021: POS hai bước cho mọi hình thức thanh toán (thay đổi phá vỡ hợp đồng)

- Trạng thái: đã cài đặt ở sales-service và lms-api. Người dùng đã chốt qua hỏi đáp.
- Liên quan: ADR-0020 (khung QR: `PaymentIntent`, `AWAITING_PAYMENT`, giữ/xuất/nhả tồn, job dọn), ADR-0003 (khóa `Controller/method`), ADR-0010 (sales, tồn kho), ADR-0006 (RLS).
- **Thay đổi phá vỡ hợp đồng FE**: `POST /api/sales/pos-orders[/discounted]` không còn trả đơn `COMPLETED` + `PAID` ngay với CASH, CARD, BANK_TRANSFER.

## 1. Bối cảnh

Trước đây POS ghi đơn PAID ngay với CASH/CARD/BANK_TRANSFER vì coi như tiền đã thu ngoài hệ thống. Điều này sai về nghiệp vụ: hệ thống ghi nhận doanh thu và trừ kho trước khi thu ngân thật sự nhận tiền, và không có bằng chứng thu (ai xác nhận, tiền khách đưa, tiền thối, mã giao dịch thẻ/chuyển khoản). ADR-0020 đã dựng khung hai bước cho QR; ADR này dùng lại cho mọi hình thức.

## 2. Quyết định

1. Tạo đơn POS (kể cả `/discounted`, kể cả đơn đổi hàng `exchangeOfReturnId`) luôn ra `AWAITING_PAYMENT` + `UNPAID`, giữ tồn (`reserveAll`) và một `PaymentIntent` PENDING. QR: `provider = SIMULATED`, có `qrPayload`, webhook tự xác nhận (không đổi). CASH/CARD/BANK_TRANSFER: `provider = MANUAL`, `qrPayload = null`, có `reference` (mã đối soát), `amount`, `expiresAt`.
2. `POST /internal/payments/intents/{id}/confirm` (khóa `PaymentIntentController/confirm`, module SALES): thu ngân xác nhận lệnh MANUAL còn hạn. Thân `{tenderedAmount?, transactionRef?}`:
   - CASH: `tenderedAmount` tùy chọn (vắng thì bằng đúng số tiền), phải lớn hơn hoặc bằng `amount`, `changeDue = tendered - amount`. CARD/BANK_TRANSFER: không có `tenderedAmount` (400), `transactionRef` bắt buộc (tối đa 64 ký tự).
   - Hệ quả (một transaction sales_db): đơn `COMPLETED` + `PAID`, `PaymentRecord` với phương thức thật, `recordedBy` = người bấm, `reference` = `transactionRef` hoặc `reference` của lệnh; tồn giữ thành xuất thật (`fulfilAll`); audit `ORDER_PAID` kèm người bấm.
   - Idempotent: xác nhận lại lệnh đã PAID trả 200 nguyên trạng. Lệnh EXPIRED/CANCELLED hoặc quá hạn: 409 `PAYMENT_INTENT_STATE_CONFLICT`. Lệnh không phải MANUAL (QR): 409 `PAYMENT_NOT_MANUAL`. Ngược lại webhook và `simulate-paid` chỉ nhận lệnh SIMULATED (MANUAL thì 409 `PAYMENT_NOT_SIMULATED`, kể cả khi biết `reference` và có chữ ký đúng).
3. Hủy và hết hạn dùng chung cơ chế ADR-0020 (`ExpirePaymentIntentsJob` xử lý mọi provider): lệnh EXPIRED/CANCELLED, đơn `CANCELLED` (vẫn `UNPAID`, không có khoản hoàn), tồn được nhả. Hạn lệnh MANUAL cấu hình riêng: `app.payments.manual.intent-ttl-minutes` (mặc định 10). Job không còn phụ thuộc sandbox: `app.payments.expire-job-enabled` (mặc định true), `expire-delay-ms`, `expire-batch-size`.
4. Lưu thêm ở `payment_intents` (migration sales `V10`): `tendered_amount`, `change_due`, `transaction_ref`. `V10` cũng đổi chỉ mục duy nhất `uq_sales_orders_exchange` thành "không tính đơn `CANCELLED`": đơn đổi hàng chờ thanh toán bị hủy/hết hạn trả lại phiếu trả để đổi lại.
5. Ngoại lệ: tổng tiền 0 (giảm giá 100%) không có gì để chờ: đơn thu ngay (`COMPLETED` + `PAID`, trừ tồn trực tiếp, không có lệnh, `paymentIntent = null`) như hành vi cũ.
6. Phiếu thu (`GET /orders/{id}/receipt`): đơn POS chưa thu (`AWAITING_PAYMENT`, hoặc hủy chưa thu) trả 409 `ORDER_NOT_PAID`. Doanh thu/top bán/thống kê vốn tính theo `paid_at IS NOT NULL` nên không đếm đơn chờ; danh sách đơn và báo cáo đơn theo kênh vẫn hiện đơn chờ (lọc được theo `status`).
7. Không đổi: đường ghi đơn đã thu xong của demo seed (`OrderingDemoSeedStep` ghi trực tiếp, không qua use case POS); `SalesOrder.createPos` (đơn thu ngay) còn dùng cho đường đó và test miền. `PlacePosOrderUseCase` cũ vẫn có nhánh "không nối luồng hai bước" chỉ để test đơn giản; cấu hình thật luôn nối luồng hai bước.

## 3. Đánh đổi

| Quyết định | Lợi | Giá phải trả |
|---|---|---|
| Hai bước cho MỌI phương thức | Doanh thu/tồn chỉ ghi khi thu thật; có bằng chứng thu (người bấm, tiền thối, mã giao dịch) | FE POS phải có màn "chờ xác nhận thu" và bước bấm xác nhận; thêm một request mỗi đơn |
| Giữ tồn khi chờ | Không bán trùng trong lúc thu ngân thao tác | Hàng bị giữ tối đa 10 phút nếu thu ngân bỏ dở; nhả nhờ job (trễ tối đa khoảng 70 giây) |
| Tổng tiền 0 thu ngay | Không cần lệnh số tiền 0 (`amount > 0`) | Một nhánh riêng; `paymentIntent = null` ở phản hồi |
| `uq_sales_orders_exchange` bỏ qua đơn hủy | Hủy đơn đổi chờ thanh toán không khóa phiếu trả vĩnh viễn | Index khác với V2 (phải kiểm khi đối chiếu schema) |

## 4. Giới hạn

- Không có "tiền về trước khi bấm xác nhận" để đối soát tự động với thẻ/chuyển khoản: thu ngân tự nhập `transactionRef` (không kiểm tính hợp lệ).
- Chưa có hoàn tác xác nhận nhầm (dùng trả hàng/hoàn tiền sau khi đã PAID như đơn thường).
- Khóa `PaymentIntentController/confirm` mới: migration identity `V29__payment_intent_permissions.sql` gán get/cancel/confirm cho mẫu Thu ngân và Quản lý cửa hàng (backfill role còn nguyên mẫu; role đã chỉnh cần gán tay). `simulatePaid` chỉ đăng ký, không gán mẫu nào.
