# API: tình trạng sách lúc mượn, bậc hỏng, chính sách đền bù, miễn/giảm, hoàn khoản

Nguồn: `docs/requirements/2026-11-18-tinh-trang-sach-va-den-bu.md`. Đường dẫn FE là `/api/**` (lms-api chuyển thành `/internal/**` của circulation-service). Lỗi theo dạng `{code, message, details[], timestamp}`. Quyền theo khóa `Controller/method` (ADR-0003); mọi controller mới thuộc module `LENDING`. Platform admin dùng header `X-Tenant-Id` (ADR-0008/0016). Tiền là VND nguyên (HALF_UP).

Migration: circulation `V11`, `V12`; identity `V35` (khóa quyền và vai trò mẫu). Role của tenant đang chạy KHÔNG tự nhận khóa mới: tenant admin gán tay.

## 1. Thay đổi trên API hiện có (tương thích ngược, chỉ thêm trường)

| API | Thay đổi |
|---|---|
| `POST /api/borrowings` | thêm tùy chọn `items[]: {bookCopyId, conditionGrade (NEW\|GOOD\|FAIR\|WORN), conditionNote (<=500)}` ghi đè từng cuốn; mặc định tự điền từ bản sao. `bookCopyId` trong `items` phải thuộc `bookCopyIds` (422 `INVALID_BORROWING`). Không có API sửa sau khi lập phiếu. |
| `BorrowingView.items[]` (mọi API trả phiếu, kể cả `GET /api/borrowings/mine`) | thêm `conditionAtBorrow: {grade, note}`. `null` = lượt mượn cũ ("không có dữ liệu lúc mượn"). Khác null và `grade == null` = bản sao chưa ghi nhận ("chưa ghi nhận"). Độc giả chỉ xem, không ký. |
| `POST /api/borrowings/returns`, `POST /api/borrowings/{id}/returns` | thêm `damageLevelCode`, `damageNote` (<=1000), `evidenceUrls` (<=3, http/https, mỗi URL <=500 ký tự, chỉ lưu). `condition` vẫn GOOD (mặc định) hoặc DAMAGED. Các trường hỏng gửi kèm `GOOD` bị 422. |
| `POST /api/borrowings/losses` | thêm `damageNote`, `evidenceUrls` (cùng giới hạn). |
| `FineView` (mọi API trả khoản phạt, `GET /api/fines/mine`, `GET /api/fines`, `POST /api/fines/payments`) | thêm `payableAmount` (số phải thu sau miễn/giảm; `WAIVED` là 0) và `assessment` (null với phí trễ và khoản cũ). `amount` là số tiền gốc, không bao giờ đổi. `status` thêm `WAIVED`, `REDUCED`. |
| `GET /api/fines*`.`totalUnpaid` | = tổng `payableAmount` của khoản `UNPAID` và `REDUCED`. `WAIVED` không tính, không chặn mượn. |
| `POST /api/fines/payments` | thu khoản `UNPAID` và `REDUCED` (thu đúng `payableAmount`); `totalCollected` theo số thực thu. |

`FineView.assessment`: `{basePrice, priceSource (COVER_PRICE|PURCHASE_PRICE: nguồn thực dùng), priceFallback (true = đã rơi về giá bìa vì bản sao không có giá mua), rate, damageLevelCode, damageLevelLabel (null ở khoản mất), amountBeforeClamp, note, evidenceUrls[]}`. Snapshot lúc lập, không đổi khi tenant sửa bậc/chính sách.

Mã lỗi thêm cho trả/báo mất (HTTP 422): `DAMAGE_LEVEL_REQUIRED` (tenant đã có bậc hoạt động mà DAMAGED không kèm `damageLevelCode`), `DAMAGE_LEVEL_UNKNOWN`, `DAMAGE_LEVEL_INACTIVE`, `INVALID_EVIDENCE_URL`, `INVALID_DAMAGE_NOTE`. Request DAMAGED cũ không kèm bậc khi tenant CHƯA cấu hình bậc: tính 50% (mã `LEGACY_DAMAGED`, nhãn "Hỏng (mặc định cũ)").

Hành vi mặc định khi tenant chưa cấu hình: hỏng 50% giá bìa, mất 150% giá bìa, không sàn/trần, hạn tìm lại 30 ngày, phí xử lý hoàn 0; phí trễ vẫn theo chính sách mượn trả.

## 2. Bậc hỏng

Khóa: `DamageLevelController/list|create|update|delete`. GET cache 60 giây theo tenant; ghi thì evict.

