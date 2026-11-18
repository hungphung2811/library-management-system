# ADR-0029: Ba kênh log (hệ thống, tích hợp gọi ra, tích hợp gọi vào), che dữ liệu nhạy cảm và log bất thường có `anomalyType`

- Trạng thái: Đề xuất (chưa cài đặt). Giai đoạn 1: `observability-common` + `sales-service` (luồng thanh toán).
- Ngày: 19/11/2026
- Liên quan: ADR-0001 (Kafka best-effort, chưa Outbox), ADR-0003 (khóa `Controller/method`), ADR-0006 (RLS fail-closed),
  ADR-0010 (audit sales cùng transaction), ADR-0020 (webhook giả lập, tra tenant xuyên tenant), ADR-0025 (MoMo thăm dò),
  ADR-0027 (`integration-api`, log truy cập `lms.integration.access`), ADR-0028 (SePay thăm dò).
- Thiết kế chi tiết, phân việc, tiêu chí test: `docs/architecture/log-tich-hop-va-bat-thuong.md`.

## 1. Bối cảnh

Hiện trạng đã đọc trong code (19/11/2026):

- Audit sales (`SalesAuditPort` → `SalesAuditAdapter`, `Propagation.MANDATORY`, cùng transaction nghiệp vụ) chỉ có
  `ORDER_CREATED`, `ORDER_PAID`, `ORDER_CANCELLED` cho thanh toán. Mọi lỗi làm transaction rollback thì không để lại gì.
- `SyncGatewayPaymentUseCase.refresh` (gọi mỗi lần FE thăm dò `GET /internal/payments/intents/{id}`, khoảng 2,5 giây) bắt
  mọi `RuntimeException` và chỉ ghi một dòng WARN `e.getMessage()`: lệch tiền, hết hạn, hủy, trùng giao dịch, lỗi cổng
  đều trông như nhau.
- `SepayPaymentGateway.get` và `MomoPaymentGateway.post` bắt `Exception` rồi ném `SalesException(PAYMENT_GATEWAY_ERROR)`
  không kèm nguyên nhân (`SalesException` chưa có hàm dựng nhận `cause`): timeout, DNS, TLS, JSON hỏng không phân biệt được.
  MoMo không kiểm mã HTTP và đưa nguyên `message` của MoMo vào thông điệp lỗi.
- `SimulatedWebhookProcessor`: chữ ký sai, mã tham chiếu lạ, thân hỏng chỉ ném exception, không log.
- Đã có tiền lệ log theo tên logger cố định: `platform-audit`, `import-audit`, `lms.integration.access` (`AccessLogFilter`
  của integration-api, dòng `key=value`, lọc ký tự lạ, không log thân/token).
- Định dạng log chung: mặc định Spring Boot + `logging.pattern.level: "%5p [%X{correlationId:-}]"`; không có
  `logback-spring.xml` ở module nào. Profile dev đặt `lms.sales: DEBUG`.

Ràng buộc: không đổi định dạng log chung; không rò token/secret/thân thô/thông tin người chuyển; không có atomicity
xuyên DB; audit sales có RLS nên chỉ ghi được khi biết tenant; webhook tra tenant xuyên tenant (ADR-0020 mục 2.3).

## 2. Quyết định

### D1. Phân kênh bằng tên logger cố định (không marker, không MDC), dòng `key=value`

| Kênh (`channel=`) | Logger | Nội dung |
|---|---|---|
| `SYSTEM` | logger theo lớp như hiện nay; riêng sự kiện có chọn lọc của hệ thống dùng `lms.log.system` | log nội bộ |
| `INTEGRATION_OUTBOUND` | `lms.log.outbound.<provider>` (vd `lms.log.outbound.sepay`) | mình gọi đối tác: SePay, MoMo, sau này email/SMS, OAuth ngoài |
| `INTEGRATION_INBOUND` | `lms.log.inbound.<nguồn>` (vd `lms.log.inbound.webhook`) | đối tác gọi vào: webhook thanh toán; giai đoạn 2: `/public/**`, `/api/integration/v1/**` |

- Mỗi dòng mở đầu bằng token sự kiện (`integration-call`, `integration-receive`, `system-event`) rồi các trường
  `key=value` theo thứ tự cố định, có `channel=` (lọc được bằng `grep` khi không có công cụ log).
- Tiền tố `lms.log` không trùng package Java nào (khác `lms.integration` là package của integration-api, đặt
  `logging.level.lms.integration` ở đó sẽ kéo theo logger kênh nếu dùng tiền tố này).
- Bật/tắt theo kênh bằng biến môi trường chuẩn của Spring (`LOGGING_LEVEL_LMS_LOG_OUTBOUND=DEBUG`), không sửa YAML.

