# ADR-0030: Hóa đơn VAT nội bộ, chốt ca thu ngân và thông báo theo sự kiện qua provider (email trước)

- Trạng thái: Đề xuất (chưa cài đặt). Người dùng đã chốt hướng sản phẩm: ưu tiên nhà sách thật; ra mắt khi chưa có 2FA và
  chưa có Outbox (Kafka vẫn best-effort theo ADR-0001); thông báo đi theo sự kiện, một provider đầu tiên là EMAIL, kênh
  khác (SMS, push, Zalo) thêm sau bằng cách thêm provider.
- Ngày: 19/11/2026
- Liên quan: ADR-0001 (Kafka best-effort, chưa Outbox), ADR-0003 (khóa `Controller/method`), ADR-0004 (migration tập
  trung), ADR-0006 (RLS fail-closed), ADR-0009 (module theo tenant), ADR-0010 (sales: tiền, thuế, audit cùng transaction,
  `sales.events`), ADR-0012 (`TenantContractExpiringSoon`, mẫu thông báo nhiều người nhận), ADR-0014 (đổi tên
  `support-service` → `audit-service`, chưa làm), ADR-0018 (không cache số liệu động), ADR-0020/0021 (POS hai bước,
  `PaymentIntent`), ADR-0025/0028/0029 (cổng MoMo/SePay, log tích hợp), ADR-0031 (xuất dữ liệu tenant và vòng đời sau
  chấm dứt hợp đồng, dùng hạ tầng thông báo của ADR này).
- Ghi chú đánh số: thư mục `docs/decisions/` hiện có HAI file số 0029 (`0029-log-tich-hop-va-dau-hieu-bat-thuong.md`,
  `0029-thanh-toan-online-website-qua-sepay.md`); số kế tiếp thật là 0030.

## 1. Bối cảnh và hiện trạng đã đối chiếu code

Đọc code nhánh `main` (gồm thay đổi chưa commit) ngày 19/11/2026.

### 1.1. Bán hàng (`services/sales-service`)

| | Điểm | Kết quả thật (file) |
|---|---|---|
| ✅ | Thuế suất | MỘT thuế suất cho cả tenant: `SalesSettings.vatRateBps` (mặc định 500 = 5%), cờ `pricesIncludeVat` (`settings/domain/SalesSettings.java`). Đơn snapshot `vatRateBps`, `pricesIncludeVat` ở cấp đơn; mỗi dòng có `vatAmount`, `amount`, `lineTotal` nhưng **không có thuế suất theo dòng** (`ordering/domain/SalesOrder.java`, `OrderLine.java`). |
| ✅ | Số chứng từ | `DocumentNumberPort.next(tenant, sequence)` (bộ đếm `sales_counters` trong cùng transaction, khóa dòng đếm), trả chuỗi `DH-000001`/`PN-…`/`TH-…` (`shared/infrastructure/persistence/DocumentNumberAdapter.java`). Chưa có hàm trả số nguyên. |
| ✅ | Thông tin người bán | Không có ở đâu: `SalesSettings` không có tên pháp lý/MST/địa chỉ; `Tenant` (identity) chỉ có `code`, `name`, `organizationId`; `Organization` chỉ có `name`. |
| ✅ | Phiếu thu | `GET /internal/sales/orders/{id}/receipt` (`SalesOrderController/receipt`, `ReceiptResponse`) là phiếu bán lẻ, không phải hóa đơn. |
| ✅ | POS hai bước | Mọi đơn POS ra `AWAITING_PAYMENT`, thu ngân xác nhận `PaymentIntentController/confirm`; `PaymentRecord.recordedBy` = người bấm; mỗi đơn đúng một `payment_records` (`uq_payment_records_order`) (ADR-0021). |
| ✅ | Phương thức | `PaymentMethod`: `CASH, BANK_TRANSFER, CARD, COD, QR, MOMO, SEPAY` (`ordering/domain/PaymentMethod.java`). |
| ✅ | Trả hàng, hoàn tiền | `SalesReturn` + `ReturnLine(orderLineId, quantity, disposition, refundAmount)` (không tách VAT); `Refund` `PENDING → REFUNDED` với `method`, `refundedBy` (`ordering/domain/Refund.java`). |
| ✅ | Ca thu ngân | Không có gì (không bảng, không context). |
| ✅ | Audit + sự kiện | `SalesAuditPort` ghi `sales_audit_log` cùng transaction; `SalesAuditAdapter` phát `SalesEvent` lên `sales.events` sau commit (`SalesEventPublisher`, best-effort). `eventType` = PascalCase của action, riêng `ORDER_CREATED` thành `OrderPlaced` (online) hoặc `OrderCompleted` (POS) (`shared/infrastructure/messaging/SalesEvent.java`). `data` chỉ có id/số tiền/mã trạng thái, **không có `customerUserId`** ở các bước workflow (`OrderWorkflowUseCases.record(..., Map.of())`). |
| ✅ | Hết hạn giữ hàng online | `OnlineReservationExpiry.expireOne` gọi `workflow.cancelLoaded(..., EXPIRE_REASON, false)` → audit `ORDER_CANCELLED`, không phân biệt được với hủy tay qua `data`. |
| ✅ | Mã lỗi | Enum dùng chung `shared/domain/ErrorCode.java` (gói A và B đều phải thêm mã: file chung). |

### 1.2. Thông báo (`services/support-service`, context `notification`)

| | Điểm | Kết quả thật (file) |
|---|---|---|
| ✅ | Chỉ có thông báo trong ứng dụng | Bảng `notifications` (`audit_db`, RLS FORCE, `UNIQUE (tenant_id, event_id)`, migration `audit/V3__create_notifications.sql`); `CreateNotificationUseCase` idempotent theo `eventId`. |
| ✅ | Consumer hiện có | `ReservationReadyConsumer` (`reservation.events`, `ReservationReadyEvent`) và `ContractExpiringConsumer` (`identity-events`, `TenantContractExpiringSoon`, eventId mỗi người nhận = `nameUUIDFromBytes(eventId:userId)`); group `support-service-notification`. Văn bản dựng cứng trong Java. |
| ✅ | Không có email, không có thử lại | Không có `JavaMailSender`, `DefaultErrorHandler`, `DeadLetterPublishingRecoverer` ở bất kỳ module nào (grep toàn repo). Lỗi xử lý message chỉ log. |
| ✅ | Audit lưu thô event | `AuditEventConsumer` nghe `auth.events`, `access-control.events`, `borrowing.events`, `identity-events`, `sales.events` và lưu envelope thô vào Mongo (`MongoRawEventArchive`, có trường `tenantId`). Hệ quả: dữ liệu cá nhân đặt trong payload các topic này sẽ nằm lâu dài ở Mongo. |
| ✅ | Tiền lệ topic riêng để audit không lưu dữ liệu nhạy cảm | identity tách `auth.password-reset` khỏi `auth.events` "để audit không lưu token" (`identity-service/.../application.yml`). |
| ⚠️ | `auth.password-reset` không có consumer | Grep không thấy consumer nào (chỉ `PasswordResetEventPublisher`). Có thể thư quên mật khẩu hiện chưa được gửi; ngoài phạm vi ADR này, ghi lại để kiểm. |
| ✅ | Mẫu gửi mail | identity: `MailSenderPort.send(to, subject, body)` + `SmtpMailSender` (`SimpleMailMessage`, `ObjectProvider<JavaMailSender>`, ném lỗi khi chưa cấu hình), `spring.mail.*` mặc định `localhost:1025`, timeout 3s/5s/5s, `management.health.mail.enabled=false`. Mailpit có trong `docker-compose.yml` (SMTP 1025, UI 8025). |

### 1.3. Thư viện (`services/circulation-service`) và identity

| | Điểm | Kết quả thật (file) |
|---|---|---|
| ✅ | Sự kiện có sẵn | `borrowing.events`: `BookBorrowedEvent(userId, dueDate…)`, `BookReturnedEvent`, `BookLostEvent`; `fine.events`: `FineIssuedEvent(readerId, amount, reason…)`, `FinePaid/Refunded/Waived`; `reservation.events`: `ReservationReadyEvent(readerId, holdUntil…)`, `ReservationExpired/Cancelled/…`. |
| ✅ | Support chưa nghe `fine.events` | `support-service/application.yml` không có `fine-events`. |
| ✅ | Job mẫu | `ExpireReservationsJob` (`@Scheduled` cron, lặp `ActiveTenantsPort.activeTenantIds()`, `TenantContext.runAs`, lỗi một tenant không chặn tenant khác). Adapter duy nhất `KnownTenantsJdbcAdapter` (bảng `known_tenants`). `ActiveTenantsPort` nằm ở `reservation.application`. |
| ✅ | Hạn trả | `borrowings.due_date`, `BorrowingStatus` chỉ `ACTIVE/RETURNED` (quá hạn là suy ra), index `idx_borrowings_tenant_due_date (tenant_id, due_date)` (`circulation/V9`). |
| ✅ | Chính sách | `CirculationPolicy(loanDays, maxRenewals, renewalDays, maxActiveLoansPerReader, finePerDay, graceDays, reservationHoldDays)`; chưa có cấu hình nhắc hạn. |
| ✅ | Email người nhận | `users.email` (bảng toàn cục, identity) và `reader_profiles.email` theo tenant (`user/domain/ReaderProfile.java`). `POST /internal/users/lookup` chỉ trả họ tên, "không trả email/điện thoại". |
| ✅ | Khóa nội bộ | `InternalPermissionController` kiểm `X-Internal-Api-Key` bằng `MessageDigest.isEqual`, fail-closed khi trống; storefront dùng bí mật riêng `STOREFRONT_CLIENT_SECRET` (ADR-0011). |

### 1.4. Số migration hiện tại (kiểm lại ngay trước khi tạo file: nhiều phiên cùng thêm)

Thư mục `services/system-service/src/main/resources/db/migration/<target>/`:

| Target | File lớn nhất hiện có | Số kế tiếp hiện tại |
|---|---|---|
| `sales` | `V15__payment_method_sepay.sql` | `V16` |
| `audit` | `V5__audit_list_and_unread_indexes.sql` | `V6` |
| `circulation` | `V14__reservations_work_id_backfill.sql` | `V15` |
| `identity` | `V38__refund_approver_catalog_permissions.sql` (không có V33) | `V39` |
| `system` | `V6__add_menus_for_new_screens.sql` | `V7` |

## 2. Quyết định

### D1. Hóa đơn VAT: context mới `invoicing` trong sales-service, hóa đơn nội bộ + cổng `InvoiceIssuerPort`

