# Thiết kế: log ba kênh, che dữ liệu và log bất thường cho luồng thanh toán

Quyết định gốc: ADR-0029. Tài liệu này là phần chi tiết cho `be-dev`, `tester`, `perf-tester`. Tên lớp và chữ ký là
đề xuất; `be-dev` chốt khi cài đặt, nhưng không đổi các ràng buộc ghi trong mục 2 đến 4.

## 1. Bối cảnh và mục tiêu

Mục tiêu:
1. Log máy đủ để trả lời "lần thăm dò/webhook này ra sao, vì sao lỗi" cho SePay, MoMo, webhook giả lập.
2. Đánh dấu bất thường bằng `anomalyType` thuộc danh sách đóng; bất thường về tiền có tenant thì tenant thấy được.
3. Phân ba kênh: hệ thống, tích hợp gọi ra, tích hợp gọi vào; lọc được mà không đổi định dạng log chung.

Ràng buộc: không rò dữ liệu nhạy cảm; không ghi DB mỗi lần thăm dò; audit chịu RLS; không atomicity xuyên DB;
Kafka best-effort (ADR-0001); domain không phụ thuộc framework (ArchUnit `SalesArchitectureTest`).

## 2. Thành phần

```
observability-common (lms.observability.log, Java thuần + slf4j-api)
  LogChannel        SYSTEM | INTEGRATION_OUTBOUND | INTEGRATION_INBOUND, kèm tiền tố logger
  IntegrationLog    builder trường cố định, tự che ref/acct/txn, tự lọc ký tự, kiểm isXxxEnabled trước khi dựng chuỗi
  LogMask           safe, reference, lastDigits, providerTxnId, partnerMessage
  LogThrottle       giảm trùng theo khóa trong cửa sổ, đếm số dòng bị nuốt, giới hạn số khóa

sales-service
  ordering.application
    PaymentAnomalyType            enum đóng (mục 4), có cờ audited
    PaymentAnomaly                record dữ liệu bất thường (không PII)
    PaymentAnomalyLogPort         port ghi log máy (impl ở infrastructure)
    RecordPaymentAnomalyUseCase   log luôn; audited && có tenant -> PaymentAnomalyAuditUseCase; nuốt lỗi của chính nó
    PaymentAnomalyAuditUseCase    @Transactional(REQUIRES_NEW): đã có dòng (intentId, type) thì bỏ, chưa thì SalesAuditPort.record
    ApplyGatewayPaymentUseCase    KHÔNG transactional: gọi ProcessPaymentWebhookUseCase, bắt SalesException, phân loại, ném lại
    SyncGatewayPaymentUseCase     dùng ApplyGatewayPaymentUseCase, bỏ dòng WARN e.getMessage()
  ordering.infrastructure.client
    GatewayErrors                 phân loại Throwable/HTTP status -> errorKind
    GatewayHealthTracker          đếm lỗi liên tiếp theo provider -> GATEWAY_POLL_FAILING_REPEATEDLY / RECOVERED
    SepayPaymentGateway, MomoPaymentGateway   ghi integration-call (OUTBOUND), giữ cause
  ordering.infrastructure.web
    SimulatedWebhookProcessor     ghi integration-receive (INBOUND), bất thường trước khi biết tenant, gọi Apply... trong callAs
  ordering.infrastructure.(persistence|config)
    PaymentAnomalyLogAdapter      impl PaymentAnomalyLogPort bằng IntegrationLog + LogThrottle
  shared.application
    SalesAuditPort.Actions.PAYMENT_ANOMALY
    SalesAuditQueryPort           thêm tra "đã có PAYMENT_ANOMALY cho (intentId, type)" (JPQL, đọc data trong bộ nhớ)
  shared.domain
    SalesException(ErrorCode, String, Throwable)   hàm dựng mới
```

