package lms.api.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.api.config.SecurityConfiguration;
import lms.api.sales.client.SalesClient;
import lms.api.sales.client.SalesPaymentClient;
import lms.api.sales.client.http.HttpSalesClient;
import lms.api.sales.client.http.HttpSalesPaymentClient;
import lms.api.sales.dto.SalesDtos.PaymentIntentView;
import lms.api.sales.dto.SalesDtos.PosOrderLineRequest;
import lms.api.sales.dto.SalesDtos.PosOrderRequest;
import lms.api.sales.dto.SalesDtos.SalesOrderView;
import lms.api.sales.web.SalesOrderController;
import lms.api.sales.web.SalesPaymentController;
import lms.api.support.TokenRelayInterceptor;
import lms.api.web.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Hợp đồng FE của thanh toán QR POS (ADR-0020) ở lms-api: /api/sales/payments/intents/** và khối paymentIntent. */
@WebMvcTest({SalesPaymentController.class, SalesOrderController.class})
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class SalesPaymentQrTest {

    @Autowired MockMvc mvc;
    @MockitoBean SalesPaymentClient payments;
    @MockitoBean SalesClient sales;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final UUID ID = UUID.randomUUID();

    private static PaymentIntentView intent(String status) {
        return new PaymentIntentView(ID, UUID.randomUUID(), "LMSABCDE2345", 200_000, status, "SIMULATED",
                "00020101021238...6304ABCD", Instant.parse("2026-11-18T03:10:00Z"), null,
                Instant.parse("2026-11-18T03:00:00Z"), null, null, null);
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/api/sales/payments/intents/" + ID)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/sales/payments/intents/" + ID + "/cancel")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/sales/payments/intents/" + ID + "/simulate-paid")).andExpect(status().isUnauthorized());
        verifyNoInteractions(payments);
    }

    @Test
    void thamDoHuyVaGiaLapChuyenSangClient() throws Exception {
        when(payments.getIntent(ID)).thenReturn(intent("PENDING"));
        when(payments.cancelIntent(ID)).thenReturn(intent("CANCELLED"));
        when(payments.simulatePaid(ID)).thenReturn(intent("PAID"));

        mvc.perform(get("/api/sales/payments/intents/" + ID).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value("LMSABCDE2345"))
                .andExpect(jsonPath("$.amount").value(200_000))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.qrPayload").value("00020101021238...6304ABCD"));
        mvc.perform(post("/api/sales/payments/intents/" + ID + "/cancel").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/sales/payments/intents/" + ID + "/simulate-paid").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        verify(payments).simulatePaid(ID);
    }

    @Test
    void xacNhanThuTayChuyenBodyVaTraTienThoi() throws Exception {
        PaymentIntentView paid = new PaymentIntentView(ID, UUID.randomUUID(), "LMSX", 200_000, "PAID", "MANUAL", null,
                Instant.parse("2026-11-18T03:10:00Z"), Instant.parse("2026-11-18T03:02:00Z"),
                Instant.parse("2026-11-18T03:00:00Z"), 300_000L, 100_000L, null);
        when(payments.confirmIntent(org.mockito.ArgumentMatchers.eq(ID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(paid);
        mvc.perform(post("/api/sales/payments/intents/" + ID + "/confirm").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tenderedAmount\":300000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("MANUAL"))
                .andExpect(jsonPath("$.tenderedAmount").value(300000))
                .andExpect(jsonPath("$.changeDue").value(100000));
        mvc.perform(post("/api/sales/payments/intents/" + ID + "/confirm").with(jwt())).andExpect(status().isOk());
        mvc.perform(post("/api/sales/payments/intents/" + ID + "/confirm").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"transactionRef\":\"" + "x".repeat(65) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/sales/payments/intents/" + ID + "/confirm")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void loiNghiepVuCuaSalesDuocChuyenNguyen() throws Exception {
        String body = "{\"code\":\"PAYMENT_SANDBOX_DISABLED\",\"message\":\"x\",\"details\":[]}";
        when(payments.simulatePaid(ID)).thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "err",
                HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        mvc.perform(post("/api/sales/payments/intents/" + ID + "/simulate-paid").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_SANDBOX_DISABLED"));
    }

    @Test
    void taoDonPosQrChoPhepVaTraKhoiPaymentIntent() throws Exception {
        UUID orderId = UUID.randomUUID();
        SalesOrderView created = new SalesOrderView(orderId, "O-1", "AWAITING_PAYMENT", "UNPAID", "POS", "COUNTER",
                "QR", null, null, List.of(), 200_000, 0, 0, 0, 200_000, 0, true, null, null, null, null, null, null,
                null, 0, null, null, intent("PENDING"));
        when(sales.createPosOrder(org.mockito.ArgumentMatchers.any())).thenReturn(created);
        String req = "{\"lines\":[{\"titleId\":\"" + UUID.randomUUID() + "\",\"quantity\":2}],"
                + "\"paymentMethod\":\"QR\",\"clientRequestId\":\"" + UUID.randomUUID() + "\"}";

        mvc.perform(post("/api/sales/pos-orders").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.paymentMethod").value("QR"))
                .andExpect(jsonPath("$.paymentIntent.reference").value("LMSABCDE2345"))
                .andExpect(jsonPath("$.paymentIntent.qrPayload").value("00020101021238...6304ABCD"));
        // phương thức ngoài danh sách vẫn bị chặn ở BFF
        mvc.perform(post("/api/sales/pos-orders").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content(req.replace("\"QR\"", "\"COD\"")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void donKhongQrKhongCoKhoiPaymentIntent() throws Exception {
        SalesOrderView cash = new SalesOrderView(UUID.randomUUID(), "O-2", "COMPLETED", "PAID", "POS", "COUNTER",
                "CASH", null, null, List.of(), 100, 0, 0, 0, 100, 0, true, null, null, null, null, null, null, null, 0,
                null, null);
        when(sales.createPosOrder(org.mockito.ArgumentMatchers.any())).thenReturn(cash);
        String req = "{\"lines\":[{\"titleId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}],"
                + "\"paymentMethod\":\"CASH\",\"clientRequestId\":\"" + UUID.randomUUID() + "\"}";
        mvc.perform(post("/api/sales/pos-orders").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentIntent").doesNotExist());
    }

    // ---- client HTTP ----

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void clientGoiDungDuongDanPaymentsVaChuyenTiepToken() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://sales")
                .requestInterceptor(new TokenRelayInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpSalesPaymentClient client = new HttpSalesPaymentClient(builder.build());
        Jwt jwt = Jwt.withTokenValue("token-nv").header("alg", "none").subject("u1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        String json = "{\"id\":\"" + ID + "\",\"orderId\":\"" + UUID.randomUUID() + "\",\"reference\":\"LMSX\","
                + "\"amount\":1000,\"status\":\"PENDING\",\"provider\":\"SIMULATED\",\"qrPayload\":\"qr\","
                + "\"expiresAt\":\"2026-11-18T03:10:00Z\",\"paidAt\":null,\"createdAt\":\"2026-11-18T03:00:00Z\","
                + "\"truongMoi\":1}";

        server.expect(requestTo("http://sales/internal/payments/intents/" + ID)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-nv"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/payments/intents/" + ID + "/cancel"))
                .andExpect(method(HttpMethod.POST)).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/payments/intents/" + ID + "/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .jsonPath("$.transactionRef").value("POS-1"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/payments/intents/" + ID + "/simulate-paid"))
                .andExpect(method(HttpMethod.POST)).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        assertEquals("LMSX", client.getIntent(ID).reference());
        assertNull(client.cancelIntent(ID).paidAt());
        assertEquals("LMSX", client.confirmIntent(ID, new lms.api.sales.dto.SalesDtos.ConfirmPaymentRequest(null, "POS-1"))
                .reference());
        assertEquals(1000, client.simulatePaid(ID).amount());
        server.verify();
        // POS gửi paymentMethod QR nguyên vẹn qua HttpSalesClient (không đổi đường dẫn)
        RestClient.Builder b2 = RestClient.builder().baseUrl("http://sales");
        MockRestServiceServer s2 = MockRestServiceServer.bindTo(b2).build();
        s2.expect(requestTo("http://sales/internal/sales/pos-orders")).andExpect(method(HttpMethod.POST))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .jsonPath("$.paymentMethod").value("QR"))
                .andRespond(withSuccess("{\"id\":\"" + ID + "\",\"paymentIntent\":" + json + "}",
                        MediaType.APPLICATION_JSON));
        SalesOrderView o = new HttpSalesClient(b2.build()).createPosOrder(new PosOrderRequest(
                List.of(new PosOrderLineRequest(UUID.randomUUID(), 1, null)), "QR", null, null, null, "c-1", null,
                null));
        assertEquals("LMSX", o.paymentIntent().reference());
        s2.verify();
    }
}
