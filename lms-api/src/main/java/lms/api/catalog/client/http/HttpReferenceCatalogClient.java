package lms.api.catalog.client.http;

import lms.api.catalog.client.ReferenceCatalogClient;
import lms.api.catalog.dto.ReferenceOptionView;
import lms.api.catalog.dto.ReferencePageView;
import lms.api.catalog.dto.ReferenceSearchQuery;
import lms.api.catalog.dto.ReferenceView;
import lms.api.catalog.dto.SaveReferenceRequest;
import lms.api.catalog.dto.SaveShelfLocationRequest;
import lms.api.catalog.dto.ShelfLocationOptionView;
import lms.api.catalog.dto.ShelfLocationPageView;
import lms.api.catalog.dto.ShelfLocationView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/** Gọi /internal/{authors,publishers,categories,shelf-locations} của catalog-service. */
public class HttpReferenceCatalogClient implements ReferenceCatalogClient {

    private static final String SHELF = "shelf-locations";

    private final RestClient client;

    public HttpReferenceCatalogClient(RestClient client) {
        this.client = client;
    }

    private static UriBuilder withQuery(UriBuilder b, String path, ReferenceSearchQuery q) {
        b.path(path);
        if (q.keyword() != null && !q.keyword().isBlank()) b.queryParam("keyword", q.keyword());
        if (q.active() != null) b.queryParam("active", q.active());
        if (q.parentId() != null) b.queryParam("parentId", q.parentId());
        if (q.page() != null) b.queryParam("page", q.page());
        if (q.size() != null) b.queryParam("size", q.size());
        return b;
    }

    @Override
    public ReferencePageView search(String resource, ReferenceSearchQuery query) {
        return client.get().uri(b -> withQuery(b, "/internal/" + resource, query).build()).retrieve()
                .body(ReferencePageView.class);
    }

    @Override
    public List<ReferenceOptionView> options(String resource, String keyword) {
        List<ReferenceOptionView> r = client.get().uri(b -> {
            b.path("/internal/" + resource + "/options");
            if (keyword != null && !keyword.isBlank()) b.queryParam("keyword", keyword);
            return b.build();
        }).retrieve().body(new ParameterizedTypeReference<>() { });
        return r == null ? List.of() : r;
    }

    @Override
    public ReferenceView get(String resource, UUID id) {
        return client.get().uri("/internal/" + resource + "/{id}", id).retrieve().body(ReferenceView.class);
    }

    @Override
    public ReferenceView create(String resource, SaveReferenceRequest request) {
        return client.post().uri("/internal/" + resource).body(request).retrieve().body(ReferenceView.class);
    }

    @Override
    public ReferenceView update(String resource, UUID id, SaveReferenceRequest request) {
        return client.put().uri("/internal/" + resource + "/{id}", id).body(request).retrieve()
                .body(ReferenceView.class);
    }

    @Override
    public ReferenceView setStatus(String resource, UUID id, boolean active) {
        return client.put().uri("/internal/" + resource + "/{id}/status", id).body(Map.of("active", active))
                .retrieve().body(ReferenceView.class);
    }

    @Override
    public ShelfLocationPageView searchShelfLocations(ReferenceSearchQuery query) {
        return client.get().uri(b -> withQuery(b, "/internal/" + SHELF, query).build()).retrieve()
                .body(ShelfLocationPageView.class);
    }

    @Override
    public List<ShelfLocationOptionView> shelfLocationOptions(String keyword) {
        List<ShelfLocationOptionView> r = client.get().uri(b -> {
            b.path("/internal/" + SHELF + "/options");
            if (keyword != null && !keyword.isBlank()) b.queryParam("keyword", keyword);
            return b.build();
        }).retrieve().body(new ParameterizedTypeReference<>() { });
        return r == null ? List.of() : r;
    }

    @Override
    public ShelfLocationView getShelfLocation(UUID id) {
        return client.get().uri("/internal/" + SHELF + "/{id}", id).retrieve().body(ShelfLocationView.class);
    }

    @Override
    public ShelfLocationView createShelfLocation(SaveShelfLocationRequest request) {
        return client.post().uri("/internal/" + SHELF).body(request).retrieve().body(ShelfLocationView.class);
    }

    @Override
    public ShelfLocationView updateShelfLocation(UUID id, SaveShelfLocationRequest request) {
        return client.put().uri("/internal/" + SHELF + "/{id}", id).body(request).retrieve()
                .body(ShelfLocationView.class);
    }

    @Override
    public ShelfLocationView setShelfLocationStatus(UUID id, boolean active) {
        return client.put().uri("/internal/" + SHELF + "/{id}/status", id).body(Map.of("active", active))
                .retrieve().body(ShelfLocationView.class);
    }
}
