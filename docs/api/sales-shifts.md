# Chốt ca thu ngân (SALES): `/api/sales/shifts/**`, `/api/sales/shift-settings` (lms-api)

Nền: ADR-0030 mục D3 (gói B) và Phụ lục A (khóa quyền đã chốt). Tiền là số nguyên đồng (VND, `long`). Thời điểm là ISO-8601 UTC.

> ℹ️ Hợp đồng khớp code sales-service (`lms.sales.shift`) và lms-api (`SalesShiftController`, `SalesShiftDtos`). FE chưa làm (gói E). Migration `sales/V17__cashier_shifts.sql` (số thật tại thời điểm viết: V16 là `invoicing` của gói A).

## Cách hoạt động

- lms-api chuyển 1-1 `/api/sales/shifts/**` sang `/internal/sales/shifts/**` và `/api/sales/shift-settings` sang `/internal/sales/shift-settings` của sales-service, giữ `Authorization: Bearer` và `X-Correlation-Id`. Kiểm khóa, module `SALES` (`403 MODULE_NOT_ENABLED` kể cả admin), tenant (`403 TENANT_REQUIRED`) ở sales-service. Lỗi 4xx chuyển nguyên `{code, message, details[], timestamp}`.
- Ca gắn với **người thu ngân** (không mô hình két). Mỗi thu ngân tối đa **một ca `OPEN`** (ràng buộc ở DB). Trường `registerLabel` là nhãn quầy tự do.
- **Tiền vào ca nào**:
  - Xác nhận thu tay (`POST /api/sales/payments/intents/{id}/confirm`): vào ca đang mở của **người bấm xác nhận** (có thể khác người tạo đơn).
  - Thanh toán qua cổng (QR, MoMo, SePay): vào ca đang mở của **người tạo đơn** tại lúc tạo đơn, kể cả khi cổng báo muộn sau khi ca đã đóng (báo cáo tách phần này).
  - Hoàn tiền (`POST /api/sales/refunds/{id}/completion`): vào ca đang mở của **người đánh dấu đã hoàn**.
  - Đơn online, đơn cũ, hoặc người thao tác không có ca mở (khi cờ tắt): `shift_id` rỗng, tiền không thuộc ca nào.
- **Tiền mặt dự kiến** = tiền đầu ca + tổng thu `CASH` của ca - tổng hoàn `CASH` (khoản `REFUNDED`) của ca. Thẻ, chuyển khoản, QR, MoMo, SePay không vào tiền mặt dự kiến. **Chênh lệch** = tiền đếm thực tế - dự kiến (dương là thừa, âm là thiếu).
- **Ngưỡng chênh lệch** (mặc định 0 đồng nghĩa mọi chênh lệch khác 0): vượt ngưỡng (`|chênh lệch| > ngưỡng`) thì bắt buộc `varianceReason` và ca chuyển chờ duyệt (`varianceStatus = PENDING_APPROVAL`); không vượt thì `NONE`.
- Sau khi đóng ca bị **khóa**. Chỉ quản lý mở lại (có lý do), mở lại xóa số liệu đóng cũ, đóng lại sinh số liệu mới. Lịch sử mọi lần mở/đóng/mở lại/duyệt nằm ở `history` (chỉ chèn thêm).
- Không đóng ca khi còn **lệnh thu tay còn chờ** (`MANUAL`, `PENDING`, chưa hết hạn 10 phút) của ca: `409 SHIFT_HAS_PENDING_PAYMENTS`, thu ngân xác nhận hoặc hủy lệnh trước. Lệnh cổng (QR/MoMo/SePay) đang chờ không chặn đóng ca.
- Cờ tenant `requireOpenShiftForPos` (mặc định **tắt**): bật thì tạo đơn POS (mọi phương thức), xác nhận thu tay và hoàn tiền **tiền mặt** khi người thao tác không có ca mở bị `409 SHIFT_NOT_OPEN`. Tắt thì không đổi hành vi cũ (bán được, tiền không vào ca nào; FE nên cảnh báo).
- Ca của người khác qua endpoint "của tôi" trả `404 SHIFT_NOT_FOUND` cùng thân với ca không tồn tại (không lộ sự tồn tại). Quyền sở hữu kiểm theo `sub` của token.

## Khóa quyền (ADR-0003, Phụ lục A: tên là hợp đồng)

Quản trị viên tenant (`is_admin`) bỏ qua kiểm khóa. Mẫu vai trò seed ở `identity/V40` (CASHIER `…c1`, STORE_MANAGER `…c3`); **không backfill role của tenant đang chạy**, tenant admin gán tay.

