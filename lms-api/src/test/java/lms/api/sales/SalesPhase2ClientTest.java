package lms.api.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.UUID;
import lms.api.sales.client.http.HttpSalesClient;
import lms.api.sales.dto.SalesDtos.*;
import lms.api.support.TokenRelayInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Pha 2: thao tác đơn, trả hàng, hoàn tiền, nhật ký gọi đúng đường dẫn/body của sales-service. */
class SalesPhase2ClientTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://sales")
            .requestInterceptor(new TokenRelayInterceptor());
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpSalesClient client = new HttpSalesClient(builder.build());
    private static final String B = "http://sales/internal/sales";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void login() {
        Jwt jwt = Jwt.withTokenValue("token-nv").header("alg", "none").subject("u1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static String order(UUID id) {
        return "{\"id\":\"" + id + "\",\"orderNo\":\"DH-000001\",\"status\":\"CONFIRMED\",\"paymentStatus\":\"UNPAID\","
                + "\"channel\":\"ONLINE\",\"fulfilment\":\"PICKUP\",\"confirmedAt\":\"2026-11-17T01:00:00Z\","
                + "\"paidAt\":null,\"cancelledAt\":null,\"cancelReason\":null,\"exchangeOfReturnId\":null,"
                + "\"contact\":{\"name\":\"N** V** A*\",\"phone\":\"***678\",\"address\":\"Q1, TP HCM\"},"
                + "\"lines\":[],\"version\":2,\"truongMoi\":1}";
    }

    @Test
    void baThaoTacDonDungDuongDanBodyVaTokenOrderViewCoTruongMoi() {
        login();
        UUID id = UUID.randomUUID();
        for (String action : List.of("confirmation", "discount", "payment", "ready-for-pickup", "shipping",
                "completion", "cancellation")) {
            server.expect(requestTo(B + "/orders/" + id + "/" + action)).andExpect(method(HttpMethod.POST))
                    .andExpect(header("Authorization", "Bearer token-nv"))
                    .andRespond(withSuccess(order(id), MediaType.APPLICATION_JSON));
        }
        SalesOrderView v = client.confirmOrder(id, new ConfirmOrderRequest(20000L, 1L));
        assertNotNull(v.confirmedAt());
        assertEquals("N** V** A*", v.contact().name());
        client.applyOrderDiscount(id, new ApplyOrderDiscountRequest(new DiscountRequest("AMOUNT", 5000),
                List.of(new LineDiscountRequest(1, new DiscountRequest("PERCENT", 1000))), null));
        client.markOrderPaid(id, new MarkOrderPaidRequest("BANK_TRANSFER", "FT1", 2L));
        client.markReadyForPickup(id, null);
        client.markShipping(id, new OrderVersionRequest(2L));
        client.completeOrder(id, null);
        client.cancelOrder(id, new CancelOrderRequest("Quá hạn", null));
        server.verify();
    }

    @Test
    void bodyThaoTacDonDuocChuyenDungTruong() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo(B + "/orders/" + id + "/confirmation")).andExpect(jsonPath("$.shippingFee").value(20000))
                .andExpect(jsonPath("$.version").doesNotExist())
                .andRespond(withSuccess(order(id), MediaType.APPLICATION_JSON));
        server.expect(requestTo(B + "/orders/" + id + "/payment")).andExpect(jsonPath("$.method").value("COD"))
                .andExpect(jsonPath("$.reference").value("R1"))
                .andRespond(withSuccess(order(id), MediaType.APPLICATION_JSON));
        server.expect(requestTo(B + "/orders/" + id + "/ready-for-pickup")).andExpect(content().string(""))
                .andRespond(withSuccess(order(id), MediaType.APPLICATION_JSON));
        client.confirmOrder(id, new ConfirmOrderRequest(20000L, null));
        client.markOrderPaid(id, new MarkOrderPaidRequest("COD", "R1", null));
        client.markReadyForPickup(id, null);
        server.verify();
    }

    @Test
    void traHangHoanTienVaNhatKy() {
        login();
        UUID id = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        String ret = "{\"id\":\"" + id + "\",\"returnNo\":\"TH-000001\",\"orderId\":\"" + order + "\",\"reason\":\"loi\","
                + "\"late\":true,\"lateReason\":\"x\",\"daysOverdue\":3,\"refundTotal\":90000,\"refundId\":null,"
                + "\"lines\":[{\"id\":\"" + line + "\",\"orderLineId\":\"" + line + "\",\"titleId\":\"" + line
                + "\",\"quantity\":1,\"disposition\":\"RESTOCK\",\"refundAmount\":90000}]}";
        server.expect(requestTo(B + "/returns")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.lines[0].disposition").value("RESTOCK"))
                .andRespond(withSuccess(ret, MediaType.APPLICATION_JSON));
        server.expect(requestTo(B + "/returns/late")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.lateReason").value("khach quen"))
                .andRespond(withSuccess(ret, MediaType.APPLICATION_JSON));
        server.expect(requestTo(B + "/returns/" + id)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ret, MediaType.APPLICATION_JSON));
        server.expect(requestTo(B + "/orders/" + order + "/returns")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[" + ret + "]", MediaType.APPLICATION_JSON));
        String refund = "{\"id\":\"" + id + "\",\"orderId\":\"" + order + "\",\"source\":\"RETURN\",\"amount\":90000,"
                + "\"status\":\"REFUNDED\",\"method\":\"CASH\",\"version\":1}";
        server.expect(requestTo(B + "/refunds?status=PENDING&page=0&size=20")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"items\":[" + refund + "],\"total\":1,\"page\":0,\"size\":20}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(B + "/refunds/" + id + "/completion")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.method").value("CASH"))
                .andRespond(withSuccess(refund, MediaType.APPLICATION_JSON));
        server.expect(requestTo(B + "/audit-log?action=ORDER_PAID&aggregateId=" + order
                        + "&from=2026-11-01T00:00:00Z&page=0&size=20")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"items\":[{\"id\":\"" + id + "\",\"action\":\"ORDER_PAID\","
                        + "\"aggregateType\":\"SalesOrder\",\"aggregateId\":\"" + order + "\",\"actorId\":null,"
                        + "\"occurredAt\":\"2026-11-17T01:00:00Z\",\"data\":{\"amount\":100}}],\"total\":1,"
                        + "\"page\":0,\"size\":20}", MediaType.APPLICATION_JSON));

        var lines = List.of(new ReturnLineRequest(line, 1, "RESTOCK"));
        assertEquals("TH-000001", client.createReturn(new CreateReturnRequest(order, lines, "loi", "c1")).returnNo());
        assertEquals(3, client.createLateReturn(new CreateLateReturnRequest(order, lines, "loi", "khach quen", "c2"))
                .daysOverdue());
        assertEquals(90000, client.getReturn(id).lines().get(0).refundAmount());
        assertEquals(1, client.listReturnsByOrder(order).size());
        assertEquals("RETURN", client.searchRefunds(new RefundSearchQuery("PENDING", 0, 20)).items().get(0).source());
        assertEquals("REFUNDED", client.markRefunded(id, new MarkRefundedRequest("CASH", null, null)).status());
        var audit = client.searchAuditLog(new AuditLogQuery("ORDER_PAID", null, order, "2026-11-01T00:00:00Z", null, 0,
                20));
        assertEquals(100, ((Number) audit.items().get(0).data().get("amount")).intValue());
        server.verify();
    }

    @Test
    void loiMoiCuaPhaHaiDuocNemNguyen() {
        UUID id = UUID.randomUUID();
        for (String code : List.of("RETURN_NOT_FOUND", "REFUND_NOT_FOUND")) {
            server.expect(requestTo(B + "/" + (code.startsWith("RETURN") ? "returns/" : "refunds/") + id))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                            .body("{\"code\":\"" + code + "\",\"message\":\"x\",\"details\":[]}"));
        }
        HttpClientErrorException ex = assertThrows(HttpClientErrorException.class, () -> client.getReturn(id));
        assertEquals(404, ex.getStatusCode().value());
        assertEquals(true, ex.getResponseBodyAsString().contains("RETURN_NOT_FOUND"));
    }
}
