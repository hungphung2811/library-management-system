package lms.integration.inventory.client.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.integration.common.dto.PageView;
import lms.integration.config.DownstreamRestClients;
import lms.integration.inventory.client.InventoryClient;
import lms.integration.inventory.dto.InventoryDtos.InventoryItemView;
import lms.integration.web.IntegrationApiException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Gọi sales-service {@code /internal/sales/stock-items} bằng token integration chuyển tiếp. Chỉ ánh xạ các trường
 * allowlist; costPrice, supplier, binLocation, note... không có trong kiểu nhận nên không thể lọt ra ngoài.
 */
public class HttpInventoryClient implements InventoryClient {

    private static final String ITEMS = "/internal/sales/stock-items";

    private final RestClient client;

    public HttpInventoryClient(RestClient client) {
        this.client = client;
    }

    @Override
    public PageView<InventoryItemView> list(boolean lowStockOnly, int page, int size) {
        UpstreamPage upstream = DownstreamRestClients.call(() -> client.get()
                .uri(b -> b.path(ITEMS).queryParam("lowStockOnly", lowStockOnly).queryParam("page", page)
                        .queryParam("size", size).build())
                .accept(MediaType.APPLICATION_JSON).retrieve().body(new ParameterizedTypeReference<UpstreamPage>() { }));
        if (upstream == null) throw IntegrationApiException.serviceUnavailable();
        List<UpstreamItem> items = upstream.items() == null ? List.of() : upstream.items();
        return new PageView<>(items.stream().map(UpstreamItem::toView).toList(), upstream.total(), upstream.page(),
                upstream.size());
    }

    @Override
    public InventoryItemView get(UUID titleId) {
        UpstreamItem item = DownstreamRestClients.call(() -> client.get().uri(ITEMS + "/{titleId}", titleId)
                .accept(MediaType.APPLICATION_JSON).retrieve().body(UpstreamItem.class));
        if (item == null) throw IntegrationApiException.serviceUnavailable();
        return item.toView();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UpstreamPage(List<UpstreamItem> items, long total, int page, int size) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UpstreamItem(UUID titleId, String titleName, String sku, int onHand, int reserved, int available,
                        Long sellingPrice, Long effectivePrice, boolean lowStock, boolean active,
                        Integer lowStockThreshold, Instant lastRestockedAt) {
        InventoryItemView toView() {
            return new InventoryItemView(titleId, titleName, sku, onHand, reserved, available, sellingPrice,
                    effectivePrice, lowStock, active, lowStockThreshold, lastRestockedAt);
        }
    }
}