Ghi chú vị trí quy tắc: "lỗi nào là bất thường, có ghi audit không, audit chứa gì" nằm ở `application`
(`PaymentAnomalyType`, `ApplyGatewayPaymentUseCase`, `RecordPaymentAnomalyUseCase`). Tầng `infrastructure` chỉ quyết định
cách viết log (kênh, che, giảm trùng) và phân loại lỗi kỹ thuật của HTTP. `application` không import `lms.observability`.

## 3. Luồng dữ liệu

### 3.1. Thăm dò (SePay/MoMo)

```mermaid
sequenceDiagram
    participant FE
    participant C as PaymentIntentController.get
    participant S as SyncGatewayPaymentUseCase
    participant G as SepayPaymentGateway
    participant A as ApplyGatewayPaymentUseCase
    participant P as ProcessPaymentWebhookUseCase (@Transactional)
    participant R as RecordPaymentAnomalyUseCase
    participant U as PaymentAnomalyAuditUseCase (REQUIRES_NEW)
    FE->>C: GET intent (mỗi ~2,5 s)
    C->>S: refresh(tenantId, current)
    S->>G: queryPayment(ref, amount, createdAt)
    G-->>G: log OUTBOUND (DEBUG nếu NO_MATCH; WARN có giảm trùng nếu lỗi)
    alt có giao dịch khớp
        S->>A: apply(tenantId, cmd, provider, source=POLL)
        A->>P: execute(...)
        alt thành công
            P-->>A: PAID (audit ORDER_PAID cùng transaction)
        else SalesException (đã rollback)
            A->>R: report(anomaly)
            R->>R: log WARN/ERROR anomaly=true
            R->>U: recordOnce(anomaly) nếu audited && có tenant
            A-->>S: ném lại exception gốc
        end
    end
    S-->>C: intent (PENDING nếu lỗi)
```

### 3.2. Webhook giả lập (và nút `simulate-paid`)

Thứ tự trong `SimulatedWebhookProcessor.process(rawBody, signature, origin)` (`origin` = `WEBHOOK` | `SIMULATE_BUTTON`):
1. Sandbox tắt: 404 như cũ, không log bất thường.
2. Thân rỗng hoặc > 4 KB: `WEBHOOK_MALFORMED` (log, không audit).
3. Chữ ký thiếu/sai: `WEBHOOK_BAD_SIGNATURE` (log, không audit), log `signaturePresent`, `bodyLength`, IP đã che
   (IPv4 bỏ octet cuối, IPv6 giữ /48), KHÔNG log chữ ký hay thân.
4. JSON hỏng sau chữ ký đúng: `WEBHOOK_MALFORMED`.
5. Không tra được tenant: `WEBHOOK_UNKNOWN_REFERENCE` (ref đã che).
6. `TenantContext.callAs(tenantId, () -> applyGatewayPayment.apply(...))`: phần bất thường về tiền như 3.1, có audit.
7. Kết quả: một dòng `integration-receive` INFO (`outcome=PROCESSED|DUPLICATE|IGNORED`) hoặc WARN (`outcome=REJECTED`).

Hợp đồng HTTP của webhook và của `simulate-paid` giữ nguyên (mã lỗi, thân phản hồi).

### 3.3. Vì sao ghi được sau rollback

`SyncGatewayPaymentUseCase`, `SimulatedWebhookProcessor`, `PaymentIntentController.get` đều không transactional, nên khi
`ApplyGatewayPaymentUseCase` bắt được `SalesException` thì transaction của `ProcessPaymentWebhookUseCase` đã rollback và
trả kết nối. `PaymentAnomalyAuditUseCase` mở transaction mới (REQUIRES_NEW để vẫn độc lập nếu sau này có người bọc
transaction bên ngoài), mượn kết nối mới, `TenantRlsConnectionInitializer` đặt `app.tenant_id` theo `TenantContext`
đang gắn. `SalesAuditAdapter` (`MANDATORY`) thỏa vì đang trong transaction đó; phát `sales.events` sau commit như cũ.

Phân biệt `PAYMENT_INTENT_STATE_CONFLICT`: đọc lại lệnh (đọc thường, transaction chỉ đọc); lệnh `PAID` với
`providerTxnId` khác mã vừa nhận thì `PAYMENT_DOUBLE_PAID`; còn lại (xung đột phiên bản, đồng thời với hủy) không phải
bất thường, log INFO `outcome=CONFLICT`.