**Chọn**: context `lms.sales.invoicing` (domain/application/infrastructure) trong sales-service, dữ liệu ở `sales_db`.
Hóa đơn là aggregate riêng, **bất biến sau ISSUED**; mọi sửa đổi bằng hóa đơn mới (thay thế hoặc điều chỉnh) gắn
`original_invoice_id`. Phát hành qua `InvoiceIssuerPort`; cài đặt mặc định `InternalInvoiceIssuer` (chỉ cấp số nội bộ,
không gửi đi đâu). Sau này cắm nhà cung cấp hóa đơn điện tử (HĐĐT) bằng adapter mới, không đổi use case.

> **Hóa đơn nội bộ này CHƯA có giá trị pháp lý của hóa đơn điện tử** theo Nghị định 123/2020/NĐ-CP và Thông tư 78/2021/TT-BTC
> cho tới khi tích hợp nhà cung cấp HĐĐT được cơ quan thuế chấp nhận (có mã của cơ quan thuế hoặc theo hình thức không mã
> đã đăng ký). FE và bản in phải ghi rõ "Hóa đơn nội bộ, không thay thế hóa đơn điện tử". ⚠️ Cần tư vấn pháp lý/kế toán
> trước khi dùng cho mục đích thuế.

Lý do: hóa đơn gắn chặt với đơn, dòng, VAT, phiếu trả (đều ở `sales_db`); đặt cùng DB thì lập hóa đơn và hóa đơn điều chỉnh
khi trả hàng nằm trong một transaction, không cần đồng bộ xuyên DB. Tách context riêng (không nhét vào `ordering`) để gói A
không sửa file của `ordering` ngoài một điểm móc.

Phương án đã loại:

| Phương án | Lý do loại |
|---|---|
| Service mới `invoice-service` + DB riêng | Lập hóa đơn điều chỉnh khi trả hàng phải đồng bộ xuyên DB (không có Outbox); thêm cổng, vai trò DB, compose. Vượt nhu cầu. |
| Mở rộng `SalesOrder` thêm trạng thái hóa đơn | Aggregate đơn vốn đã lớn; hóa đơn có vòng đời riêng (hủy, thay thế, điều chỉnh) và nhiều hóa đơn trên một đơn. |
| Tích hợp ngay nhà cung cấp HĐĐT | Người dùng đã loại khỏi giai đoạn này; cần hợp đồng nhà cung cấp, chứng thư số, đăng ký mẫu. |
| Tạo PDF phía server ngay (openhtmltopdf/OpenPDF) | Thêm phụ thuộc, phải nhúng font tiếng Việt, tốn RAM; HTML in được (trình duyệt "In → Lưu PDF") đáp ứng đủ. Để `InvoiceRendererPort` sẵn chỗ thêm PDF sau. |

Quy tắc nghiệp vụ (đặt ở `invoicing.domain`, Java thuần):

1. **Trạng thái**: `DRAFT → ISSUED → CANCELLED`. `DRAFT` chưa có số; số cấp đúng lúc ISSUED (trong cùng transaction với
   bộ đếm nên rollback không để lỗ hổng số). Sau ISSUED mọi trường bất biến (domain không có setter; adapter không có câu
   `UPDATE` trừ chuyển sang CANCELLED và cột tích hợp `external_*`). Không xóa hóa đơn ở bất kỳ trạng thái nào (DRAFT
   bỏ thì chuyển `CANCELLED` với lý do).
2. **Loại** (`kind`): `ORIGINAL`, `REPLACEMENT` (thay thế hóa đơn đã hủy, có `original_invoice_id`), `ADJUSTMENT_DECREASE`
   (điều chỉnh giảm, có `original_invoice_id`, có thể có `return_id`). Điều chỉnh tăng: ngoài phạm vi (đơn đã thu không
   tăng tiền được trong hệ thống hiện tại).
3. **Điều kiện lập**: chỉ đơn `COMPLETED` + `PAID` (POS sau xác nhận thu; online sau hoàn tất). Mỗi đơn tối đa một hóa đơn
   `ORIGINAL|REPLACEMENT` còn hiệu lực (chỉ mục duy nhất một phần). Đơn đã hoàn tất không hủy được (ADR-0010), nên không có
   xung đột "đơn hủy sau khi có hóa đơn".
4. **Hủy và thay thế**: `cancel(reason)` chỉ từ ISSUED, lý do bắt buộc; `replace` = hủy hóa đơn gốc + lập `REPLACEMENT`
   ISSUED trong cùng transaction (thường để sửa thông tin người mua). Hóa đơn gốc đã có điều chỉnh thì không thay thế
   được (409 `INVOICE_HAS_ADJUSTMENTS`).
5. **Trả hàng sinh điều chỉnh giảm**: khi `CommitReturnUseCase` commit phiếu trả cho đơn có hóa đơn còn hiệu lực, cùng
   transaction lập `ADJUSTMENT_DECREASE` ISSUED với dòng = các dòng trả, số tiền = `refundAmount` của dòng; VAT của dòng
   điều chỉnh = `round(refundAmount × vatAmount_dòng_gốc / lineTotal_dòng_gốc)` (HALF_UP), phần còn lại là tiền trước
   thuế. Tổng các điều chỉnh không vượt hóa đơn gốc (bất biến domain). Móc bằng port `InvoiceAdjustmentPort` (application
   của `invoicing`) mà `ordering` gọi: một dòng sửa trong `CommitReturnUseCase`.
6. **Thuế suất theo dòng**: `invoice_lines.vat_rate_bps` sao từ `sales_orders.vat_rate_bps` (hiện một thuế suất cho cả đơn);
   lưu theo dòng để sau này hỗ trợ nhiều thuế suất không phải đổi schema hóa đơn. Phí ship là một dòng riêng
   `vat_rate_bps = NULL` (không chịu thuế, đúng ADR-0010 S3). ⚠️ Cách thể hiện phí ship và sách "không chịu thuế" trên hóa
   đơn cần kế toán xác nhận.
7. **Tổng tiền bằng chữ**: `VietnameseAmountInWords` trong `invoicing.domain` (Java thuần), lưu snapshot `amount_in_words`
   lúc ISSUED. Quy tắc đọc: "linh/lẻ" (chọn "linh"), "mươi/mười", "mốt" (21, 31…), "lăm" (15, 25…), "tư" (24, 34…),
   nghìn/triệu/tỷ, hậu tố "đồng", viết hoa chữ đầu. Test bảng giá trị biên bắt buộc.
8. **Snapshot**: thông tin người bán (từ `invoice_settings` lúc ISSUED), người mua (từ yêu cầu xuất hóa đơn), mẫu số, ký
   hiệu, dòng hàng (tên, ISBN, đơn vị "cuốn", số lượng, đơn giá, giảm giá, tiền trước thuế, thuế suất, tiền thuế, thành tiền).
   Đổi cấu hình sau đó không đổi hóa đơn đã phát hành.
9. **Người mua**: không bắt buộc. Khách lẻ không yêu cầu thì hóa đơn ghi "Người mua không lấy hóa đơn" (⚠️ cách ghi cần kế
   toán xác nhận) và không có MST. Có MST thì bắt buộc tên đơn vị + địa chỉ; MST kiểm định dạng 10 hoặc 10-3 hoặc 12 chữ số
   (không kiểm tồn tại với cơ quan thuế).
10. **Số hóa đơn**: tuần tự theo `(tenant, template_code, series, năm)`, bắt đầu từ 1 mỗi năm (⚠️ theo cách hiểu TT78, cần
   xác nhận); bộ đếm `sales_counters` tên `INVOICE:<mẫu số>:<ký hiệu>:<năm>`. Số hiển thị đệm 8 chữ số. Khi tích hợp HĐĐT,
   adapter có thể trả số do nhà cung cấp cấp (`IssuerResult.assignedNumber`), use case ưu tiên số đó.

### D2. Yêu cầu xuất hóa đơn tách khỏi luồng tạo đơn

**Chọn**: bảng `invoice_requests` (một yêu cầu cho mỗi đơn) với endpoint riêng cho nhân viên (POS, chi tiết đơn) và cho
khách (đơn online của chính mình, qua customer-api). FE storefront gọi ngay sau khi đặt đơn thành công (hai request).
Bật cờ `autoIssueOnCompletion` trong `invoice_settings` thì đơn có yêu cầu được lập hóa đơn ngay khi đủ điều kiện (POS: lúc
xác nhận thu; online: lúc hoàn tất) qua cùng port móc ở `ordering`; tắt (mặc định) thì nhân viên bấm "Xuất hóa đơn".

Phương án đã loại: thêm `invoiceRequest` vào thân `POST /pos-orders` và đặt đơn online (sửa `PlacePosOrderCommand`,
`PlaceOnlineOrderCommand`, customer-api và hợp đồng FE cùng lúc; đụng file của các phiên đang sửa thanh toán). Đánh đổi
của phương án chọn: hai request không nguyên tử; yêu cầu thất bại thì đơn vẫn có, khách/nhân viên gửi lại từ chi tiết đơn.

### D3. Chốt ca thu ngân: context mới `shift` trong sales-service

**Chọn**: context `lms.sales.shift`, bảng ở `sales_db`. Ca gắn với **người thu ngân** (không mô hình két/quầy riêng giai
đoạn này; có trường `register_label` tự do để ghi quầy). Liên kết tiền vào ca bằng cột `shift_id` trên `payment_intents`,
`payment_records`, `refunds` (nullable, đơn cũ/online không có ca).

Quy tắc (ở `shift.domain`):

1. Mỗi thu ngân tối đa một ca `OPEN` (chỉ mục duy nhất một phần `(tenant_id, cashier_user_id) WHERE status = 'OPEN'`;
   mở trùng → 409 `SHIFT_ALREADY_OPEN`).
2. Mở ca: `openingFloat ≥ 0` (tiền đầu ca), `clientRequestId` chống bấm hai lần.
3. Gắn tiền vào ca:
   - Tạo đơn POS: `payment_intents.shift_id` = ca đang mở của người tạo (nếu có).
   - Xác nhận thu tay (`PaymentIntentController/confirm`, CASH/CARD/BANK_TRANSFER): `payment_records.shift_id` = ca đang
     mở của **người bấm xác nhận** (tiền nằm trong ngăn kéo của người đó).
   - Cổng xác nhận (QR/MoMo/SePay, webhook/thăm dò, không có người bấm): `payment_records.shift_id` = `payment_intents.shift_id`.
   - Hoàn tiền đánh dấu `REFUNDED`: `refunds.shift_id` = ca đang mở của người đánh dấu.
4. Cờ tenant `requireOpenShiftForPos` (mặc định `false`): bật thì tạo đơn POS, xác nhận thu tay và hoàn tiền **tiền mặt**
   khi người thao tác không có ca mở → 409 `SHIFT_NOT_OPEN`. Tắt thì vẫn bán được, tiền không vào ca nào (báo cáo ca chỉ
   tính phần có ca; FE cảnh báo).
