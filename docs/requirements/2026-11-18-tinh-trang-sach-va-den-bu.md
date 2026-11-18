# Đề bài: Tình trạng sách khi mượn/trả và chính sách đền bù

Ngày: 2026-11-18. Phạm vi service: circulation-service (chính), catalog-service (chỉ đọc thêm trường), system-service (migration), lms-api (chuyển tiếp). FE ở repo riêng, chỉ nêu hợp đồng.

## 1. Mục tiêu

ℹ️ Làm rõ trách nhiệm đền bù giữa thư viện/hiệu sách và độc giả, theo chính sách riêng của từng tenant:
1. Chụp tình trạng bản sao lúc cho mượn làm bằng chứng đối chiếu khi trả.
2. Mức hỏng theo bậc do tenant tự định nghĩa; ghi chú và ảnh bằng chứng khi trả.
3. Chính sách đền bù theo tenant: tỉ lệ mất, nguồn giá, trần/sàn.
4. Miễn/giảm khoản phạt chưa thu có người duyệt; hoàn khoản khi tìm lại sách đã báo mất.

## 2. Người dùng và role

- Thủ thư (người lập phiếu mượn/trả): ghi tình trạng, đề nghị miễn/giảm, đề nghị hoàn.
- Người duyệt: bất kỳ tài khoản được gán khóa duyệt, và phải KHÁC người đề nghị. Role gán khóa sau theo ADR-0003, không hardcode tên role.
- Quản trị tenant: cấu hình bậc hỏng và chính sách đền bù.
- Độc giả: chỉ xem tình trạng lúc mượn và trạng thái khoản phạt của mình, không ký xác nhận.

## 3. Hiện trạng (đã xác nhận bằng code)

- `borrowing_items` chỉ có `item_condition` khi đóng (GOOD|DAMAGED|LOST), ràng buộc `ck_borrowing_items_closed` (V4).
- `FineRates` cố định: hỏng 50%, mất 150% giá bìa; chỉ phí trễ/ngày và ngày ân hạn chỉnh theo tenant (`circulation_policies`, V8).
- Giá tính phạt lấy từ `CopyInfo.coverPrice` (giá bìa của `Title`). `CopyInfo` chưa có `conditionGrade`, `conditionNote`, `purchasePrice`.
- `Fine` chỉ UNPAID -> PAID; unique (tenant, lượt mượn, bản sao, lý do). Không có miễn, hoàn, khiếu nại.
- Không có object storage; ảnh chỉ là URL ngoài.
- Migration circulation hiện tới V10; số kế tiếp V11 (BE phải kiểm lại trước khi tạo vì nhiều người cùng sửa).

## 4. Phạm vi

### 4.1. Chụp tình trạng lúc mượn
- ✅ Thêm vào `borrowing_items`: tình trạng lúc mượn (`conditionGrade` kiểu NEW|GOOD|FAIR|WORN, nullable) và ghi chú (nullable, tối đa 500 ký tự).
- ✅ Lúc lập phiếu, tự điền từ `conditionGrade`/`conditionNote` của bản sao (catalog bổ sung hai trường vào dữ liệu bản sao trả cho circulation). Thủ thư có thể sửa từng cuốn trong request mượn khi tình trạng thực tế khác.
- ✅ Bản sao chưa có `conditionGrade` thì lưu null, hiển thị "chưa ghi nhận".
- ✅ Lượt mượn đang mở trước khi triển khai: không backfill, hiển thị "không có dữ liệu lúc mượn".
- ✅ Độc giả xem được tình trạng lúc mượn trong `GET /mine` và cổng độc giả; không có bước đồng ý/ký.
- ✅ Bất biến sau khi lập phiếu: không có API sửa tình trạng lúc mượn.

