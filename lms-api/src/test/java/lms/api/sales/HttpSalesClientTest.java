package lms.api.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

/** HttpSalesClient gọi đúng /internal/sales/**, chuyển tiếp token, bỏ trường lạ, chỉ gửi tham số query có giá trị. */
class HttpSalesClientTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://sales")
            .requestInterceptor(new TokenRelayInterceptor());
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpSalesClient client = new HttpSalesClient(builder.build());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void login() {
        Jwt jwt = Jwt.withTokenValue("token-nv").header("alg", "none").subject("u1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void cauHinhVaMatHangDungDuongDanVaChuyenTiepToken() {
        login();
        UUID title = UUID.randomUUID();
        String settings = "{\"vatRateBps\":500,\"pricesIncludeVat\":true,\"returnWindowDays\":7,\"fixedShippingFee\":0,"
                + "\"lowStockThreshold\":5,\"walkInPiiRetentionMonths\":12,\"version\":3,\"truongMoi\":1}";
        String item = "{\"titleId\":\"" + title + "\",\"titleName\":\"Sách\",\"onHand\":4,\"reserved\":1,\"available\":3,"
                + "\"sellingPrice\":null,\"effectivePrice\":90000,\"lowStock\":true}";

        server.expect(requestTo("http://sales/internal/sales/settings")).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-nv"))
                .andRespond(withSuccess(settings, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/sales/settings")).andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.version").value(3))
                .andRespond(withSuccess(settings, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/sales/stock-items?keyword=java&lowStockOnly=true&page=0&size=20"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"items\":[" + item + "],\"total\":1,\"page\":0,\"size\":20}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/sales/stock-items/" + title + "/price"))
                .andExpect(method(HttpMethod.PUT)).andExpect(jsonPath("$.sellingPrice").doesNotExist())
                .andRespond(withSuccess(item, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/sales/stock-adjustments")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.delta").value(-2))
                .andRespond(withSuccess(item, MediaType.APPLICATION_JSON));

        assertEquals(500, client.getSettings().vatRateBps());
        client.updateSettings(new UpdateSalesSettingsRequest(500, true, 7, 0, 5, 12, 3));
        SalesPage<StockItemView> page = client.searchStockItems(new StockItemSearchQuery("java", true, 0, 20));
        assertEquals(1, page.total());
        assertNull(page.items().get(0).sellingPrice());
        assertEquals(90000, client.updateStockItemPrice(title, new UpdateStockItemPriceRequest(null)).effectivePrice());
        client.adjustStock(new StockAdjustmentRequest(title, -2, "Hỏng"));
        server.verify();
    }

    @Test
    void timKiemBoQuaThamSoRongVaTraTrangRongKhiBodyNull() {
        server.expect(requestTo("http://sales/internal/sales/orders?status=COMPLETED&size=10"))
                .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));
        SalesPage<SalesOrderView> page = client.searchOrders(
                new SalesOrderSearchQuery("COMPLETED", " ", null, null, "", null, null, 10));
        assertEquals(List.of(), page.items());
        server.verify();
    }

    @Test
    void nhapKhoVaDonPos() {
        UUID id = UUID.randomUUID();
        String receipt = "{\"id\":\"" + id + "\",\"status\":\"DRAFT\",\"lines\":[],\"totalCost\":0,\"version\":0}";
        String order = "{\"id\":\"" + id + "\",\"orderNo\":\"POS-1\",\"status\":\"COMPLETED\",\"grandTotal\":105000}";
        server.expect(requestTo("http://sales/internal/sales/goods-receipts")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.clientRequestId").value("c1"))
                .andRespond(withSuccess(receipt, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/sales/goods-receipts/" + id + "/confirmation"))
                .andExpect(method(HttpMethod.POST)).andRespond(withSuccess(receipt, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/sales/goods-receipts/" + id + "/cancellation"))
                .andExpect(method(HttpMethod.POST)).andRespond(withSuccess(receipt, MediaType.APPLICATION_JSON));
        // Không giảm giá thì không có khóa giảm giá trong JSON (NON_NULL), để sales-service không báo DISCOUNT_NOT_ALLOWED nhầm.
        server.expect(requestTo("http://sales/internal/sales/pos-orders")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.orderDiscount").doesNotExist())
                .andExpect(jsonPath("$.lines[0].discount").doesNotExist())
                .andRespond(withSuccess(order, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/sales/pos-orders/discounted")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.lines[0].discount.type").value("PERCENT"))
                .andRespond(withSuccess(order, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/sales/orders/" + id + "/receipt")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"orderNo\":\"POS-1\",\"grandTotal\":105000,\"lines\":[]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/sales/orders/" + id + "/contact")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"name\":\"An\",\"phone\":\"0900000000\"}", MediaType.APPLICATION_JSON));

        UUID title = UUID.randomUUID();
        client.createGoodsReceipt(new CreateGoodsReceiptRequest(UUID.randomUUID(),
                List.of(new GoodsReceiptLineRequest(title, 3, 50000)), "c1"));
        client.confirmGoodsReceipt(id);
        client.cancelGoodsReceipt(id);
        client.createPosOrder(new PosOrderRequest(List.of(new PosOrderLineRequest(title, 1, null)), "CASH", null, null,
                null, "r1", null));
        client.createPosOrderWithDiscount(new PosOrderRequest(
                List.of(new PosOrderLineRequest(title, 1, new DiscountRequest("PERCENT", 1000))), "CASH", null, null,
                null, "r2", UUID.randomUUID()));
        assertEquals(105000, client.getReceipt(id).grandTotal());
        assertEquals("An", client.getOrderContact(id).name());
        server.verify();
    }

    @Test
    void loi4xxCuaServiceDuocNemRaDeApiExceptionHandlerChuyenNguyen() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo("http://sales/internal/sales/orders/" + id))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"MODULE_NOT_ENABLED\",\"message\":\"x\",\"details\":[]}"));
        HttpClientErrorException ex = org.junit.jupiter.api.Assertions.assertThrows(HttpClientErrorException.class,
                () -> client.getOrder(id));
        assertEquals(403, ex.getStatusCode().value());
    }
}