Phương án đã loại:
- SLF4J `Marker`: mẫu log mặc định không in marker, lọc phải viết `logback-spring.xml` cho từng service.
- Khóa MDC `logChannel`: muốn thấy phải đổi `logging.pattern.level` (đổi định dạng chung), dễ rò sang dòng sau cùng luồng
  nếu quên xóa, và không thêm gì so với tên logger.
- Bật structured logging JSON của Spring Boot (`logging.structured.format.console`): đổi định dạng mọi service. Hoãn; dòng
  `key=value` + tên logger chuyển sang JSON được sau mà chỉ sửa một lớp (D3).
- File/appender riêng cho từng kênh: cần `logback-spring.xml`, Docker hiện đọc stdout. Hoãn.

### D2. Phạm vi giai đoạn 1: thư viện dùng chung + sales-service

- `observability-common` thêm package `lms.observability.log` (Java thuần + SLF4J, không Spring): `LogChannel`,
  `IntegrationLog` (builder các trường cố định), `LogMask` (che, lọc ký tự), `LogThrottle` (giảm trùng theo khóa).
  Đặt ở thư viện chung vì quy tắc che và tên kênh phải giống nhau ở mọi service; loại anomaly của thanh toán thì ở sales.
- Áp dụng giai đoạn 1: SePay, MoMo (gọi ra), webhook giả lập và nút "giả lập đã thanh toán" (gọi vào), bất thường thanh toán.
- Hoãn (giai đoạn 2): đổi tên `lms.integration.access` sang `lms.log.inbound.integration-api` (giữ token `integration-access`),
  log truy cập `/public/**` của customer-api, gọi ra ở identity (đăng nhập ngoài) và support (thông báo), JSON log.
  `lms-api` là BFF nội bộ (FE của chính mình) nên thuộc `SYSTEM`, không phải kênh tích hợp.

### D3. Bản ghi tích hợp chỉ có trường trong danh sách trắng, che theo kiểu dữ liệu

`IntegrationLog` không có phương thức nhận `Map` hay chuỗi tự do: chỉ các trường `channel, provider, op, cid, tenantId,
intentId, ref, outcome, httpStatus, durationMs, errorCode, errorKind, errorClass, partnerCode, retryAfter, suppressed,
anomaly, anomalyType`. Mọi giá trị qua `LogMask.safe` (bỏ CR/LF, ký tự ngoài `[A-Za-z0-9._:/{}@=*-]` thành `_`, cắt độ dài).
Quy tắc che: mã tham chiếu giữ 3 đầu + 4 cuối; số tài khoản chỉ 4 số cuối; mã giao dịch cổng giữ tiền tố + 6 ký tự cuối;
thông điệp của đối tác không vào log (log `partnerCode` thay vào), nếu buộc dùng thì `LogMask.partnerMessage` (bỏ email,
dãy số từ 6 chữ số, cắt 100 ký tự). KHÔNG BAO GIỜ có trong log: token, secret, header `Authorization`, chữ ký `X-Signature`,
URL kèm query, thân request/response, `transaction_content`, tên/điện thoại/email người chuyển. Chi tiết ở tài liệu thiết kế.

### D4. Danh sách `anomalyType` đóng; chỉ bất thường về tiền có tenant mới ghi audit `PAYMENT_ANOMALY`

- Mọi bất thường ghi log máy (WARN/ERROR, `anomaly=true anomalyType=...`) ở kênh của nguồn kích hoạt.
- Thêm audit (action mới `PAYMENT_ANOMALY`, `aggregateType=PaymentIntent`, `actorId=null`, `data.anomalyType`) chỉ khi:
  biết tenant và là bất thường về tiền mà cửa hàng phải xử lý tay (lệch tiền, tiền về sau hết hạn/hủy, thu hai lần, mã
  giao dịch dùng cho lệnh khác, lệnh thuộc cổng khác). Hiện trên màn Nhật ký hoạt động (`GET /api/sales/audit-log?action=PAYMENT_ANOMALY`)
  và theo `sales.events` sang support (eventType `PaymentAnomaly`).
- Bất thường hạ tầng/bảo mật (chữ ký sai, mã lạ, 401/403/429, cổng lỗi liên tục, phản hồi lạ) chỉ log máy: chưa biết
  tenant (RLS không cho ghi) hoặc là việc của vận hành nền tảng, không phải của tenant.
- Chống spam: audit tối đa một dòng cho mỗi `(paymentIntentId, anomalyType)`; log giảm trùng theo `(anomalyType, khóa)`
  trong 60 giây và báo `suppressed=N` ở dòng kế tiếp.
- Loại: bảng riêng `payment_anomalies` có trạng thái xử lý (hoãn, chỉ làm khi cần quy trình đối soát); một action audit
  cho mỗi loại (làm phình danh sách action); chỉ log máy cho mọi loại (tenant không thấy khoản tiền phải hoàn tay);
  coi webhook gửi lại cùng mã giao dịch là bất thường (cổng thật gửi lại theo thiết kế, giữ là `outcome=DUPLICATE` INFO).

