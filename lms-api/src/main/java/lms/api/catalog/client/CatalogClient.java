package lms.api.catalog.client;

import lms.api.catalog.dto.AddCopyRequest;
import lms.api.catalog.dto.CopyDetailsView;
import lms.api.catalog.dto.CopyStatus;
import lms.api.catalog.dto.CopyView;
import lms.api.catalog.dto.CreateTitleRequest;
import lms.api.catalog.dto.TitleView;
import java.util.List;
import java.util.UUID;

/** Cổng sang catalog-service (API nội bộ). Lỗi 4xx của service được ném dưới dạng RestClientResponseException. */
public interface CatalogClient {

    List<TitleView> searchTitles(String keyword, boolean onlyAvailable);

    TitleView getTitle(UUID id);

    TitleView createTitle(CreateTitleRequest request);

    List<CopyView> listCopies(UUID titleId);

    CopyView addCopy(AddCopyRequest request);

    CopyDetailsView getCopy(UUID id);

    CopyDetailsView getCopyByBarcode(String barcode);

    CopyView changeCopyStatus(UUID id, CopyStatus status);
}
