package lms.customer.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lms.customer.shop.client.http.HttpShopInvoiceClient;
import lms.customer.shop.dto.ShopInvoiceDtos.InvoiceBuyerRequest;
import lms.customer.shop.dto.ShopInvoiceDtos.InvoiceRequestBody;
import lms.customer.shop.dto.ShopInvoiceDtos.InvoiceRequestStatusView;
import lms.customer.web.PublicApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Stub sales: token NGƯỜI DÙNG được chuyển tiếp, thân gửi đi là allowlist, phản hồi chỉ lấy orderId và status. */
class HttpShopInvoiceClientTest {

    private final UUID orderId = UUID.randomUUID();
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://sales");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpShopInvoiceClient client = new HttpShopInvoiceClient(builder.build());

    private static InvoiceRequestBody body() {
        return new InvoiceRequestBody(new InvoiceBuyerRequest("An", "Công ty A", "0123456789", "1 Lê Lợi", "an@a.vn"));
    }

    @Test
    void chuyenTokenNguoiDungVaThanAllowlistRoiLocPhanHoi() throws Exception {
        server.expect(requestTo("http://sales/internal/sales/shop/orders/" + orderId + "/invoice-request"))
                .andExpect(method(HttpMethod.PUT)).andExpect(header("Authorization", "Bearer user-token"))
                .andExpect(content().json("{\"buyer\":{\"name\":\"An\",\"company\":\"Công ty A\",\"taxCode\":\"0123456789\","
                        + "\"address\":\"1 Lê Lợi\",\"email\":\"an@a.vn\"}}", true))
                .andRespond(withSuccess("{\"orderId\":\"" + orderId + "\",\"status\":\"OPEN\",\"version\":4,"
                        + "\"buyerName\":\"An\",\"tenantId\":\"t\"}", MediaType.APPLICATION_JSON));
        InvoiceRequestStatusView view = client.saveInvoiceRequest("user-token", orderId, body());
        assertThat(view).isEqualTo(new InvoiceRequestStatusView(orderId, "OPEN"));
        assertThat(new ObjectMapper().writeValueAsString(view)).doesNotContain("version", "buyerName", "tenantId");
        server.verify();
    }

    @Test
    void phanHoiThieuTruongThiLaLoiPhiaSauChungKhongLoDuLieu() {
        server.expect(requestTo("http://sales/internal/sales/shop/orders/" + orderId + "/invoice-request"))
                .andRespond(withSuccess("{\"orderId\":null}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.saveInvoiceRequest("t", orderId, body())).isInstanceOf(PublicApiException.class)
                .extracting(e -> ((PublicApiException) e).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void loiCuaSalesDuocNemNguyenDeBoLocLoi() {
        server.expect(requestTo("http://sales/internal/sales/shop/orders/" + orderId + "/invoice-request"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"ORDER_NOT_FOUND\",\"message\":\"x\"}"));
        assertThatThrownBy(() -> client.saveInvoiceRequest("t", orderId, body()))
                .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
    }
}