| Method, đường dẫn | Body / trả về |
|---|---|
| `GET /api/circulation/damage-levels` | `{custom, maxLevels (10), items:[{code,label,rate,sortOrder,active,version,updatedAt}]}`. `custom=false`: `items` là hạt giống `MINOR` 20%, `MAJOR` 50% (chưa ghi DB, `version` 0). Gồm cả bậc đã tắt. |
| `POST /api/circulation/damage-levels` | `{code, label, rate, sortOrder?, active?}` -> 201 `DamageLevel`. `code`: `[A-Z0-9_]{2,30}`; `rate` 0 đến 5.00. |
| `PUT /api/circulation/damage-levels/{code}` | `{code?, label, rate, sortOrder, active, version?}` -> `DamageLevel`. Không đổi được `code`. |
| `DELETE /api/circulation/damage-levels/{code}` | 204. Chỉ xóa được bậc chưa từng được dùng; đã dùng thì tắt (`active=false`). |

Lỗi: 409 `DAMAGE_LEVEL_CODE_EXISTS`, 409 `DAMAGE_LEVEL_LIMIT_REACHED` (tối đa 10 bậc/tenant), 409 `DAMAGE_LEVEL_IN_USE`, 409 `POLICY_VERSION_CONFLICT`, 404 `DAMAGE_LEVEL_NOT_FOUND`, 422 `INVALID_DAMAGE_LEVEL`, 422 `DAMAGE_LEVEL_CODE_IMMUTABLE`. Bậc đã tắt không chọn được cho khoản mới nhưng vẫn hiển thị ở khoản cũ.

## 3. Chính sách đền bù

Khóa: `CompensationPolicyController/get|update`. GET cache 60 giây.

- `GET /api/circulation/compensation-policy` -> `{lostRate, priceSource (COVER_PRICE|PURCHASE_PRICE), minAmount, maxAmount (null = không giới hạn), foundWindowDays, refundProcessingFee, custom, version, updatedAt}`. `custom=false`: mặc định (1.50, COVER_PRICE, null, null, 30, 0; version 0).
- `PUT /api/circulation/compensation-policy` body như trên (không có `custom`, `updatedAt`) kèm `version` đã đọc (0 nếu chưa cấu hình). `lostRate` 0..5, `foundWindowDays` 1..365, tiền 0..1.000.000.000. Lỗi: 422 `INVALID_COMPENSATION_POLICY`, 422 `COMPENSATION_BOUNDS_INVALID` (sàn > trần), 409 `POLICY_VERSION_CONFLICT`.
- Sàn/trần áp cho từng khoản hỏng và mất, không áp cho phí trễ. Chỉ áp cho khoản lập sau khi lưu.

## 4. Miễn / giảm khoản phạt chưa thu

Khóa riêng: `FineAdjustmentController/request`, `list`, `approve`, `reject`.

| Method, đường dẫn | Mô tả |
|---|---|
| `POST /api/fines/{id}/adjustment-requests` | `{type: WAIVE\|REDUCE, payableAmount?, reason}` -> 201 `Adjustment`. `REDUCE` cần `payableAmount` (số phải thu MỚI) > 0 và < `amount` gốc. `reason` bắt buộc (<=500). |
| `GET /api/fines/adjustment-requests?status&fineId&readerId&page&size` | `{items,total,page,size}`, mới nhất trước, size tối đa 100. |
| `POST /api/fines/adjustment-requests/{requestId}/approve` | body tùy chọn `{note}`. WAIVE: khoản thành `WAIVED`; REDUCE: `REDUCED`. |
| `POST /api/fines/adjustment-requests/{requestId}/reject` | body tùy chọn `{note}`. |

`Adjustment`: `{id, fineId, readerId, fineReason, fineAmount, type, payableAmount, reason, status (PENDING|APPROVED|REJECTED), requestedBy, requestedAt, decidedBy, decidedAt, decisionNote, tenantId}`.

Lỗi: 409 `APPROVER_MUST_DIFFER` (người duyệt/từ chối trùng người đề nghị, kiểm trong use case ngoài khóa), 409 `FINE_NOT_ADJUSTABLE` (khoản không còn `UNPAID`; khoản đã thu chỉ xử lý bằng hoàn khoản), 409 `ADJUSTMENT_ALREADY_PENDING`, 409 `ADJUSTMENT_NOT_PENDING`, 404 `FINE_NOT_FOUND` / `ADJUSTMENT_NOT_FOUND`, 422 `INVALID_ADJUSTMENT_AMOUNT`, 422 `ADJUSTMENT_REASON_REQUIRED`.

## 5. Hoàn khoản khi tìm lại sách đã báo mất

