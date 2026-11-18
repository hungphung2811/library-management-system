# Audit chống lạm dụng đặt hàng (Order Abuse)

Ngày: 2026-11-18. Phạm vi: đường đặt hàng online của khách (storefront) và tồn kho trong `sales-service`, cổng `customer-api`, đăng ký ở `identity-service`, giới hạn tốc độ ở nginx. Chỉ đọc code, chưa sửa gì.

Quy ước: ✅ đã có và đúng · ⚠️ có nhưng thiếu · ❌ chưa có · ℹ️ ghi chú. Mục nào chưa đọc hết code ghi rõ "chưa kiểm chứng".

## 1. Kiến trúc hiện tại

```
Khách ─► nginx (limit_req theo IP) ─► customer-api 8088 (/public/t/{code}/**, không DB)
                                         │ token khách (JWT), tra tenant theo {code}
                                         ▼
                                   sales-service 8087 (/internal/sales/shop/**)
                                   TenantContext từ JWT, RLS sales_db
```

- Đăng ký khách: `POST /public/t/{code}/auth/signup` (identity cấp role `signup_default` của tenant). Không có xác minh email, số điện thoại, OTP hay CAPTCHA.
- Đặt đơn: `POST /public/t/{code}/orders` → `ShopController.placeOrder` → `PlaceOnlineOrderUseCase` → `CommitOnlineOrderUseCase` (một transaction: giá, giữ tồn, lưu đơn, audit).
- Tenant lấy từ token và từ `{code}` (đã có `ShopSessionGuard`), không nhận tenant id tùy ý từ FE. ✅

## 2. Luồng đặt hàng hiện tại

1. Khách gửi `lines[]` (tối đa 200 dòng, mỗi dòng số lượng tối đa 10.000), `fulfilment`, `paymentMethod`, người nhận, `clientRequestId` (UUID).
2. Có đơn cùng `(tenant, clientRequestId)` thì trả đơn cũ (nếu cùng khách), khác khách thì `409 CLIENT_REQUEST_CONFLICT`.
3. Tra catalog (HTTP) ngoài transaction, rồi `CommitOnlineOrderUseCase.execute`: tính giá, `SalesOrder.placeOnline` (trạng thái `PENDING_CONFIRMATION`, `UNPAID`), `StockService.reserveAll`, lưu đơn, audit `ORDER_CREATED`.
4. Phương thức thanh toán của đơn online: `COD` (chỉ giao hàng), `CASH` (chỉ nhận tại quầy), `BANK_TRANSFER`. `CARD`, `QR`, `MOMO` bị từ chối (`method.isGateway()`), nên đơn online hiện **không đi qua cổng thanh toán**.
5. Nhân viên xác nhận (`confirm`), thu tiền tay (`markPaid`), giao, hoàn tất. Khách hủy được khi còn `PENDING_CONFIRMATION`; nhân viên hủy được đến `SHIPPING`.

Trạng thái hiện có:

| Loại | Giá trị |
|---|---|
| `OrderStatus` | `PENDING_CONFIRMATION`, `CONFIRMED`, `READY_FOR_PICKUP`, `SHIPPING`, `COMPLETED`, `CANCELLED`, `AWAITING_PAYMENT` (chỉ POS) |
| `PaymentStatus` | `UNPAID`, `PAID`, `PARTIALLY_REFUNDED`, `REFUNDED` |
| `PaymentIntentStatus` | `PENDING`, `PAID`, `EXPIRED`, `CANCELLED` (chỉ POS QR/MoMo/thu tay) |

Khác với đề bài: chưa có `PENDING_PAYMENT`, `PROCESSING`, `EXPIRED` ở đơn, và chưa có `PENDING`, `FAILED` ở thanh toán.

## 3. Cách cập nhật tồn kho

`StockItem` có hai bộ đếm `onHand` và `reserved`, `available = onHand - reserved` (tương đương công thức đề bài, trong đó "sold" đã trừ thẳng vào `onHand` khi xuất).

- `StockPersistenceAdapter.tryApply`: **một câu UPDATE có điều kiện** (`onHand + d >= 0`, `reserved + d >= 0`, `reserved + d <= onHand + d`), số dòng bị ảnh hưởng quyết định thành công. Nhiều request đồng thời không bán vượt được, tồn không âm được. ✅
- `StockService` gộp dòng trùng và xử lý theo `titleId` tăng dần (tránh khóa chéo). ✅
- Mỗi biến động ghi sổ cái `StockMovement` (RESERVE, RELEASE, FULFIL...). ✅
- Giữ hàng là **bộ đếm theo mặt hàng**, không có bản ghi "reservation" riêng: không có hạn, không có trạng thái ACTIVE/CONSUMED/RELEASED/EXPIRED, và không thể liệt kê "ai đang giữ bao nhiêu" ngoài việc lần ngược qua đơn. ⚠️
- Chống nhả kho hai lần dựa vào trạng thái đơn (`cancel` chỉ chạy từ một số trạng thái) và khóa lạc quan `version` của đơn. Chưa thấy test đồng thời cho đường hủy đơn online (chưa kiểm chứng). ⚠️