### 4.2. Bậc hỏng do tenant định nghĩa
- ✅ Bảng `damage_levels` theo tenant: `id`, `tenant_id`, `code` (khóa ổn định), `label` (nhãn hiển thị, sửa được), `rate` (tỉ lệ trên giá gốc, 0 đến 5.00), `sort_order`, `active`, `version`, thời điểm tạo/sửa, người sửa. `UNIQUE (tenant_id, code)`.
- ✅ `code` không đổi được sau khi tạo (chữ in hoa, số, gạch dưới, 2 đến 30 ký tự). Bậc đã được dùng không xóa được, chỉ tắt (`active=false`); bậc đã tắt không chọn được cho khoản mới nhưng vẫn hiển thị ở khoản cũ.
- ✅ Hạt giống mặc định khi tenant chưa có bậc nào: `MINOR` "Hỏng nhẹ" 20%, `MAJOR` "Hỏng nặng" 50%. Hạt giống là mặc định trong code (như `circulation_policies`), không ghi dữ liệu vào DB của tenant cho đến khi tenant cấu hình lần đầu.
- ✅ Khi trả: request chọn `damageLevelCode` (bắt buộc khi condition là DAMAGED), `damageNote` (tối đa 1000), `evidenceUrls` (tối đa 3, http/https, mỗi URL tối đa 500 ký tự).
- ✅ Tương thích ngược: request cũ gửi DAMAGED mà không có `damageLevelCode` thì tính tương đương mức 50% (như hành vi cũ), lưu mã `LEGACY_DAMAGED`, nhãn "Hỏng (mặc định cũ)", tỉ lệ snapshot 50%. Khi tenant đã cấu hình bậc thì cho phép hành vi này hay bắt buộc chọn bậc là câu hỏi mở (mục 12).
- ✅ Khoản phạt snapshot `damageLevelCode`, `damageLevelLabel`, `rate` tại lúc lập, không đổi khi tenant sửa bậc sau đó.
- ✅ Ghi chú và URL ảnh lưu theo (lượt mượn, bản sao) kèm khoản phạt hỏng/mất; độc giả xem được ghi chú và ảnh của khoản phạt của mình.
- Mất: tỉ lệ mất lưu riêng trong chính sách đền bù (`lostRate`, mặc định 150%).

### 4.3. Chính sách đền bù theo tenant
- ✅ Mở rộng chính sách (bảng mới `compensation_policies` một dòng mỗi tenant, RLS, `version` khóa lạc quan, không sửa V8): `lostRate` (mặc định 1.50), `priceSource` (COVER_PRICE mặc định | PURCHASE_PRICE), `minAmount` và `maxAmount` mỗi khoản (null = không giới hạn), `foundWindowDays` (mặc định 30), `refundProcessingFee` (mặc định 0, số tiền VND).
- ✅ PURCHASE_PRICE: dùng `purchasePrice` của bản sao; không có thì fallback về giá bìa.
- ✅ Nguồn giá đi qua cổng `PriceSource` ở tầng application của circulation, adapter hiện tại gọi catalog. Phiên thiết kế sách 3 tầng chỉ thay adapter, không đụng logic phạt. Không phụ thuộc mô hình 3 tầng.
- ✅ Sàn/trần áp cho từng khoản hỏng và mất (không áp cho phí trễ). Nếu sàn lớn hơn trần thì lưu bị từ chối.
- ✅ Khoản phạt snapshot: giá gốc, nguồn giá thực dùng (ghi rõ nếu đã fallback), tỉ lệ, số tiền trước khi kẹp sàn/trần, số tiền cuối.
- ✅ Phí trễ vẫn theo `circulation_policies` hiện có.