5. Đóng ca: nhập `countedCash`; hệ thống tính và snapshot:
   `expectedCash = openingFloat + Σ payment_records(method = CASH, shift_id = ca) − Σ refunds(status = REFUNDED, method = CASH, shift_id = ca)`;
   `variance = countedCash − expectedCash` (dương = thừa, âm = thiếu). `|variance| > varianceThreshold` (cấu hình tenant,
   mặc định 0 đồng nghĩa mọi chênh lệch khác 0) thì `varianceReason` bắt buộc (400 `SHIFT_VARIANCE_REASON_REQUIRED`) và
   `variance_status = PENDING_APPROVAL`; ngược lại `NONE`. Ca còn lệnh thu tay `PENDING` của chính ca → 409
   `SHIFT_HAS_PENDING_PAYMENTS` (lệnh tự hết hạn sau 10 phút, ADR-0021).
6. Sau `CLOSED` ca bị khóa: không sửa số. Quản lý mở lại (`reopen`, lý do bắt buộc) → `OPEN` (409 nếu thu ngân đang có ca
   mở khác); đóng lại sinh snapshot mới. Mọi thao tác ghi bảng lịch sử chỉ chèn thêm `cashier_shift_events` và audit.
7. Duyệt chênh lệch: quản lý `approveVariance(note)` → `APPROVED`. Không có "từ chối" giai đoạn này (từ chối = mở lại ca).
8. Báo cáo ca: theo phương thức (`CASH, CARD, BANK_TRANSFER, QR, MOMO, SEPAY`): số giao dịch, tổng thu, tổng hoàn, ròng; tách
   "phát sinh sau khi chốt" (`paid_at > closed_at`, chỉ có thể là cổng thanh toán đến muộn, không ảnh hưởng tiền mặt). Tính
   bằng một câu gom nhóm trên `payment_records`/`refunds` theo `(tenant_id, shift_id)`, không lặp theo đơn.
9. Tiền nộp/rút giữa ca (cash in/out), két dùng chung nhiều thu ngân, bàn giao ca: ngoài phạm vi.

Phương án đã loại:

| Phương án | Lý do loại |
|---|---|
| `shift_id` chỉ trên `sales_orders` | Người tạo đơn và người thu tiền có thể khác nhau; hoàn tiền không gắn được; tiền mặt phải tính theo nơi tiền thực sự vào. |
| Tính tiền ca theo khoảng thời gian `opened_at..closed_at` + `recorded_by` (không cột `shift_id`) | Không cần migration cột nhưng sai khi mở lại ca, khi đồng hồ lệch, khi cổng xác nhận muộn; truy vấn khoảng thời gian tốn hơn. |
| Mô hình két (register) với nhiều thu ngân | Nhà sách nhỏ thường một người một quầy; thêm thực thể, phân quyền và màn hình. Ghi trường `register_label` để mở rộng. |
| Chặn bán khi chưa mở ca cho mọi tenant | Phá luồng của tenant đang dùng; người dùng yêu cầu cờ theo tenant. |

### D4. Thông báo theo sự kiện: pipeline trong support-service, cổng `NotificationProvider` theo kênh

**Chọn**: mở rộng context `notification` hiện có trong support-service (sẽ thành `audit-service` theo ADR-0014, package giữ
nguyên). Luồng: consumer Kafka → ánh xạ sự kiện thành `NotificationTrigger` → use case `EnqueueNotificationUseCase` (một
transaction `audit_db`): tạo thông báo trong ứng dụng (như hiện nay) + chèn `notification_deliveries` cho từng (người nhận ×
kênh ngoài được bật) ở trạng thái `PENDING` với khóa khử trùng → job `NotificationDispatchJob` lấy dòng đến hạn, tra địa chỉ,
dựng nội dung từ mẫu, gửi qua `NotificationProvider` của kênh, cập nhật trạng thái/lần thử.

Thành phần:

- `NotificationChannel` (domain enum): `EMAIL` (giai đoạn này), dự kiến `SMS`, `PUSH`, `ZALO`. `IN_APP` không đi qua provider
  (ghi thẳng bảng `notifications`, giữ hành vi hiện có).
- `NotificationProvider` (application port): `channel()`, `send(RenderedMessage, ResolvedAddress) → SendResult(providerMessageId)`,
  ném `TransientSendException` (thử lại) hoặc `PermanentSendException` (không thử lại: địa chỉ sai, bị từ chối).
- `NotificationProviderRegistry`: `Map<NotificationChannel, NotificationProvider>` dựng từ mọi bean provider; kênh không có
  provider thì bỏ qua (delivery `SKIPPED`, `skip_reason = NO_PROVIDER`). Thêm SMS = thêm một bean `SmsProvider` + mẫu
  `sms`, không sửa use case.
- `EmailProvider` (infrastructure, theo mẫu `SmtpMailSender` của identity): `spring.mail.*` mặc định Mailpit
  `localhost:1025`, timeout giống identity, `management.health.mail.enabled=false`; From = `lms.notification.email.from`
  với tên hiển thị là tên tenant (lấy cùng lúc tra địa chỉ). Văn bản thuần giai đoạn này.
- `RecipientDirectoryPort` (application) + adapter HTTP sang identity (D5).
- Mẫu nội dung (D6), tùy chọn nhận (D7), khử trùng/thử lại/hàng chờ lỗi (D8), giới hạn tốc độ (D9).

Phương án đã loại:

| Phương án | Lý do loại |
|---|---|
| Mỗi service tự gửi mail (như identity gửi OTP) | Lặp cấu hình SMTP, mẫu, tùy chọn, thử lại ở 4 service; người dùng chốt "theo sự kiện, một chỗ". OTP identity giữ nguyên (đồng bộ, cần ngay). |
| Gửi ngay trong luồng consumer | Kafka poll bị chặn theo SMTP (timeout 5s/thư); lỗi tạm thời phải dựa vào Kafka giao lại (repo chưa có error handler/DLT); khó giới hạn tốc độ. |
| Dùng Kafka retry topic + DLT (`@RetryableTopic`) | Thêm topic và cấu hình cho mỗi consumer, khó xem/thao tác lại cho quản trị; bảng `notification_deliveries` vừa là nhật ký vừa là hàng chờ, đơn giản hơn. |
| Service thông báo mới | Thêm cổng, DB, compose; support đã có context `notification`, DB `audit_db`, consumer group. |
| Template engine (Thymeleaf/FreeMarker) | Phụ thuộc mới, rủi ro chèn mẫu do tenant sửa (SSTI). Thay thế `{{tên}}` có danh sách tham số cho phép là đủ. |

### D5. Địa chỉ người nhận: tra identity lúc gửi, không đặt email vào sự kiện

**Chọn**: sự kiện chỉ mang `userId`. Lúc gửi, dispatcher gom `userId` của lô theo tenant và gọi identity
`POST /internal/notification-recipients/lookup` (tối đa 100 id/lần) trả `{userId, email, displayName, active}` và
`tenantDisplayName`. Ưu tiên `reader_profiles.email` của tenant, không có thì `users.email`. Chỉ trả người có membership
ACTIVE trong tenant đó (id ngoài tenant không có trong kết quả, ADR-0006). Bảo vệ bằng bí mật riêng
`NOTIFICATION_CLIENT_SECRET` trong header (tiền lệ `STOREFRONT_CLIENT_SECRET`, so sánh hằng thời gian, trống = từ chối),
`@PublicEndpoint` ở mức interceptor như `InternalPermissionController`; không đi qua lms-api/nginx.

`notification_deliveries` chỉ lưu `recipient_address_masked` (ví dụ `n***@gmail.com`), không lưu địa chỉ đầy đủ.

Phương án đã loại:

| Phương án | Lý do loại |
|---|---|
| Email đi kèm sự kiện | Dữ liệu cá nhân trên Kafka và **bị lưu lâu dài ở Mongo** vì `AuditEventConsumer` lưu thô `sales.events`, `borrowing.events`, `identity-events`; email cũ khi người dùng đã đổi. |
| support giữ bản sao danh bạ qua sự kiện identity | Thêm bảng dữ liệu cá nhân ở `audit_db`, phải đồng bộ (mất sự kiện là sai địa chỉ), phải xóa khi ẩn danh. |
| Dùng `INTERNAL_API_KEY` | Khóa này mọi service đều giữ (đăng ký quyền); lộ một service là đọc được email mọi tenant. Bí mật riêng thu hẹp phạm vi. |

Ngoại lệ có chủ đích: người nhận **không có tài khoản** (khách lẻ POS để lại email trên yêu cầu hóa đơn). sales phát lên
topic riêng `notification.direct` (không nằm trong danh sách topic của `AuditEventConsumer`, giống tiền lệ
`auth.password-reset`) với `recipientEmail` trong payload. support lưu địa chỉ ở cột `recipient_address` của delivery và
**xóa về NULL khi delivery đến trạng thái kết thúc** (`SENT`, `DEAD`, `SKIPPED`). ⚠️ Topic giữ message theo retention của
Kafka (đặt `retention.ms` ngắn, ví dụ 3 ngày, khi tạo topic; chưa kiểm cấu hình broker hiện tại).

### D6. Mẫu nội dung: mặc định trong classpath có phiên bản, tenant ghi đè trong DB

- Mặc định: `support-service/src/main/resources/notification-templates/vi/<NOTIFICATION_TYPE>/<channel>.v<n>.txt`, dòng đầu
  là tiêu đề (email), phần còn lại là thân. Phiên bản mặc định là hằng số trong `NotificationTemplateCatalog` (đổi nội dung =
  thêm file `v<n+1>`, không sửa file cũ, để delivery cũ vẫn truy được nội dung đã gửi).
- Ghi đè theo tenant: bảng `notification_templates` (RLS), mỗi lần lưu là một phiên bản mới, một phiên bản `active`.
- Tham số: `{{ten_tham_so}}`; mỗi loại có danh sách tham số cho phép (`NotificationType.allowedParams`), lưu mẫu có tham số
  lạ → 400 `TEMPLATE_PARAM_NOT_ALLOWED`. Thay thế là văn bản thuần (không thực thi gì). Mỗi delivery ghi
  `template_source` (`DEFAULT|TENANT`) + `template_version`.
- Kiểm soát dữ liệu cá nhân: tham số chỉ gồm số chứng từ, ngày, số tiền, số lượng, tên tenant, tên hiển thị người nhận,
  đường dẫn. Không có địa chỉ giao hàng, điện thoại, MST người mua, ghi chú nội bộ, lý do hủy dạng tự do.

