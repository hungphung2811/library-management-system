# Báo cáo triển khai chống lạm dụng đặt hàng online

Ngày: 2026-11-18. Căn cứ: `docs/security/ORDER_ABUSE_AUDIT.md`. Phạm vi đã làm: pha 1 đến 5 (một phần) của kế hoạch audit. Quy ước: ✅ đã kiểm chứng · ⚠️ chưa kiểm chứng đủ · ⏳ chưa làm · 🚫 bị chặn.

## 1. Audit Findings

Xem `ORDER_ABUSE_AUDIT.md` (A1 đến A9). Tồn kho đã nguyên tử sẵn (`tryApply`); lỗ hổng chính là giữ hàng đơn online không hết hạn, không trần theo khách, đăng ký không xác minh, đặt đơn dùng chung ngưỡng giới hạn với đọc, idempotency chỉ theo khóa.

## 2. Existing Architecture

customer-api (BFF công khai) → sales-service `/internal/sales/shop/**`, tenant lấy từ JWT, RLS `sales_db`. Tồn kho: `onHand`/`reserved`, `available = onHand - reserved`, một câu UPDATE có điều kiện.

## 3. Changes Implemented

| Pha | Thay đổi | Trạng thái |
|---|---|---|
| 1 | Hạn giữ hàng `reservation_expires_at` (mặc định 15 phút theo tenant); `ExpireOnlineReservationsJob` + `OnlineReservationExpiry` (`FOR UPDATE SKIP LOCKED`, hủy đơn, `RELEASE`, audit cùng transaction); xác nhận xóa hạn | ✅ |
| 2 | Chính sách theo tenant (`order_abuse_policies`, API get/put có khóa lạc quan, qua cả `lms-api`): đơn chờ, đơn/24 giờ, số lượng mỗi dòng/đơn, đơn/phút theo khách/tenant | ✅ |
| 2 | Khóa tư vấn theo (tenant, khách) `CustomerOrderLockPort` để trần không bị vượt khi đặt đồng thời | ✅ |
| 3 | Idempotency so khớp nội dung (`request_hash`): cùng khóa khác nội dung → `422 IDEMPOTENCY_KEY_REUSED` | ✅ |
| 4 | `RateLimiter` Redis (cache-common, công tắc riêng `lms.ratelimit.enabled`), áp theo khách rồi tenant ở `ShopController.placeOrder` → `429 ORDER_RATE_LIMITED`; nginx vùng `public_order` | ✅ (đơn vị, WebMvc); ⚠️ chưa thử trên Redis thật |
| 5 | `AntiAbuseService`: mức LOW/MEDIUM/HIGH/CRITICAL từ tỷ lệ hủy 30 ngày, đơn 24 giờ, số lượng; HIGH siết trần đơn chờ một nửa, CRITICAL chặn, mức ghi vào audit `ORDER_CREATED`. Ngưỡng ở `lms.sales.abuse.risk.*` | ✅ đơn vị; ⚠️ ngưỡng là cấu hình nền tảng, chưa theo tenant/gói |
| Phụ | Sửa lỗi sẵn có phát hiện khi hồi quy: `@Transactional` bị mất do gọi chéo overload ở `RegisterStockItemUseCase` và `ProcessPaymentWebhookUseCase` (lỗi thật trên đường webhook); `@ConditionalOnWebApplication` cho hai controller import để tiến trình seed demo không thiếu bean | ✅ |

## 4. Database Migration

`V13__order_abuse_guard.sql` (sales): thêm cột nullable `reservation_expires_at`, `request_hash` vào `sales_orders`, index một phần `idx_sales_orders_reservation_expiry`, bảng `order_abuse_policies` (RLS + FORCE). Không phá dữ liệu; đơn cũ có hạn NULL nên không bị worker động tới.

## 5. API Changes

- Mới: `GET/PUT /api/sales/settings/order-abuse-policy` (khóa `OrderAbusePolicyController/get|update`, role cần được gán).
- Mã lỗi mới ở đặt đơn: `422 IDEMPOTENCY_KEY_REUSED`, `429 ORDER_LIMIT_EXCEEDED`, `429 ORDER_RATE_LIMITED`, `400 INVALID_QUANTITY` (trần số lượng của tenant). Đã ghi `docs/api/sales.md`, `docs/api/customer-api.md`; FE cần đồng bộ thông điệp.
- Hành vi đổi: số lượng tối đa mặc định 50/dòng và 100/đơn (trước đây 10.000/dòng).

## 6. Security Improvements

