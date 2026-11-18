package lms.api.catalog.client.http;

import java.util.UUID;
import lms.api.catalog.client.WorkCatalogClient;
import lms.api.catalog.dto.WorkDtos.MergeWorksRequest;
import lms.api.catalog.dto.WorkDtos.SaveWorkRequest;
import lms.api.catalog.dto.WorkDtos.WorkDetailView;
import lms.api.catalog.dto.WorkDtos.WorkPageView;
import lms.api.catalog.dto.WorkDtos.WorkSearchQuery;
import org.springframework.web.client.RestClient;

/** Gọi /internal/works của catalog-service. */
public class HttpWorkCatalogClient implements WorkCatalogClient {

    private final RestClient client;

    public HttpWorkCatalogClient(RestClient client) {
        this.client = client;
    }

    @Override
    public WorkPageView search(WorkSearchQuery q) {
        return client.get().uri(b -> {
            b.path("/internal/works");
            if (q.keyword() != null && !q.keyword().isBlank()) b.queryParam("keyword", q.keyword());
            if (q.seriesId() != null) b.queryParam("seriesId", q.seriesId());
            if (q.categoryId() != null) b.queryParam("categoryId", q.categoryId());
            if (q.authorId() != null) b.queryParam("authorId", q.authorId());
            if (q.page() != null) b.queryParam("page", q.page());
            if (q.size() != null) b.queryParam("size", q.size());
            return b.build();
        }).retrieve().body(WorkPageView.class);
    }

    @Override
    public WorkDetailView get(UUID id) {
        return client.get().uri("/internal/works/{id}", id).retrieve().body(WorkDetailView.class);
    }

    @Override
    public WorkDetailView create(SaveWorkRequest request) {
        return client.post().uri("/internal/works").body(request).retrieve().body(WorkDetailView.class);
    }

    @Override
    public WorkDetailView update(UUID id, SaveWorkRequest request) {
        return client.put().uri("/internal/works/{id}", id).body(request).retrieve().body(WorkDetailView.class);
    }

    @Override
    public WorkDetailView merge(UUID targetId, MergeWorksRequest request) {
        return client.post().uri("/internal/works/{id}/merge", targetId).body(request).retrieve()
                .body(WorkDetailView.class);
    }
}
