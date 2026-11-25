package lms.sales.ordering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.ordering.application.ApplyGatewayPaymentUseCase;
import lms.sales.ordering.application.PaymentAnomalyAuditUseCase;
import lms.sales.ordering.application.PaymentAnomalySource;
import lms.sales.ordering.application.PaymentAnomalyType;
import lms.sales.ordering.application.PaymentIntentResponse;
import lms.sales.ordering.application.PaymentGatewayPort;
import lms.sales.ordering.application.PlacePosOrderCommand;
import lms.sales.ordering.application.PosOrderResult;
import lms.sales.ordering.application.ProcessPaymentWebhookUseCase;
import lms.sales.ordering.application.RecordPaymentAnomalyUseCase;
import lms.sales.ordering.domain.PaymentIntentStatus;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.infrastructure.client.GatewayCallLogger;
import lms.sales.ordering.infrastructure.client.GatewayHealthTracker;
import lms.sales.ordering.infrastructure.client.SepayPaymentGateway;
import lms.sales.ordering.infrastructure.config.PaymentAnomalyLogAdapter;
import lms.sales.ordering.infrastructure.web.SimulatedWebhookProcessor;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.support.GatewayStub;
import lms.sales.support.Harness;
import lms.sales.support.LogCapture;
import lms.sales.support.QrFixture;
import org.junit.jupiter.api.Test;

/**
 * Mỗi loại bất thường (ADR-0029 mục 4.3) một test: đúng kênh, đúng mức, {@code anomaly=true anomalyType=...}, đúng
 * có/không audit, exception gốc không đổi. Dùng adapter log THẬT ({@link PaymentAnomalyLogAdapter}) và logback bắt log;
 * use case nghiệp vụ chạy trên bộ giả của Harness, trong đó {@code h.tx} mô phỏng rollback của transaction.
 */
class PaymentAnomalyTypesTest {

    private static final String SECRET = "unit-test-secret";

    private final Harness h = new Harness();
    private final QrFixture fx = new QrFixture(h);
    private final PaymentAnomalyLogAdapter adapter = new PaymentAnomalyLogAdapter(h.clock);
    private final PaymentAnomalyAuditUseCase audit = new PaymentAnomalyAuditUseCase(h.auditPort, h.auditQueryPort,
            h.clock);
    private final RecordPaymentAnomalyUseCase recorder = new RecordPaymentAnomalyUseCase(adapter, audit);
    /** Như bean thật: execute chạy trong "transaction" của Harness, lỗi thì rollback mọi ghi trong đó. */
    private final ProcessPaymentWebhookUseCase transactional = new ProcessPaymentWebhookUseCase(h.intents,
            h.orderRepo, h.stockService, h.auditPort, h.clock) {
        @Override
        public Result execute(UUID tenantId, Command cmd, String provider) {
            return h.tx(() -> super.execute(tenantId, cmd, provider));
        }
    };
    private final ApplyGatewayPaymentUseCase apply = new ApplyGatewayPaymentUseCase(transactional, h.intents,
            recorder, adapter);
    private final SimulatedWebhookProcessor processor = new SimulatedWebhookProcessor(apply, recorder, fx.resolver,
            new ObjectMapper(), true, SECRET);

    // ---- helpers ----

    private PosOrderResult place(UUID title, int qty, PaymentMethod method) {
        return h.tx(() -> fx.placePos.createWithPayment(new PlacePosOrderCommand(h.tenant, h.user,
                List.of(new PlacePosOrderCommand.Line(title, qty, null)), method, null, "Nguyễn Văn An",
                "0912345678", null, UUID.randomUUID())));
    }

    private static String body(String reference, long amount, String txn, String status) {
        return "{\"reference\":\"" + reference + "\",\"amount\":" + amount + ",\"providerTxnId\":\"" + txn
                + "\",\"status\":\"" + status + "\"}";
    }

    private ProcessPaymentWebhookUseCase.Result send(String body) {
        return processor.process(body, processor.sign(body), PaymentAnomalySource.WEBHOOK, "203.0.113.77");
    }

    private SalesException sendExpectingError(String body) {
        return assertThrows(SalesException.class, () -> send(body));
    }

    private List<SalesAuditPort.AuditEntry> anomalyAudits() {
        return h.audits().stream().filter(a -> SalesAuditPort.Actions.PAYMENT_ANOMALY.equals(a.action())).toList();
    }

    private static ILoggingEvent anomalyLine(LogCapture cap, PaymentAnomalyType type) {
        List<ILoggingEvent> found = cap.channelEvents("anomalyType=" + type.name());
        assertEquals(1, found.size(), "đúng một dòng log cho " + type + ": " + cap.allLines());
        return found.get(0);
    }

