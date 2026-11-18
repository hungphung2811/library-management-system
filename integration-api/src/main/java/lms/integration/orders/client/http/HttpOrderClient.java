package lms.integration.orders.client.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.integration.common.dto.PageView;
import lms.integration.config.DownstreamRestClients;
import lms.integration.orders.client.OrderClient;
import lms.integration.orders.dto.OrderDtos.OrderLineView;
import lms.integration.orders.dto.OrderDtos.OrderView;
import lms.integration.web.IntegrationApiException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Gọi sales-service {@code /internal/sales/orders} (chỉ danh sách và chi tiết) bằng token integration chuyển tiếp.
 * Kiểu nhận không có trường liên hệ/ghi chú nên các trường đó không thể lọt vào {@link OrderView}. Cố ý KHÔNG gọi
 * {@code /{id}/contact}, {@code /{id}/notes}, {@code /{id}/receipt}.
 */
public class HttpOrderClient implements OrderClient {

    private static final String ORDERS = "/internal/sales/orders";

    private final RestClient client;

    public HttpOrderClient(RestClient client) {
        this.client = client;
    }

    @Override
    public PageView<OrderView> list(String status, int page, int size) {
        UpstreamPage upstream = DownstreamRestClients.call(() -> client.get().uri(b -> {
            b.path(ORDERS).queryParam("page", page).queryParam("size", size);
            if (status != null) b.queryParam("status", status);
            return b.build();
        }).accept(MediaType.APPLICATION_JSON).retrieve().body(new ParameterizedTypeReference<UpstreamPage>() { }));
        if (upstream == null) throw IntegrationApiException.serviceUnavailable();
        List<UpstreamOrder> items = upstream.items() == null ? List.of() : upstream.items();
        return new PageView<>(items.stream().map(UpstreamOrder::toView).toList(), upstream.total(), upstream.page(),
                upstream.size());
    }

    @Override
    public OrderView get(UUID id) {
        UpstreamOrder order = DownstreamRestClients.call(() -> client.get().uri(ORDERS + "/{id}", id)
                .accept(MediaType.APPLICATION_JSON).retrieve().body(UpstreamOrder.class));
        if (order == null) throw IntegrationApiException.serviceUnavailable();
        return order.toView();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UpstreamPage(List<UpstreamOrder> items, long total, int page, int size) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UpstreamLine(int lineNo, UUID titleId, String titleName, String isbn, int quantity, long unitPrice,
                        long lineDiscount, long allocatedOrderDiscount, long amount, long vatAmount, long lineTotal) {
        OrderLineView toView() {
            return new OrderLineView(lineNo, titleId, titleName, isbn, quantity, unitPrice, lineDiscount,
                    allocatedOrderDiscount, amount, vatAmount, lineTotal);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UpstreamOrder(UUID id, String orderNo, String channel, String status, String paymentStatus,
                         String paymentMethod, String fulfilment, String currency, int vatRateBps,
                         boolean pricesIncludeVat, long subtotal, long discountTotal, long vatTotal, long shippingFee,
                         long grandTotal, Instant createdAt, Instant confirmedAt, Instant paidAt,
                         Instant completedAt, Instant cancelledAt, long version, List<UpstreamLine> lines) {
        OrderView toView() {
            return new OrderView(id, orderNo, channel, status, paymentStatus, paymentMethod, fulfilment, currency,
                    vatRateBps, pricesIncludeVat, subtotal, discountTotal, vatTotal, shippingFee, grandTotal,
                    createdAt, confirmedAt, paidAt, completedAt, cancelledAt, version,
                    lines == null ? List.of() : lines.stream().map(UpstreamLine::toView).toList());
        }
    }
}