Khóa: `BorrowingController/reportFound` (ghi nhận tìm lại, đề nghị hoàn), `FineRefundController/list|approve|reject`.

- `POST /api/borrowings/found` body `{bookCopyId | barcode, restoreStatus: AVAILABLE|DAMAGED, checkNote?, refundMethod: CASH|BANK_TRANSFER, referenceCode?}` -> 201 `Refund`. Bản sao phải đang `LOST` ở catalog; trong `foundWindowDays` kể từ ngày báo mất. Chưa đổi trạng thái bản sao ở bước này.
- `GET /api/fines/refund-requests?status&readerId&page&size` -> trang `Refund`.
- `POST /api/fines/refund-requests/{id}/approve|reject` body tùy chọn `{note}`.
- Chế độ `mode` theo trạng thái khoản mất lúc đề nghị: `REFUND` (đã thu: `refundAmount = max(0, collectedAmount - refundProcessingFee)`, với `collectedAmount` là số thực thu sau giảm), `WAIVE` (chưa thu: duyệt thì miễn toàn phần thay vì hoàn), `RESTORE_ONLY` (đã được miễn: chỉ đưa bản sao về kho).
- Duyệt: ghi bút toán (chỉ ghi nhận, không chuyển tiền thật), rồi đổi bản sao LOST -> AVAILABLE/DAMAGED ở catalog là bước cuối; catalog lỗi thì rollback toàn bộ (không có bút toán), 503 `DEPENDENCY_UNAVAILABLE`.

`Refund`: `{id, fineId, borrowingId, bookCopyId, readerId, lostOn, foundOn, restoreStatus, checkNote, refundMethod, referenceCode, mode, fineStatusAtRequest, collectedAmount, processingFee, refundAmount, status, requestedBy, requestedAt, decidedBy, decidedAt, decisionNote, tenantId}`.

Lỗi: 409 `COPY_NOT_LOST`, 409 `REFUND_WINDOW_EXPIRED`, 409 `REFUND_ALREADY_EXISTS` (mỗi khoản mất hoàn một lần), 409 `REFUND_NOT_PENDING`, 409 `REFUND_FINE_STATE_CHANGED` (khoản đổi trạng thái từ lúc đề nghị: từ chối rồi lập lại), 409 `APPROVER_MUST_DIFFER`, 404 `LOST_BORROWING_NOT_FOUND` / `LOST_FINE_NOT_FOUND` / `REFUND_NOT_FOUND`, 422 `INVALID_CHECK_NOTE`.

## 6. Khóa quyền mới (14) và vai trò mẫu (identity V35)

`DamageLevelController/list|create|update|delete`, `CompensationPolicyController/get|update`, `FineAdjustmentController/request|list|approve|reject`, `FineRefundController/list|approve|reject`, `BorrowingController/reportFound`. Khóa trả/báo mất hiện có (`BorrowingController/returnCopy|returnBook|reportLost`) không đổi. Thủ thư mẫu (`LIBRARIAN`): đề nghị, xem danh sách, ghi nhận tìm lại, xem bậc và chính sách. Quản lý mẫu (`LIBRARY_ADMIN`): thêm duyệt/từ chối và cấu hình. Độc giả xem tình trạng lúc mượn và khoản phạt của mình qua `/mine` (`@AuthenticatedOnly`, đã có).

## 7. Event (Kafka, best-effort, ADR-0001)

Topic `fine-events`: `FineWaivedEvent {fineId, readerId, kind (WAIVE|REDUCE), originalAmount, payableAmount, requestedBy, approvedBy}` khi duyệt miễn/giảm (cả miễn do duyệt hoàn khoản chưa thu); `FineRefundedEvent {refundId, fineId, readerId, bookCopyId, refundAmount, refundMethod, requestedBy, approvedBy}` khi duyệt hoàn đã thu. Nội dung và kênh thông báo độc giả do support-service chốt riêng.

## 8. Ngoài đợt này

Cổng độc giả `customer-api` chưa bổ sung tình trạng lúc mượn (đề xuất của đề bài: đợt sau). Catalog: `GET /internal/book-copies/{id}` và `/by-barcode/{barcode}` trả thêm `conditionGrade`, `conditionNote`, `purchasePrice` (additive). CHƯA XỬ LÝ: bảng chuyển trạng thái của catalog (`BookCopy.changeStatus`) hiện không cho `LOST -> AVAILABLE/DAMAGED`, nên duyệt hoàn khoản sẽ nhận 409 `COPY_STATUS_CONFLICT` và rollback cho tới khi catalog cho phép chuyển này (ngoài phạm vi đợt này, chờ quyết định).