### 4.4. Miễn/giảm và hoàn khoản
- ✅ Thủ thư đề nghị miễn toàn phần hoặc giảm một phần cho một khoản phạt UNPAID, kèm lý do bắt buộc. Người duyệt khác người đề nghị mới duyệt hoặc từ chối.
- ✅ Duyệt toàn phần: khoản thành WAIVED. Duyệt một phần: khoản thành REDUCED với số tiền phải thu mới thấp hơn. Số tiền gốc (`amount`) không sửa; thêm `payableAmount` hoặc tương đương (BE chọn thiết kế, ranh giới ở mục 7). Ghi người duyệt, thời điểm, lý do.
- ✅ Khoản REDUCED vẫn thu được phần còn lại qua `POST /payments`; khoản WAIVED không còn tính vào tổng nợ (không chặn mượn).
- ✅ Khoản đã PAID không miễn/giảm, chỉ xử lý bằng hoàn khoản.
- ✅ Hoàn khoản khi tìm lại sách đã báo mất: thủ thư ghi nhận "tìm lại" trong thời hạn `foundWindowDays` kể từ ngày báo mất, kèm kiểm tra tình trạng và hình thức hoàn (tiền mặt hoặc chuyển khoản). Hệ thống tạo đề nghị hoàn: `số hoàn = số tiền đền bù mất đã thu (hoặc phải thu sau giảm) trừ refundProcessingFee`, tối thiểu 0. Người duyệt khác người đề nghị mới duyệt.
- ✅ Chỉ ghi bút toán REFUND (không chuyển tiền thật, không nối sổ cái sales). Thủ thư ghi hình thức hoàn và mã tham chiếu tùy chọn.
- ✅ Khi duyệt hoàn: bản sao chuyển LOST -> AVAILABLE hoặc DAMAGED theo kiểm tra (đổi trạng thái idempotent qua catalog; lỗi catalog thì rollback). Khoản phạt mất chưa thu thì xử lý bằng miễn toàn phần thay vì hoàn.
- ✅ Phát `FineWaivedEvent` (miễn/giảm) và `FineRefundedEvent` (hoàn) qua Kafka best-effort (ADR-0001) cho audit và thông báo độc giả.

## 5. Ngoài phạm vi

- ⏭️ Khiếu nại do độc giả nộp trên cổng độc giả (customer-api); chỉ thủ thư đề nghị.
- ⏭️ Kho tệp/object storage, tải ảnh lên (chỉ lưu URL do FE cung cấp).
- ⏭️ Chuyển tiền hoàn thật, ví, cổng thanh toán, nối sổ cái sales.
- ⏭️ Độc giả ký/đồng ý tình trạng lúc mượn.
- ⏭️ Backfill tình trạng lúc mượn cho lượt đang mở.
- ⏭️ Giá theo ấn bản/tác phẩm (phiên sách 3 tầng); chỉ chừa cổng `PriceSource`.
- ⏭️ Tính lại khoản phạt đã lập khi tenant đổi chính sách.
- ⏭️ Hạn mức miễn giảm tự duyệt của thủ thư (mọi miễn/giảm đều cần người duyệt khác).

## 6. Quy tắc nghiệp vụ và ngoại lệ

1. Chính sách đền bù và bậc hỏng áp theo thời điểm trả/báo mất; chỉ khoản lập từ nay, khoản cũ không đổi.
2. Tenant chưa cấu hình: hỏng 50% giá bìa (mã `LEGACY_DAMAGED` hoặc hạt giống MINOR/MAJOR), mất 150% giá bìa, không sàn/trần, phí trễ như hiện tại.
3. Hỏng mà trễ chịu cả hai; mất không cộng phí trễ; unique (tenant, lượt mượn, bản sao, lý do) giữ nguyên, lập lại vẫn idempotent.
4. Người đề nghị không tự duyệt được (409/403 với mã lỗi riêng, ví dụ `APPROVER_MUST_DIFFER`).
5. Một khoản có tối đa một đề nghị miễn/giảm đang chờ; khoản đã xử lý (PAID, WAIVED, REDUCED đã duyệt) không đề nghị lại.
6. Mức giảm một phần phải lớn hơn 0 và nhỏ hơn số tiền gốc.
7. Tìm lại sách quá `foundWindowDays` hoặc bản sao không ở LOST: từ chối với mã lỗi rõ.
8. Mỗi khoản mất chỉ hoàn một lần.
9. Mọi đề nghị/duyệt/từ chối ghi người, thời điểm, lý do; không sửa hoặc xóa lịch sử.
10. Ảnh: tối đa 3 URL, http/https, ≤ 500 ký tự; không tải nội dung từ URL phía server (tránh SSRF).
11. Tiền VND làm tròn HALF_UP về số nguyên (như `FineCalculator`).

