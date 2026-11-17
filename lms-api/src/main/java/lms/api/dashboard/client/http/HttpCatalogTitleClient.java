package lms.api.dashboard.client.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.api.dashboard.client.CatalogTitleClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

public class HttpCatalogTitleClient implements CatalogTitleClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TitleRef(UUID id, String name) {
    }

    private final RestClient catalog;

    public HttpCatalogTitleClient(RestClient catalog) {
        this.catalog = catalog;
    }

    @Override
    public Map<UUID, String> names(Collection<UUID> ids) {
        List<TitleRef> refs = catalog.post().uri("/internal/books/lookup").body(Map.of("ids", List.copyOf(ids)))
                .retrieve().body(new ParameterizedTypeReference<List<TitleRef>>() { });
        Map<UUID, String> out = new LinkedHashMap<>();
        if (refs != null) {
            refs.forEach(r -> out.put(r.id(), r.name()));
        }
        return out;
    }
}