## 4. Hợp đồng dữ liệu

### 4.1. Dòng log tích hợp

Thứ tự trường cố định, thiếu thì `-`:

```
integration-call channel=INTEGRATION_OUTBOUND provider=SEPAY op=queryPayment cid=<correlationId> tenantId=<uuid>
  intentId=<uuid> ref=LMS******K7Q2 outcome=FAIL httpStatus=- durationMs=10003 errorCode=PAYMENT_GATEWAY_ERROR
  errorKind=TIMEOUT errorClass=HttpTimeoutException partnerCode=- retryAfter=- suppressed=0 anomaly=false anomalyType=-
```

(viết thành một dòng; xuống dòng ở đây chỉ để dễ đọc)

| Trường | Giá trị |
|---|---|
| token đầu | `integration-call` (OUTBOUND), `integration-receive` (INBOUND), `system-event` (SYSTEM) |
| `provider` | `SEPAY`, `MOMO`, `SIMULATED` |
| `op` | `createQr`, `queryPayment`, `webhook`, `simulatePaid` |
| `cid` | MDC `correlationId` (job không có thì `-`) |
| `outcome` | OUTBOUND: `OK`, `NO_MATCH`, `MATCHED`, `FAIL`, `RECOVERED`; INBOUND: `PROCESSED`, `DUPLICATE`, `IGNORED`, `REJECTED`, `CONFLICT` |
| `httpStatus` | mã HTTP của đối tác (OUTBOUND) hoặc mã mình trả (INBOUND) |
| `errorCode` | `ErrorCode` của sales |
| `errorKind` | `TIMEOUT`, `CONNECT`, `DNS`, `TLS`, `HTTP_AUTH`, `HTTP_RATE_LIMIT`, `HTTP_4XX`, `HTTP_5XX`, `PARSE`, `INTERRUPTED`, `UNKNOWN` |
| `errorClass` | `getClass().getSimpleName()` của nguyên nhân gốc |
| `partnerCode` | mã kết quả của đối tác (MoMo `resultCode`, SePay `error_code`), `[A-Za-z0-9_-]{1,32}` |
| `retryAfter` | giá trị header `Retry-After` (số giây) khi 429 |

Phân loại `errorKind` (`GatewayErrors`): `HttpTimeoutException`/`HttpConnectTimeoutException` → TIMEOUT;
`ConnectException` → CONNECT; `UnknownHostException` → DNS; `SSLException` và lớp con → TLS; `JsonProcessingException` →
PARSE; 401/403 → HTTP_AUTH; 429 → HTTP_RATE_LIMIT; 4xx khác → HTTP_4XX; 5xx → HTTP_5XX.

### 4.2. Quy tắc che (`LogMask`)

| Dữ liệu | Quy tắc | Ví dụ |
|---|---|---|
| Mã tham chiếu (`reference`) | độ dài ≥ 10: 3 đầu + `*` + 4 cuối; 6 đến 9: `*` + 2 cuối; ngắn hơn: `***` | `LMSABCDEFGHJK` → `LMS******GHJK` |
| Số tài khoản | `****` + 4 số cuối; ít hơn 8 số: `****` | `0123456789` → `****6789` |
| Mã giao dịch cổng | giữ tiền tố (`SEPAY-`, `MOMO-`) + `…` + 6 ký tự cuối | `SEPAY-…a1b2c3` |
| Thông điệp đối tác | không log; nếu bắt buộc: bỏ ký tự điều khiển, email → `[email]`, dãy ≥ 6 chữ số → `[num]`, cắt 100 ký tự | |
| IP người gọi (INBOUND) | IPv4 bỏ octet cuối (`203.0.113.x`), IPv6 giữ /48 | |
| Mọi giá trị | `LogMask.safe`: bỏ CR/LF, ký tự ngoài `[A-Za-z0-9._:/{}@=*-]` thành `_`, cắt 128 ký tự | chống chèn dòng log |
| Không bao giờ log | token, secret, `Authorization`, `X-Signature`, `accessKey`/`secretKey`/chữ ký MoMo, URL có query, thân thô, `transaction_content`, tên/điện thoại/email/số tài khoản người chuyển, `e.getMessage()` và stack trace của lỗi HTTP | |