## 7. API và dữ liệu dự kiến (mức ranh giới)

Đường dẫn BE là `/internal/**`, lms-api chuyển thành `/api/**`. Tên cụ thể do `sa` chốt.

| Nhóm | Dự kiến |
|---|---|
| Mượn | `POST /internal/borrowings` thêm tùy chọn `items[].conditionGrade`, `items[].conditionNote` (ghi đè giá trị mặc định từ bản sao). Phản hồi phiếu trả thêm tình trạng lúc mượn từng cuốn. |
| Trả/báo mất | `POST /internal/borrowings/returns`, `/{id}/returns` thêm `damageLevelCode`, `damageNote`, `evidenceUrls`. Phản hồi khoản phạt có snapshot (giá gốc, nguồn, tỉ lệ, mã/nhãn bậc, trước/sau kẹp). |
| Bậc hỏng | `GET/POST/PUT /internal/circulation/damage-levels` (sửa nhãn, tỉ lệ, thứ tự, bật/tắt; không sửa `code`). |
| Chính sách đền bù | `GET/PUT /internal/circulation/compensation-policy` (khóa lạc quan `version`, cache 60 giây như policy hiện có, use case nghiệp vụ dùng bean gốc không qua cache). |
| Miễn/giảm | `POST /internal/fines/{id}/adjustment-requests`, `GET /internal/fines/adjustment-requests`, `POST .../{requestId}/approve`, `POST .../{requestId}/reject`. |
| Hoàn khoản | `POST /internal/borrowings/found` (đề nghị, kèm kiểm tra tình trạng và hình thức hoàn), `POST /internal/fines/refund-requests/{id}/approve`, `.../reject`, `GET` danh sách. |
| Event | `FineWaivedEvent`, `FineRefundedEvent` (Kafka, EventEnvelope). |

Dữ liệu (migration circulation, số bắt đầu V11, tăng liên tục, BE kiểm số kế tiếp trước khi tạo):
- V11: `borrowing_items` thêm `condition_grade`, `condition_note` (nullable, không backfill); khoản phạt thêm cột snapshot (giá gốc, nguồn giá, tỉ lệ, mã/nhãn bậc, số trước kẹp, ghi chú, URL ảnh), `status` thêm giá trị WAIVED/REDUCED, `payable_amount` (mặc định = amount cho dòng cũ). Không phá `ck_borrowing_items_closed`; thêm index theo quy ước (`tenant_id` đứng đầu).
- V12: `damage_levels`, `compensation_policies`; bảng đề nghị miễn/giảm và hoàn (`fine_adjustment_requests`, `fine_refunds`): người đề nghị, người duyệt, trạng thái, lý do, thời điểm, hình thức hoàn, mã tham chiếu. Bảng mới có `tenant_id`, RLS + FORCE, entity `@TenantId` (ArchUnit `TenantArchitectureRulesTest`); quyền do `afterMigrate.sql`.
- Migration chạy trên dữ liệu sống: chỉ thêm cột nullable hoặc có default, không khóa bảng dài, không ghi bảng RLS bằng dữ liệu mà không đặt `app.tenant_id`.
- Catalog: bổ sung `conditionGrade`, `conditionNote`, `purchasePrice` vào dữ liệu bản sao circulation đọc (`GET /internal/book-copies/...`), không đổi schema catalog.

## 8. Phân quyền (ADR-0003)

Khóa theo `Controller/method`, tự đăng ký lần gọi đầu, role gán sau; endpoint mới chưa gán role thì người không phải admin bị 403. Tên controller/method là dự kiến; đổi tên làm mất quyền đã gán.