    private static void assertAnomalyLine(ILoggingEvent e, PaymentAnomalyType type, String loggerName, Level level,
                                          String channel) {
        assertEquals(loggerName, e.getLoggerName());
        assertEquals(level, e.getLevel());
        String m = e.getFormattedMessage();
        assertTrue(m.contains("channel=" + channel), m);
        assertTrue(m.contains("anomaly=true anomalyType=" + type.name()), m);
    }

    private void assertAudited(PaymentAnomalyType type, PaymentIntentResponse intent) {
        List<SalesAuditPort.AuditEntry> rows = anomalyAudits();
        assertEquals(1, rows.size(), "một dòng audit");
        SalesAuditPort.AuditEntry a = rows.get(0);
        assertEquals("PaymentIntent", a.aggregateType());
        assertEquals(intent.id(), a.aggregateId());
        assertNull(a.actorId());
        assertEquals(type.name(), a.data().get("anomalyType"));
        assertEquals(intent.reference(), a.data().get("reference"));
        assertEquals(intent.amount(), a.data().get("expectedAmount"));
        assertFalse(a.data().toString().contains("Nguyễn"), "audit không có PII");
        assertFalse(a.data().toString().contains("0912345678"), "audit không có PII");
    }

    private PaymentIntentResponse qrIntent() {
        UUID a = h.book("Sách " + UUID.randomUUID(), 100_000, null, 10);
        return place(a, 1, PaymentMethod.QR).paymentIntent();
    }

    // ---- 6 loại bất thường về tiền (có audit) ----

