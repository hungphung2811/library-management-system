package lms.customer.author.client.http;

import java.util.List;
import java.util.UUID;
import lms.customer.author.client.TopSoldClient;
import lms.customer.author.dto.AuthorDtos.TopSoldItem;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/** {@code GET /internal/sales/storefront/top-sold?days&limit} của sales-service: chỉ đọc {@code titleId}, {@code quantity}. */
public class HttpTopSoldClient implements TopSoldClient {

    private final RestClient client;

    public HttpTopSoldClient(RestClient client) {
        this.client = client;
    }

    @Override
    public List<TopSoldItem> topSold(String serviceToken, int days, int limit) {
        List<UpstreamTopSold> body = AuthorDownstream.call("sales", () -> client.get()
                .uri(b -> b.path("/internal/sales/storefront/top-sold").queryParam("days", days)
                        .queryParam("limit", limit).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken).retrieve()
                .body(new ParameterizedTypeReference<List<UpstreamTopSold>>() { }));
        return body.stream().filter(i -> i != null && i.titleId() != null)
                .map(i -> new TopSoldItem(i.titleId(), i.quantity())).toList();
    }

    record UpstreamTopSold(UUID titleId, long quantity) {
    }
}
