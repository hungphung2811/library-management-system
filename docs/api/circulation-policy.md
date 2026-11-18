# Chính sách mượn trả theo đơn vị (tenant)

Hợp đồng `lms-api`. Quyền do circulation-service kiểm theo khóa `CirculationPolicyController/get` và `CirculationPolicyController/update`
(gán cho role như các khóa khác; admin bỏ qua kiểm khóa). Thuộc module LENDING.

## GET /api/circulation/policy
Trả chính sách của đơn vị hiện tại. Chưa cấu hình: giá trị mặc định, `custom=false`, `version=0`, `updatedAt=null`.

```json
{ "loanDays": 14, "maxRenewals": 2, "renewalDays": 14, "maxActiveLoansPerReader": 5,
  "finePerDay": 5000, "graceDays": 0, "reservationHoldDays": 3,
  "dueReminderDaysBefore": 2, "overdueReminderIntervalDays": 3, "overdueReminderMax": 3,
  "custom": false, "version": 0, "updatedAt": null }
```

## PUT /api/circulation/policy
Body: 7 trường như trên (bắt buộc) + `version` (giá trị đọc từ GET; thiếu coi như 0) + 3 trường nhắc hạn (ADR-0030, KHÔNG bắt buộc: thiếu hoặc `null` thì giữ giá trị đang lưu, chưa lưu thì mặc định 2/3/3, nên client cũ vẫn gọi được). Trả cùng dạng như GET (`custom=true`, version mới).

| Trường | Khoảng |
|---|---|
| loanDays | 1..365 |
| maxRenewals | 0..20 |
| renewalDays | 1..365 |
| maxActiveLoansPerReader | 1..100 |
| finePerDay (số nguyên, VND) | 0..10.000.000 |
| graceDays | 0..90 |
| reservationHoldDays | 1..30 |
| dueReminderDaysBefore (nhắc sắp đến hạn trước bao nhiêu ngày; 0 = tắt) | 0..30, mặc định 2 |
| overdueReminderIntervalDays (chu kỳ nhắc quá hạn, ngày) | 1..30, mặc định 3 |
| overdueReminderMax (trần số lần nhắc quá hạn mỗi hạn trả; 0 = tắt) | 0..20, mặc định 3 |

Lỗi: 400 `VALIDATION_FAILED` (thiếu trường), 422 `INVALID_CIRCULATION_POLICY` (ngoài khoảng, thông điệp tiếng Việt),
409 `POLICY_VERSION_CONFLICT` (version cũ, tải lại rồi sửa), 401/403 (thiếu đăng nhập/quyền).

## Hành vi
- Đơn vị đã lưu chính sách (`custom=true`): mượn dùng `loanDays` và `maxActiveLoansPerReader` cho mọi loại độc giả
  (thay quy định theo loại độc giả `borrow_rules`); gia hạn dùng `maxRenewals`/`renewalDays`; phạt trễ dùng `finePerDay`
  sau khi trừ `graceDays`; giữ chỗ đặt trước dùng `reservationHoldDays`.
- Chưa cấu hình: hành vi cũ không đổi (borrow_rules theo loại độc giả, 14 ngày/2 lần, 5.000đ/ngày, 3 ngày).
- Chỉ áp cho thao tác mới: phiếu đã lập, khoản phạt đã lập, đặt trước đã giữ bản sao không bị tính lại.
- Nhắc hạn (ADR-0030 D11): job `LoanReminderJob` chạy hằng ngày 07:00 giờ Việt Nam (cron `app.borrowing.reminder-cron`, múi giờ
  `app.borrowing.reminder-zone`, tắt hẳn bằng `app.borrowing.reminder-enabled=false`), lần lượt từng tenant ACTIVE, đọc ba trường trên
  từ chính sách (bean gốc, không qua cache). Chỉ ghi sổ `loan_reminders` và phát sự kiện, không gửi thư; chỉ áp cho phiếu còn
  ít nhất một cuốn chưa trả.
  - Sắp đến hạn: phiếu có hạn trả trong `[hôm nay, hôm nay + dueReminderDaysBefore]` và chưa được nhắc cho đúng hạn trả đó (gia hạn
    đổi hạn trả thì nhắc lại cho hạn mới). Mỗi (phiếu, hạn trả) nhắc một lần.
  - Quá hạn: phiếu có hạn trả trước `hôm nay - graceDays` (nghĩa là trễ hơn số ngày ân hạn), đã nhắc ít hơn `overdueReminderMax` lần
    cho hạn trả đó và lần nhắc gần nhất cách ít nhất `overdueReminderIntervalDays` ngày (lần đầu: ngay lượt chạy đầu tiên sau khi
    qua ân hạn). Đủ trần thì dừng.
  - Phát sự kiện đồng bộ (chờ broker xác nhận, 5 giây) trước khi commit lô 500 phiếu: phát lỗi thì rollback cả lô và hôm sau thử
    lại; không có Outbox nên nếu commit lỗi sau khi đã phát thì lần sau phát lại cùng `eventId`.

## Sự kiện nhắc hạn (Kafka `borrowing.events`, `version = 1`, cho support-service)
Envelope `EventEnvelope` (`eventId, eventType, aggregateId = borrowingId, occurredAt, version, payload, tenantId`). Payload chỉ có id và
số liệu, KHÔNG có email hay tên (support-service tự tra người nhận theo `readerId`, circulation không gọi catalog nên không có tên sách).
`eventId` tất định `UUID.nameUUIDFromBytes("tenantId:borrowingId:KIND:dueDate:seq")` nên giao lại không sinh thư trùng.

```json
// eventType = LoanDueSoon  (daysBefore: số ngày còn lại tới hạn, 0 = hạn là hôm nay)
{ "eventId": "uuid", "borrowingId": "uuid", "readerId": "uuid", "dueDate": "2026-11-21",
  "daysBefore": 2, "itemCount": 3, "occurredAt": "2026-11-19T00:00:03Z" }
// eventType = LoanOverdueReminder  (reminderNo: lần nhắc thứ mấy của hạn trả này, 1..maxReminders)
{ "eventId": "uuid", "borrowingId": "uuid", "readerId": "uuid", "dueDate": "2026-11-10",
  "daysOverdue": 9, "reminderNo": 2, "maxReminders": 3, "itemCount": 1, "occurredAt": "2026-11-19T00:00:03Z" }
```
`itemCount` là số cuốn CHƯA trả của phiếu tại lúc nhắc.

## Sự kiện đặt trước và phạt (đã có, không đổi, đủ cho thông báo, không có email)
- `ReservationReadyEvent` (`reservation.events`): `eventId, reservationId, readerId, titleId, copyId, holdUntil, occurredAt`.
- `FineIssuedEvent` (`fine.events`): `eventId, fineId, readerId, borrowingId, bookCopyId, reason, amount, occurredAt`.
Cả hai kèm `tenantId` ở envelope. Không có tên sách (chỉ `titleId`/`bookCopyId`): nếu cần hiển thị tên, support-service tra catalog.