    @Test
    void paymentAmountMismatch() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PaymentIntentResponse i = place(a, 1, PaymentMethod.QR).paymentIntent();
        long reservedBefore = h.item(a).reserved();
        try (LogCapture cap = LogCapture.info()) {
            SalesException e = sendExpectingError(body(i.reference(), i.amount() - 1_000, "SIM-1", "PAID"));

            assertEquals(ErrorCode.PAYMENT_AMOUNT_MISMATCH, e.code());
            assertAnomalyLine(anomalyLine(cap, PaymentAnomalyType.PAYMENT_AMOUNT_MISMATCH), PaymentAnomalyType.PAYMENT_AMOUNT_MISMATCH,
                    "lms.log.inbound.webhook", Level.WARN, "INTEGRATION_INBOUND");
        }
        assertAudited(PaymentAnomalyType.PAYMENT_AMOUNT_MISMATCH, i);
        // Rollback không động tới lệnh/tồn, và audit anomaly (ghi sau rollback) không bị cuốn theo
        assertEquals(PaymentIntentStatus.PENDING, fx.intentUseCases.get(h.tenant, i.id()).status());
        assertEquals(reservedBefore, h.item(a).reserved());
        assertTrue(h.audits().stream().noneMatch(x -> SalesAuditPort.Actions.ORDER_PAID.equals(x.action())));
        assertEquals(Map.ofEntries(Map.entry("anomalyType", "PAYMENT_AMOUNT_MISMATCH"), Map.entry("source", "WEBHOOK"),
                        Map.entry("provider", "SIMULATED"), Map.entry("paymentIntentId", i.id().toString()),
                        Map.entry("orderId", i.orderId().toString()), Map.entry("reference", i.reference()),
                        Map.entry("expectedAmount", (Object) i.amount()),
                        Map.entry("receivedAmount", (Object) (i.amount() - 1_000)),
                        Map.entry("providerTxnId", "SIM-1"), Map.entry("intentStatus", "PENDING"),
                        Map.entry("errorCode", "PAYMENT_AMOUNT_MISMATCH")),
                anomalyAudits().get(0).data());
    }

    @Test
    void paymentAfterExpiry() {
        PaymentIntentResponse i = qrIntent();
        h.clock.advanceSeconds(601);
        try (LogCapture cap = LogCapture.info()) {
            SalesException e = sendExpectingError(body(i.reference(), i.amount(), "SIM-2", "PAID"));

            assertEquals(ErrorCode.PAYMENT_INTENT_EXPIRED, e.code());
            assertAnomalyLine(anomalyLine(cap, PaymentAnomalyType.PAYMENT_AFTER_EXPIRY), PaymentAnomalyType.PAYMENT_AFTER_EXPIRY,
                    "lms.log.inbound.webhook", Level.WARN, "INTEGRATION_INBOUND");
        }
        assertAudited(PaymentAnomalyType.PAYMENT_AFTER_EXPIRY, i);
    }

    @Test
    void paymentAfterCancel() {
        PaymentIntentResponse i = qrIntent();
        h.tx(() -> fx.intentUseCases.cancel(h.tenant, h.user, i.id()));
        try (LogCapture cap = LogCapture.info()) {
            SalesException e = sendExpectingError(body(i.reference(), i.amount(), "SIM-3", "PAID"));

            assertEquals(ErrorCode.PAYMENT_INTENT_CANCELLED, e.code());
            assertAnomalyLine(anomalyLine(cap, PaymentAnomalyType.PAYMENT_AFTER_CANCEL), PaymentAnomalyType.PAYMENT_AFTER_CANCEL,
                    "lms.log.inbound.webhook", Level.WARN, "INTEGRATION_INBOUND");
        }
        assertAudited(PaymentAnomalyType.PAYMENT_AFTER_CANCEL, i);
    }

    @Test
    void paymentDoubleCharged() {
        PaymentIntentResponse i = qrIntent();
        send(body(i.reference(), i.amount(), "SIM-FIRST", "PAID"));
        assertEquals(0, anomalyAudits().size(), "lần đầu thành công không có bất thường");
        try (LogCapture cap = LogCapture.info()) {
            SalesException e = sendExpectingError(body(i.reference(), i.amount(), "SIM-SECOND", "PAID"));

            assertEquals(ErrorCode.PAYMENT_INTENT_STATE_CONFLICT, e.code());
            assertAnomalyLine(anomalyLine(cap, PaymentAnomalyType.PAYMENT_DOUBLE_PAID), PaymentAnomalyType.PAYMENT_DOUBLE_PAID,
                    "lms.log.inbound.webhook", Level.ERROR, "INTEGRATION_INBOUND");
        }
        assertEquals(1, anomalyAudits().size());
        assertEquals("PAYMENT_DOUBLE_PAID", anomalyAudits().get(0).data().get("anomalyType"));
        assertEquals("PAID", anomalyAudits().get(0).data().get("intentStatus"));
    }

    @Test
    void paymentTransactionReusedForOtherIntent() {
        PaymentIntentResponse first = qrIntent();
        PaymentIntentResponse second = qrIntent();
        send(body(first.reference(), first.amount(), "SIM-SAME", "PAID"));
        try (LogCapture cap = LogCapture.info()) {
            SalesException e = sendExpectingError(body(second.reference(), second.amount(), "SIM-SAME", "PAID"));

            assertEquals(ErrorCode.PAYMENT_DUPLICATE_TRANSACTION, e.code());
            assertAnomalyLine(anomalyLine(cap, PaymentAnomalyType.PAYMENT_TXN_REUSED_OTHER_INTENT),
                    PaymentAnomalyType.PAYMENT_TXN_REUSED_OTHER_INTENT, "lms.log.inbound.webhook", Level.ERROR,
                    "INTEGRATION_INBOUND");
        }
        assertAudited(PaymentAnomalyType.PAYMENT_TXN_REUSED_OTHER_INTENT, second);
        assertEquals(PaymentIntentStatus.PENDING, fx.intentUseCases.get(h.tenant, second.id()).status());
    }

    @Test
    void paymentProviderMismatch() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PaymentIntentResponse manual = place(a, 1, PaymentMethod.CASH).paymentIntent();
        try (LogCapture cap = LogCapture.info()) {
            SalesException e = sendExpectingError(body(manual.reference(), manual.amount(), "SIM-9", "PAID"));

            assertEquals(ErrorCode.PAYMENT_NOT_SIMULATED, e.code());
            assertAnomalyLine(anomalyLine(cap, PaymentAnomalyType.PAYMENT_PROVIDER_MISMATCH),
                    PaymentAnomalyType.PAYMENT_PROVIDER_MISMATCH, "lms.log.inbound.webhook", Level.WARN,
                    "INTEGRATION_INBOUND");
        }
        assertAudited(PaymentAnomalyType.PAYMENT_PROVIDER_MISMATCH, manual);
    }

    // ---- 3 loại webhook (chỉ log máy, không audit) ----

    @Test
    void webhookBadSignature() {
        PaymentIntentResponse i = qrIntent();
        String payload = body(i.reference(), i.amount(), "SIM-4", "PAID");
        try (LogCapture cap = LogCapture.info()) {
            SalesException e = assertThrows(SalesException.class,
                    () -> processor.process(payload, "sha256=deadbeefBADSIG", PaymentAnomalySource.WEBHOOK,
                            "203.0.113.77"));

            assertEquals(ErrorCode.INVALID_WEBHOOK_SIGNATURE, e.code());
            ILoggingEvent line = anomalyLine(cap, PaymentAnomalyType.WEBHOOK_BAD_SIGNATURE);
            assertAnomalyLine(line, PaymentAnomalyType.WEBHOOK_BAD_SIGNATURE, "lms.log.inbound.webhook", Level.WARN,
                    "INTEGRATION_INBOUND");
            String m = line.getFormattedMessage();
            assertTrue(m.contains("signaturePresent=true"), m);
            assertTrue(m.contains("bodyLength=" + payload.length()), m);
            assertTrue(m.contains("clientIp=203.0.113.x"), m);
            assertFalse(m.contains("BADSIG") || m.contains("deadbeef"), "không log chữ ký");
            assertFalse(m.contains(i.reference()), "không log mã tham chiếu đầy đủ");
        }
        assertEquals(0, anomalyAudits().size(), "chưa biết tenant: chỉ log máy");
    }

    @Test
    void webhookMalformed() {
        try (LogCapture cap = LogCapture.info()) {
            SalesException tooBig = assertThrows(SalesException.class, () -> processor.process("x".repeat(5000), "sig",
                    PaymentAnomalySource.WEBHOOK, null));
            assertEquals(ErrorCode.INVALID_REQUEST, tooBig.code());
            String notJson = "not-json";
            SalesException bad = assertThrows(SalesException.class, () -> processor.process(notJson,
                    processor.sign(notJson), PaymentAnomalySource.WEBHOOK, null));
            assertEquals(ErrorCode.INVALID_REQUEST, bad.code());

            List<ILoggingEvent> lines = cap.channelEvents("anomalyType=WEBHOOK_MALFORMED");
            assertFalse(lines.isEmpty());
            assertAnomalyLine(lines.get(0), PaymentAnomalyType.WEBHOOK_MALFORMED, "lms.log.inbound.webhook",
                    Level.WARN, "INTEGRATION_INBOUND");
            assertTrue(lines.get(0).getFormattedMessage().contains("bodyLength=5000"));
            assertFalse(cap.allLines().stream().anyMatch(l -> l.contains("xxxxxxxxxx") || l.contains("not-json")),
                    "không log thân thô");
        }
        assertEquals(0, anomalyAudits().size());
    }

    @Test
    void webhookUnknownReference() {
        try (LogCapture cap = LogCapture.info()) {
            String payload = body("LMSZZZZZZZZZZ", 1000, "SIM-5", "PAID");
            SalesException e = sendExpectingError(payload);

            assertEquals(ErrorCode.PAYMENT_INTENT_NOT_FOUND, e.code());
            ILoggingEvent line = anomalyLine(cap, PaymentAnomalyType.WEBHOOK_UNKNOWN_REFERENCE);
            assertAnomalyLine(line, PaymentAnomalyType.WEBHOOK_UNKNOWN_REFERENCE, "lms.log.inbound.webhook",
                    Level.WARN, "INTEGRATION_INBOUND");
            assertTrue(line.getFormattedMessage().contains("ref=LMS******ZZZZ"), line.getFormattedMessage());
        }
        assertEquals(0, anomalyAudits().size());
    }

    // ---- 4 loại phía cổng gọi ra (chỉ log máy, không audit) ----

    private SepayPaymentGateway sepay(GatewayStub stub, GatewayHealthTracker tracker) {
        return new SepayPaymentGateway(new ObjectMapper(), stub.url(), "tok_unit", "970422", "0123456789", "LMS", "",
                new GatewayCallLogger("SEPAY", h.clock, adapter, tracker));
    }

    @Test
    void gatewayAuthRejected() {
        try (GatewayStub stub = new GatewayStub(); LogCapture cap = LogCapture.info()) {
            stub.status = 401;
            stub.body = "{\"error_code\":\"AUTH_FAIL\",\"message\":\"bad token\"}";
            SalesException e = assertThrows(SalesException.class,
                    () -> sepay(stub, new GatewayHealthTracker(h.clock, adapter))
                            .queryPayment("LMSABCDEFGH23", 1000, h.clock.instant()));

            assertEquals(ErrorCode.PAYMENT_GATEWAY_ERROR, e.code());
            assertTrue(e.getMessage().startsWith("SePay từ chối token"));
            ILoggingEvent line = anomalyLine(cap, PaymentAnomalyType.GATEWAY_AUTH_REJECTED);
            assertAnomalyLine(line, PaymentAnomalyType.GATEWAY_AUTH_REJECTED, "lms.log.outbound.sepay", Level.ERROR,
                    "INTEGRATION_OUTBOUND");
            assertTrue(line.getFormattedMessage().contains("httpStatus=401"));
            assertTrue(line.getFormattedMessage().contains("errorKind=HTTP_AUTH"));
            assertTrue(line.getFormattedMessage().contains("partnerCode=AUTH_FAIL"));
        }
        assertEquals(0, anomalyAudits().size());
    }

    @Test
    void gatewayRateLimited() {
        try (GatewayStub stub = new GatewayStub(); LogCapture cap = LogCapture.info()) {
            stub.status = 429;
            stub.headers.put("Retry-After", "30");
            SalesException e = assertThrows(SalesException.class,
                    () -> sepay(stub, new GatewayHealthTracker(h.clock, adapter))
                            .queryPayment("LMSABCDEFGH23", 1000, h.clock.instant()));

            assertEquals(ErrorCode.PAYMENT_GATEWAY_ERROR, e.code());
            ILoggingEvent line = anomalyLine(cap, PaymentAnomalyType.GATEWAY_RATE_LIMITED);
            assertAnomalyLine(line, PaymentAnomalyType.GATEWAY_RATE_LIMITED, "lms.log.outbound.sepay", Level.WARN,
                    "INTEGRATION_OUTBOUND");
            assertTrue(line.getFormattedMessage().contains("retryAfter=30"), line.getFormattedMessage());
            assertTrue(line.getFormattedMessage().contains("errorKind=HTTP_RATE_LIMIT"));
        }
        assertEquals(0, anomalyAudits().size());
    }

    @Test
    void gatewayUnexpectedResponse() {
        try (GatewayStub stub = new GatewayStub(); LogCapture cap = LogCapture.info()) {
            stub.body = "{\"status\":\"success\"}"; // 2xx nhưng thiếu mảng data
            var result = sepay(stub, new GatewayHealthTracker(h.clock, adapter))
                    .queryPayment("LMSABCDEFGH23", 1000, h.clock.instant());

            assertTrue(result.isEmpty());
            ILoggingEvent line = anomalyLine(cap, PaymentAnomalyType.GATEWAY_UNEXPECTED_RESPONSE);
            assertAnomalyLine(line, PaymentAnomalyType.GATEWAY_UNEXPECTED_RESPONSE, "lms.log.outbound.sepay",
                    Level.WARN, "INTEGRATION_OUTBOUND");
        }
        assertEquals(0, anomalyAudits().size());
    }

    @Test
    void gatewayUnexpectedResponseWhenJsonBroken() {
        try (GatewayStub stub = new GatewayStub(); LogCapture cap = LogCapture.info()) {
            stub.body = "{not json at all";
            assertThrows(SalesException.class, () -> sepay(stub, new GatewayHealthTracker(h.clock, adapter))
                    .queryPayment("LMSABCDEFGH23", 1000, h.clock.instant()));

            ILoggingEvent line = anomalyLine(cap, PaymentAnomalyType.GATEWAY_UNEXPECTED_RESPONSE);
            assertTrue(line.getFormattedMessage().contains("errorKind=PARSE"), line.getFormattedMessage());
            assertTrue(line.getFormattedMessage().contains("errorClass=JsonParseException"),
                    line.getFormattedMessage());
        }
    }

    @Test
    void gatewayPollFailingRepeatedlyThenRecovered() {
        GatewayHealthTracker tracker = new GatewayHealthTracker(h.clock, adapter);
        try (LogCapture cap = LogCapture.info()) {
            // 4 lỗi đầu chưa đủ ngưỡng; lỗi thứ 5 ở giây 30 mới đủ (>= 5 lỗi và >= 30 giây kể từ lỗi đầu)
            for (int s : new int[] {0, 7, 14, 21}) {
                h.clock.advanceSeconds(s == 0 ? 0 : 7);
                tracker.onFailure("SEPAY", "TIMEOUT");
            }
            assertTrue(cap.channelEvents("anomalyType=GATEWAY_POLL_FAILING_REPEATEDLY").isEmpty());
            h.clock.advanceSeconds(9);
            tracker.onFailure("SEPAY", "TIMEOUT");
            h.clock.advanceSeconds(5);
            tracker.onFailure("SEPAY", "TIMEOUT"); // lỗi thứ 6: vẫn trong cửa sổ giảm trùng 10 phút
            tracker.onFailure("SEPAY", "TIMEOUT");

            ILoggingEvent line = anomalyLine(cap, PaymentAnomalyType.GATEWAY_POLL_FAILING_REPEATEDLY);
            assertAnomalyLine(line, PaymentAnomalyType.GATEWAY_POLL_FAILING_REPEATEDLY, "lms.log.outbound.sepay",
                    Level.ERROR, "INTEGRATION_OUTBOUND");
            assertTrue(line.getFormattedMessage().contains("failures=5"), line.getFormattedMessage());

            tracker.onSuccess("SEPAY");
            tracker.onSuccess("SEPAY"); // lần hai không còn gì để báo
            List<ILoggingEvent> recovered = cap.channelEvents("outcome=RECOVERED");
            assertEquals(1, recovered.size());
            assertEquals(Level.INFO, recovered.get(0).getLevel());
            assertTrue(recovered.get(0).getFormattedMessage().contains("failures=7"),
                    recovered.get(0).getFormattedMessage());
        }
        assertEquals(0, anomalyAudits().size());
    }

    @Test
    void sauLoiLienTiepNhungChuaDuBayMuoiGiayThiKhongBao() {
        GatewayHealthTracker tracker = new GatewayHealthTracker(h.clock, adapter);
        try (LogCapture cap = LogCapture.info()) {
            for (int i = 0; i < 20; i++) {
                tracker.onFailure("SEPAY", "TIMEOUT"); // cùng một thời điểm
            }
            tracker.onSuccess("SEPAY");
            assertTrue(cap.channelEvents().isEmpty(), "chưa tới ngưỡng thời gian: không bất thường, không RECOVERED");
        }
    }

    // ---- giảm trùng, độ bền ----

    @Test
    void muoiLanLechTienCungLenhChiMotDongAuditVaLogCoSuppressed() {
        PaymentIntentResponse i = qrIntent();
        try (LogCapture cap = LogCapture.info()) {
            for (int n = 0; n < 10; n++) {
                sendExpectingError(body(i.reference(), i.amount() - 1, "SIM-M" + n, "PAID"));
            }
            assertEquals(1, anomalyAudits().size(), "tối đa một audit cho mỗi (lệnh, loại)");
            assertEquals(1, cap.channelEvents("anomalyType=PAYMENT_AMOUNT_MISMATCH").size(),
                    "9 dòng log còn lại bị nuốt trong 60 giây");
            h.clock.advanceSeconds(61);
            sendExpectingError(body(i.reference(), i.amount() - 1, "SIM-M99", "PAID"));
            List<ILoggingEvent> lines = cap.channelEvents("anomalyType=PAYMENT_AMOUNT_MISMATCH");
            assertEquals(2, lines.size());
            assertTrue(lines.get(1).getFormattedMessage().contains("suppressed=9"),
                    lines.get(1).getFormattedMessage());
        }
        assertEquals(1, anomalyAudits().size());
    }

    @Test
    void khacLoaiCungLenhThiGhiAuditRieng() {
        PaymentIntentResponse i = qrIntent();
        sendExpectingError(body(i.reference(), i.amount() - 1, "SIM-A", "PAID"));
        h.clock.advanceSeconds(601);
        sendExpectingError(body(i.reference(), i.amount(), "SIM-B", "PAID"));
        assertEquals(2, anomalyAudits().size());
        assertEquals(List.of("PAYMENT_AMOUNT_MISMATCH", "PAYMENT_AFTER_EXPIRY"),
                anomalyAudits().stream().map(a -> (String) a.data().get("anomalyType")).toList());
    }

    @Test
    void loiKhiGhiBatThuongKhongCheExceptionGoc() {
        PaymentIntentResponse i = qrIntent();
        SalesAuditPort failing = entry -> {
            throw new IllegalStateException("audit hỏng");
        };
        var brokenAudit = new PaymentAnomalyAuditUseCase(failing, h.auditQueryPort, h.clock);
        var logFails = new lms.sales.support.RecordingAnomalyLog();
        logFails.failWith = new IllegalStateException("log hỏng");
        var applyBroken = new ApplyGatewayPaymentUseCase(transactional, h.intents,
                new RecordPaymentAnomalyUseCase(logFails, brokenAudit), logFails);

        SalesException e = assertThrows(SalesException.class, () -> applyBroken.apply(h.tenant,
                new ProcessPaymentWebhookUseCase.Command(i.reference(), i.amount() - 5, "SIM-X", "PAID"),
                "SIMULATED", PaymentAnomalySource.WEBHOOK));

        assertEquals(ErrorCode.PAYMENT_AMOUNT_MISMATCH, e.code(), "exception gốc nguyên vẹn");
        assertEquals(0, anomalyAudits().size());
    }

    @Test
    void xungDotPhienBanKhongPhaiBatThuongChiLogInfoConflict() {
        PaymentIntentResponse i = qrIntent();
        ProcessPaymentWebhookUseCase conflicting = new ProcessPaymentWebhookUseCase(h.intents, h.orderRepo,
                h.stockService, h.auditPort, h.clock) {
            @Override
            public Result execute(UUID tenantId, Command cmd, String provider) {
                throw new SalesException(ErrorCode.PAYMENT_INTENT_STATE_CONFLICT, "version lệch");
            }
        };
        var applyConflict = new ApplyGatewayPaymentUseCase(conflicting, h.intents, recorder, adapter);
        try (LogCapture cap = LogCapture.info()) {
            SalesException e = assertThrows(SalesException.class, () -> applyConflict.apply(h.tenant,
                    new ProcessPaymentWebhookUseCase.Command(i.reference(), i.amount(), "SIM-C", "PAID"),
                    "SIMULATED", PaymentAnomalySource.POLL));

            assertEquals(ErrorCode.PAYMENT_INTENT_STATE_CONFLICT, e.code());
            List<ILoggingEvent> lines = cap.channelEvents("outcome=CONFLICT");
            assertEquals(1, lines.size());
            assertEquals(Level.INFO, lines.get(0).getLevel());
            assertTrue(lines.get(0).getFormattedMessage().contains("anomaly=false"));
            assertTrue(cap.channelEvents("anomaly=true").isEmpty());
        }
        assertEquals(0, anomalyAudits().size());
    }

    @Test
    void loiNghiepVuKhacKhongPhaiBatThuongVaDuocNemLai() {
        PaymentIntentResponse i = qrIntent();
        try (LogCapture cap = LogCapture.info()) {
            SalesException e = assertThrows(SalesException.class, () -> apply.apply(h.tenant,
                    new ProcessPaymentWebhookUseCase.Command(i.reference(), i.amount(), "", "PAID"), "SIMULATED",
                    PaymentAnomalySource.POLL));

            assertEquals(ErrorCode.INVALID_REQUEST, e.code());
            assertTrue(cap.channelEvents().isEmpty());
        }
        assertEquals(0, anomalyAudits().size());
    }

    // ---- dòng kết quả INBOUND ----

    @Test
    void dongKetQuaInboundProcessedDuplicateIgnored() {
        PaymentIntentResponse i = qrIntent();
        try (LogCapture cap = LogCapture.info()) {
            send(body(i.reference(), i.amount(), "SIM-OK", "PAID"));
            send(body(i.reference(), i.amount(), "SIM-OK", "PAID"));
            send(body(i.reference(), i.amount(), "SIM-OK", "FAILED"));

            List<ILoggingEvent> lines = cap.channelEvents("integration-receive");
            assertEquals(3, lines.size());
            assertTrue(lines.get(0).getFormattedMessage().contains("outcome=PROCESSED"));
            assertTrue(lines.get(1).getFormattedMessage().contains("outcome=DUPLICATE"));
            assertTrue(lines.get(2).getFormattedMessage().contains("outcome=IGNORED"));
            for (ILoggingEvent l : lines) {
                assertEquals(Level.INFO, l.getLevel());
                assertEquals("lms.log.inbound.webhook", l.getLoggerName());
                assertTrue(l.getFormattedMessage().contains("op=webhook"));
                assertTrue(l.getFormattedMessage().contains("httpStatus=200"));
                assertTrue(l.getFormattedMessage().contains("anomaly=false"));
                assertTrue(l.getFormattedMessage().contains("intentId=" + i.id()));
                assertFalse(l.getFormattedMessage().contains(i.reference()));
            }
        }
    }

    @Test
    void nutGiaLapGhiOpSimulatePaid() {
        PaymentIntentResponse i = qrIntent();
        String payload = body(i.reference(), i.amount(), "SIM-BTN", "PAID");
        try (LogCapture cap = LogCapture.info()) {
            processor.process(payload, processor.sign(payload), PaymentAnomalySource.SIMULATE_BUTTON, null);

            List<ILoggingEvent> lines = cap.channelEvents("integration-receive");
            assertEquals(1, lines.size());
            assertTrue(lines.get(0).getFormattedMessage().contains("op=simulatePaid"));
            assertTrue(lines.get(0).getFormattedMessage().contains("source=SIMULATE_BUTTON"));
        }
    }

    // ---- thăm dò không ghi DB, không INFO ở đường bình thường ----

    @Test
    void thamDoKhongCoGiaoDichKhongGhiDbVaKhongInfoKenhOutbound() {
        PaymentIntentResponse i = qrIntent();
        // Coi lệnh QR vừa tạo là lệnh của cổng SEPAY (cổng thật dựng trên máy chủ giả bên dưới)
        PaymentIntentResponse pendingSepay = new PaymentIntentResponse(i.id(), i.orderId(), i.reference(), i.amount(),
                PaymentIntentStatus.PENDING, "SEPAY", i.qrPayload(), i.expiresAt(), null, i.createdAt(), null, null,
                null);
        try (GatewayStub stub = new GatewayStub(); LogCapture cap = LogCapture.info()) {
            stub.body = "{\"status\":\"success\",\"data\":[]}";
            var sync = new lms.sales.ordering.application.SyncGatewayPaymentUseCase(
                    List.<PaymentGatewayPort>of(sepay(stub, new GatewayHealthTracker(h.clock, adapter))), apply);
            int savesBefore = h.intents.saves;
            int auditsBefore = h.audits().size();

            for (int n = 0; n < 50; n++) {
                assertEquals(PaymentIntentStatus.PENDING, sync.refresh(h.tenant, pendingSepay).status());
            }

            assertEquals(50, stub.calls.get());
            assertEquals(savesBefore, h.intents.saves, "không ghi lệnh");
            assertEquals(auditsBefore, h.audits().size(), "không ghi audit");
            assertTrue(cap.channelEvents().isEmpty(), "INFO: không dòng nào của lms.log.*: " + cap.allLines());
        }
        try (GatewayStub stub = new GatewayStub(); LogCapture cap = LogCapture.debug()) {
            stub.body = "{\"status\":\"success\",\"data\":[]}";
            sepay(stub, new GatewayHealthTracker(h.clock, adapter)).queryPayment(i.reference(), i.amount(),
                    h.clock.instant());
            List<ILoggingEvent> lines = cap.channelEvents("outcome=NO_MATCH");
            assertEquals(1, lines.size());
            assertEquals(Level.DEBUG, lines.get(0).getLevel());
            assertEquals("lms.log.outbound.sepay", lines.get(0).getLoggerName());
        }
    }

    @Test
    void thamDoKhopGiaoDichGhiInfoMatchedVaPaid() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PaymentIntentResponse i = place(a, 1, PaymentMethod.QR).paymentIntent();
        PaymentIntentResponse sepayIntent = new PaymentIntentResponse(i.id(), i.orderId(), i.reference(), i.amount(),
                PaymentIntentStatus.PENDING, "SIMULATED", i.qrPayload(), i.expiresAt(), null, i.createdAt(), null,
                null, null);
        PaymentGatewayPort found = new PaymentGatewayPort() {
            @Override
            public String provider() {
                return "SIMULATED";
            }

            @Override
            public QrCharge createQr(ChargeRequest request) {
                return new QrCharge("x");
            }

            @Override
            public java.util.Optional<GatewayPayment> queryPayment(String reference, long amount,
                                                                   java.time.Instant notBefore) {
                return java.util.Optional.of(new GatewayPayment(true, "SEPAY-123456789ab", amount));
            }
        };
        try (LogCapture cap = LogCapture.info()) {
            var sync = new lms.sales.ordering.application.SyncGatewayPaymentUseCase(List.of(found), apply);
            PaymentIntentResponse paid = sync.refresh(h.tenant, sepayIntent);

            assertEquals(PaymentIntentStatus.PAID, paid.status());
            assertTrue(cap.allLines().stream().anyMatch(l -> l.contains("Đã ghi nhận thanh toán qua thăm dò")
                    && l.contains("intentId=" + i.id())), cap.allLines().toString());
            assertFalse(cap.allLines().stream().anyMatch(l -> l.contains(i.reference())), "không log mã tham chiếu");
        }
        assertEquals(0, anomalyAudits().size());
    }

    @Test
    void thoiGianCuaSoGiamTrungDaiChoLoaiAuthRejected() {
        try (GatewayStub stub = new GatewayStub(); LogCapture cap = LogCapture.info()) {
            stub.status = 403;
            var gw = sepay(stub, new GatewayHealthTracker(h.clock, adapter));
            for (int n = 0; n < 5; n++) {
                assertThrows(SalesException.class, () -> gw.queryPayment("LMSABCDEFGH23", 1000, h.clock.instant()));
            }
            h.clock.advance(Duration.ofSeconds(61));
            assertThrows(SalesException.class, () -> gw.queryPayment("LMSABCDEFGH23", 1000, h.clock.instant()));
            assertEquals(1, cap.channelEvents("anomalyType=GATEWAY_AUTH_REJECTED").size(),
                    "GATEWAY_AUTH_REJECTED giảm trùng 10 phút");
            h.clock.advance(Duration.ofMinutes(10));
            assertThrows(SalesException.class, () -> gw.queryPayment("LMSABCDEFGH23", 1000, h.clock.instant()));
            List<ILoggingEvent> lines = cap.channelEvents("anomalyType=GATEWAY_AUTH_REJECTED");
            assertEquals(2, lines.size());
            assertTrue(lines.get(1).getFormattedMessage().contains("suppressed=5"),
                    lines.get(1).getFormattedMessage());
        }
    }

    @Test
    void bangLoaiBatThuongKhopThietKe() {
        List<PaymentAnomalyType> audited = List.of(PaymentAnomalyType.values()).stream()
                .filter(PaymentAnomalyType::audited).toList();
        assertEquals(List.of(PaymentAnomalyType.PAYMENT_AMOUNT_MISMATCH, PaymentAnomalyType.PAYMENT_AFTER_EXPIRY,
                PaymentAnomalyType.PAYMENT_AFTER_CANCEL, PaymentAnomalyType.PAYMENT_DOUBLE_PAID,
                PaymentAnomalyType.PAYMENT_TXN_REUSED_OTHER_INTENT, PaymentAnomalyType.PAYMENT_PROVIDER_MISMATCH),
                audited);
        assertEquals(13, PaymentAnomalyType.values().length);
        assertEquals(List.of(PaymentAnomalyType.PAYMENT_DOUBLE_PAID, PaymentAnomalyType.PAYMENT_TXN_REUSED_OTHER_INTENT,
                PaymentAnomalyType.GATEWAY_AUTH_REJECTED, PaymentAnomalyType.GATEWAY_POLL_FAILING_REPEATEDLY),
                List.of(PaymentAnomalyType.values()).stream()
                        .filter(t -> t.severity() == PaymentAnomalyType.Severity.ERROR).toList());
    }
}
