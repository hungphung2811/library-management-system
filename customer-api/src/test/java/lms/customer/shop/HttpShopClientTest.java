package lms.customer.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import lms.customer.shop.client.http.HttpShopClient;
import lms.customer.shop.dto.ShopDtos.ContactRequestBody;
import lms.customer.shop.dto.ShopDtos.ContactTopic;
import lms.customer.shop.dto.ShopDtos.Fulfilment;
import lms.customer.shop.dto.ShopDtos.LineRequest;
import lms.customer.shop.dto.ShopDtos.OrderPageView;
import lms.customer.shop.dto.ShopDtos.OrderView;
import lms.customer.shop.dto.ShopDtos.PaymentMethod;
import lms.customer.shop.dto.ShopDtos.PlaceOrderRequest;
import lms.customer.shop.dto.ShopDtos.RecipientRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Stub sales: token NGƯỜI DÙNG được chuyển tiếp, thân gửi đi là allowlist, phản hồi lọc từng trường + cancellable. */
class HttpShopClientTest {

    private final UUID title = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://sales");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpShopClient client = new HttpShopClient(builder.build());
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private String upstream(String status) {
        return "{\"id\":\"" + orderId + "\",\"orderNo\":\"ON-1\",\"status\":\"" + status + "\",\"paymentStatus\":\"UNPAID\","
                + "\"paymentMethod\":\"COD\",\"fulfilment\":\"DELIVERY\","
                + "\"recipient\":{\"name\":\"An\",\"phone\":\"09\",\"address\":\"HN\",\"internalNote\":\"x\"},"
                + "\"currency\":\"VND\",\"subtotal\":100,\"discountTotal\":0,\"vatTotal\":0,\"shippingFee\":0,"
                + "\"grandTotal\":100,\"createdAt\":\"2026-11-17T01:00:00Z\",\"confirmedAt\":null,"
                + "\"customerUserId\":\"u\",\"version\":3,\"tenantId\":\"t\",\"channel\":\"ONLINE\",\"cancelReason\":\"nội bộ\","
                + "\"lines\":[{\"titleId\":\"" + title + "\",\"titleName\":\"Sách\",\"quantity\":1,\"unitPrice\":100,"
                + "\"lineTotal\":100,\"isbn\":\"9\",\"lineDiscount\":5}]}";
    }

    @Test
    void datHangChuyenTokenNguoiDungVaGuiBodyAllowlist() throws Exception {
        PlaceOrderRequest request = new PlaceOrderRequest(List.of(new LineRequest(title, 2)), Fulfilment.DELIVERY,
                PaymentMethod.COD, new RecipientRequest(" An ", "0900", "  "), requestId);
        server.expect(requestTo("http://sales/internal/sales/shop/orders")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer user-token"))
                .andExpect(content().json("{\"lines\":[{\"titleId\":\"" + title + "\",\"quantity\":2}],"
                        + "\"fulfilment\":\"DELIVERY\",\"paymentMethod\":\"COD\","
                        + "\"recipient\":{\"name\":\"An\",\"phone\":\"0900\",\"address\":null},"
                        + "\"clientRequestId\":\"" + requestId + "\"}", true))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body(upstream("PENDING_CONFIRMATION")));
        OrderView view = client.placeOrder("user-token", request);
        assertTrue(view.cancellable());
        assertEquals(orderId, view.id());
        // Trường nội bộ thừa của sales không bao giờ ra DTO công khai.
        assertThat(json.writeValueAsString(view)).doesNotContain("customerUserId", "version", "tenantId", "channel",
                "cancelReason", "nội bộ", "isbn", "lineDiscount", "internalNote");
        server.verify();
    }

    @Test
    void cancellableChiKhiChoXacNhan() {
        for (String status : new String[] {"CONFIRMED", "READY_FOR_PICKUP", "SHIPPING", "COMPLETED", "CANCELLED"}) {
            server.reset();
            server.expect(requestTo("http://sales/internal/sales/shop/orders/" + orderId))
                    .andRespond(withSuccess(upstream(status), MediaType.APPLICATION_JSON));
            assertFalse(client.myOrder("t", orderId).cancellable(), status);
        }
    }

