package lms.sales.web;

import static lms.sales.support.WebTokens.TENANT;
import static lms.sales.support.WebTokens.USER;
import static lms.sales.support.WebTokens.noPermission;
import static lms.sales.support.WebTokens.withKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lms.sales.config.SecurityConfiguration;
import lms.sales.ordering.domain.OrderStatus;
import lms.sales.ordering.infrastructure.web.PaymentIntentController;
import lms.sales.ordering.infrastructure.web.PaymentWebhookController;
import lms.sales.ordering.infrastructure.web.PosOrderController;
import lms.sales.ordering.infrastructure.web.SimulatedWebhookProcessor;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.support.Harness;
import lms.sales.support.QrFixture;
import lms.sales.support.QrWebBeans;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Thanh toán QR qua HTTP (ADR-0020), có bật lại kiểm xác thực và kiểm khóa: tạo đơn QR, webhook công khai (chỉ chữ
 * ký, không JWT) với chữ ký sai/đúng/trùng/lệch tiền/hết hạn/đã hủy, nút giả lập, khóa quyền của endpoint FE.
 */
@WebMvcTest({PosOrderController.class, PaymentIntentController.class, PaymentWebhookController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class,
        QrWebBeans.class, SimulatedWebhookProcessor.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true",
        "app.payments.sandbox.enabled=true", "app.payments.sandbox.webhook-secret=test-secret"})
class PaymentQrWebTest {

    private static final String SECRET = "test-secret";
    private static final String HOOK = "/internal/payments/webhook/simulated";
    private static final String INTENTS = "/internal/payments/intents";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    Harness h;
    @Autowired
    QrFixture fx;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PermissionKeyRegistrar permissionKeyRegistrar;

    private UUID book(long price, long stock) {
        UUID id = UUID.randomUUID();
        h.catalogTitles.put(id, new CatalogLookupPort.CatalogTitle(id, "Sach " + id.toString().substring(0, 6),
                "Tac gia", "ISBN-" + id.toString().substring(0, 8), BigDecimal.valueOf(price)));
        h.registerItem.execute(TENANT, USER, id, null, null);
        h.adjust.execute(TENANT, USER, id, stock, "ton dau");
        return id;
    }