`intentId` (UUID) và `tenantId` để nguyên: dùng để nối log với audit và dữ liệu. Audit `PAYMENT_ANOMALY` giữ `reference`
và `providerTxnId` đầy đủ (giống `ORDER_PAID` hiện có), vì audit có RLS, có khóa quyền `SalesAuditController/search`, và
cửa hàng cần mã đầy đủ để đối soát/hoàn tiền.

`IntegrationLog` không có phương thức nhận `Map` hay chuỗi tự do, nên muốn log thân hay token phải sửa thư viện chung
(lộ ra khi review). Thông điệp `SalesException` của MoMo (`"MoMo từ chối tạo giao dịch: " + message`) đi qua
`LogMask.partnerMessage` trước khi ném, vì nó trả về FE.

### 4.3. Danh sách `anomalyType` (đóng)

| `anomalyType` | Kích hoạt | Kênh | Mức | Audit |
|---|---|---|---|---|
| `PAYMENT_AMOUNT_MISMATCH` | `markPaid` ném `PAYMENT_AMOUNT_MISMATCH` | nguồn | WARN | có |
| `PAYMENT_AFTER_EXPIRY` | `PAYMENT_INTENT_EXPIRED` | nguồn | WARN | có |
| `PAYMENT_AFTER_CANCEL` | `PAYMENT_INTENT_CANCELLED` | nguồn | WARN | có |
| `PAYMENT_DOUBLE_PAID` | `PAYMENT_INTENT_STATE_CONFLICT` và đọc lại thấy lệnh PAID bằng giao dịch khác | nguồn | ERROR | có |
| `PAYMENT_TXN_REUSED_OTHER_INTENT` | `PAYMENT_DUPLICATE_TRANSACTION` (UNIQUE `(tenant_id, provider_txn_id)`) | nguồn | ERROR | có |
| `PAYMENT_PROVIDER_MISMATCH` | `PAYMENT_NOT_SIMULATED` (chữ ký đúng nhưng lệnh thuộc cổng khác) | INBOUND | WARN | có |
| `WEBHOOK_BAD_SIGNATURE` | chữ ký thiếu/sai | INBOUND | WARN | không (chưa có tenant) |
| `WEBHOOK_MALFORMED` | thân rỗng/quá 4 KB/không phải JSON/thiếu trường | INBOUND | WARN | không |
| `WEBHOOK_UNKNOWN_REFERENCE` | chữ ký đúng, không tra được tenant theo mã | INBOUND | WARN | không |
| `GATEWAY_AUTH_REJECTED` | đối tác trả 401/403 (sai token/khóa) | OUTBOUND | ERROR | không |
| `GATEWAY_RATE_LIMITED` | 429 | OUTBOUND | WARN | không |
| `GATEWAY_POLL_FAILING_REPEATEDLY` | ≥ 5 lỗi liên tiếp của cùng provider và ≥ 30 giây từ lỗi đầu | OUTBOUND | ERROR | không |
| `GATEWAY_UNEXPECTED_RESPONSE` | 2xx nhưng không đọc được JSON, SePay thiếu mảng `data`, MoMo thiếu `resultCode` | OUTBOUND | WARN | không |

"Nguồn" = kênh của đường kích hoạt (`POLL` → OUTBOUND, `WEBHOOK`/`SIMULATE_BUTTON` → INBOUND).

Không phải bất thường (log thường): gửi lại cùng mã giao dịch (`DUPLICATE`, INFO), status khác PAID (`IGNORED`, INFO),
xung đột phiên bản (`CONFLICT`, INFO), một lần lỗi mạng lẻ (WARN có giảm trùng, không `anomaly=true`).