Chặn hoarding (hạn giữ + trần đơn chờ), đặt đơn hàng loạt (giới hạn tốc độ theo khách/tenant/IP), lặp hủy-đặt (tỷ lệ hủy), phát lại sai nội dung, cách ly tenant (khóa Redis và khóa tư vấn theo tenant, RLS).

## 7. Inventory Reservation Flow

Đặt đơn → khóa khách → kiểm trần/rủi ro → `reserveAll` (reserved += q) + đơn `PENDING_CONFIRMATION` kèm hạn. Nhánh: nhân viên xác nhận (xóa hạn) / khách hoặc nhân viên hủy (`RELEASE`) / quá hạn (job hủy + `RELEASE`). Mỗi nhánh một transaction và kiểm lại trạng thái sau khi giữ khóa nên không nhả kho hai lần.

## 8. Order State Flow

Không thêm trạng thái mới: đơn hết hạn là `CANCELLED` với lý do "Hết hạn giữ hàng". ⏳ Chưa thêm `EXPIRED`, `PENDING_PAYMENT`, `PROCESSING` hay trạng thái thanh toán `PENDING`/`FAILED` theo đề bài (cần đổi enum, CHECK, FE).

## 9. Test Results

- ✅ `OrderAbuseGuardTest` (13), `OrderAbuseWebTest` (7), `AntiAbuseServiceTest`, `RedisRateLimiterTest` (7), `OnlineOrderUseCaseTest` (17): đạt.
- ✅ `OrderAbuseIT` 8/8 trên Postgres thật (20 luồng đặt đồng thời không bán vượt tồn; trần đơn chờ không vượt; cùng khóa đồng thời một đơn; 4 worker nhả kho đúng 20 lần; xác nhận đua với hết hạn không lệch tồn; cách ly tenant). Kiểm tra đột biến: tắt khóa khách thì test fail.
- ✅ IT hồi quy: `PaymentQrIT` 5/5, `SalesPhase2IT` 9/9, `SalesDemoSeedIT` 5/5; `SalesFlowIT` 11/12.
- ❌ `SalesFlowIT.haiYeuCauCungClientRequestIdChayDongThoiChiTruMotLan`: luồng thua nhận `ORDER_STATE_CONFLICT` thay vì phát lại đơn (đường POS hai bước `CommitPosPendingOrderUseCase`, ngoài phạm vi online; chưa xác định có sẵn từ trước hay không).
- ✅ `lms-api`: 54 test sales đạt. `nginx -t` đạt.

## 10. Performance Considerations

Mỗi lần đặt thêm: 1 khóa tư vấn, 4 truy vấn đếm theo `(tenant_id, customer_user_id, created_at)` (index `idx_sales_orders_tenant_customer` có sẵn), 2 lệnh Redis. Index một phần cho worker. Khóa tư vấn tuần tự hóa chỉ các request của cùng một khách. ⚠️ Chưa đo tải.

## 11. Known Issues / Chưa làm

- 🚫 Xác minh email/điện thoại, OTP, CAPTCHA: cần quyết định kênh gửi (SMTP/SMS) và chính sách bắt buộc. Đăng ký giả hàng loạt vẫn chỉ bị chặn bởi nginx theo IP và trần từng tài khoản.
- ⏳ Tín hiệu thiết bị/IP và "nhiều tài khoản cùng thiết bị" của `AntiAbuseService` (hệ thống chưa có định danh thiết bị); hàng chờ duyệt tay cho CRITICAL (hiện chỉ chặn).
- ⏳ Hoàn tiền tự động khi thanh toán đến sau hết hạn (đơn online hiện không qua cổng thanh toán; chỉ POS QR).
- ⏳ Ngưỡng rủi ro và giới hạn chưa theo gói dịch vụ (Subscription Plan).
- ⏳ Giới hạn tốc độ cho đăng ký/đăng nhập/OTP/thanh toán lại ở tầng ứng dụng (hiện chỉ nginx).
- ⚠️ Giới hạn tốc độ fail-open khi Redis lỗi; nginx là lớp dự phòng.

## 12. Rollback Plan

1. Tắt job: `lms.sales.abuse.reservation-expiry-job-enabled=false`; tắt giới hạn tốc độ: `lms.ratelimit.enabled=false`.
2. Nới trần: `PUT order-abuse-policy` (hạn 0 = không hết hạn) hoặc `LMS_ABUSE_*`.
3. Rollback code: các cột/bảng V13 chỉ thêm mới và nullable nên bản cũ vẫn chạy với schema mới; không cần xóa. Muốn xóa hẳn thì viết migration mới (không sửa V13).
