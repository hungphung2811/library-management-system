package lms.customer.author.client.http;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lms.customer.author.client.AuthorCatalogClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * {@code GET /internal/books/new-authors} và {@code POST /internal/books/lookup} của catalog-service. Chỉ đọc đúng các
 * trường cần ({@code id}, {@code author}); trường khác của catalog (giá bìa, ISBN...) bị bỏ qua. Id được chia lô 100
 * (trần của catalog), nên top 100 đầu sách tốn đúng một lời gọi.
 */
public class HttpAuthorCatalogClient implements AuthorCatalogClient {

    /** Trần số id mỗi lời gọi của catalog ({@code LookupTitlesRequest.MAX_IDS}). */
    static final int MAX_BATCH = 100;

    private final RestClient client;

    public HttpAuthorCatalogClient(RestClient client) {
        this.client = client;
    }

    @Override
    public List<String> newAuthors(String serviceToken, int days, int limit) {
        List<String> body = AuthorDownstream.call("catalog", () -> client.get()
                .uri(b -> b.path("/internal/books/new-authors").queryParam("days", days)
                        .queryParam("limit", limit).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken).retrieve()
                .body(new ParameterizedTypeReference<List<String>>() { }));
        return body.stream().filter(n -> n != null && !n.isBlank()).toList();
    }

    @Override
    public Map<UUID, String> authorsByTitle(String serviceToken, Collection<UUID> titleIds) {
        Map<UUID, String> authors = new HashMap<>();
        if (titleIds == null || titleIds.isEmpty()) {
            return authors;
        }
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(titleIds));
        ids.removeIf(Objects::isNull);
        for (int from = 0; from < ids.size(); from += MAX_BATCH) {
            List<UUID> batch = List.copyOf(ids.subList(from, Math.min(from + MAX_BATCH, ids.size())));
            List<UpstreamTitle> body = AuthorDownstream.call("catalog", () -> client.post()
                    .uri("/internal/books/lookup").header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                    .body(new LookupBody(batch)).retrieve()
                    .body(new ParameterizedTypeReference<List<UpstreamTitle>>() { }));
            for (UpstreamTitle t : body) {
                if (t != null && t.id() != null && t.author() != null && !t.author().isBlank()) {
                    authors.put(t.id(), t.author());
                }
            }
        }
        return authors;
    }

    record LookupBody(List<UUID> ids) {
    }

    /** Chỉ lấy id và tác giả. */
    record UpstreamTitle(UUID id, String author) {
    }
}
