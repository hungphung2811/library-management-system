package lms.sales.ordering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.sales.ordering.application.ApplyGatewayPaymentUseCase;
import lms.sales.ordering.application.PaymentAnomalyAuditUseCase;
import lms.sales.ordering.application.PaymentAnomalySource;
import lms.sales.ordering.application.PaymentGatewayPort;
import lms.sales.ordering.application.RecordPaymentAnomalyUseCase;
import lms.sales.ordering.infrastructure.client.GatewayCallLogger;
import lms.sales.ordering.infrastructure.client.GatewayHealthTracker;
import lms.sales.ordering.infrastructure.client.MomoPaymentGateway;
import lms.sales.ordering.infrastructure.client.SepayPaymentGateway;
import lms.sales.ordering.infrastructure.config.PaymentAnomalyLogAdapter;
import lms.sales.ordering.infrastructure.web.SimulatedWebhookProcessor;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import lms.sales.support.GatewayStub;
import lms.sales.support.Harness;
import lms.sales.support.LogCapture;
import lms.sales.support.QrFixture;
import org.junit.jupiter.api.Test;

/**
 * Test rò rỉ (ADR-0029): đặt token, khóa, số tài khoản, thông tin người chuyển GIẢ vào cấu hình, request, response và
 * lỗi, rồi khẳng định TOÀN BỘ dòng log bắt được (mọi logger, kèm throwable) không chứa chúng. Không dùng token thật.
 */
class GatewayLogLeakTest {

    private static final String TOKEN = "tok_SECRET_should_not_leak";
    private static final String MOMO_SECRET = "momo_SECRET_should_not_leak";
    private static final String MOMO_ACCESS = "momo_ACCESS_should_not_leak";
    private static final String ACCOUNT = "0123456789";
    private static final String REF = "LMSABCDEFGHJK";
    private static final String CONTENT = REF + " NGUYEN VAN A 0912345678 a@b.vn";

    private static final List<String> FORBIDDEN = List.of(TOKEN, MOMO_SECRET, MOMO_ACCESS, "Bearer", "Authorization",
            ACCOUNT, "NGUYEN", "0912345678", "a@b.vn", REF, "transaction_content", "X-Signature", "127.0.0.1:",
            "SIGNATURE_FAKE");

    private final Harness h = new Harness();
    private final QrFixture fx = new QrFixture(h);
    private final PaymentAnomalyLogAdapter adapter = new PaymentAnomalyLogAdapter(h.clock);
    private final GatewayHealthTracker tracker = new GatewayHealthTracker(h.clock, adapter);

    private SepayPaymentGateway sepay(String endpoint) {
        return new SepayPaymentGateway(new ObjectMapper(), endpoint, TOKEN, "970422", ACCOUNT, "LMS", "",
                new GatewayCallLogger("SEPAY", h.clock, adapter, tracker));
    }

    private MomoPaymentGateway momo(String endpoint) {
        return new MomoPaymentGateway(new ObjectMapper(), endpoint, "MOMOTEST", MOMO_ACCESS, MOMO_SECRET,
                "https://example.com/return", "https://example.com/ipn",
                new GatewayCallLogger("MOMO", h.clock, adapter, tracker));
    }

    private static String sepayTx() {
        return "{\"status\":\"success\",\"data\":[{\"id\":\"900123456\",\"transfer_type\":\"in\",\"amount_in\":1000,"
                + "\"transaction_content\":\"" + CONTENT + "\",\"transaction_date\":\"2099-01-01 00:00:00\","
                + "\"account_number\":\"" + ACCOUNT + "\",\"token\":\"" + TOKEN + "\"}]}";
    }

    private void assertNoLeak(LogCapture cap) {
        List<String> lines = cap.allLines();
        for (String line : lines) {
            for (String bad : FORBIDDEN) {
                assertFalse(line.contains(bad), "log chứa '" + bad + "': " + line);
            }
        }
        for (ILoggingEvent e : cap.events()) {
            if (e.getLoggerName().startsWith("lms.")) {
                assertFalse(e.getFormattedMessage().contains("?"), "log chứa query URL: " + e.getFormattedMessage());
                assertTrue(e.getThrowableProxy() == null, "không log stack trace: " + e.getLoggerName());
            }
        }
    }