### D7. Cấu hình kênh theo tenant và tùy chọn của người dùng

- `notification_channel_settings (tenant, notification_type, channel, enabled)`: không có dòng = mặc định trong code
  (EMAIL bật cho mọi loại của đợt đầu). Quản trị tenant bật/tắt.
- `notification_preferences (tenant, user, category, channel, enabled)`: người dùng tắt nhận theo nhóm (`ORDER`, `INVOICE`,
  `LOAN_REMINDER`, `RESERVATION`, `FINE`) và kênh. Nhóm bắt buộc không tắt được (400 `PREFERENCE_MANDATORY`): `ACCOUNT`
  (hợp đồng, xuất dữ liệu, vòng đời tenant ở ADR-0031), `REFUND`. Thông báo trong ứng dụng luôn ghi (không tắt được) để
  người dùng không mất thông tin.
- Quyết định gửi (thứ tự): kênh có provider → tenant bật → người dùng không tắt (hoặc nhóm bắt buộc) → có địa chỉ. Không đạt
  thì delivery `SKIPPED` với `skip_reason` (`NO_PROVIDER`, `CHANNEL_DISABLED`, `OPTED_OUT`, `NO_ADDRESS`, `INACTIVE_USER`),
  để lịch sử giải thích được vì sao không gửi.

### D8. Khử trùng, thử lại có giới hạn, hàng chờ lỗi

- Khóa khử trùng: `UNIQUE (tenant_id, event_id, channel, recipient_key)` với `recipient_key` = `userId` (có tài khoản) hoặc
  `sha256(lower(email))` (người nhận trực tiếp). Chèn bằng "chèn nếu chưa có" (cùng cách `saveIfAbsent` hiện có): Kafka
  giao lại, consumer chạy hai lần, hai instance cùng nhận đều chỉ ra một delivery.
- Sự kiện một-nhiều (`TenantContractExpiringSoon` mang danh sách người nhận): giữ quy ước hiện có `eventId` dẫn xuất
  `nameUUIDFromBytes(eventId:userId)` cho thông báo trong ứng dụng; delivery dùng `eventId` gốc + `recipient_key`.
- Dispatcher: `@Scheduled(fixedDelay = 5s)`; lặp tenant từ bảng `notification_tenants` (không RLS, giống `sales_tenants`
  ADR-0010 2.8, chèn "nếu chưa có" khi enqueue); mỗi tenant trong `TenantContext.runAs` lấy tối đa 50 delivery
  `status IN (PENDING, RETRY) AND next_attempt_at <= now()` theo `next_attempt_at`, khóa bi quan + bỏ qua dòng đã khóa
  (JPA `PESSIMISTIC_WRITE` + hint `jakarta.persistence.lock.timeout = -2`; ⚠️ chưa kiểm chứng Hibernate 6 sinh `SKIP LOCKED`
  trên PostgreSQL, IT phải xác nhận; nếu không thì đánh dấu `SENDING` bằng cập nhật có điều kiện `WHERE status IN (...)`).
  Chuyển `SENDING`, commit, gửi ngoài transaction, rồi ghi kết quả trong transaction mới. Dòng kẹt `SENDING` quá 5 phút
  (instance chết giữa chừng) được đưa lại `RETRY` (có thể gửi trùng một lần: chấp nhận, ghi ⚠️).
- Thử lại: tối đa 5 lần, chờ 1 phút, 5 phút, 30 phút, 2 giờ, 6 giờ (cấu hình `lms.notification.retry.*`). Lỗi tạm thời (SMTP
  4xx, timeout, identity không trả lời) → `RETRY`; lỗi vĩnh viễn hoặc hết lượt → `DEAD`. "Hàng chờ lỗi" = các dòng `DEAD`;
  quản trị tenant xem và **gửi lại** (`POST .../deliveries/{id}/retry` đưa về `PENDING`, `attempts` giữ, ghi người bấm).
- Lỗi được che: chỉ lưu `last_error_code` (ví dụ `SMTP_4XX`, `SMTP_5XX`, `TIMEOUT`, `RECIPIENT_LOOKUP_FAILED`,
  `TEMPLATE_ERROR`) và lớp ngoại lệ; không lưu message của SMTP (có thể chứa địa chỉ). Log theo kênh
  `lms.log.outbound.smtp` của ADR-0029 (che địa chỉ).
- Sự kiện hỏng (không parse được, thiếu `tenantId`): chỉ log WARN không kèm payload (giữ hành vi `TenantAwareEventHandler`).

### D9. Giới hạn tốc độ theo tenant

Dispatcher đếm số delivery `SENT` của tenant trong 60 giây gần nhất (truy vấn trên chỉ mục `(tenant_id, sent_at)`) và
dừng lấy thêm khi đạt `lms.notification.rate.per-tenant-per-minute` (mặc định 60); dòng còn lại giữ `PENDING` tới lượt sau.
Đếm bằng DB nên đúng khi nhiều instance. Thêm giới hạn toàn cục `lms.notification.rate.global-per-minute` (mặc định 600) để
không bị nhà cung cấp SMTP chặn. Không dùng Redis (support chưa phụ thuộc Redis).

### D10. Danh sách sự kiện đợt đầu: có sẵn và phải thêm

Sự kiện nghiệp vụ do service sở hữu dữ liệu phát; support-service chỉ tiêu thụ. Envelope `EventEnvelope` hiện có
(`eventId, eventType, aggregateId, occurredAt, version, payload, tenantId`).

| Loại thông báo | Sự kiện (topic) | Hiện trạng | Người nhận | Nhóm |
|---|---|---|---|---|
| Đơn online đã tiếp nhận | `OrderPlaced` (`sales.events`) | ✅ có; ⏳ cần làm giàu `customerUserId`, `orderNo`, `channel` | khách của đơn | ORDER |
| Đã thanh toán | `OrderPaid` | ✅ có; ⏳ làm giàu | khách (đơn có `customerUserId`) | ORDER |
| Sẵn sàng nhận tại quầy | `OrderReadyForPickup` | ✅ có; ⏳ làm giàu | khách | ORDER |
| Đang giao | `OrderShipping` | ✅ có; ⏳ làm giàu | khách | ORDER |
| Hoàn tất | `OrderCompleted` (chỉ `channel = ONLINE`) | ✅ có; ⏳ làm giàu (POS cũng ra `OrderCompleted`, phân biệt bằng `channel`) | khách | ORDER |
| Bị hủy / hết hạn giữ hàng | `OrderCancelled` | ✅ có; ⏳ làm giàu + `cancelKind` (`CUSTOMER`, `STAFF`, `EXPIRED`, `PAYMENT_TIMEOUT`) | khách | ORDER |
| Hoàn tiền xong | `RefundCompleted` | ✅ có; ⏳ làm giàu `customerUserId`, `orderNo` | khách | REFUND |
| Hóa đơn đã phát hành | `InvoiceIssued` (`sales.events`) | ⏳ mới (gói A, qua audit `INVOICE_ISSUED`) | khách có tài khoản; khách lẻ có email qua `notification.direct` | INVOICE |
| Sắp đến hạn trả | `LoanDueSoon` (`borrowing.events`) | ⏳ mới (gói D, job) | độc giả | LOAN_REMINDER |
| Đã quá hạn | `LoanOverdueReminder` (`borrowing.events`) | ⏳ mới (gói D, job) | độc giả | LOAN_REMINDER |
| Sách đặt trước sẵn sàng | `ReservationReadyEvent` (`reservation.events`) | ✅ có, đã có thông báo trong ứng dụng; ⏳ thêm email | độc giả | RESERVATION |
| Phạt mới phát sinh | `FineIssuedEvent` (`fine.events`) | ✅ có; ⏳ support chưa nghe topic | độc giả | FINE |
| Hợp đồng sắp hết hạn | `TenantContractExpiringSoon` (`identity-events`) | ✅ có, đã có thông báo trong ứng dụng; ⏳ thêm email | quản trị tenant | ACCOUNT |
| Thẻ độc giả sắp hết hạn | `ReaderCardExpiringSoon` (`identity-events`) | ⏭️ hoãn sang đợt sau (identity cần job riêng) | độc giả | ACCOUNT |

**Làm giàu sự kiện đơn hàng (gói D)**: thêm port `SalesEventEnrichmentPort` trong `shared.application`, cài đặt
`OrderEventEnricher` trong `ordering.infrastructure.messaging` (đọc đơn qua `SalesOrderRepository` trong cùng transaction,
thường đã nằm trong persistence context). `SalesAuditAdapter` gọi port khi `aggregateType ∈ {SalesOrder, Refund, Invoice}`
và **chỉ thêm vào sự kiện**, không thêm vào dòng `sales_audit_log`: `customerUserId`, `orderNo`, `channel`, `fulfilment`,
`grandTotal`, `cancelKind`. Đều là id/mã/số tiền, đúng ràng buộc "không dữ liệu cá nhân" của ADR-0010 2.6. Thay đổi cộng
thêm, giữ `version = 1`. Chọn cách này thay vì sửa từng chỗ gọi `audit.record(...)` (rải ở `OrderWorkflowUseCases`,
`PaymentIntentUseCases`, `ProcessPaymentWebhookUseCase`, `CommitReturnUseCase`, `RefundUseCases`) để gói D không đụng file
của gói A và B.

**Sự kiện nhắc hạn (gói D)**, `borrowing.events`, `version = 1`:

```json
// LoanDueSoon
{ "eventId": "uuid tất định", "borrowingId": "uuid", "readerId": "uuid", "dueDate": "2026-11-21",
  "daysBefore": 2, "itemCount": 3, "occurredAt": "..." }
// LoanOverdueReminder
{ "eventId": "uuid tất định", "borrowingId": "uuid", "readerId": "uuid", "dueDate": "2026-11-10",
  "daysOverdue": 9, "reminderNo": 2, "maxReminders": 3, "itemCount": 1, "occurredAt": "..." }
```

`eventId` tất định = `nameUUIDFromBytes(tenantId:borrowingId:KIND:dueDate:seq)` để job chạy lại không sinh sự kiện mới.
Không có tên sách (tránh gọi catalog; email ghi số cuốn và số phiếu). Chỉ id nên an toàn khi audit lưu thô.

**`InvoiceIssued`** (gói A, qua audit): `data = {invoiceId, invoiceNo, series, kind, orderId, grandTotal}`; enricher thêm
`customerUserId`, `orderNo`. Thông báo chứa đường dẫn tới trang đơn của khách (storefront) hoặc trang đơn nội bộ; **không
đính kèm PDF** giai đoạn này.

