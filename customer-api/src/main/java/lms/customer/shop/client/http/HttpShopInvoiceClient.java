package lms.customer.shop.client.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;
import lms.customer.shop.client.ShopInvoiceClient;
import lms.customer.shop.dto.ShopInvoiceDtos.InvoiceRequestBody;
import lms.customer.shop.dto.ShopInvoiceDtos.InvoiceRequestStatusView;
import lms.customer.web.PublicApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * Gọi {@code /internal/sales/shop/orders/{id}/invoice-request}. Thân gửi đi dựng từ record allowlist; phản hồi đọc vào
 * record riêng rồi ánh xạ từng trường (chỉ orderId và status ra ngoài). Lỗi của sales (404 ORDER_NOT_FOUND cho đơn không
 * phải của khách, 409 INVOICE_ALREADY_EXISTS, 400 INVALID_TAX_CODE...) được ném nguyên để PublicExceptionHandler lọc.
 */
public class HttpShopInvoiceClient implements ShopInvoiceClient {

    private final RestClient client;

    public HttpShopInvoiceClient(RestClient client) {
        this.client = client;
    }

    @Override
    public InvoiceRequestStatusView saveInvoiceRequest(String userToken, UUID orderId, InvoiceRequestBody request) {
        var b = request.buyer();
        UpstreamStatus body = client.put().uri("/internal/sales/shop/orders/{id}/invoice-request", orderId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .body(new Body(new BuyerBody(b.name(), b.company(), b.taxCode(), b.address(), b.email())))
                .retrieve().body(UpstreamStatus.class);
        if (body == null || body.orderId() == null || body.status() == null) {
            throw PublicApiException.serviceUnavailable();
        }
        return new InvoiceRequestStatusView(body.orderId(), body.status());
    }

    record Body(BuyerBody buyer) {
        @Override
        public String toString() {
            return "Body[***]";
        }
    }

    record BuyerBody(String name, String company, String taxCode, String address, String email) {
        @Override
        public String toString() {
            return "BuyerBody[***]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UpstreamStatus(UUID orderId, String status) {
    }
}