| Việc | Khóa dự kiến |
|---|---|
| Mượn có ghi đè tình trạng | `BorrowingController/borrow` (khóa hiện có) |
| Trả/báo mất có bậc, ghi chú, ảnh | `BorrowingController/returnCopy`, `returnBook`, `reportLost` (khóa hiện có) |
| Xem/sửa bậc hỏng | `DamageLevelController/list`, `create`, `update` |
| Xem/sửa chính sách đền bù | `CompensationPolicyController/get`, `update` |
| Đề nghị miễn/giảm | `FineAdjustmentController/request` |
| Duyệt/từ chối miễn/giảm | `FineAdjustmentController/approve`, `reject` (khóa riêng tách khỏi `request`) |
| Ghi nhận tìm lại, đề nghị hoàn | `FineRefundController/requestRefund` |
| Duyệt/từ chối hoàn | `FineRefundController/approve`, `reject` |
| Độc giả xem khoản phạt, tình trạng lúc mượn của mình | `@AuthenticatedOnly` (`/mine`) |

- ✅ Ngoài khóa, kiểm "người duyệt khác người đề nghị" trong use case (không dựa vào role).
- Toàn bộ controller mới gắn `@RequiresModule(LENDING)`; platform admin dùng `X-Tenant-Id` như ADR-0008/0016.

## 9. Mặc định giữ hành vi cũ khi tenant chưa cấu hình

- ✅ Không có dòng `compensation_policies`: mất 150%, giá bìa, không sàn/trần, hạn tìm lại 30 ngày, phí xử lý hoàn 0.
- ✅ Không có `damage_levels`: dùng hạt giống MINOR 20%, MAJOR 50%; DAMAGED cũ không kèm bậc tính 50% (`LEGACY_DAMAGED`).
- ✅ Mượn không gửi tình trạng: tự điền từ bản sao; trả không gửi bậc/ghi chú/ảnh vẫn hợp lệ (trừ khi tenant bắt buộc bậc, mục 12).
- ✅ Khoản phạt cũ giữ nguyên số tiền, `payable_amount = amount`, snapshot null.

## 10. Tiêu chí nghiệm thu (kiểm được bằng test)

1. ✅ Mượn bản sao có `conditionGrade=GOOD`, `conditionNote="Gáy hơi cong"`: phiếu lưu đúng hai giá trị; thủ thư ghi đè thành FAIR thì lưu FAIR.
2. ✅ Bản sao không có `conditionGrade`: mượn thành công, lưu null, phản hồi "chưa ghi nhận".
3. ✅ Lượt mượn mở trước migration: phản hồi không có tình trạng lúc mượn; trả bình thường.
4. ✅ Không có API/đường nào sửa tình trạng lúc mượn sau khi lập phiếu.
5. ✅ Tenant chưa cấu hình: trả DAMAGED không kèm bậc cho khoản phạt đúng 50% giá bìa; báo mất đúng 150%; kết quả trùng test hiện có.
6. ✅ Tenant có bậc `LIGHT` 10% và `SEVERE` 70%: trả với `SEVERE` cho khoản 70% giá gốc, snapshot mã/nhãn/tỉ lệ; sửa nhãn hoặc tỉ lệ sau đó không đổi khoản cũ.
7. ✅ Tạo bậc trùng `code` trong cùng tenant bị từ chối; tenant khác dùng cùng `code` được; sửa `code` bị từ chối; bậc đã dùng không xóa được.
8. ✅ DAMAGED với `damageLevelCode` không tồn tại hoặc đã tắt: từ chối 4xx có mã lỗi rõ.
9. ✅ `evidenceUrls` quá 3, sai scheme hoặc URL > 500 ký tự: từ chối; hợp lệ thì lưu và độc giả xem được.
10. ✅ `priceSource=PURCHASE_PRICE`: dùng giá mua; bản sao không có giá mua thì dùng giá bìa và ghi nguồn đã fallback.
11. ✅ Sàn/trần: số tiền tính ra thấp hơn sàn thì bằng sàn; cao hơn trần thì bằng trần; snapshot có số trước kẹp; sàn > trần bị từ chối khi lưu.
12. ✅ Chính sách đổi sau khi lập khoản không làm đổi khoản cũ; khoản lập sau dùng chính sách mới.
13. ✅ Idempotent: trả lại cùng (lượt mượn, bản sao, lý do) không tạo khoản trùng.
14. ✅ Cô lập tenant: tenant A không đọc/sửa bậc, chính sách, đề nghị của tenant B (RLS, test IT bằng vai trò app).
15. ✅ Người đề nghị tự duyệt bị từ chối `APPROVER_MUST_DIFFER`; người khác có khóa duyệt thì duyệt được; người không có khóa duyệt bị 403.
16. ✅ Miễn toàn phần: khoản WAIVED, không còn tính vào `unpaidTotal`, độc giả mượn được lại; giảm một phần: REDUCED, thu đúng phần còn lại, `amount` gốc không đổi.
17. ✅ Miễn/giảm khoản đã PAID bị từ chối; đề nghị thứ hai khi đã có đề nghị chờ bị từ chối.
18. ✅ Tìm lại trong hạn: tạo đề nghị hoàn = tiền đã thu trừ phí xử lý; duyệt thì ghi REFUND, bản sao về AVAILABLE hoặc DAMAGED theo kiểm tra; quá hạn bị từ chối; hoàn lần hai bị từ chối.
19. ✅ Duyệt hoàn mà catalog lỗi: rollback toàn bộ, không có bút toán REFUND.
20. ✅ Duyệt miễn/giảm và hoàn phát `FineWaivedEvent`/`FineRefundedEvent`; Kafka lỗi không làm hỏng request.
21. ✅ Không có N+1 khi liệt kê phiếu/khoản phạt có tình trạng, bậc, đề nghị (đếm câu SQL).
22. ✅ ArchUnit: domain thuần, context chỉ dùng tầng application của context khác; `TenantArchitectureRulesTest` qua với bảng mới.
23. ✅ Migration chạy trên DB có dữ liệu cũ mà không mất dữ liệu; `mvn test` và các IT liên quan qua.