**`notification.direct`** (gói A phát, gói C tiêu thụ): `{eventId, tenantId, notificationType: "INVOICE_ISSUED",
recipientEmail, params: {invoiceNo, orderNo, grandTotal, issuedAt}}`; chỉ phát khi yêu cầu hóa đơn có email và đơn không có
`customerUserId`.

### D11. Job nhắc hạn trả ở circulation-service, phát sự kiện, không gửi mail

- `LoanReminderJob` (`borrowing.infrastructure.messaging`), cron `${app.borrowing.reminder-cron:0 0 7 * * *}` theo múi giờ
  `Asia/Ho_Chi_Minh`, lặp `ActiveTenantsPort` (dùng `reservation.application`, được phép theo luật ArchUnit) như
  `ExpireReservationsJob`.
- `SendLoanRemindersUseCase` (`borrowing.application`): đọc chính sách (bean gốc, không qua cache, đúng quy ước cache);
  - Sắp đến hạn: phiếu `ACTIVE` có `due_date` trong `[today, today + dueReminderDaysBefore]` và chưa có nhắc `DUE_SOON` cho
    `(phiếu, due_date)` (gia hạn đổi `due_date` thì nhắc lại cho hạn mới).
  - Quá hạn: phiếu `ACTIVE` có `due_date < today`, số lần nhắc `OVERDUE` cho `(phiếu, due_date)` < `overdueReminderMax` và
    lần cuối ≤ `today − overdueReminderIntervalDays` (lần đầu: ngay ngày đầu quá hạn sau `graceDays`).
  - Duyệt theo lô 500 (keyset theo `id`), một transaction mỗi lô; ghi `loan_reminders` rồi **phát đồng bộ trước khi commit**
    (`publishAndWait`, timeout 5s). Phát lỗi → rollback lô, mai thử lại; commit lỗi sau khi đã phát → sự kiện trùng
    `eventId` tất định, support khử trùng. Đây là cách giảm mất sự kiện cho job mà không cần Outbox.
- Chính sách thêm 3 trường: `dueReminderDaysBefore` (0 đến 30, mặc định 2; 0 = tắt), `overdueReminderIntervalDays` (1 đến 30,
  mặc định 3), `overdueReminderMax` (0 đến 20, mặc định 3; 0 = tắt).

Phương án đã loại: job ở support-service đọc dữ liệu mượn (vi phạm "không truy cập DB của nhau"); phát sau commit như
`SalesEventPublisher` (mất sự kiện là độc giả không bao giờ được nhắc vì `loan_reminders` đã ghi); không lưu
`loan_reminders` mà suy ra từ ngày (job lỡ một ngày là mất nhắc, không có trần số lần chắc chắn).

## 3. Thiết kế

### 3.1. Mô hình dữ liệu

Mọi bảng có `tenant_id` dưới đây: `ENABLE` + `FORCE ROW LEVEL SECURITY` với policy `tenant_isolation` cùng mẫu
`audit/V3`, entity có `@TenantId`. Tiền là `BIGINT` đồng. Chỉ mục theo quy ước CLAUDE.md (`tenant_id` đứng đầu).

**sales_db, gói A (`invoicing`)**, migration `sales/V16__invoicing.sql` (⚠️ kiểm số):

| Bảng | Cột chính | Ràng buộc / chỉ mục |
|---|---|---|
| `invoice_settings` | `tenant_id` PK, `seller_legal_name`, `seller_tax_code`, `seller_address`, `seller_phone`, `seller_email`, `template_code` (mẫu số, ví dụ `1`), `series` (ký hiệu, ví dụ `C26TAA`), `auto_issue_on_completion` bool, `version`, `updated_at`, `updated_by` | tạo lười như `sales_settings`; chưa cấu hình MST/tên pháp lý thì phát hành → 409 `INVOICE_SETTINGS_INCOMPLETE` |
| `invoice_requests` | `id`, `tenant_id`, `order_id`, `buyer_name`, `buyer_company`, `buyer_tax_code`, `buyer_address`, `buyer_email`, `source` (`POS`/`ONLINE`), `status` (`OPEN`/`FULFILLED`/`WITHDRAWN`), `requested_by`, `created_at`, `updated_at`, `pii_anonymized_at`, `version` | `UNIQUE (tenant_id, order_id)` |
| `invoices` | `id`, `tenant_id`, `kind`, `status`, `order_id`, `return_id` NULL, `original_invoice_id` NULL, `template_code`, `series`, `fiscal_year`, `invoice_no` INT NULL, `issued_at`, `issued_by`, `cancelled_at`, `cancelled_by`, `cancel_reason`, snapshot người bán (5 cột), snapshot người mua (5 cột), `currency`, `prices_include_vat`, `total_before_vat`, `vat_total`, `grand_total`, `amount_in_words`, `issuer` (`INTERNAL`/mã nhà cung cấp), `external_id`, `external_status`, `external_lookup_code`, `client_request_id`, `created_by`, `created_at`, `version` | `CHECK (grand_total >= 0)`; `UNIQUE (tenant_id, template_code, series, fiscal_year, invoice_no) WHERE invoice_no IS NOT NULL`; `UNIQUE (tenant_id, order_id) WHERE kind IN ('ORIGINAL','REPLACEMENT') AND status <> 'CANCELLED'`; `UNIQUE (tenant_id, return_id) WHERE return_id IS NOT NULL AND status <> 'CANCELLED'`; `UNIQUE (tenant_id, client_request_id) WHERE client_request_id IS NOT NULL`; chỉ mục `(tenant_id, status, issued_at DESC)`, `(tenant_id, order_id)`, `(tenant_id, original_invoice_id) WHERE original_invoice_id IS NOT NULL` |
| `invoice_lines` | `id`, `tenant_id`, `invoice_id`, `line_no`, `order_line_id` NULL (dòng phí ship), `item_name`, `isbn`, `unit`, `quantity`, `unit_price`, `discount`, `amount_before_vat`, `vat_rate_bps` NULL, `vat_amount`, `line_total` | `UNIQUE (tenant_id, invoice_id, line_no)` (đồng thời phục vụ nạp dòng theo hóa đơn) |

**sales_db, gói B (`shift`)**, migration `sales/V17__cashier_shifts.sql` (⚠️ kiểm số; A lấy V16, B lấy V17; ai merge
sau phải đổi số nếu có phiên khác chen vào):

| Bảng / cột | Cột chính | Ràng buộc / chỉ mục |
|---|---|---|
| `cashier_shift_settings` | `tenant_id` PK, `require_open_shift_for_pos` bool default false, `variance_threshold` BIGINT default 0, `version`, `updated_at`, `updated_by` | tạo lười |
| `cashier_shifts` | `id`, `tenant_id`, `shift_no` (`CA-000001`, `DocumentNumberPort` sequence `SHIFT`), `cashier_user_id`, `register_label`, `status` (`OPEN`/`CLOSED`), `opened_at`, `opening_float`, `closed_at`, `closed_by`, `counted_cash`, `expected_cash`, `variance`, `variance_reason`, `variance_status` (`NONE`/`PENDING_APPROVAL`/`APPROVED`), `approved_by`, `approved_at`, `approval_note`, `reopen_count`, `client_request_id`, `version` | `CHECK (opening_float >= 0)`; `UNIQUE (tenant_id, cashier_user_id) WHERE status = 'OPEN'`; `UNIQUE (tenant_id, client_request_id)`; chỉ mục `(tenant_id, status, opened_at DESC)`, `(tenant_id, cashier_user_id, opened_at DESC)`, `(tenant_id, variance_status) WHERE variance_status = 'PENDING_APPROVAL'` |
| `cashier_shift_events` | `id`, `tenant_id`, `shift_id`, `type` (`OPENED`/`CLOSED`/`REOPENED`/`VARIANCE_APPROVED`), `actor_id`, `occurred_at`, `snapshot` JSONB (số tiền, lý do) | chỉ chèn thêm; chỉ mục `(tenant_id, shift_id, occurred_at)` |
| `payment_intents.shift_id`, `payment_records.shift_id`, `refunds.shift_id` | UUID NULL | chỉ mục `(tenant_id, shift_id) WHERE shift_id IS NOT NULL` trên `payment_records` và `refunds` (báo cáo ca) |

Không cần thêm bảng vào `TENANT_EXEMPT_TABLES` cho gói A, B.

**audit_db, gói C (`notification`)**, migration `audit/V6__notification_deliveries.sql` (⚠️ kiểm số):

| Bảng | Cột chính | Ràng buộc / chỉ mục |
|---|---|---|
| `notification_tenants` | `tenant_id` PK, `first_seen_at` | **không RLS**, thêm vào `TenantArchitectureRules.TENANT_EXEMPT_TABLES` (không chứa dữ liệu tenant); không cần `@TenantId` |
| `notification_deliveries` | `id`, `tenant_id`, `event_id`, `event_type`, `notification_type`, `category`, `channel`, `recipient_user_id` NULL, `recipient_key`, `recipient_address` NULL (chỉ người nhận trực tiếp, xóa khi kết thúc), `recipient_address_masked`, `params` JSONB (không dữ liệu cá nhân), `template_source`, `template_version`, `status` (`PENDING`/`SENDING`/`SENT`/`RETRY`/`DEAD`/`SKIPPED`), `skip_reason`, `attempts`, `next_attempt_at`, `last_error_code`, `last_error_at`, `sent_at`, `provider_message_id`, `created_at`, `updated_at` | `UNIQUE (tenant_id, event_id, channel, recipient_key)`; chỉ mục `(tenant_id, status, next_attempt_at)` (dispatcher), `(tenant_id, sent_at) WHERE status = 'SENT'` (giới hạn tốc độ), `(tenant_id, recipient_user_id, created_at DESC)` (lịch sử của tôi), `(tenant_id, created_at DESC)` (danh sách quản trị) |
| `notification_templates` | `id`, `tenant_id`, `notification_type`, `channel`, `version`, `subject`, `body`, `active`, `created_by`, `created_at` | `UNIQUE (tenant_id, notification_type, channel, version)`; `UNIQUE (tenant_id, notification_type, channel) WHERE active` |
| `notification_channel_settings` | `tenant_id`, `notification_type`, `channel`, `enabled`, `updated_by`, `updated_at` | PK `(tenant_id, notification_type, channel)` |
| `notification_preferences` | `tenant_id`, `user_id`, `category`, `channel`, `enabled`, `updated_at` | PK `(tenant_id, user_id, category, channel)` |

Dọn dữ liệu: job hằng ngày xóa delivery kết thúc quá `lms.notification.retention-days` (mặc định 180).

**circulation_db, gói D**, migration `circulation/V15__loan_reminders.sql` (⚠️ kiểm số):