Giảm trùng:
- Log: `LogThrottle` khóa `(anomalyType, intentId hoặc provider)`, cửa sổ 60 giây (`GATEWAY_AUTH_REJECTED` và
  `GATEWAY_POLL_FAILING_REPEATEDLY`: 10 phút); tối đa 10.000 khóa, vượt thì gộp về khóa `(anomalyType)`.
- Audit: tối đa một dòng cho mỗi `(paymentIntentId, anomalyType)`. Tra bằng JPQL lấy `data` các dòng
  `action = PAYMENT_ANOMALY AND aggregateType = 'PaymentIntent' AND aggregateId = :id` (dùng index
  `idx_sales_audit_log_aggregate` có sẵn), so `anomalyType` trong bộ nhớ. Không thêm native query. Hai lần đồng thời
  có thể ra hai dòng: chấp nhận.

### 4.4. Audit `PAYMENT_ANOMALY`

`SalesAuditPort.AuditEntry(tenantId, "PAYMENT_ANOMALY", "PaymentIntent", intentId, actorId = null, now, data)`; `data`:
`anomalyType, source (POLL|WEBHOOK|SIMULATE_BUTTON), provider, paymentIntentId, orderId, reference, expectedAmount,
receivedAmount, providerTxnId, intentStatus, errorCode`. Không PII, không nội dung chuyển khoản. Không cần migration
(cột `action VARCHAR(50)` không CHECK, `aggregate_type VARCHAR(30)` đủ). Lọc trên màn Nhật ký:
`GET /api/sales/audit-log?action=PAYMENT_ANOMALY`. `be-dev` bổ sung `docs/api/sales.md` (đang có thay đổi chưa commit của
người khác: chỉ Edit đúng đoạn audit thanh toán).

## 5. Phân việc

| # | Ai | Việc | Phụ thuộc |
|---|---|---|---|
| T1 | be-dev | `observability-common`: `lms.observability.log` (`LogChannel`, `IntegrationLog`, `LogMask`, `LogThrottle`) + unit test | không |
| T2 | be-dev | sales domain/application: `SalesException` có `cause`; `PaymentAnomalyType`, `PaymentAnomaly`, `PaymentAnomalyLogPort`, `RecordPaymentAnomalyUseCase`, `PaymentAnomalyAuditUseCase`, `ApplyGatewayPaymentUseCase`; `SalesAuditPort.Actions.PAYMENT_ANOMALY`; tra trùng trong `SalesAuditQueryPort`; sửa `SyncGatewayPaymentUseCase`; bean trong `PaymentsBeanConfiguration` | T1 chỉ cho adapter; phần application làm song song T1 |
| T3 | be-dev | sales infrastructure client: `GatewayErrors`, `GatewayHealthTracker`, log OUTBOUND trong `SepayPaymentGateway`/`MomoPaymentGateway` (giữ cause, MoMo kiểm mã HTTP, che thông điệp MoMo) | T1 |
| T4 | be-dev | sales infrastructure: `PaymentAnomalyLogAdapter`; `SimulatedWebhookProcessor` (bất thường trước tenant, `origin`, gọi `ApplyGatewayPaymentUseCase` trong `callAs`, dòng INBOUND); `PaymentIntentController.simulatePaid` truyền `SIMULATE_BUTTON` | T1, T2 |
| T5 | be-dev | ArchUnit `SalesArchitectureTest`: domain không phụ thuộc `org.slf4j..`, `lms.observability..`; application không phụ thuộc `lms.observability..` | T2 |
| T6 | be-dev | `docs/api/sales.md` (action `PAYMENT_ANOMALY`), ghi chú biến `LOGGING_LEVEL_LMS_LOG_*` trong README mục vận hành | T4 |
| T7 | tester | test mục 5.1 | T4 |
| T8 | perf-tester | đo mục 5.2 | T4 |
| T9 | be-dev | chỉ khi người dùng đồng ý câu hỏi Q1/Q2 (mục 6) | T4 |