    private JsonNode createQr(UUID title, int qty) throws Exception {
        String body = "{\"lines\":[{\"titleId\":\"" + title + "\",\"quantity\":" + qty + "}],"
                + "\"paymentMethod\":\"QR\",\"clientRequestId\":\"" + UUID.randomUUID() + "\"}";
        MvcResult r = mvc.perform(post("/internal/sales/pos-orders").with(withKey("PosOrderController/create"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    /** Mã giao dịch duy nhất mỗi lần gọi: Harness dùng chung trong context nên không được trùng giữa các test. */
    private static String txn() {
        return "TXN-" + UUID.randomUUID();
    }

    private static String hook(String reference, long amount, String txn, String status) {
        return "{\"reference\":\"" + reference + "\",\"amount\":" + amount + ",\"providerTxnId\":\"" + txn
                + "\",\"status\":\"" + status + "\"}";
    }

    private org.springframework.test.web.servlet.ResultActions callHook(String body, String signature)
            throws Exception {
        var req = post(HOOK).contentType(MediaType.APPLICATION_JSON).content(body);
        if (signature != null) {
            req = req.header("X-Signature", signature);
        }
        return mvc.perform(req); // cố ý không có JWT
    }

    @Test
    void taoDonQrTra201KemKhoiPaymentIntentVaGiuNguyenCacTruongDon() throws Exception {
        UUID title = book(100_000, 5);
        JsonNode order = createQr(title, 2);

        assertThat(order.get("status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(order.get("paymentStatus").asText()).isEqualTo("UNPAID");
        assertThat(order.get("paymentMethod").asText()).isEqualTo("QR");
        assertThat(order.get("grandTotal").asLong()).isEqualTo(200_000);
        JsonNode pi = order.get("paymentIntent");
        assertThat(pi.get("id").asText()).isNotBlank();
        assertThat(pi.get("reference").asText()).matches("LMS[A-Z2-9]{10}");
        assertThat(pi.get("amount").asLong()).isEqualTo(200_000);
        assertThat(pi.get("status").asText()).isEqualTo("PENDING");
        assertThat(pi.get("qrPayload").asText()).contains(pi.get("reference").asText())
                .matches("^0002010102.*6304[0-9A-F]{4}$");
        assertThat(pi.get("expiresAt").asText()).isNotBlank();
        assertThat(h.item(title).reserved()).isEqualTo(2);
    }

    private JsonNode createPos(UUID title, int qty, String method) throws Exception {
        String body = "{\"lines\":[{\"titleId\":\"" + title + "\",\"quantity\":" + qty + "}],"
                + "\"paymentMethod\":\"" + method + "\",\"clientRequestId\":\"" + UUID.randomUUID() + "\"}";
        var r = mvc.perform(post("/internal/sales/pos-orders").with(withKey("PosOrderController/create"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void donCashHaiBuocTraAwaitingPaymentKemLenhManualKhongQr() throws Exception {
        UUID title = book(100_000, 5);
        JsonNode order = createPos(title, 1, "CASH");
        assertThat(order.get("status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(order.get("paymentStatus").asText()).isEqualTo("UNPAID");
        JsonNode pi = order.get("paymentIntent");
        assertThat(pi.get("provider").asText()).isEqualTo("MANUAL");
        assertThat(pi.get("qrPayload").isNull()).isTrue();
        assertThat(pi.get("reference").asText()).startsWith("LMS");
        assertThat(h.item(title).reserved()).isEqualTo(1);
        assertThat(h.item(title).onHand()).isEqualTo(5);
    }

    @Test
    void xacNhanTienMatQuaHttpTraTienThoiVaKhoaQuyenRieng() throws Exception {
        UUID title = book(100_000, 5);
        JsonNode pi = createPos(title, 2, "CASH").get("paymentIntent");
        String url = INTENTS + "/" + pi.get("id").asText() + "/confirm";
        String body = "{\"tenderedAmount\":250000}";

        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(url).with(withKey("PaymentIntentController/cancel")).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isForbidden());
        mvc.perform(post(url).with(withKey("PaymentIntentController/confirm")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenderedAmount\":199999}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAYMENT_AMOUNT_MISMATCH"));
        mvc.perform(post(url).with(withKey("PaymentIntentController/confirm")).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.tenderedAmount").value(250000))
                .andExpect(jsonPath("$.changeDue").value(50000));
        assertThat(h.item(title).onHand()).isEqualTo(3);
        assertThat(h.item(title).reserved()).isEqualTo(0);
        // lặp lại: 200 nguyên trạng; thân rỗng cũng được với tiền mặt
        mvc.perform(post(url).with(withKey("PaymentIntentController/confirm"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.changeDue").value(50000));
        // nút giả lập không dùng được cho lệnh MANUAL
        JsonNode other = createPos(title, 1, "CASH").get("paymentIntent");
        mvc.perform(post(INTENTS + "/" + other.get("id").asText() + "/simulate-paid")
                        .with(withKey("PaymentIntentController/simulatePaid")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_NOT_SIMULATED"));
    }

    @Test
    void xacNhanTheCanMaGiaoDichVaLenhQrBi409() throws Exception {
        UUID title = book(100_000, 5);
        JsonNode card = createPos(title, 1, "CARD").get("paymentIntent");
        String url = INTENTS + "/" + card.get("id").asText() + "/confirm";
        mvc.perform(post(url).with(withKey("PaymentIntentController/confirm")).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post(url).with(withKey("PaymentIntentController/confirm")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionRef\":\"" + "x".repeat(65) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url).with(withKey("PaymentIntentController/confirm")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionRef\":\"POS-1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.transactionRef").value("POS-1"));
        JsonNode qr = createQr(title, 1).get("paymentIntent");
        mvc.perform(post(INTENTS + "/" + qr.get("id").asText() + "/confirm")
                        .with(withKey("PaymentIntentController/confirm")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_NOT_MANUAL"));
    }

    @Test
    void webhookKhongChuKyHoacSaiChuKyBi401VaKhongDoiGi() throws Exception {
        UUID title = book(100_000, 5);
        JsonNode pi = createQr(title, 1).get("paymentIntent");
        String body = hook(pi.get("reference").asText(), 100_000, txn(), "PAID");

        callHook(body, null).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_SIGNATURE"));
        callHook(body, "deadbeef").andExpect(status().isUnauthorized());
        callHook(body, sign(body.replace("100000", "1"))).andExpect(status().isUnauthorized());
        callHook(body, sign(body).toUpperCase().replace('A', 'B')).andExpect(status().isUnauthorized());

        mvc.perform(get(INTENTS + "/" + pi.get("id").asText()).with(withKey("PaymentIntentController/get")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void webhookDungChuKyKhongCanJwtDonThanhPaidVaTruTon() throws Exception {
        UUID title = book(100_000, 5);
        JsonNode order = createQr(title, 2);
        JsonNode pi = order.get("paymentIntent");
        String body = hook(pi.get("reference").asText(), 200_000, txn(), "PAID");

        callHook(body, sign(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("PROCESSED"))
                .andExpect(jsonPath("$.intentStatus").value("PAID"));

        assertThat(h.item(title).onHand()).isEqualTo(3);
        assertThat(h.item(title).reserved()).isEqualTo(0);
        var saved = h.orderRepo.findById(TENANT, UUID.fromString(order.get("id").asText())).orElseThrow();
        assertThat(saved.status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(saved.payments()).hasSize(1);
    }

    @Test
    void webhookChapNhanChuKyCoTienToSha256() throws Exception {
        UUID title = book(100_000, 5);
        JsonNode pi = createQr(title, 1).get("paymentIntent");
        String body = hook(pi.get("reference").asText(), 100_000, txn(), "PAID");
        callHook(body, "sha256=" + sign(body)).andExpect(status().isOk());
    }

    @Test
    void webhookTrungMaGiaoDichIdempotent() throws Exception {
        UUID title = book(100_000, 5);
        JsonNode pi = createQr(title, 2).get("paymentIntent");
        String body = hook(pi.get("reference").asText(), 200_000, txn(), "PAID");

        callHook(body, sign(body)).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("PROCESSED"));
        callHook(body, sign(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ALREADY_PROCESSED"));

        assertThat(h.item(title).onHand()).isEqualTo(3);
    }

    @Test
    void webhookLechTienBi400() throws Exception {
        UUID title = book(100_000, 5);
        JsonNode pi = createQr(title, 1).get("paymentIntent");
        String body = hook(pi.get("reference").asText(), 99_000, txn(), "PAID");
        callHook(body, sign(body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_AMOUNT_MISMATCH"));
        assertThat(h.item(title).reserved()).isEqualTo(1);
    }

    @Test
    void webhookLenhHetHanBi409() throws Exception {
        UUID title = book(100_000, 5);
        JsonNode pi = createQr(title, 1).get("paymentIntent");
        h.clock.advanceSeconds(601);
        String body = hook(pi.get("reference").asText(), 100_000, txn(), "PAID");
        callHook(body, sign(body)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_EXPIRED"));
    }

    @Test
    void webhookLenhDaHuyBi409() throws Exception {
        UUID title = book(100_000, 5);
        JsonNode pi = createQr(title, 1).get("paymentIntent");
        mvc.perform(post(INTENTS + "/" + pi.get("id").asText() + "/cancel")
                        .with(withKey("PaymentIntentController/cancel")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(h.item(title).reserved()).isEqualTo(0);
        String body = hook(pi.get("reference").asText(), 100_000, txn(), "PAID");
        callHook(body, sign(body)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_CANCELLED"));
    }

    @Test
    void webhookMaThamChieuLaVaThanSaiDinhDang() throws Exception {
        String unknown = hook("LMSKHONGCO1", 1000, txn(), "PAID");
        callHook(unknown, sign(unknown)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_NOT_FOUND"));
        String notJson = "khong phai json";
        callHook(notJson, sign(notJson)).andExpect(status().isBadRequest());
        String noAmount = "{\"reference\":\"LMSX\",\"providerTxnId\":\"T\",\"status\":\"PAID\"}";
        callHook(noAmount, sign(noAmount)).andExpect(status().isBadRequest());
    }

    @Test
    void endpointFeCanKhoaQuyenTheoQuyUoc() throws Exception {
        UUID title = book(100_000, 5);
        JsonNode pi = createQr(title, 1).get("paymentIntent");
        String id = pi.get("id").asText();

        mvc.perform(get(INTENTS + "/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(get(INTENTS + "/" + id).with(noPermission())).andExpect(status().isForbidden());
        mvc.perform(get(INTENTS + "/" + id).with(withKey("PaymentIntentController/cancel")))
                .andExpect(status().isForbidden());
        mvc.perform(get(INTENTS + "/" + id).with(withKey("PaymentIntentController/get")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value(pi.get("reference").asText()))
                .andExpect(jsonPath("$.qrPayload").value(matchesPattern("^0002010102.*6304[0-9A-F]{4}$")));
        mvc.perform(post(INTENTS + "/" + id + "/cancel").with(withKey("PaymentIntentController/get")))
                .andExpect(status().isForbidden());
        mvc.perform(post(INTENTS + "/" + id + "/simulate-paid").with(withKey("PaymentIntentController/cancel")))
                .andExpect(status().isForbidden());
        mvc.perform(get(INTENTS + "/" + UUID.randomUUID()).with(withKey("PaymentIntentController/get")))
                .andExpect(status().isNotFound());
    }

    @Test
    void nutGiaLapDaThanhToanDiDungLuongWebhook() throws Exception {
        UUID title = book(100_000, 5);
        JsonNode pi = createQr(title, 2).get("paymentIntent");
        String id = pi.get("id").asText();

        mvc.perform(post(INTENTS + "/" + id + "/simulate-paid").with(withKey("PaymentIntentController/simulatePaid")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        assertThat(h.item(title).onHand()).isEqualTo(3);
        assertThat(h.item(title).reserved()).isEqualTo(0);
        // đã thanh toán thì hủy bị 409
        mvc.perform(post(INTENTS + "/" + id + "/cancel").with(withKey("PaymentIntentController/cancel")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_STATE_CONFLICT"));
    }
}
