# TODO

Việc đã chốt nhưng chưa làm. Mỗi mục ghi nguồn để lần lại ngữ cảnh. Làm xong thì chuyển trạng thái, đừng xóa dòng khi chưa commit.

## Thanh toán QR: giai đoạn 2, đối soát

- Nguồn: ADR-0029 (`docs/decisions/0029-log-tich-hop-va-dau-hieu-bat-thuong.md`), thiết kế chi tiết `docs/architecture/log-tich-hop-va-bat-thuong.md`, ADR-0028 (SePay polling).
- Điều kiện bắt đầu: giai đoạn 1 (log tích hợp, che dữ liệu, audit `PAYMENT_ANOMALY`) đã xong và đã kiểm chứng.

| Trạng thái | Việc | Ghi chú |
|---|---|---|
| ⏳ | Hỏi cổng lần cuối khi lệnh hết hạn (job `ExpirePaymentIntentsJob`) và khi thu ngân hủy lệnh | Hiện `SyncGatewayPaymentUseCase.refresh` chỉ hỏi cổng khi lệnh còn `PENDING`, nên tiền về sau hết hạn hoặc hủy không bao giờ bị phát hiện. Chỉ ghi bất thường (`PAYMENT_AFTER_EXPIRY`, `PAYMENT_AFTER_CANCEL`), không tự ghi nhận `PAID`. Áp dụng cho SePay và MoMo |
| ⏳ | Bỏ lọc số tiền phía SePay (`amount_in_min/max`), so số tiền phía mình | Hiện giao dịch sai số tiền không về tới BE nên `PAYMENT_AMOUNT_MISMATCH` không bao giờ xảy ra với SePay. Cần cập nhật ADR-0028 và test của `SepayPaymentGateway` |
| ⏳ | Đối soát định kỳ lệnh `PENDING` quá hạn và lệnh đã hết hạn gần đây | Bắt tiền về muộn. Dùng chung đường hỏi cổng ở trên; cần chống gọi quá giới hạn 3 yêu cầu mỗi giây của SePay |
| ⏳ | Kiểm chứng bằng IT: `REQUIRES_NEW` có đặt đúng `app.tenant_id` khi ghi audit anomaly | Cần Docker (Testcontainers), máy hiện không có. Chạy `-Dtest=<TênIT> -DargLine=-Dapi.version=1.44` |
| ⏳ | Xác minh với SePay thật: định dạng `transaction_date`, có trả `error_code` ở 401/429 hay không | Hiện suy ra từ tài liệu; chưa thấy phản hồi lỗi thật |