T1 và phần application của T2 làm song song (hai phạm vi file khác nhau); T3 và T4 làm song song sau T1 (client/ và
web/ khác thư mục, chung `PaymentsBeanConfiguration`: giao cho một người hoặc gộp sửa file đó vào T4).

### 5.1. Tiêu chí kiểm thử (tester)

- ⏳ `LogMaskTest`: từng quy tắc ở 4.2, giá trị null/rỗng/ngắn, ký tự CR/LF và Unicode bị lọc.
- ⏳ Test bắt rò rỉ (OutputCaptureExtension hoặc logback `ListAppender`): SePay stub (`HttpServer` như `SepayPaymentGatewayTest`)
  trả 200/401/429/500/JSON hỏng/timeout với token giả `tok_SECRET_should_not_leak`, số tài khoản `0123456789`, nội dung
  `"LMSABCDEFGHJK NGUYEN VAN A 0912345678 a@b.vn"`; khẳng định log không chứa token, `Bearer`, số tài khoản đầy đủ,
  `NGUYEN`, `0912345678`, `a@b.vn`, mã tham chiếu đầy đủ, chuỗi `transaction_content`, `?` của query. Tương tự MoMo với
  `secretKey`/`accessKey` giả và `message` chứa số điện thoại; webhook với `X-Signature` giả và thân có trường lạ.
- ⏳ Mỗi `anomalyType` một test: đúng kênh, đúng mức, `anomaly=true`, đúng có/không audit; exception gốc và mã HTTP phản
  hồi không đổi (WebMvc của webhook và `simulate-paid`).
- ⏳ Giảm trùng: 10 lần lệch tiền cùng lệnh → 1 dòng audit, log có `suppressed`; 6 lỗi liên tiếp > 30 giây (đồng hồ giả)
  → đúng 1 dòng `GATEWAY_POLL_FAILING_REPEATEDLY`, thành công sau đó → 1 dòng `RECOVERED`.
- ⏳ Thăm dò NO_MATCH ở mức INFO không sinh dòng nào của `lms.log.outbound.*`.
- ⏳ IT Testcontainers (vai trò `sales_app`, RLS thật), mở rộng `PaymentQrIT` hoặc IT mới: webhook lệch tiền → lệnh vẫn
  PENDING, tồn không đổi, có đúng 1 dòng `PAYMENT_ANOMALY` đúng tenant; tenant khác không thấy dòng đó; chữ ký sai không
  ghi dòng audit nào. Chạy `-Dtest=<TênIT> -DargLine=-Dapi.version=1.44`.
- ⏳ ArchUnit T5 xanh; `mvn -pl services/sales-service -am test` xanh.

### 5.2. Hiệu năng (perf-tester)

- ⏳ 20 lệnh SePay PENDING thăm dò 2,5 giây/lệnh trong 5 phút với stub: số câu SQL ghi = 0 trên đường bình thường; số dòng
  log INFO kênh OUTBOUND = 0.
- ⏳ Stub trả 429/timeout liên tục: số dòng WARN/ERROR mỗi phút bị chặn trên (khoảng vài dòng, không theo số lần gọi);
  bộ nhớ `LogThrottle` không tăng quá giới hạn khóa.
- ⏳ Độ trễ p95 của `GET intent` không tăng đáng kể so với trước (chuỗi log dựng lười khi mức tắt).

## 6. Rủi ro, câu hỏi mở, việc chưa kiểm chứng

### Câu hỏi cho người dùng

- ⚠️ Q1. Với SePay/MoMo (thăm dò), tiền về sau khi lệnh hết hạn hoặc bị hủy hiện KHÔNG BAO GIỜ được phát hiện, vì
  `refresh` chỉ hỏi cổng khi lệnh còn PENDING; `PAYMENT_AFTER_EXPIRY/CANCEL` chỉ bắt được ở webhook hoặc trong cửa sổ
  rất hẹp. Có thêm "hỏi cổng lần cuối" khi job hết hạn chuyển lệnh sang EXPIRED và khi thu ngân hủy (chỉ ghi bất thường,
  không tự ghi nhận PAID) không? Khuyến nghị: có, ở giai đoạn 2 (T9), vì thêm lời gọi HTTP vào job và luồng hủy.
