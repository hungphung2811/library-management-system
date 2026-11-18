package lms.sales.ordering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;
import lms.sales.ordering.application.PaymentGatewayPort;
import lms.sales.ordering.infrastructure.client.SepayPaymentGateway;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SepayPaymentGateway (ADR-0028) với máy chủ HTTP giả nội bộ: không gọi SePay thật. */
class SepayPaymentGatewayTest {

    private static final String REF = "LMSABCDEFGH23";
    private static final Instant CREATED = Instant.parse("2026-11-18T10:00:00Z");
    private static final DateTimeFormatter VN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private HttpServer server;
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private SepayPaymentGateway gateway;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/transactions", ex -> {
            lastQuery.set(ex.getRequestURI().getRawQuery());
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            lastMethod.set(ex.getRequestMethod());
            byte[] out = body.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status.get(), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        gateway = new SepayPaymentGateway(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-token", "970422", "0123456789", "LMS TEST", "");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String tx(String id, long amountIn, String content, Instant at) {
        return "{\"id\":\"" + id + "\",\"transfer_type\":\"in\",\"amount_in\":" + amountIn
                + ",\"transaction_content\":\"" + content + "\",\"transaction_date\":\"" + VN.format(at) + "\"}";
    }

    private void respond(String... txs) {
        body.set("{\"status\":\"success\",\"data\":[" + String.join(",", txs) + "]}");
    }

    @Test
    void taoQrLaVietQrCoNoiDungLaMaThamChieu() {
        var qr = gateway.createQr(new PaymentGatewayPort.ChargeRequest(REF, Money.of(150_000), CREATED.plusSeconds(600)));
        assertTrue(qr.qrPayload().contains(REF));
        assertTrue(qr.qrPayload().contains("0123456789"));
        assertEquals("SEPAY", gateway.provider());
    }

    @Test
    void giaoDichKhopThiBaoDaThanhToanVaGuiBearerTokenBangGet() {
        respond(tx("11111111-aaaa", 150_000, "mo phong " + REF.toLowerCase() + " thanh toan", CREATED.plusSeconds(30)));

        var p = gateway.queryPayment(REF, 150_000, CREATED).orElseThrow();

        assertTrue(p.paid());
        assertEquals("SEPAY-11111111-aaaa", p.providerTxnId());
        assertEquals(150_000, p.amount());
        assertEquals("GET", lastMethod.get());
        assertEquals("Bearer test-token", lastAuth.get());
        assertTrue(lastQuery.get().contains("amount_in_min=150000"));
        assertTrue(lastQuery.get().contains("transaction_content=" + REF));
        assertTrue(lastQuery.get().contains("transfer_type=in"));
    }

    @Test
    void sauKhiLocThamSoVanKiemLaiPhiaTaNenKhongKhopSaiTienThiBoQua() {
        respond(tx("t1", 149_000, "xx " + REF, CREATED.plusSeconds(30)));
        assertTrue(gateway.queryPayment(REF, 150_000, CREATED).isEmpty());
    }

    @Test
    void noiDungKhongChuaMaThamChieuThiBoQua() {
        respond(tx("t1", 150_000, "LMSZZZZZZZZZZ", CREATED.plusSeconds(30)));
        assertTrue(gateway.queryPayment(REF, 150_000, CREATED).isEmpty());
    }

    @Test
    void giaoDichCuHonThoiDiemTaoLenhThiBoQuaNhungDungSaiLechDongHoThiNhan() {
        respond(tx("old", 150_000, REF, CREATED.minusSeconds(120)));
        assertTrue(gateway.queryPayment(REF, 150_000, CREATED).isEmpty());

        respond(tx("skew", 150_000, REF, CREATED.minusSeconds(5)));
        assertEquals("SEPAY-skew", gateway.queryPayment(REF, 150_000, CREATED).orElseThrow().providerTxnId());
    }

    @Test
    void boQuaGiaoDichCuVaChonGiaoDichMoiKhop() {
        respond(tx("old", 150_000, REF, CREATED.minusSeconds(3600)), tx("new", 150_000, REF, CREATED.plusSeconds(10)));
        assertEquals("SEPAY-new", gateway.queryPayment(REF, 150_000, CREATED).orElseThrow().providerTxnId());
    }

    @Test
    void khongCoGiaoDichThiRong() {
        respond();
        assertTrue(gateway.queryPayment(REF, 150_000, CREATED).isEmpty());
        assertFalse(gateway.queryPayment(REF).isPresent(), "bản không số tiền không dò");
    }

    @Test
    void loi401Va429NemPaymentGatewayError() {
        status.set(401);
        body.set("{\"status\":\"error\",\"error_code\":\"unauthorized\"}");
        assertEquals(ErrorCode.PAYMENT_GATEWAY_ERROR,
                assertThrows(SalesException.class, () -> gateway.queryPayment(REF, 150_000, CREATED)).code());

        status.set(429);
        body.set("{\"status\":\"error\",\"error_code\":\"rate_limited\"}");
        assertEquals(ErrorCode.PAYMENT_GATEWAY_ERROR,
                assertThrows(SalesException.class, () -> gateway.queryPayment(REF, 150_000, CREATED)).code());

        status.set(500);
        assertEquals(ErrorCode.PAYMENT_GATEWAY_ERROR,
                assertThrows(SalesException.class, () -> gateway.queryPayment(REF, 150_000, CREATED)).code());
    }

    @Test
    void matKetNoiHoacHetThoiGianNemPaymentGatewayError() {
        server.stop(0);
        assertEquals(ErrorCode.PAYMENT_GATEWAY_ERROR,
                assertThrows(SalesException.class, () -> gateway.queryPayment(REF, 150_000, CREATED)).code());
    }
}