    @Test
    void sepayMoiTinhHuongHttpKhongRoRi() {
        for (int status : new int[] {200, 401, 403, 429, 500, 502, 404}) {
            h.clock.advance(java.time.Duration.ofMinutes(11)); // qua cửa sổ giảm trùng của lần trước
            try (GatewayStub stub = new GatewayStub(); LogCapture cap = LogCapture.debug()) {
                stub.status = status;
                stub.body = status == 200 ? sepayTx() : "{\"error_code\":\"E" + status + "\",\"message\":\"" + TOKEN
                        + " " + CONTENT + " Bearer " + TOKEN + "\"}";
                stub.headers.put("Retry-After", "7");
                try {
                    sepay(stub.url()).queryPayment(REF, 1000, Instant.parse("2000-01-01T00:00:00Z"));
                } catch (SalesException e) {
                    assertEquals(ErrorCode.PAYMENT_GATEWAY_ERROR, e.code());
                    assertFalse(e.getMessage().contains(TOKEN));
                }
                assertFalse(cap.channelEvents().isEmpty(), "có dòng kênh OUTBOUND cho " + status);
                assertNoLeak(cap);
            }
        }
    }

    @Test
    void sepayJsonHongVaThanLaKhongRoRi() {
        try (GatewayStub stub = new GatewayStub(); LogCapture cap = LogCapture.debug()) {
            stub.body = "{\"data\": [ " + TOKEN + " " + CONTENT + " ";
            assertThrows(SalesException.class, () -> sepay(stub.url()).queryPayment(REF, 1000, Instant.EPOCH));
            assertTrue(cap.channelEvents("errorKind=PARSE").size() == 1, cap.allLines().toString());
            assertNoLeak(cap);
        }
        try (GatewayStub stub = new GatewayStub(); LogCapture cap = LogCapture.debug()) {
            stub.body = "<html>" + TOKEN + CONTENT + "</html>";
            assertThrows(SalesException.class, () -> sepay(stub.url()).queryPayment(REF, 1000, Instant.EPOCH));
            assertNoLeak(cap);
        }
    }

    @Test
    void sepayKhongKetNoiDuocKhongRoRiVaLaConnect() {
        String url;
        try (GatewayStub stub = new GatewayStub()) {
            url = stub.url();
        }
        try (LogCapture cap = LogCapture.debug()) {
            SalesException e = assertThrows(SalesException.class,
                    () -> sepay(url).queryPayment(REF, 1000, Instant.EPOCH));
            assertEquals("Không gọi được SePay sandbox", e.getMessage());
            assertTrue(e.getCause() != null, "giữ nguyên nhân gốc cho log máy");
            List<ILoggingEvent> lines = cap.channelEvents("outcome=FAIL");
            assertEquals(1, lines.size());
            assertTrue(lines.get(0).getFormattedMessage().contains("errorKind=CONNECT"),
                    lines.get(0).getFormattedMessage());
            assertTrue(lines.get(0).getFormattedMessage().contains("errorClass=ConnectException"),
                    lines.get(0).getFormattedMessage());
            assertNoLeak(cap);
        }
    }

    @Test
    void sepayLoiTlsVaDnsKhongRoRi() {
        try (GatewayStub stub = new GatewayStub(); LogCapture cap = LogCapture.debug()) {
            // https tới máy chủ chỉ nói HTTP thường: bắt tay TLS hỏng
            assertThrows(SalesException.class,
                    () -> sepay("https://127.0.0.1:" + stub.port()).queryPayment(REF, 1000, Instant.EPOCH));
            List<ILoggingEvent> lines = cap.channelEvents("outcome=FAIL");
            assertEquals(1, lines.size());
            String m = lines.get(0).getFormattedMessage();
            assertTrue(m.contains("errorKind=TLS") || m.contains("errorKind=UNKNOWN") || m.contains("errorKind=CONNECT"),
                    m);
            assertNoLeak(cap);
        }
        try (LogCapture cap = LogCapture.debug()) {
            assertThrows(SalesException.class,
                    () -> sepay("http://no-such-host-" + UUID.randomUUID() + ".invalid").queryPayment(REF, 1000,
                            Instant.EPOCH));
            String m = cap.channelEvents("outcome=FAIL").get(0).getFormattedMessage();
            assertTrue(m.contains("errorKind=DNS"), m);
            assertNoLeak(cap);
        }
    }