### D5. Ghi bất thường SAU khi transaction nghiệp vụ đã rollback, bằng transaction riêng

`ProcessPaymentWebhookUseCase.execute` là `@Transactional`; lệch tiền/hết hạn/hủy ném `SalesException` nên mọi thứ ghi
trong transaction đó (kể cả audit `MANDATORY`) đều mất. Quyết định: use case mới `ApplyGatewayPaymentUseCase`
(application, KHÔNG transactional) bọc lời gọi, bắt `SalesException` sau khi rollback xong, phân loại theo `ErrorCode`
(trường hợp `PAYMENT_INTENT_STATE_CONFLICT` thì đọc lại lệnh để tách "đã thu bằng giao dịch khác" khỏi xung đột phiên
bản), gọi `RecordPaymentAnomalyUseCase`, rồi ném lại exception gốc (hợp đồng HTTP không đổi). Phần ghi audit chạy
trong `@Transactional(propagation = REQUIRES_NEW)` ở một bean riêng, trong tenant đã gắn (`TenantContext` của request
hoặc `callAs` của webhook). Lỗi khi ghi bất thường chỉ log, không che exception gốc.

Loại: `noRollbackFor` (commit nửa chừng tồn kho/lệnh); ghi audit trong transaction rồi bắt lỗi (vẫn rollback);
`TransactionSynchronization.afterCompletion(ROLLED_BACK)` (vẫn cần REQUIRES_NEW, khó đọc, khó test); chỉ phát Kafka
(best-effort theo ADR-0001, không hiện ở màn audit sales).

### D6. Thăm dò: DEBUG khi không đổi, INFO khi đổi trạng thái, WARN có giảm trùng khi lỗi, luôn có nguyên nhân gốc

- Lần gọi cổng thành công không có giao dịch: DEBUG (không có dòng INFO mỗi 2,5 giây). Khớp và ghi nhận PAID: INFO.
  `createQr`: INFO (một lần mỗi lệnh).
- Lỗi gọi cổng: WARN với `errorKind` (TIMEOUT, CONNECT, DNS, TLS, HTTP_AUTH, HTTP_RATE_LIMIT, HTTP_4XX, HTTP_5XX, PARSE,
  INTERRUPTED, UNKNOWN) và `errorClass` (tên lớp exception), KHÔNG `getMessage()`/stack trace của lỗi HTTP (thông điệp
  của `HttpClient` có thể chứa URL với mã tham chiếu). Giảm trùng theo `(provider, errorKind)` 60 giây.
- Lỗi liên tiếp ≥ 5 lần và kéo dài ≥ 30 giây: một dòng ERROR `GATEWAY_POLL_FAILING_REPEATEDLY`, lặp lại tối đa 10 phút/lần;
  hồi phục thì một dòng INFO `outcome=RECOVERED failures=N`. Bộ đếm trong bộ nhớ theo provider (mỗi instance).
- `SalesException` thêm hàm dựng nhận `cause` để giữ nguyên nhân cho log; thông điệp trả FE không đổi.

## 3. Hệ quả

- Lọc được ba kênh bằng tên logger, định dạng dòng log của mọi service giữ nguyên.
- Tenant thấy các khoản tiền bất thường cần xử lý tay; vận hành thấy cổng lỗi, sai cấu hình, dò chữ ký.
- Thăm dò không thêm câu ghi DB nào ở đường bình thường; chỉ đường lỗi có thêm một transaction ngắn.
- Thêm action audit `PAYMENT_ANOMALY` (cột `action VARCHAR(50)` không có CHECK, không cần migration). FE màn Nhật ký cần
  nhãn cho action mới.
- Phát hiện khi rà: với cổng thăm dò (SePay, MoMo), tiền về SAU khi lệnh hết hạn/hủy không bao giờ được thấy vì
  `refresh` chỉ hỏi cổng khi lệnh còn PENDING; và SePay lọc số tiền ở phía SePay (`amount_in_min/max`) nên chuyển sai
  số tiền là vô hình. Hai điểm này là câu hỏi mở (tài liệu thiết kế mục 6), không tự đổi trong ADR này.

## 4. Chưa kiểm chứng

- ⚠️ `REQUIRES_NEW` mượn kết nối mới và `TenantRlsConnectionInitializer` đặt `app.tenant_id` theo `TenantContext` hiện
  tại (đọc từ Javadoc, chưa chạy IT cho luồng này).
- ⚠️ support-service nhận eventType mới `PaymentAnomaly` mà không cần sửa (đọc thấy consumer chung, chưa chạy).
- ⚠️ Structured logging của Spring Boot 3.5 có đưa key-value của SLF4J fluent API vào JSON hay không (liên quan việc hoãn).
- ⚠️ FE màn Nhật ký hoạt động hiển thị action lạ thế nào (repo FE không nằm ở đây).