| Bảng / cột | Cột chính | Ràng buộc / chỉ mục |
|---|---|---|
| `circulation_policies` thêm cột | `due_reminder_days_before` INT NOT NULL DEFAULT 2, `overdue_reminder_interval_days` INT NOT NULL DEFAULT 3, `overdue_reminder_max` INT NOT NULL DEFAULT 3 | `CHECK` theo khoảng ở D11 |
| `loan_reminders` | `id`, `tenant_id`, `borrowing_id`, `kind` (`DUE_SOON`/`OVERDUE`), `due_date`, `seq`, `sent_on` DATE, `event_id` | `UNIQUE (tenant_id, borrowing_id, kind, due_date, seq)` (dùng cả cho tra "đã nhắc chưa"/"lần cuối") |

Truy vấn chọn phiếu dùng chỉ mục có sẵn `idx_borrowings_tenant_due_date (tenant_id, due_date)`; ⚠️ nếu `EXPLAIN` cho thấy
lọc `status = 'ACTIVE'` tốn, thêm chỉ mục một phần `(tenant_id, due_date) WHERE status = 'ACTIVE'` trong cùng V15 và bỏ cái
cũ nếu không còn dùng (không tạo thừa).

**identity_db, khóa quyền**, migration `identity/V39__invoice_shift_notification_permissions.sql` (⚠️ kiểm số): chèn khóa
vào `permissions` (`ON CONFLICT DO NOTHING`) và gán mẫu `role_template_permissions` theo mẫu V17/V29 (CASHIER `…c1`,
STORE_MANAGER `…c3`). Không backfill role của tenant đang chạy (tenant admin gán tay), giống V38.

**system_db, menu (gói E)**: `system/V7__menus_invoice_shift_notification.sql` (⚠️ kiểm số) thêm mục menu: Bán hàng → Hóa
đơn, Ca thu ngân; Quản trị → Thông báo (cấu hình, lịch sử gửi); Hồ sơ → Tùy chọn thông báo.

### 3.2. API (đường dẫn lms-api; service tương ứng `/internal/...`; khóa ADR-0003 là `Controller/method` của controller trong service)

Tên method dưới đây là đề xuất; **đổi tên method là đổi khóa**, be-dev chốt tên trước khi viết migration V39.

Gói A, sales-service `invoicing.infrastructure.web`, mọi controller `@RequiresModule(SALES)`:

| Method, lms-api | Khóa | Mẫu vai trò |
|---|---|---|
| `GET /api/sales/invoices` (query record: `status, kind, from, to, keyword, page, size`) | `InvoiceController/search` | c1, c3 |
| `GET /api/sales/invoices/{id}` | `InvoiceController/get` | c1, c3 |
| `GET /api/sales/invoices/{id}/print` (text/html in được) | `InvoiceController/print` | c1, c3 |
| `POST /api/sales/orders/{orderId}/invoice` `{clientRequestId}` | `InvoiceController/issue` | c1, c3 |
| `POST /api/sales/invoices/{id}/cancellation` `{reason}` | `InvoiceController/cancel` | c3 |
| `POST /api/sales/invoices/{id}/replacement` `{reason, buyer{...}, clientRequestId}` | `InvoiceController/replace` | c3 |
| `GET/PUT /api/sales/orders/{orderId}/invoice-request` | `InvoiceRequestController/get`, `/save` | c1, c3 |
| `GET/PUT /api/sales/invoice-settings` | `InvoiceSettingsController/get`, `/update` | c3 |
| customer-api `PUT /public/t/{code}/me/orders/{orderId}/invoice-request` → sales `/internal/sales/shop/orders/{id}/invoice-request` | `ShopInvoiceController/saveRequest` (cùng mẫu `ShopController`: `@RequiresModule(SALES)`, khóa động, khách lấy từ token, đơn của người khác → 404 `ORDER_NOT_FOUND`) | mẫu khách (V20) |

Lỗi: 404 `INVOICE_NOT_FOUND`; 409 `ORDER_NOT_INVOICEABLE` (chưa `COMPLETED+PAID`), `INVOICE_ALREADY_EXISTS`,
`INVOICE_STATE_CONFLICT`, `INVOICE_HAS_ADJUSTMENTS`, `INVOICE_SETTINGS_INCOMPLETE`; 400 `INVALID_TAX_CODE`,
`CANCEL_REASON_REQUIRED`.

Gói B, sales-service `shift.infrastructure.web`:

| Method, lms-api | Khóa | Mẫu vai trò |
|---|---|---|
| `POST /api/sales/shifts` `{openingFloat, registerLabel?, clientRequestId}` | `CashierShiftController/open` | c1, c3 |
| `GET /api/sales/shifts/me/current` (kèm số liệu tạm tính) | `CashierShiftController/current` | c1, c3 |
| `GET /api/sales/shifts/me` (query record) và `GET /api/sales/shifts/me/{id}` | `CashierShiftController/mine`, `/getMine` | c1, c3 |
| `POST /api/sales/shifts/me/current/closing` `{countedCash, varianceReason?}` | `CashierShiftController/close` | c1, c3 |
| `GET /api/sales/shifts` (mọi ca, lọc `cashierUserId, status, varianceStatus, from, to`) và `GET /api/sales/shifts/{id}` | `CashierShiftAdminController/search`, `/get` | c3 |
| `POST /api/sales/shifts/{id}/closing` (quản lý đóng thay) | `CashierShiftAdminController/closeOnBehalf` | c3 |
| `POST /api/sales/shifts/{id}/reopening` `{reason}` | `CashierShiftAdminController/reopen` | c3 |
| `POST /api/sales/shifts/{id}/variance-approval` `{note?}` | `CashierShiftAdminController/approveVariance` | c3 |
| `GET/PUT /api/sales/shift-settings` | `CashierShiftSettingsController/get`, `/update` | c3 |

Tách controller "của tôi" và "quản trị" vì ADR-0003 chỉ kiểm quyền theo endpoint; quyền sở hữu ("ca của mình") kiểm trong
use case theo `CurrentUser`. Lỗi: 409 `SHIFT_ALREADY_OPEN`, `SHIFT_NOT_OPEN`, `SHIFT_STATE_CONFLICT`,
`SHIFT_HAS_PENDING_PAYMENTS`; 400 `SHIFT_VARIANCE_REASON_REQUIRED`, `REOPEN_REASON_REQUIRED`; 404 `SHIFT_NOT_FOUND`.

Gói C, support-service `notification.infrastructure.web` (không thuộc module nào, tenant bắt buộc):

| Method, lms-api | Khóa |
|---|---|
| `GET /api/notifications/me/preferences`, `PUT /api/notifications/me/preferences` | `@AuthenticatedOnly` (của chính mình) |
| `GET /api/notifications/me/deliveries` (lịch sử gửi cho tôi, địa chỉ đã che) | `@AuthenticatedOnly` |
| `GET /api/notification-deliveries` (query record: `status, channel, type, from, to, page, size`) | `NotificationDeliveryController/search` |
| `POST /api/notification-deliveries/{id}/retry` | `NotificationDeliveryController/retry` |
| `GET/PUT /api/notification-settings/channels` | `NotificationSettingsController/getChannels`, `/updateChannels` |
| `GET /api/notification-templates`, `GET /api/notification-templates/{type}/{channel}`, `PUT` (tạo phiên bản mới), `POST .../preview` | `NotificationTemplateController/list`, `/get`, `/save`, `/preview` |

Khóa C mặc định chỉ tenant admin (`is_admin`); không gán mẫu nào trong V39.

identity (gói C): `POST /internal/notification-recipients/lookup` `{tenantId, userIds[≤100]}` → `{tenantDisplayName,
recipients[{userId, email, displayName, active}]}`; header `X-Notification-Client-Secret`; `@PublicEndpoint`; không qua
lms-api.

Gói D, circulation: `GET/PUT /api/circulation/policy` thêm 3 trường (khóa có sẵn, không đổi tên). Không thêm endpoint.

### 3.3. Luồng chính

```mermaid
sequenceDiagram
    participant S as sales/circulation/identity
    participant K as Kafka
    participant N as support: consumer + Enqueue
    participant DB as audit_db
    participant J as NotificationDispatchJob
    participant I as identity /internal/notification-recipients
    participant P as EmailProvider (SMTP/Mailpit)
    S->>S: ghi nghiệp vụ + audit (1 transaction)
    S-->>K: sự kiện (best-effort sau commit; job nhắc hạn: đồng bộ trước commit)
    K->>N: envelope (at-least-once)
    N->>DB: notifications (in-app) + notification_deliveries PENDING (ON CONFLICT DO NOTHING)
    J->>DB: lấy lô đến hạn theo tenant, SENDING
    J->>I: tra địa chỉ theo lô userId
    J->>P: gửi (ngoài transaction)
    J->>DB: SENT / RETRY (next_attempt_at) / DEAD / SKIPPED
```

Phát hành hóa đơn (A): `IssueInvoiceUseCase` (một transaction `sales_db`): khóa đơn (`PESSIMISTIC_WRITE` như trả hàng) →
kiểm điều kiện → dựng `Invoice` từ đơn + `invoice_settings` + `invoice_requests` → `InvoiceIssuerPort.issue` (nội bộ: cấp số
từ bộ đếm) → lưu → audit `INVOICE_ISSUED` → yêu cầu `FULFILLED` → (sau commit) sự kiện qua `SalesAuditAdapter` và
`notification.direct` nếu cần. Khi có nhà cung cấp HĐĐT thật, lời gọi ra ngoài **không** được nằm trong transaction giữ khóa
đơn: lúc đó tách thành DRAFT (commit) → gọi nhà cung cấp → ISSUED (transaction mới); port đã trả `IssuerResult` có trạng
thái để hỗ trợ bước này.

Chốt ca (B): `CloseShiftUseCase`: khóa ca (`@Version` + kiểm `OPEN`) → kiểm không còn lệnh thu tay `PENDING` của ca → một
câu tổng hợp tiền mặt → tính chênh lệch → kiểm lý do theo ngưỡng → lưu, sự kiện lịch sử, audit `SHIFT_CLOSED`.

### 3.4. Ranh giới service

- sales-service sở hữu hóa đơn, ca, yêu cầu hóa đơn; phát sự kiện. Không gửi mail.
- circulation-service sở hữu lịch nhắc và chính sách nhắc; phát sự kiện. Không gửi mail.
- identity-service cung cấp địa chỉ và tên người nhận theo tenant (API nội bộ); giữ gửi OTP của mình.
- support-service (sau này `audit-service`) là nơi duy nhất gửi thông báo ra ngoài.
- lms-api: thêm client + controller chuyển tiếp cho các endpoint trên (`SalesInvoiceController`, `SalesShiftController` trong
  `lms.api.sales.web`; mở rộng `lms.api.support.web.NotificationController` + controller quản trị thông báo mới).

