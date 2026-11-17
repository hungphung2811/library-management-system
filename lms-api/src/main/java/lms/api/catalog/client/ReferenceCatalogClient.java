package lms.api.catalog.client;

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
import java.util.UUID;

/**
 * Cổng sang các danh mục chuẩn của catalog-service. {@code resource} là authors, publishers hoặc categories
 * (vị trí kệ có phương thức riêng vì dùng trường code). Lỗi 4xx của service ném dưới dạng RestClientResponseException.
 */
public interface ReferenceCatalogClient {

    ReferencePageView search(String resource, ReferenceSearchQuery query);

    List<ReferenceOptionView> options(String resource, String keyword);

    ReferenceView get(String resource, UUID id);

    ReferenceView create(String resource, SaveReferenceRequest request);

    ReferenceView update(String resource, UUID id, SaveReferenceRequest request);

    ReferenceView setStatus(String resource, UUID id, boolean active);

    ShelfLocationPageView searchShelfLocations(ReferenceSearchQuery query);

    List<ShelfLocationOptionView> shelfLocationOptions(String keyword);

    ShelfLocationView getShelfLocation(UUID id);

    ShelfLocationView createShelfLocation(SaveShelfLocationRequest request);

    ShelfLocationView updateShelfLocation(UUID id, SaveShelfLocationRequest request);

    ShelfLocationView setShelfLocationStatus(UUID id, boolean active);
}