## 4. Lỗ hổng có thể bị khai thác

| # | Mức | Lỗ hổng | Cách khai thác | Bằng chứng |
|---|---|---|---|---|
| A1 | ❌ Cao | **Giữ hàng của đơn online không bao giờ hết hạn.** Đơn `PENDING_CONFIRMATION` giữ `reserved` vô thời hạn cho tới khi nhân viên xử lý hoặc khách tự hủy. Job hết hạn hiện chỉ dọn lệnh thanh toán QR của POS. | Tạo nhiều tài khoản, mỗi tài khoản đặt đơn COD cho toàn bộ tồn, hàng "hết" với khách thật (Inventory Hoarding). | `ExpirePaymentIntentsUseCase` chỉ xử lý `PaymentIntent`; `CommitOnlineOrderUseCase` không đặt hạn |
| A2 | ❌ Cao | **Không giới hạn số đơn chờ và số lượng theo khách.** Chỉ có trần kỹ thuật 200 dòng và 10.000 mỗi dòng. | Một tài khoản đặt nhiều đơn hoặc số lượng lớn tới hết tồn. | `PlaceOnlineOrderUseCase.MAX_LINES/MAX_QUANTITY` |
| A3 | ❌ Cao | **Đăng ký không xác minh.** Chỉ có nginx giới hạn 5 lần/phút/IP. Không email/SĐT/OTP/CAPTCHA, đổi IP là tạo tiếp. | Tạo hàng loạt tài khoản giả bằng nhiều IP. | `customer-api` `AuthController.signup`, `nginx.conf` vùng `public_form` |
| A4 | ❌ Cao | **Không giới hạn tốc độ riêng cho đặt đơn.** `/public/t/{code}/orders` dùng chung `public_read` (20 r/s/IP) và `public_tenant` (50 r/s/tenant); ghi mà dùng ngưỡng của đọc. Không có giới hạn theo user, theo thiết bị hay theo mặt hàng. | Spam đặt/hủy liên tục. Ngưỡng theo tenant còn cho một tenant tự làm nghẽn mình, và chưa phân biệt gói dịch vụ. | `nginx.conf` location `/public/` |
| A5 | ⚠️ Trung bình | **Idempotency chỉ theo khóa, không so khớp nội dung.** Cùng `clientRequestId` nhưng khác dòng hàng vẫn trả đơn cũ, không báo lỗi. | Client tưởng đã đặt đơn mới nhưng nhận đơn cũ; khó phát hiện lỗi tích hợp. | `PlaceOnlineOrderUseCase.replay` |
| A6 | ⚠️ Trung bình | **Hủy rồi đặt lại không để lại dấu vết lạm dụng.** Khách hủy được `PENDING_CONFIRMATION` tùy ý, không đếm tỷ lệ hủy/không thanh toán. | Chu kỳ giữ rồi hủy để "khóa" hàng theo từng đợt. | `SalesOrder.cancel`, không có bảng tín hiệu |
| A7 | ⚠️ Trung bình | **Thanh toán đến sau khi lệnh đã hết hạn chỉ bị từ chối, chưa có hoàn tiền tự động.** Webhook trả lỗi `PAYMENT_INTENT_STATE_CONFLICT`; tiền ở cổng không được tự trả lại. | Khách đã trả tiền nhưng đơn đã hủy, cần xử lý tay. | `ProcessPaymentWebhookUseCase`, `PaymentIntentUseCases.expireOne` |
| A8 | ⚠️ Thấp | Thân webhook chưa thấy chống phát lại theo nhãn thời gian (chỉ idempotent theo `providerTxnId`). Chữ ký HMAC có. Chưa đọc `markPaid` để xác nhận kiểm số tiền (chưa kiểm chứng). | Phát lại webhook cũ hợp lệ: được chặn bởi trạng thái lệnh (an toàn), nhưng không có cửa sổ thời gian. | `PaymentWebhookController`, `SimulatedWebhookProcessor` |
| A9 | ℹ️ | Đơn POS QR đã có cơ chế hết hạn và tranh chấp webhook/hủy bằng khóa lạc quan `version` của lệnh: bên thua nhận 409 và rollback. Đây là mẫu tốt cần tái dùng. | | `PaymentIntentUseCases.expireOne` |

## 5. Thành phần có thể tái sử dụng