## 11. Giả định

- ⚠️ Hạt giống MINOR/MAJOR chỉ là mặc định trong code, không phải dữ liệu DB.
- ⚠️ Chỉ khoản phạt hỏng/mất chịu sàn/trần và bậc; phí trễ không.
- ⚠️ Giá trị `rate` bậc hỏng giới hạn 0 đến 5.00; `lostRate` 0 đến 5.00; `foundWindowDays` 1 đến 365.
- ⚠️ "Hình thức hoàn" gồm CASH và BANK_TRANSFER; mã tham chiếu tùy chọn.
- ⚠️ Quyền xem lịch sử đề nghị theo khóa `list` riêng; BE/SA có thể gộp nếu hợp lý.
- ⚠️ Tên endpoint, tên bảng, cột là gợi ý mức ranh giới.
- ⚠️ Cache: chính sách đền bù và bậc hỏng có thể cache 60 giây theo tenant, nhưng use case lập phạt đọc bản gốc (không qua cache).

## 12. Câu hỏi còn mở

- 🚫 Khi tenant đã cấu hình bậc hỏng, request DAMAGED không kèm bậc có bị từ chối (bắt buộc chọn bậc) hay vẫn tính `LEGACY_DAMAGED` 50%? Đề xuất: bắt buộc chọn bậc khi tenant đã có ít nhất một bậc hoạt động.
- 🚫 Khoản phạt mất đã thu nhưng bản sao tìm lại sau khi đã miễn/giảm một phần: hoàn theo số thực đã thu (đề xuất), đúng không?
- 🚫 Thông điệp thông báo cho độc giả (nội dung, kênh) khi miễn/giảm/hoàn do support-service định, cần BA/SA chốt riêng.
- 🚫 Phản hồi cổng độc giả (customer-api) có cần bổ sung tình trạng lúc mượn ngay đợt này hay đợt sau?
- 🚫 Có giới hạn số bậc hỏng tối đa mỗi tenant (đề xuất 10)?
- 🚫 Platform admin có được duyệt miễn/giảm/hoàn thay tenant không (đề xuất: có, qua `X-Tenant-Id`, vẫn phải khác người đề nghị)?
