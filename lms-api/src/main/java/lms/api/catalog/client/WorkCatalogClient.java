package lms.api.catalog.client;

import java.util.UUID;
import lms.api.catalog.dto.WorkDtos.MergeWorksRequest;
import lms.api.catalog.dto.WorkDtos.SaveWorkRequest;
import lms.api.catalog.dto.WorkDtos.WorkDetailView;
import lms.api.catalog.dto.WorkDtos.WorkPageView;
import lms.api.catalog.dto.WorkDtos.WorkSearchQuery;

/** Cổng sang /internal/works của catalog-service (ADR-0026). Lỗi 4xx của service ném dưới dạng RestClientResponseException. */
public interface WorkCatalogClient {

    WorkPageView search(WorkSearchQuery query);

    WorkDetailView get(UUID id);

    WorkDetailView create(SaveWorkRequest request);

    WorkDetailView update(UUID id, SaveWorkRequest request);

    WorkDetailView merge(UUID targetId, MergeWorksRequest request);
}