## 4. Phân việc

Thứ tự phụ thuộc: **S0 → (A ∥ B ∥ C ∥ D-circulation) → D-sales (sau A) → E (theo từng gói BE xong hợp đồng)**.

| Gói | Ai | Phạm vi file/package (chỉ gói này sửa) | Phụ thuộc |
|---|---|---|---|
| ⏳ S0 | be-dev (một người, ngắn) | sales-service: thêm toàn bộ mã lỗi mới của A và B vào `shared/domain/ErrorCode.java`; thêm `DocumentNumberPort.nextValue(tenant, sequence): long` và hằng `SHIFT` + tiền tố `CA` trong `DocumentNumberAdapter`; thêm hằng audit vào `SalesAuditPort.Actions` (`INVOICE_ISSUED`, `INVOICE_CANCELLED`, `INVOICE_REPLACED`, `INVOICE_ADJUSTED`, `INVOICE_REQUEST_SAVED`, `INVOICE_SETTINGS_CHANGED`, `SHIFT_OPENED`, `SHIFT_CLOSED`, `SHIFT_REOPENED`, `SHIFT_VARIANCE_APPROVED`, `SHIFT_SETTINGS_CHANGED`). messaging-common: `KafkaEventPublisher.publishAndWait(event, timeout)`. Chốt tên method/khóa của A, B, C rồi viết `identity/V39`. | không |
| ⏳ A Hóa đơn | be-dev 1 | sales-service `lms.sales.invoicing.**` (mới); `sales/V16__invoicing.sql`; **một điểm móc** trong `ordering/application/CommitReturnUseCase.java` (gọi `InvoiceAdjustmentPort`) và điểm móc `autoIssueOnCompletion` (gọi `InvoiceAutoIssuePort`, no-op khi tắt) ở ba nơi đơn thành `COMPLETED + PAID`: `ordering/application/OrderWorkflowUseCases.complete` (online), `PaymentIntentUseCases.confirm` (POS thu tay), `ApplyGatewayPaymentUseCase` (POS qua cổng; ⚠️ be-dev xác nhận đây đúng là điểm chung của webhook và thăm dò); bean trong `invoicing.infrastructure.config.InvoicingBeanConfiguration`; publisher topic `notification.direct` trong `invoicing.infrastructure.messaging`. lms-api: `lms.api.sales.web.SalesInvoiceController` + phương thức client mới trong file client riêng `SalesInvoiceClient` (không sửa `SalesClient`). customer-api: endpoint yêu cầu hóa đơn của khách. | S0 |
| ⏳ B Chốt ca | be-dev 2 | sales-service `lms.sales.shift.**` (mới); `sales/V17__cashier_shifts.sql` (gồm cột `shift_id` trên 3 bảng của ordering); điểm móc qua port `ShiftAttributionPort` (thuộc `shift.application`) trong `ordering/application/PlacePosOrderUseCase` / `CommitPosPendingOrderUseCase` (gắn `payment_intents.shift_id`, chặn khi cờ bật), `PaymentIntentUseCases.confirm` và `ProcessPaymentWebhookUseCase`/`ApplyGatewayPaymentUseCase` (chép `shift_id` sang `payment_records`), `RefundUseCases` (hoàn tiền mặt); thêm trường `shiftId` vào entity JPA của `payment_intents`, `payment_records`, `refunds` (`ordering/infrastructure/persistence`). lms-api: `SalesShiftController` + `SalesShiftClient`. | S0 |
| ⏳ C Thông báo | be-dev 3 | support-service `lms.support.notification.**` (mở rộng: domain `NotificationChannel`, `NotificationType`, `NotificationCategory`, `Delivery`; application `NotificationProvider`, `NotificationProviderRegistry`, `EnqueueNotificationUseCase`, `DispatchNotificationsUseCase`, `RecipientDirectoryPort`, use case cấu hình/tùy chọn/mẫu; infrastructure `messaging` consumer cho `sales.events`, `borrowing.events`, `fine.events`, `reservation.events`, `identity-events`, `notification.direct`; `email/EmailProvider`; `client/HttpRecipientDirectory`; `scheduling/NotificationDispatchJob`); sửa `ReservationReadyConsumer`, `ContractExpiringConsumer` để đi qua `EnqueueNotificationUseCase`; `audit/V6`; `application.yml` support (khối `spring.mail`, `lms.notification` ở cuối file; topic `fine-events`, `notification-direct`); `pom.xml` support thêm `spring-boot-starter-mail`; mẫu trong `resources/notification-templates/`. identity: `lms.identity.user.infrastructure.web.NotificationRecipientController` + use case trong `user.application`; cấu hình `lms.notification.client-secret`. tenant-common: thêm `notification_tenants` vào `TENANT_EXEMPT_TABLES`. lms-api: controller/client thông báo. docker-compose/.env: **không sửa** (ghi yêu cầu biến `NOTIFICATION_CLIENT_SECRET`, `MAIL_HOST` cho người vận hành). | S0 (chỉ cần hợp đồng sự kiện; consumer chịu được trường thiếu) |
| ⏳ D Nhắc hạn + làm giàu sự kiện | be-dev 4 | circulation: `borrowing/application/SendLoanRemindersUseCase`, `LoanReminderRepository` port, `borrowing/domain/LoanDueSoonEvent`, `LoanOverdueReminderEvent`, `borrowing/infrastructure/messaging/LoanReminderJob`, persistence `loan_reminders`; `policy/**` (3 trường mới, request/response, validate); `circulation/V15`. sales (sau khi A merge vì A có thêm `aggregateType = Invoice`): `shared/application/SalesEventEnrichmentPort`, `shared/infrastructure/persistence/SalesAuditAdapter.java` (file chung, chỉ D sửa), `ordering/infrastructure/messaging/OrderEventEnricher` (mới). | S0; phần sales sau A |
| ⏳ E FE | fe-dev | repo `library-management-system-fe`, `src/pages/sales/`: `OrderDetail.tsx` (khối hóa đơn: yêu cầu, xuất, in, hủy/thay thế, danh sách điều chỉnh), `Pos.tsx` (mở ca bắt buộc nếu cờ bật, nhập thông tin xuất hóa đơn, nút in hóa đơn sau thu), trang mới `Invoices.tsx`, `InvoiceSettings` (trong `Settings.tsx` hoặc trang riêng), `Shifts.tsx` (của tôi + quản trị, đóng ca, duyệt chênh lệch, báo cáo theo phương thức); `src/pages/admin/` trang cấu hình thông báo (kênh, mẫu, lịch sử gửi, gửi lại); `src/pages/profile/` tùy chọn nhận thông báo; `system/V7` menu (BE làm cùng gói C hoặc E, một người). | theo hợp đồng từng gói BE |

Song song an toàn: A, B, C, D-circulation không chung file ngoài S0. Điểm giao A–B trong `ordering`: A sửa
`CommitReturnUseCase`, `OrderWorkflowUseCases.complete`; B sửa `PlacePosOrderUseCase`/`CommitPosPendingOrderUseCase`,
`ProcessPaymentWebhookUseCase`/`ApplyGatewayPaymentUseCase`, `RefundUseCases`, entity persistence; **cả hai sửa
`PaymentIntentUseCases.confirm` và `ApplyGatewayPaymentUseCase`** (A: móc tự phát hành; B: gắn ca) → B merge trước, A
rebase (mỗi bên chỉ thêm một lời gọi port).
Lưu ý `PaymentIntentController`, `SyncGatewayPaymentUseCase`, các gateway đang có thay đổi chưa commit của phiên khác:
không chạm.

Kiểm thử từng gói (tester):

| Gói | Bắt buộc |
|---|---|
| A | Unit domain: máy trạng thái, bất biến sau ISSUED, `VietnameseAmountInWords` (0, 1, 5, 10, 11, 15, 21, 24, 25, 101, 105, 1.000.005, 1.000.000.000, giá trị lớn), VAT điều chỉnh làm tròn, tổng điều chỉnh không vượt gốc. WebMvc phân quyền mọi endpoint (bật `lms.security.permission.enabled`, `@MockitoBean PermissionKeyRegistrar`), module SALES tắt → 403. IT (Testcontainers, vai trò `sales_app`): RLS hai tenant (không thấy/không phát hành hóa đơn tenant khác), số hóa đơn liên tục khi hai phát hành song song, rollback không để lỗ số, trả hàng sinh điều chỉnh cùng transaction, chỉ mục duy nhất chặn hai hóa đơn gốc. ArchUnit hiện có phải xanh (`invoicing.domain` không Spring). |
| B | Unit domain: tính chênh lệch, ngưỡng, mở lại. IT: hai yêu cầu mở ca song song → một thành công; đóng ca tính đúng với đơn CASH xác nhận bởi người khác ca, hoàn tiền mặt, cổng xác nhận muộn; cờ bật chặn POS/confirm/hoàn tiền mặt; RLS hai tenant. WebMvc: thu ngân không đóng/xem ca người khác qua endpoint "của tôi"; endpoint quản trị cần khóa riêng. Đếm câu SQL báo cáo ca không tăng theo số đơn. |
| C | Unit: registry chọn provider theo kênh, kênh không provider → SKIPPED; render mẫu chặn tham số lạ; che địa chỉ. IT (`audit_app`, Kafka Testcontainers hoặc gọi thẳng consumer): **giao lại cùng message 3 lần → 1 delivery, 1 thư** (Mailpit/GreenMail hoặc provider giả); SMTP lỗi tạm → RETRY với `next_attempt_at` đúng lịch, hết lượt → DEAD, gửi lại → PENDING; opt-out → SKIPPED; nhóm bắt buộc không tắt được; giới hạn tốc độ giữ PENDING; RLS hai tenant cho deliveries/preferences/templates; identity lookup sai bí mật → 401, userId tenant khác không trả. WebMvc phân quyền endpoint quản trị. |
| D | Unit: chọn phiếu sắp hạn/quá hạn theo chính sách, trần số lần, gia hạn đổi hạn thì nhắc lại; `eventId` tất định. IT circulation: job chạy hai lần cùng ngày không phát thêm; Kafka lỗi → rollback lô, không ghi `loan_reminders`; nhiều tenant chạy độc lập. sales: sự kiện `OrderReadyForPickup` có `customerUserId`, `OrderCancelled` do hết hạn có `cancelKind = EXPIRED`; dòng `sales_audit_log` không đổi nội dung. |
| E | e2e: POS mở ca → bán tiền mặt → xác nhận → in hóa đơn → đóng ca lệch → quản lý duyệt; trả hàng → thấy hóa đơn điều chỉnh; Mailpit nhận thư "sẵn sàng nhận tại quầy". |

