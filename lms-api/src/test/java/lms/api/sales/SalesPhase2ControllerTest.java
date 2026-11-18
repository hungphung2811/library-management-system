package lms.api.sales;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.api.config.SecurityConfiguration;
import lms.api.sales.client.SalesClient;
import lms.api.sales.dto.SalesDtos.*;
import lms.api.sales.web.SalesOrderController;
import lms.api.sales.web.SalesReturnController;
import lms.api.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;

/** Hợp đồng FE pha 2 của /api/sales/**: thao tác đơn, trả hàng, hoàn tiền, nhật ký. BFF không kiểm quyền. */
@WebMvcTest({SalesOrderController.class, SalesReturnController.class})
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class SalesPhase2ControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean SalesClient sales;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final UUID ID = UUID.randomUUID();
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private static HttpClientErrorException downstream(HttpStatus status, String code) {
        String body = "{\"code\":\"" + code + "\",\"message\":\"x\",\"details\":[]}";
        return HttpClientErrorException.create(status, "err", HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    private static SalesOrderView order() {
        return new SalesOrderView(ID, "DH-000001", "CONFIRMED", "UNPAID", "ONLINE", "PICKUP", "BANK_TRANSFER", null, null,
                List.of(), 100000, 0, 0, 0, 100000, 0, false, null, null, null, null, null, null, null, 1, null, null);
    }

    private static ReturnView ret() {
        return new ReturnView(ID, "TH-000001", UUID.randomUUID(), "loi", false, null, 0, 90000, null, null, null, List.of());
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(post("/api/sales/orders/" + ID + "/confirmation")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/sales/returns")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/sales/refunds")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/sales/audit-log")).andExpect(status().isUnauthorized());
        verifyNoInteractions(sales);
    }

    @Test
    void bayThaoTacDonChuyenXuongClient() throws Exception {
        when(sales.confirmOrder(eq(ID), any())).thenReturn(order());
        when(sales.applyOrderDiscount(eq(ID), any())).thenReturn(order());
        when(sales.markOrderPaid(eq(ID), any())).thenReturn(order());
        when(sales.markReadyForPickup(eq(ID), any())).thenReturn(order());
        when(sales.markShipping(eq(ID), any())).thenReturn(order());
        when(sales.completeOrder(eq(ID), any())).thenReturn(order());
        when(sales.cancelOrder(eq(ID), any())).thenReturn(order());

        // body tùy chọn: không body vẫn chuyển được
        mvc.perform(post("/api/sales/orders/" + ID + "/confirmation").with(jwt())).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value("DH-000001"));
        mvc.perform(post("/api/sales/orders/" + ID + "/confirmation").with(jwt()).contentType(JSON)
                .content("{\"shippingFee\":20000,\"version\":1}")).andExpect(status().isOk());
        ArgumentCaptor<ConfirmOrderRequest> c = ArgumentCaptor.forClass(ConfirmOrderRequest.class);
        verify(sales, org.mockito.Mockito.times(2)).confirmOrder(eq(ID), c.capture());
        org.junit.jupiter.api.Assertions.assertNull(c.getAllValues().get(0));
        org.junit.jupiter.api.Assertions.assertEquals(20000L, c.getAllValues().get(1).shippingFee());

        mvc.perform(post("/api/sales/orders/" + ID + "/discount").with(jwt()).contentType(JSON)
                .content("{\"orderDiscount\":{\"type\":\"AMOUNT\",\"value\":5000},\"lineDiscounts\":"
                        + "[{\"lineNo\":1,\"discount\":{\"type\":\"PERCENT\",\"value\":1000}}]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/sales/orders/" + ID + "/payment").with(jwt()).contentType(JSON)
                .content("{\"method\":\"BANK_TRANSFER\",\"reference\":\"FT1\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/sales/orders/" + ID + "/ready-for-pickup").with(jwt())).andExpect(status().isOk());
        mvc.perform(post("/api/sales/orders/" + ID + "/shipping").with(jwt()).contentType(JSON)
                .content("{\"version\":2}")).andExpect(status().isOk());
        mvc.perform(post("/api/sales/orders/" + ID + "/completion").with(jwt())).andExpect(status().isOk());
        mvc.perform(post("/api/sales/orders/" + ID + "/cancellation").with(jwt()).contentType(JSON)
                .content("{\"reason\":\"Quá hạn chuyển khoản\"}")).andExpect(status().isOk());
        verify(sales).markOrderPaid(eq(ID), any());
    }

    @Test
    void validateBodyThaoTacDon() throws Exception {
        mvc.perform(post("/api/sales/orders/" + ID + "/cancellation").with(jwt()).contentType(JSON)
                .content("{\"reason\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/sales/orders/" + ID + "/payment").with(jwt()).contentType(JSON)
                .content("{\"method\":\"BITCOIN\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/sales/orders/" + ID + "/discount").with(jwt()).contentType(JSON)
                .content("{\"orderDiscount\":{\"type\":\"HALF\",\"value\":1}}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/sales/orders/" + ID + "/confirmation").with(jwt()).contentType(JSON)
                .content("{\"shippingFee\":-1}")).andExpect(status().isBadRequest());
        verifyNoInteractions(sales);
    }

    @Test
    void traHangThuongVaQuaHanTra201VaLocation() throws Exception {
        when(sales.createReturn(any())).thenReturn(ret());
        when(sales.createLateReturn(any())).thenReturn(ret());
        when(sales.getReturn(ID)).thenReturn(ret());
        when(sales.listReturnsByOrder(ID)).thenReturn(List.of(ret()));
        String body = "{\"orderId\":\"" + UUID.randomUUID() + "\",\"lines\":[{\"orderLineId\":\"" + UUID.randomUUID()
                + "\",\"quantity\":1,\"disposition\":\"RESTOCK\"}],\"reason\":\"loi\",\"clientRequestId\":\"c1\"";
        mvc.perform(post("/api/sales/returns").with(jwt()).contentType(JSON).content(body + "}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/sales/returns/" + ID));
        mvc.perform(post("/api/sales/returns/late").with(jwt()).contentType(JSON)
                        .content(body + ",\"lateReason\":\"khach quen\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/sales/returns/" + ID));
        mvc.perform(get("/api/sales/returns/" + ID).with(jwt())).andExpect(status().isOk())
                .andExpect(jsonPath("$.returnNo").value("TH-000001"));
        mvc.perform(get("/api/sales/orders/" + ID + "/returns").with(jwt())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ID.toString()));
        mvc.perform(post("/api/sales/returns").with(jwt()).contentType(JSON)
                .content(body.replace("RESTOCK", "THROW") + "}")).andExpect(status().isBadRequest());
    }

    @Test
    void thieuLateReasonKhongBiBffChanMaLoiLaCuaService() throws Exception {
        when(sales.createLateReturn(any())).thenThrow(downstream(HttpStatus.BAD_REQUEST, "LATE_REASON_REQUIRED"));
        String body = "{\"orderId\":\"" + UUID.randomUUID() + "\",\"lines\":[{\"orderLineId\":\"" + UUID.randomUUID()
                + "\",\"quantity\":1,\"disposition\":\"WRITE_OFF\"}],\"reason\":\"loi\",\"clientRequestId\":\"c1\"}";
        mvc.perform(post("/api/sales/returns/late").with(jwt()).contentType(JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LATE_REASON_REQUIRED"));
    }

    @Test
    void hoanTienVaNhatKyAnhXaQuery() throws Exception {
        RefundView r = new RefundView(ID, UUID.randomUUID(), "RETURN", ID, 90000, "REFUNDED", "CASH", null, null, null,
                null, 1);
        when(sales.searchRefunds(any())).thenReturn(new SalesPage<>(List.of(r), 1, 0, 20));
        when(sales.markRefunded(eq(ID), any())).thenReturn(r);
        when(sales.searchAuditLog(any())).thenReturn(new SalesPage<>(List.of(new AuditLogEntryView(ID, "ORDER_PAID",
                "SalesOrder", ID, null, null, Map.of("amount", 100))), 1, 0, 20));
        mvc.perform(get("/api/sales/refunds?status=PENDING&page=0&size=20").with(jwt())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].source").value("RETURN"));
        ArgumentCaptor<RefundSearchQuery> q = ArgumentCaptor.forClass(RefundSearchQuery.class);
        verify(sales).searchRefunds(q.capture());
        org.junit.jupiter.api.Assertions.assertEquals("PENDING", q.getValue().status());
        mvc.perform(post("/api/sales/refunds/" + ID + "/completion").with(jwt()).contentType(JSON)
                .content("{\"method\":\"CASH\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/sales/refunds/" + ID + "/completion").with(jwt()).contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/sales/audit-log?action=ORDER_PAID&aggregateType=SalesOrder&aggregateId=" + ID
                        + "&from=2026-11-01T00:00:00Z&to=2026-12-01T00:00:00Z&page=1&size=5").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].data.amount").value(100));
        ArgumentCaptor<AuditLogQuery> a = ArgumentCaptor.forClass(AuditLogQuery.class);
        verify(sales).searchAuditLog(a.capture());
        org.junit.jupiter.api.Assertions.assertEquals("2026-11-01T00:00:00Z", a.getValue().from());
        org.junit.jupiter.api.Assertions.assertEquals(ID, a.getValue().aggregateId());
    }

    @Test
    void loiNghiepVuPha2ChuyenNguyenMa() throws Exception {
        when(sales.completeOrder(eq(ID), any())).thenThrow(downstream(HttpStatus.CONFLICT, "ORDER_NOT_PAID"));
        when(sales.getReturn(ID)).thenThrow(downstream(HttpStatus.NOT_FOUND, "RETURN_NOT_FOUND"));
        when(sales.createReturn(any())).thenThrow(downstream(HttpStatus.CONFLICT, "RETURN_WINDOW_EXPIRED"));
        when(sales.markRefunded(eq(ID), any())).thenThrow(downstream(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND"));
        mvc.perform(post("/api/sales/orders/" + ID + "/completion").with(jwt())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PAID"));
        mvc.perform(get("/api/sales/returns/" + ID).with(jwt())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RETURN_NOT_FOUND"));
        mvc.perform(post("/api/sales/refunds/" + ID + "/completion").with(jwt()).contentType(JSON)
                .content("{\"method\":\"CASH\"}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REFUND_NOT_FOUND"));
        mvc.perform(post("/api/sales/returns").with(jwt()).contentType(JSON).content("{\"orderId\":\"" + UUID.randomUUID()
                        + "\",\"lines\":[{\"orderLineId\":\"" + UUID.randomUUID()
                        + "\",\"quantity\":1,\"disposition\":\"RESTOCK\"}],\"reason\":\"x\",\"clientRequestId\":\"c\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RETURN_WINDOW_EXPIRED"));
    }

    @Test
    void khongCoDuongDanShopTrenLmsApi() throws Exception {
        // khách đi qua customer-api (ADR-0011), lms-api không phơi /api/shop/**
        mvc.perform(get("/api/shop/orders").with(jwt())).andExpect(status().isNotFound());
        verifyNoInteractions(sales);
    }
}
