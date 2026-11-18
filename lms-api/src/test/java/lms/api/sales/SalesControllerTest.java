package lms.api.sales;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import lms.api.config.SecurityConfiguration;
import lms.api.sales.client.SalesClient;
import lms.api.sales.dto.SalesDtos.*;
import lms.api.sales.web.SalesOrderController;
import lms.api.sales.web.SalesPurchasingController;
import lms.api.sales.web.SalesStockController;
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

/** Hợp đồng FE của /api/sales/**: xác thực, validate, 201/Location, ánh xạ query và chuyển nguyên lỗi nghiệp vụ. */
@WebMvcTest({SalesStockController.class, SalesPurchasingController.class, SalesOrderController.class})
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class SalesControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean SalesClient sales;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final UUID TITLE = UUID.randomUUID();

    private static HttpClientErrorException downstream(HttpStatus status, String code) {
        String body = "{\"code\":\"" + code + "\",\"message\":\"x\",\"details\":[]}";
        return HttpClientErrorException.create(status, "err", HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    private static SalesOrderView order(UUID id) {
        return new SalesOrderView(id, "POS-1", "COMPLETED", "PAID", "POS", null, "CASH", null, null, List.of(), 100000, 0,
                5000, 0, 105000, 500, false, null, null, null, null, null, null, null, 0, null, null);
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/api/sales/settings")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/sales/pos-orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/sales/orders/" + UUID.randomUUID() + "/receipt")).andExpect(status().isUnauthorized());
        verifyNoInteractions(sales);
    }

    @Test
    void docCauHinh() throws Exception {
        when(sales.getSettings()).thenReturn(new SalesSettingsView(500, true, 7, 0, 5, 12, 0));
        mvc.perform(get("/api/sales/settings").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.vatRateBps").value(500))
                .andExpect(jsonPath("$.returnWindowDays").value(7));
    }

    @Test
    void capNhatCauHinhSaiGiaTriBi400() throws Exception {
        mvc.perform(put("/api/sales/settings").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vatRateBps\":-1,\"pricesIncludeVat\":true,\"returnWindowDays\":7,\"fixedShippingFee\":0,"
                                + "\"lowStockThreshold\":5,\"walkInPiiRetentionMonths\":12,\"version\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(sales);
    }

    @Test
    void dangKyMatHangTra201KemLocationVaThieuTitleIdBi400() throws Exception {
        when(sales.registerStockItem(any())).thenReturn(new StockItemView(TITLE, "S", 0, 0, 0, null, 90000, true, null, null, null, null, null));
        mvc.perform(post("/api/sales/stock-items").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"" + TITLE + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/sales/stock-items/" + TITLE));
        mvc.perform(post("/api/sales/stock-items").with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void timMatHangGomQueryVaoRecord() throws Exception {
        when(sales.searchStockItems(any())).thenReturn(new SalesPage<>(List.of(), 0, 1, 10));
        mvc.perform(get("/api/sales/stock-items?keyword=java&lowStockOnly=true&page=1&size=10").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(1));
        ArgumentCaptor<StockItemSearchQuery> q = ArgumentCaptor.forClass(StockItemSearchQuery.class);
        verify(sales).searchStockItems(q.capture());
        org.junit.jupiter.api.Assertions.assertEquals(new StockItemSearchQuery("java", true, 1, 10), q.getValue());
    }

    @Test
    void dieuChinhTonThieuLyDoBi400() throws Exception {
        mvc.perform(post("/api/sales/stock-adjustments").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"" + TITLE + "\",\"delta\":-1,\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/sales/stocktakes").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"" + TITLE + "\",\"countedOnHand\":-1,\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(sales);
    }

    @Test
    void nhaCungCapVaPhieuNhapKho() throws Exception {
        UUID id = UUID.randomUUID();
        when(sales.createSupplier(any())).thenReturn(new SupplierView(id, "NCC", null, null, null, null, true, 0));
        when(sales.createGoodsReceipt(any())).thenReturn(
                new GoodsReceiptView(id, "GR-1", id, "NCC", "DRAFT", List.of(), 0, null, null, null, 0));
        when(sales.confirmGoodsReceipt(id)).thenReturn(
                new GoodsReceiptView(id, "GR-1", id, "NCC", "CONFIRMED", List.of(), 0, null, null, null, 1));
        mvc.perform(post("/api/sales/suppliers").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NCC\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/sales/suppliers/" + id));
        mvc.perform(post("/api/sales/suppliers").with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/sales/goods-receipts").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":\"" + id + "\",\"clientRequestId\":\"c1\",\"lines\":"
                                + "[{\"titleId\":\"" + TITLE + "\",\"quantity\":3,\"unitCost\":50000}]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/sales/goods-receipts/" + id));
        mvc.perform(post("/api/sales/goods-receipts").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":\"" + id + "\",\"clientRequestId\":\"c1\",\"lines\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/sales/goods-receipts").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierId\":\"" + id + "\",\"clientRequestId\":\"c1\",\"lines\":"
                                + "[{\"titleId\":\"" + TITLE + "\",\"quantity\":0,\"unitCost\":1}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/sales/goods-receipts/" + id + "/confirmation").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void banPosTra201VaThieuClientRequestIdHoacPhuongThucSaiBi400() throws Exception {
        UUID id = UUID.randomUUID();
        when(sales.createPosOrder(any())).thenReturn(order(id));
        String lines = "\"lines\":[{\"titleId\":\"" + TITLE + "\",\"quantity\":2}]";
        mvc.perform(post("/api/sales/pos-orders").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{" + lines + ",\"paymentMethod\":\"CASH\",\"clientRequestId\":\"r1\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/sales/orders/" + id))
                .andExpect(jsonPath("$.grandTotal").value(105000));
        mvc.perform(post("/api/sales/pos-orders").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{" + lines + ",\"paymentMethod\":\"CASH\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/sales/pos-orders").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{" + lines + ",\"paymentMethod\":\"COD\",\"clientRequestId\":\"r1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/sales/pos-orders").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"titleId\":\"" + TITLE + "\",\"quantity\":0}],\"paymentMethod\":\"CASH\","
                                + "\"clientRequestId\":\"r1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void banPosCoGiamGiaChuyenTruongGiamGiaXuongService() throws Exception {
        when(sales.createPosOrderWithDiscount(any())).thenReturn(order(UUID.randomUUID()));
        mvc.perform(post("/api/sales/pos-orders/discounted").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"titleId\":\"" + TITLE + "\",\"quantity\":1,"
                                + "\"discount\":{\"type\":\"PERCENT\",\"value\":1000}}],\"paymentMethod\":\"CARD\","
                                + "\"orderDiscount\":{\"type\":\"AMOUNT\",\"value\":5000},\"clientRequestId\":\"r1\"}"))
                .andExpect(status().isCreated());
        ArgumentCaptor<PosOrderRequest> c = ArgumentCaptor.forClass(PosOrderRequest.class);
        verify(sales).createPosOrderWithDiscount(c.capture());
        org.junit.jupiter.api.Assertions.assertEquals("PERCENT", c.getValue().lines().get(0).discount().type());
        org.junit.jupiter.api.Assertions.assertEquals(5000, c.getValue().orderDiscount().value());
        mvc.perform(post("/api/sales/pos-orders/discounted").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"titleId\":\"" + TITLE + "\",\"quantity\":1,"
                                + "\"discount\":{\"type\":\"HALF\",\"value\":1}}],\"paymentMethod\":\"CARD\","
                                + "\"clientRequestId\":\"r1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void docDonVaPhieuIn() throws Exception {
        UUID id = UUID.randomUUID();
        when(sales.getOrder(id)).thenReturn(order(id));
        when(sales.getReceipt(id)).thenReturn(new ReceiptView(id, "POS-1", null, List.of(), 100000, 0, 5000, 0, 105000, 500,
                false, "CASH"));
        when(sales.searchOrders(any())).thenReturn(new SalesPage<>(List.of(order(id)), 1, 0, 20));
        mvc.perform(get("/api/sales/orders/" + id).with(jwt())).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value("POS-1"));
        mvc.perform(get("/api/sales/orders/" + id + "/receipt").with(jwt())).andExpect(status().isOk())
                .andExpect(jsonPath("$.grandTotal").value(105000));
        mvc.perform(get("/api/sales/orders?status=COMPLETED&paymentStatus=PAID&channel=POS&page=0&size=20").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(id.toString()));
        ArgumentCaptor<SalesOrderSearchQuery> q = ArgumentCaptor.forClass(SalesOrderSearchQuery.class);
        verify(sales).searchOrders(q.capture());
        org.junit.jupiter.api.Assertions.assertEquals("PAID", q.getValue().paymentStatus());
    }

    @Test
    void loiNghiepVuCuaSalesServiceDuocChuyenNguyenMa() throws Exception {
        when(sales.createPosOrder(any())).thenThrow(downstream(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK"));
        when(sales.getSettings()).thenThrow(downstream(HttpStatus.FORBIDDEN, "MODULE_NOT_ENABLED"));
        mvc.perform(post("/api/sales/pos-orders").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"titleId\":\"" + TITLE + "\",\"quantity\":1}],\"paymentMethod\":\"CASH\","
                                + "\"clientRequestId\":\"r1\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        mvc.perform(get("/api/sales/settings").with(jwt()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
    }
}