perf-tester: dispatcher với 10.000 delivery PENDING ở 20 tenant (thời gian xả, tôn trọng giới hạn tốc độ, không khóa chéo hai
instance); báo cáo ca với 5.000 đơn/ca; job nhắc hạn với 100.000 phiếu ACTIVE (thời gian, số câu SQL theo lô).

## 5. Giả định đã tự chốt (người dùng sửa được)

1. Hóa đơn chỉ lập cho đơn `COMPLETED + PAID`; mặc định nhân viên bấm xuất (cờ `autoIssueOnCompletion` tắt).
2. Một thuế suất cho cả đơn như hiện tại; hóa đơn lưu thuế suất theo dòng để mở rộng sau. Phí ship là dòng không chịu thuế.
3. Số hóa đơn đặt lại về 1 mỗi năm theo (mẫu số, ký hiệu); một mẫu số/ký hiệu đang dùng cho mỗi tenant.
4. Hủy hóa đơn chỉ quản lý cửa hàng (c3); thu ngân xuất và in được.
5. Trả hàng tự động sinh hóa đơn điều chỉnh giảm ở trạng thái ISSUED nếu đơn có hóa đơn.
6. Bản in là HTML; không có PDF phía server và không đính kèm PDF trong email (gửi đường dẫn).
7. Ca gắn với người thu ngân, không mô hình két; không có nộp/rút tiền giữa ca; ngưỡng chênh lệch mặc định 0 đồng.
8. Cờ "chặn bán khi chưa mở ca" mặc định tắt cho mọi tenant.
9. Kênh đầu tiên là EMAIL văn bản thuần; thông báo trong ứng dụng vẫn ghi cho mọi sự kiện có người nhận có tài khoản.
10. Nhóm `ACCOUNT` và `REFUND` là bắt buộc (không tắt email được).
11. Thử lại 5 lần (1 phút → 6 giờ); 60 thư/phút/tenant, 600 thư/phút toàn hệ thống; giữ lịch sử gửi 180 ngày.
12. Nhắc sắp hạn trước 2 ngày; quá hạn nhắc mỗi 3 ngày, tối đa 3 lần; job chạy 07:00 giờ Việt Nam.
13. Email của độc giả ưu tiên `reader_profiles.email` của tenant, không có thì `users.email`.
14. Đường dẫn trong thư: đơn online dẫn tới trang đơn của khách trên storefront theo `{code}` của tenant; thông báo nội bộ dẫn
   tới trang quản trị. URL gốc cấu hình `lms.notification.links.*` (⚠️ chưa có cấu hình URL storefront dùng chung).

## 6. Rủi ro và việc chưa kiểm chứng

- ⚠️ Giá trị pháp lý: hóa đơn nội bộ không phải HĐĐT; cách ghi khách lẻ, phí ship, sách không chịu thuế/5%, quy tắc đánh số
  theo năm cần kế toán/pháp lý xác nhận trước khi dùng cho thuế.
- ⚠️ Sự kiện mất khi Kafka lỗi (ADR-0001): thông báo đơn hàng có thể không bao giờ gửi. Chấp nhận theo quyết định người dùng;
  job nhắc hạn đã giảm thiểu bằng phát đồng bộ trước commit + `eventId` tất định. `sales_audit_log.published_at` vẫn để sẵn
  cho Outbox.
- ⚠️ Gửi trùng tối đa một lần khi instance chết giữa `SENDING` và ghi kết quả (đưa lại RETRY sau 5 phút).
- ⚠️ Hibernate 6 + PostgreSQL có sinh `FOR UPDATE SKIP LOCKED` với hint `lock.timeout = -2` hay không: chưa kiểm; IT phải xác
  nhận, không được thêm native query ngoài danh sách đã rà.
- ⚠️ `auth.password-reset` không có consumer (grep): thư quên mật khẩu có thể không được gửi. Có thể đưa vào pipeline này sau
  (cần bàn vì payload mang token thô).
- ⚠️ Đổi tên `support-service` → `audit-service` (ADR-0014) đang chờ: gói C và phiên đổi tên sửa cùng thư mục; phải xếp
  tuần tự (khuyến nghị gói C trước, đổi tên sau).
- ⚠️ Topic mới `notification.direct` chứa email: cần `retention.ms` ngắn và không thêm vào `AuditEventConsumer`; chưa kiểm cấu
  hình tạo topic tự động của broker trong compose.
- ⚠️ Biến môi trường mới (`NOTIFICATION_CLIENT_SECRET`, `MAIL_HOST/PORT/USERNAME/PASSWORD` cho support,
  `lms.notification.email.from`): người vận hành phải đặt trong compose/.env; SA không sửa các file đó.
- ⚠️ `dev-start.ps1` tắt support mặc định (`-WithSupport`): không bật support thì không có thông báo; sự kiện dồn trong Kafka
  và được xử lý khi bật (consumer `earliest`), có thể gửi dồn thư cũ. Cân nhắc bỏ qua sự kiện quá cũ (ví dụ > 3 ngày) cho
  loại nhắc hạn.
- ⚠️ Số migration V16/V17 (sales), V6 (audit), V15 (circulation), V39 (identity), V7 (system) là số tại thời điểm viết.

## 7. Câu hỏi mở (đã có mặc định để làm tiếp)

1. Có cần hóa đơn điều chỉnh tăng hoặc lập một hóa đơn cho nhiều đơn (gộp) không? Mặc định: không.
2. Thu ngân có được tự hủy/thay thế hóa đơn trong ngày không? Mặc định: không, chỉ quản lý.
3. Ngưỡng chênh lệch tiền mặt mặc định 0 đồng có quá chặt? Mặc định giữ 0 để mọi chênh lệch phải có lý do; tenant tự nâng.
4. Thông báo cho đơn POS có tài khoản khách (`customerUserId`) có gửi không? Mặc định: chỉ `InvoiceIssued` và `RefundCompleted`,
   không gửi `OrderPaid`/`OrderCompleted` cho POS (khách đang đứng tại quầy).

## Phụ lục A. Khóa quyền đã chốt (gói S0, 19/11/2026)

Migration seed là `identity/V40__invoice_shift_notification_permissions.sql` (V39 đã bị `V39__sales_storefront_topsold_byids_permissions.sql`
dùng). Tên file cố ý không bắt đầu bằng `sales_` để `SalesPermissionSeedMatchesCodeTest` (chỉ đối chiếu `V*__sales_*.sql`) không
đòi controller chưa tồn tại. **Tên dưới đây là hợp đồng**: gói A, B, C, F đặt đúng tên controller/method; đổi tên = đổi khóa
= phải thêm migration mới. Không backfill role của tenant đang chạy (tenant admin gán tay, như V38).

| Gói | Khóa | Gán mẫu |
|---|---|---|
| A | `InvoiceController/search`, `/get`, `/print`, `/issue` | c1, c3 |
| A | `InvoiceController/cancel`, `/replace` | c3 |
| A | `InvoiceRequestController/get`, `/save` | c1, c3 |
| A | `InvoiceSettingsController/get`, `/update` | c3 |
| A | `ShopInvoiceController/saveRequest` (khách, đơn của mình) | c4 |
| B | `CashierShiftController/open`, `/current`, `/mine`, `/getMine`, `/close` | c1, c3 |
| B | `CashierShiftAdminController/search`, `/get`, `/closeOnBehalf`, `/reopen`, `/approveVariance` | c3 |
| B | `CashierShiftSettingsController/get`, `/update` | c3 |
| C | `NotificationDeliveryController/search`, `/retry`; `NotificationSettingsController/getChannels`, `/updateChannels`; `NotificationTemplateController/list`, `/get`, `/save`, `/preview` | không mẫu (chỉ `is_admin`) |
| F | `TenantDataExportController/request`, `/list`, `/get`, `/download` (identity, ADR-0031) | không mẫu (chỉ `is_admin`) |

Ghi chú: endpoint `@AuthenticatedOnly` của gói C (tùy chọn nhận thông báo của tôi) không có khóa. `PlatformTenantDataController/*`
là `@PlatformAdminOnly` nên không seed khóa. Đường dẫn metadata của khóa C, F trong V40 là dự kiến (`ON CONFLICT DO NOTHING`, bản
tự đăng ký lúc chạy không bị ghi đè; chỉ ảnh hưởng hiển thị).

## Phụ lục B. Hợp đồng dùng chung đã thêm ở S0

- `ErrorCode` (sales): mã lỗi gói A (`INVOICE_NOT_FOUND` 404; `ORDER_NOT_INVOICEABLE`, `INVOICE_ALREADY_EXISTS`,
  `INVOICE_STATE_CONFLICT`, `INVOICE_HAS_ADJUSTMENTS`, `INVOICE_SETTINGS_INCOMPLETE` 409; `INVALID_TAX_CODE`,
  `CANCEL_REASON_REQUIRED` 400) và gói B (`SHIFT_NOT_FOUND` 404; `SHIFT_ALREADY_OPEN`, `SHIFT_NOT_OPEN`, `SHIFT_STATE_CONFLICT`,
  `SHIFT_HAS_PENDING_PAYMENTS` 409; `SHIFT_VARIANCE_REASON_REQUIRED`, `REOPEN_REASON_REQUIRED` 400).
- `DocumentNumberPort`: hằng `SHIFT` (tiền tố `CA`, hiển thị `CA-000001`) và `long nextValue(tenant, sequence)` (số thô, cùng
  transaction, `Propagation.MANDATORY`). Lưu ý cho gói A: `sales_counters.name` là `VARCHAR(30)`; tên bộ đếm hóa đơn
  `INVOICE:<mẫu số>:<ký hiệu>:<năm>` phải ≤ 30 ký tự (ví dụ `INVOICE:1:C26TAA:2026` = 21), nếu không thì gói A nới cột trong
  `sales/V16`.
- `SalesAuditPort.Actions`: `INVOICE_ISSUED`, `INVOICE_CANCELLED`, `INVOICE_REPLACED`, `INVOICE_ADJUSTED`, `INVOICE_REQUEST_SAVED`,
  `INVOICE_SETTINGS_CHANGED`, `SHIFT_OPENED`, `SHIFT_CLOSED`, `SHIFT_REOPENED`, `SHIFT_VARIANCE_APPROVED`, `SHIFT_SETTINGS_CHANGED`.
- `KafkaEventPublisher.publishAndWait(DomainEvent, Duration)`: gửi đồng bộ, ném `KafkaEventPublisher.EventPublishException` khi
  broker lỗi, hết giờ, bị ngắt hoặc `send` ném ngoại lệ; `publish` giữ nguyên best-effort.