    @Test
    void cancellableChoDonChoThanhToanVaDonChoXacNhanChuaTra() {
        // MockRestServiceServer: khai báo đủ kỳ vọng (theo thứ tự) TRƯỚC khi gọi, không thêm kỳ vọng sau khi đã có lời gọi
        server.expect(requestTo("http://sales/internal/sales/shop/orders/" + orderId))
                .andRespond(withSuccess(upstream("AWAITING_PAYMENT"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/sales/shop/orders/" + orderId))
                .andRespond(withSuccess(upstream("PENDING_CONFIRMATION").replace("\"UNPAID\"", "\"PAID\""),
                        MediaType.APPLICATION_JSON));
        assertTrue(client.myOrder("t", orderId).cancellable());
        // đơn chờ xác nhận nhưng đã trả tiền thì không hủy được
        assertFalse(client.myOrder("t", orderId).cancellable());
        server.verify();
    }

    @Test
    void thanhToanChuyenTokenKhachVaChiLayTruongAllowlist() throws Exception {
        server.expect(requestTo("http://sales/internal/sales/shop/orders/" + orderId + "/payment"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer user-token"))
                .andRespond(withSuccess("{\"orderId\":\"" + orderId + "\",\"orderNo\":\"ON-1\","
                        + "\"orderStatus\":\"AWAITING_PAYMENT\",\"paymentStatus\":\"UNPAID\",\"paymentMethod\":\"SEPAY\","
                        + "\"tenantId\":\"t\",\"version\":2,"
                        + "\"intent\":{\"status\":\"PENDING\",\"provider\":\"SEPAY\",\"qrPayload\":\"https://qr/x\","
                        + "\"amount\":100,\"currency\":\"VND\",\"reference\":\"ON1\",\"expiresAt\":\"2026-11-17T01:15:00Z\","
                        + "\"paidAt\":null,\"id\":\"i1\",\"providerTxnId\":\"tx\",\"transactionRef\":\"tr\","
                        + "\"tenderedAmount\":5,\"changeDue\":0}}", MediaType.APPLICATION_JSON));
        var view = client.orderPayment("user-token", orderId);
        assertEquals(orderId, view.orderId());
        assertEquals("SEPAY", view.paymentMethod());
        assertEquals("PENDING", view.intent().status());
        assertEquals("https://qr/x", view.intent().qrPayload());
        assertEquals(100, view.intent().amount());
        assertThat(json.writeValueAsString(view)).doesNotContain("tenantId", "version", "providerTxnId",
                "transactionRef", "tenderedAmount", "changeDue", "\"id\"");
        server.verify();
    }

    @Test
    void thanhToanDonKhongQuaCongCoIntentNull() {
        server.expect(requestTo("http://sales/internal/sales/shop/orders/" + orderId + "/payment"))
                .andRespond(withSuccess("{\"orderId\":\"" + orderId + "\",\"orderNo\":\"ON-1\","
                        + "\"orderStatus\":\"PENDING_CONFIRMATION\",\"paymentStatus\":\"UNPAID\","
                        + "\"paymentMethod\":\"COD\",\"intent\":null}", MediaType.APPLICATION_JSON));
        assertThat(client.orderPayment("t", orderId).intent()).isNull();
        server.verify();
    }

    @Test
    void phuongThucThanhToanChuyenTokenKhachVaBoTruongLa() throws Exception {
        server.expect(requestTo("http://sales/internal/sales/shop/payment-options"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer user-token"))
                .andRespond(withSuccess("{\"methods\":[{\"method\":\"COD\",\"fulfilments\":[\"DELIVERY\"],\"secret\":1},"
                        + "{\"method\":\"SEPAY\",\"fulfilments\":[\"PICKUP\",\"DELIVERY\"],\"intentTtlMinutes\":15}],"
                        + "\"deliveryFee\":30000,\"apiKey\":\"x\"}", MediaType.APPLICATION_JSON));
        var view = client.paymentOptions("user-token");
        assertEquals(2, view.methods().size());
        assertEquals(30000, view.deliveryFee());
        assertEquals(15, view.methods().get(1).intentTtlMinutes());
        String out = json.writeValueAsString(view);
        assertThat(out).doesNotContain("secret", "apiKey");
        // COD không có intentTtlMinutes thì không xuất hiện
        assertThat(json.readTree(out).get("methods").get(0).has("intentTtlMinutes")).isFalse();
        server.verify();
    }

    @Test
    void danhSachGuiPageSizeVaTokenNguoiDung() {
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("page", "0"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("size", "50"))
                .andExpect(header("Authorization", "Bearer user-token"))
                .andRespond(withSuccess("{\"items\":[" + upstream("CONFIRMED") + "],\"total\":1,\"page\":0,\"size\":50}",
                        MediaType.APPLICATION_JSON));
        OrderPageView page = client.myOrders("user-token", 0, 50);
        assertEquals(1, page.items().size());
        assertEquals(1, page.total());
    }

    @Test
    void demDonChuyenTokenNguoiDungVaChiLayHaiSo() {
        server.expect(requestTo("http://sales/internal/sales/shop/orders/count")).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer user-token"))
                .andRespond(withSuccess("{\"total\":7,\"pending\":2,\"customerUserId\":\"u\",\"tenantId\":\"t\"}",
                        MediaType.APPLICATION_JSON));
        var counts = client.myOrderCounts("user-token");
        assertEquals(7, counts.total());
        assertEquals(2, counts.pending());
        server.verify();
    }

    @Test
    void huyGuiLyDoChiKhiCoVaChuyenTokenNguoiDung() {
        server.expect(requestTo("http://sales/internal/sales/shop/orders/" + orderId + "/cancellation"))
                .andExpect(header("Authorization", "Bearer user-token"))
                .andExpect(content().json("{\"reason\":\"đổi ý\"}", true))
                .andRespond(withSuccess(upstream("CANCELLED"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/sales/shop/orders/" + orderId + "/cancellation"))
                .andExpect(content().json("{}", true))
                .andRespond(withSuccess(upstream("CANCELLED"), MediaType.APPLICATION_JSON));
        client.cancelMyOrder("user-token", orderId, "đổi ý");
        client.cancelMyOrder("user-token", orderId, null);
        server.verify();
    }

    @Test
    void guiLienHeChuyenTokenKhachVaChiGuiSauTruongAllowlistConsentLuonTrue() throws Exception {
        server.expect(requestTo("http://sales/internal/sales/shop/contact-requests"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer user-token"))
                .andExpect(request -> {
                    var node = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request)
                            .getBodyAsString());
                    var names = new java.util.TreeSet<String>();
                    node.fieldNames().forEachRemaining(names::add);
                    assertThat(names).containsExactly("consent", "email", "fullName", "message", "phone", "topic");
                    assertEquals("PRODUCT", node.get("topic").asText());
                    assertEquals("An", node.get("fullName").asText()); // đã cắt khoảng trắng
                    assertEquals("0900000000", node.get("phone").asText());
                    assertTrue(node.get("consent").asBoolean());
                }).andRespond(withStatus(HttpStatus.CREATED));
        client.submitContactRequest("user-token", new ContactRequestBody(ContactTopic.PRODUCT, " An ", "an@x.vn",
                " 0900000000 ", "Xin chào", true));
        server.verify();
    }

    @Test
    void guiLienHeBoQuaDienThoaiRongVaLoiCuaSalesDuocNemNguyen() {
        server.expect(requestTo("http://sales/internal/sales/shop/contact-requests"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("phone"))))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"SHOP_CONTACT_LIMIT_EXCEEDED\"}"));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.client.HttpClientErrorException.class,
                () -> client.submitContactRequest("user-token", new ContactRequestBody(ContactTopic.OTHER, "An",
                        "an@x.vn", null, "Hi", true)));
    }
}