- ⚠️ Q2. SePay lọc `amount_in_min/max` ở phía SePay nên chuyển đúng mã nhưng sai số tiền là vô hình, `PAYMENT_AMOUNT_MISMATCH`
  không bao giờ xảy ra với SePay. Bỏ lọc số tiền phía SePay (chỉ lọc nội dung, so tiền phía ta, ưu tiên giao dịch đúng
  tiền) để bắt được lệch tiền không? Khuyến nghị: có (T9), đổi nhỏ trong `SepayPaymentGateway`, cần cập nhật ADR-0028.
- ⚠️ Q3. Tenant (quản lý cửa hàng) có được thấy `PAYMENT_ANOMALY` trên màn Nhật ký hoạt động không, hay chỉ vận hành nền
  tảng? Khuyến nghị: tenant thấy, vì họ phải hoàn/xử lý tay khoản tiền đó (ADR-0020 mục 2.4).
- ℹ️ Q4. Có hệ thống gom log (Loki/ELK) và yêu cầu thời gian lưu không? Quyết định chuyển JSON log và appender riêng
  cho từng kênh. Khuyến nghị: hoãn tới khi có hạ tầng gom log.

### Rủi ro

- ⚠️ Lỗi khi ghi bất thường che mất exception gốc hoặc làm hỏng phản hồi: `RecordPaymentAnomalyUseCase` phải bắt mọi
  `RuntimeException` của chính nó, chỉ log `errorClass`; test bắt buộc.
- ⚠️ Giảm trùng và bộ đếm lỗi liên tiếp trong bộ nhớ theo instance: chạy nhiều instance sales thì mỗi instance báo riêng;
  khởi động lại thì mất đếm. Chấp nhận ở giai đoạn 1.
- ⚠️ Hai bất thường đồng thời cùng `(intentId, anomalyType)` có thể ra hai dòng audit.
- ⚠️ `PAYMENT_ANOMALY` đi theo `sales.events` (best-effort, ADR-0001) sang support; mất event thì bản ở support thiếu,
  nguồn chuẩn vẫn là `sales_audit_log`.
- ⚠️ Mức che 3+4 của mã tham chiếu làm khó tìm theo mã trong log; dùng `intentId` (đầy đủ) để nối log với audit.
- ℹ️ Không có migration, không đổi khóa quyền, không đổi cổng/nginx/biến môi trường bắt buộc.

### Chưa kiểm chứng

- ⚠️ REQUIRES_NEW lấy kết nối mới có `app.tenant_id` đúng (đọc Javadoc `TenantRlsConnectionInitializer`, chưa chạy IT).
- ⚠️ support-service ghi được eventType mới `PaymentAnomaly` mà không sửa (consumer đọc chung, chưa chạy).
- ⚠️ FE màn Nhật ký hiển thị action chưa biết thế nào (repo FE ngoài phạm vi đã đọc).
- ⚠️ Tên lớp exception thực tế của `java.net.http.HttpClient` khi timeout/DNS/TLS trên máy chạy (dùng cho bảng `errorKind`).
- ⚠️ SePay trả `error_code` trong thân lỗi 401/429 như tài liệu nói (ADR-0028 chưa gọi bằng token thật).

## 7. Việc hoãn

- ⏭️ Đổi tên logger `lms.integration.access` (integration-api) sang `lms.log.inbound.integration-api`.
- ⏭️ Log truy cập kênh INBOUND cho customer-api `/public/**` (cần cân nhắc khối lượng, đã có `limit_req` ở nginx).
- ⏭️ Kênh OUTBOUND cho identity (đăng nhập ngoài) và support (gửi thông báo).
- ⏭️ JSON log / appender riêng theo kênh / cảnh báo tự động.
- ⏭️ Bảng `payment_anomalies` có trạng thái xử lý và job đối soát giao dịch tiền vào chưa khớp lệnh (ADR-0028 "Lên production").