- `StockService.reserveAll/releaseAll/fulfilAll` và `tryApply` (nguyên tử): giữ nguyên, **không viết lại**.
- Mẫu hết hạn của `ExpirePaymentIntentsJob` (job theo tenant, mỗi lệnh một transaction, lỗi từng lệnh chỉ log) cho worker hết hạn đơn online.
- `cache-common` (Redis, khóa theo tenant, fail-open) làm nền cho bộ đếm giới hạn tốc độ và tín hiệu abuse.
- `SalesAuditPort` ghi audit; `TenantContext` + RLS cô lập tenant; `SalesSettings` (theo tenant) để chứa ngưỡng cấu hình.
- Khóa duy nhất `uq_sales_orders_tenant_request (tenant_id, client_request_id)` làm lớp chặn cuối cho idempotency.
- nginx `limit_req` giữ làm lớp ngoài cùng (chống lưu lượng thô theo IP).

## 6. Thay đổi cần thiết (đề xuất, theo thứ tự ưu tiên)

Chọn **mở rộng mô hình hiện có**, không dựng bảng reservation riêng cho mọi thứ ngay: đơn online `PENDING_CONFIRMATION` chính là "reservation". Thêm hạn (`reservation_expires_at`) vào đơn thay vì một bảng mới, vì đơn đã gắn tenant, đã có khóa lạc quan, đã có sổ cái RESERVE/RELEASE. Bảng reservation riêng chỉ cần khi một đơn có nhiều đợt giữ độc lập (chưa có yêu cầu này).

| Pha | Nội dung | Giải quyết | Migration (sales, số kế tiếp là V13) |
|---|---|---|---|
| 1 | Hạn giữ hàng cho đơn online + worker hết hạn (khóa `FOR UPDATE SKIP LOCKED`, nhả kho và hủy đơn cùng transaction, audit) | A1 | Cột `reservation_expires_at`, index `(tenant_id, status, reservation_expires_at)` |
| 2 | Giới hạn theo khách: số đơn chờ, số lượng mỗi đơn và mỗi mặt hàng, số đơn trong khoảng thời gian; cấu hình trong `SalesSettings` | A2, A6 | Cột cấu hình trong `sales_tenants`/settings |
| 3 | So khớp nội dung idempotency (băm payload chuẩn hóa, lưu cùng đơn); khác nội dung thì `422/409` | A5 | Cột `request_hash` |
| 4 | Giới hạn tốc độ riêng cho `orders` (theo user, tenant, IP; Redis, cấu hình theo tenant) và nginx vùng `public_order` | A4 | Không |
| 5 | `AntiAbuseService`: tín hiệu (đơn chờ, tỷ lệ hủy, số đơn gần đây, số tài khoản trên cùng thiết bị, số lượng bất thường) → mức rủi ro LOW/MEDIUM/HIGH/CRITICAL → hành động; bảng tín hiệu và hàng chờ duyệt tay | A2, A6 | Bảng `abuse_signals`, `abuse_reviews` (RLS + `@TenantId`) |
| 6 | Xác minh email và OTP (identity): băm OTP, hạn, giới hạn lần thử và tần suất gửi, không lộ tài khoản tồn tại; bắt buộc theo chính sách tenant/mức rủi ro. Cần chọn kênh gửi (SMTP/SMS) | A3 | Bảng OTP/cờ xác minh ở identity |
| 7 | Thanh toán đến muộn: ghi nhận khoản "tiền đến sau hết hạn" và tạo yêu cầu hoàn tiền (liên kết `Refund` sẵn có) thay vì chỉ từ chối | A7 | Có thể dùng bảng `refunds` hiện có |
| 8 | Thêm trạng thái đơn/thanh toán theo đề bài (`EXPIRED`, `PENDING`, `FAILED`...) | Đồng bộ mô hình | Nới `CHECK` của cột `status`, không phá dữ liệu |

Pha 1 và 2 giảm rủi ro lớn nhất với thay đổi nhỏ nhất. Pha 5 và 6 phụ thuộc quyết định sản phẩm (kênh OTP, ngưỡng mặc định, có hàng chờ duyệt tay hay không).

## 7. Điều chưa kiểm chứng

- ⚠️ Chưa đọc `PaymentIntent.markPaid` (kiểm số tiền) và `SimulatedWebhookProcessor` (chữ ký, tra tenant).
- ⚠️ Chưa có test đồng thời cho hủy đơn online vs xác nhận (chỉ đọc code).
- ⚠️ Chưa đọc luồng `POST /internal/auth/signup` ở identity; kết luận "không xác minh" dựa trên việc không tìm thấy trường/cột xác minh email hoặc OTP trong identity-service (chỉ có `ExternalIdentity`).
- ℹ️ Chưa đo hiệu năng; chưa chạy lại bộ test hiện có cho giai đoạn này.