    @Test
    void momoMoiTinhHuongKhongRoRiKhoaVaSoDienThoai() {
        for (int status : new int[] {200, 400, 401, 429, 500}) {
            h.clock.advance(java.time.Duration.ofMinutes(11));
            try (GatewayStub stub = new GatewayStub(); LogCapture cap = LogCapture.debug()) {
                stub.status = status;
                stub.body = "{\"resultCode\":1006,\"message\":\"Khach 0912345678 a@b.vn " + MOMO_SECRET + " "
                        + MOMO_ACCESS + " that bai\"}";
                try {
                    momo(stub.url()).createQr(new PaymentGatewayPort.ChargeRequest(REF, Money.of(1000),
                            Instant.now().plusSeconds(600)));
                } catch (SalesException e) {
                    assertEquals(ErrorCode.PAYMENT_GATEWAY_ERROR, e.code());
                    assertFalse(e.getMessage().contains("0912345678"), "thông điệp MoMo phải che số: " + e.getMessage());
                    assertFalse(e.getMessage().contains("a@b.vn"), e.getMessage());
                }
                try {
                    momo(stub.url()).queryPayment(REF);
                } catch (SalesException e) {
                    assertEquals(ErrorCode.PAYMENT_GATEWAY_ERROR, e.code());
                }
                assertNoLeak(cap);
            }
        }
    }

    @Test
    void momoThongDiepTuChoiDiQuaPartnerMessage() {
        try (GatewayStub stub = new GatewayStub()) {
            stub.body = "{\"resultCode\":1006,\"message\":\"Khach 0912345678 a@b.vn bi tu choi\"}";
            SalesException e = assertThrows(SalesException.class, () -> momo(stub.url()).createQr(
                    new PaymentGatewayPort.ChargeRequest(REF, Money.of(1000), Instant.now().plusSeconds(600))));
            assertEquals("MoMo từ chối tạo giao dịch: Khach [num] [email] bi tu choi", e.getMessage());
        }
    }

    @Test
    void momoTimKhopVaKhongKhopGhiDungMucVaKhongRoRi() {
        try (GatewayStub stub = new GatewayStub(); LogCapture cap = LogCapture.debug()) {
            stub.body = "{\"resultCode\":0,\"transId\":\"2718123456789\",\"amount\":1000}";
            var paid = momo(stub.url()).queryPayment(REF);
            assertEquals("MOMO-2718123456789", paid.orElseThrow().providerTxnId());
            stub.body = "{\"resultCode\":1000,\"message\":\"dang cho\"}";
            assertTrue(momo(stub.url()).queryPayment(REF).isEmpty());
            assertEquals(1, cap.channelEvents("outcome=MATCHED").size());
            assertEquals(1, cap.channelEvents("outcome=NO_MATCH").size());
            assertNoLeak(cap);
        }
    }

    @Test
    void webhookVoiChuKyGiaVaThanLaKhongRoRi() {
        var audit = new PaymentAnomalyAuditUseCase(h.auditPort, h.auditQueryPort, h.clock);
        var recorder = new RecordPaymentAnomalyUseCase(adapter, audit);
        var apply = new ApplyGatewayPaymentUseCase(fx.webhook, h.intents, recorder, adapter);
        var processor = new SimulatedWebhookProcessor(apply, recorder, fx.resolver, new ObjectMapper(), true,
                "unit-secret");
        try (LogCapture cap = LogCapture.debug()) {
            String body = "{\"reference\":\"" + REF + "\",\"amount\":1000,\"providerTxnId\":\"X\",\"status\":\"PAID\","
                    + "\"payer\":\"NGUYEN VAN A\",\"phone\":\"0912345678\",\"email\":\"a@b.vn\","
                    + "\"note\":\"" + CONTENT + "\",\"token\":\"" + TOKEN + "\"}";
            assertThrows(SalesException.class, () -> processor.process(body, "SIGNATURE_FAKE", PaymentAnomalySource.WEBHOOK,
                    "203.0.113.5"));
            // chữ ký đúng nhưng mã lạ: log mã đã che, không log thân
            assertThrows(SalesException.class, () -> processor.process(body, processor.sign(body),
                    PaymentAnomalySource.WEBHOOK, "2001:db8:1:2:3:4:5:6"));

            assertEquals(2, cap.channelEvents().size());
            assertTrue(cap.channelEvents("clientIp=2001:db8:1::/48").size() == 1, cap.allLines().toString());
            assertNoLeak(cap);
        }
    }
}