| Method, đường dẫn (BFF) | Khóa quyền | Mẫu |
|---|---|---|
| POST `/api/sales/shifts` | `CashierShiftController/open` | c1, c3 |
| GET `/api/sales/shifts/me/current` | `CashierShiftController/current` | c1, c3 |
| GET `/api/sales/shifts/me` | `CashierShiftController/mine` | c1, c3 |
| GET `/api/sales/shifts/me/{id}` | `CashierShiftController/getMine` | c1, c3 |
| POST `/api/sales/shifts/me/current/closing` | `CashierShiftController/close` | c1, c3 |
| GET `/api/sales/shifts` | `CashierShiftAdminController/search` | c3 |
| GET `/api/sales/shifts/{id}` | `CashierShiftAdminController/get` | c3 |
| POST `/api/sales/shifts/{id}/closing` | `CashierShiftAdminController/closeOnBehalf` | c3 |
| POST `/api/sales/shifts/{id}/reopening` | `CashierShiftAdminController/reopen` | c3 |
| POST `/api/sales/shifts/{id}/variance-approval` | `CashierShiftAdminController/approveVariance` | c3 |
| GET `/api/sales/shift-settings` | `CashierShiftSettingsController/get` | c3 |
| PUT `/api/sales/shift-settings` | `CashierShiftSettingsController/update` | c3 |

Khóa "của tôi" không cho vào endpoint quản lý và ngược lại (kiểm bằng test). Không có khóa mới nào so với Phụ lục A.

## Endpoint

### Ca của tôi

| Endpoint | Body / query | Kết quả |
|---|---|---|
| POST `/api/sales/shifts` | `{openingFloat >= 0, registerLabel? (<= 100), clientRequestId}` | `201` + `Location` `/api/sales/shifts/me/{id}`, `ShiftView`. Gửi lại cùng `clientRequestId` của chính mình trả ca cũ (không mở thêm). `409 SHIFT_ALREADY_OPEN` nếu đang có ca mở (hoặc hai yêu cầu mở đồng thời: chỉ một thắng). `clientRequestId` của người khác: `400 INVALID_REQUEST` |
| GET `/api/sales/shifts/me/current` | | `ShiftDetailView` của ca đang mở kèm số liệu tạm tính (`provisionalExpectedCash`); không có ca mở: `404 SHIFT_NOT_FOUND` |
| GET `/api/sales/shifts/me` | query record `{status? (OPEN\|CLOSED), page?, size?}` | trang `ShiftView` của tôi, mới nhất trước |
| GET `/api/sales/shifts/me/{id}` | | `ShiftDetailView`; ca không phải của tôi hoặc không tồn tại: `404 SHIFT_NOT_FOUND` |
| POST `/api/sales/shifts/me/current/closing` | `{countedCash >= 0, varianceReason? (<= 500)}` | `ShiftView` đã `CLOSED`. `400 SHIFT_VARIANCE_REASON_REQUIRED` (vượt ngưỡng không có lý do), `409 SHIFT_HAS_PENDING_PAYMENTS`, `404 SHIFT_NOT_FOUND` (không có ca mở) |

### Quản lý (mọi thu ngân trong tenant)

| Endpoint | Body / query | Kết quả |
|---|---|---|
| GET `/api/sales/shifts` | query record `{cashierUserId?, status?, varianceStatus? (NONE\|PENDING_APPROVAL\|APPROVED), from?, to? (lọc theo openedAt, ISO-8601 instant, from gồm, to loại trừ), page?, size?}` | trang `ShiftView` |
| GET `/api/sales/shifts/{id}` | | `ShiftDetailView`; `404 SHIFT_NOT_FOUND` |
| POST `/api/sales/shifts/{id}/closing` | như đóng ca của tôi | `ShiftView`: quản lý đóng thay ca đang mở (người đóng ghi ở `closedBy`) |
| POST `/api/sales/shifts/{id}/reopening` | `{reason}` (bắt buộc, <= 500) | `ShiftView` `OPEN` (`phase = REOPENED`). `400 REOPEN_REASON_REQUIRED`, `409 SHIFT_STATE_CONFLICT` (ca đang mở), `409 SHIFT_ALREADY_OPEN` (thu ngân đó đang có ca mở khác) |
| POST `/api/sales/shifts/{id}/variance-approval` | `{note?}` (thân tùy chọn, <= 500) | `ShiftView` `varianceStatus = APPROVED`. `409 SHIFT_STATE_CONFLICT` nếu ca không đang chờ duyệt. Không có "từ chối": từ chối là mở lại ca |

### Cấu hình ca

| Endpoint | Body | Kết quả |
|---|---|---|
| GET `/api/sales/shift-settings` | | `{requireOpenShiftForPos, varianceThreshold, version, updatedAt}`; chưa lưu thì mặc định `false`, `0`, `version` 0, `updatedAt` null. Không cache |
| PUT `/api/sales/shift-settings` | `{requireOpenShiftForPos, varianceThreshold 0..1000000000, version}` (mọi trường bắt buộc, `version` = giá trị đã đọc) | cấu hình mới (version tăng); `409 SETTINGS_VERSION_CONFLICT`, `400 INVALID_SETTINGS` |

## Kiểu dữ liệu

`ShiftView`:

| Trường | Ghi chú |
|---|---|
| `id`, `shiftNo` | `shiftNo` dạng `CA-000001` (bộ đếm theo tenant) |
| `cashierUserId`, `registerLabel` | |
| `status` | `OPEN` \| `CLOSED` |
| `phase` | Gộp để hiển thị: `OPEN`, `REOPENED` (đang mở sau khi quản lý mở lại), `PENDING_APPROVAL` (đã đóng, chênh lệch chờ duyệt), `CLOSED` |
| `openedAt`, `openingFloat` | |
| `closedAt`, `closedBy`, `countedCash`, `expectedCash`, `variance`, `varianceReason` | null khi ca đang mở (mở lại xóa số liệu đóng cũ; xem `history`) |
| `varianceStatus` | `NONE` \| `PENDING_APPROVAL` \| `APPROVED` |
| `approvedBy`, `approvedAt`, `approvalNote` | |
| `reopenCount`, `version` | |

`ShiftDetailView`: `{shift: ShiftView, report: ShiftReportView, provisionalExpectedCash (chỉ khi ca đang mở), history[{id, type (OPENED\|CLOSED\|REOPENED\|VARIANCE_APPROVED), actorId, occurredAt, snapshot{...}}]}`.

`ShiftReportView`: `{orderCount, grandTotal, discountTotal, methods[], collectedTotal, refundedTotal, netTotal}`. `orderCount`, `grandTotal`, `discountTotal` tính trên các đơn có tiền thu vào ca. `methods` luôn đủ 6 dòng theo thứ tự `CASH, CARD, BANK_TRANSFER, QR, MOMO, SEPAY` (kể cả 0), mỗi dòng `{method, paymentCount, collected, refundCount, refunded, net, paymentCountAfterClose, collectedAfterClose, refundCountAfterClose, refundedAfterClose}`. Các trường `...AfterClose` là phần phát sinh **sau thời điểm chốt** (cổng báo muộn hoặc hoàn tiền ghi sau), đã nằm trong tổng và không đổi tiền mặt dự kiến đã chụp. Báo cáo tính bằng số câu SQL cố định, không theo số đơn.

## Mã lỗi

| HTTP | `code` | Khi nào |
|---|---|---|
| 404 | `SHIFT_NOT_FOUND` | Ca không tồn tại, ca của người khác ở endpoint "của tôi", hoặc không có ca mở khi gọi `current`/đóng |
| 409 | `SHIFT_ALREADY_OPEN` | Mở ca khi đang có ca mở; mở lại ca khi thu ngân có ca mở khác |
| 409 | `SHIFT_NOT_OPEN` | Cờ chặn bán bật và người thao tác chưa mở ca: tạo đơn POS, xác nhận thu tay, hoàn tiền mặt |
| 409 | `SHIFT_STATE_CONFLICT` | Đóng ca đã đóng, mở lại ca đang mở, duyệt ca không chờ duyệt, ca bị đổi bởi thao tác khác |
| 409 | `SHIFT_HAS_PENDING_PAYMENTS` | Đóng ca khi còn lệnh thu tay chờ; `details` có `pendingPayments=<số>` |
| 400 | `SHIFT_VARIANCE_REASON_REQUIRED` | Chênh lệch vượt ngưỡng mà thiếu `varianceReason` |
| 400 | `REOPEN_REASON_REQUIRED` | Mở lại ca không có lý do |
| 400 | `INVALID_PRICE` | Tiền đầu ca hoặc tiền đếm âm (ở sales-service); BFF chặn trước bằng `400 VALIDATION_FAILED` |
| 409 | `SETTINGS_VERSION_CONFLICT`, 400 `INVALID_SETTINGS` | Cấu hình ca |

## Hợp đồng ảnh hưởng tới API khác (để FE đồng bộ)

- Không đổi hình dạng request/response của `/api/sales/pos-orders`, `/api/sales/payments/intents/**`, `/api/sales/refunds/**`. Khác biệt duy nhất: khi tenant bật `requireOpenShiftForPos`, các endpoint đó có thể trả thêm `409 SHIFT_NOT_OPEN`. Mặc định tắt nên hành vi cũ giữ nguyên.
- Audit (`GET /api/sales/audit-log`) có thêm `SHIFT_OPENED`, `SHIFT_CLOSED`, `SHIFT_REOPENED`, `SHIFT_VARIANCE_APPROVED`, `SHIFT_SETTINGS_CHANGED` (`aggregateType` `CashierShift` hoặc `ShiftSettings`). `data` chỉ có id, số tiền, mã trạng thái; lý do tự do không vào audit/Kafka mà nằm ở `history[].snapshot` của ca.

## Chưa kiểm chứng

- ⚠️ Chưa có FE (gói E): màn hình mở/đóng ca, duyệt chênh lệch, báo cáo theo phương thức, cảnh báo khi cờ tắt.
- ⚠️ Hoàn tiền khi người đánh dấu không có ca mở và cờ tắt: khoản hoàn không thuộc ca nào, nên không trừ vào tiền mặt dự kiến của ca nào.
- ⚠️ Thu tiền của đơn online (`POST /api/sales/orders/{id}/payment`, COD/đánh dấu đã thu) chưa gắn ca (ADR-0030 chỉ nêu POS).
- ⚠️ Giao dịch ghi vào ca đúng lúc ca đang được đóng đồng thời có thể không nằm trong số tiền mặt dự kiến đã chụp (không khóa ca khi gắn tiền); nó hiện ở các trường `...AfterClose` nếu thời điểm ghi sau thời điểm chốt.
