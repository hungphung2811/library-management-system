package lms.api.catalog.client;

import lms.api.catalog.dto.AddCopyRequest;
import lms.api.catalog.dto.CopyDetailsView;
import lms.api.catalog.dto.CopyStatus;
import lms.api.catalog.dto.CopyView;
import lms.api.catalog.dto.CreateTitleRequest;
import lms.api.catalog.dto.TitleView;
import lms.api.catalog.dto.UpdateTitleRequest;
import java.util.List;
import java.util.UUID;

/** Cổng sang catalog-service (API nội bộ). Lỗi 4xx của service được ném dưới dạng RestClientResponseException. */
public interface CatalogClient {

    List<TitleView> searchTitles(String keyword, boolean onlyAvailable, String tenantId);

    /** Như searchTitles nhưng lọc thêm theo thể loại và/hoặc nhà xuất bản (null = không lọc). */
    default List<TitleView> searchTitles(String keyword, boolean onlyAvailable, String tenantId, UUID categoryId,
                                         UUID publisherId) {
        return searchTitles(keyword, onlyAvailable, tenantId);
    }

    TitleView getTitle(UUID id);

    TitleView createTitle(CreateTitleRequest request);

    TitleView updateTitle(UUID id, UpdateTitleRequest request);

    lms.api.catalog.dto.TitlePreviewView getPreview(UUID id);

    lms.api.catalog.dto.TitlePreviewView updatePreview(UUID id, lms.api.catalog.dto.UpdateTitlePreviewRequest request);

    List<CopyView> listCopies(UUID titleId, String tenantId);

    CopyView addCopy(AddCopyRequest request);

    CopyDetailsView getCopy(UUID id);

    CopyDetailsView getCopyByBarcode(String barcode);

    CopyView changeCopyStatus(UUID id, CopyStatus status);

    /** Gán/đổi/gỡ vị trí kệ của bản sao (shelfLocationId null = gỡ). */
    CopyView moveCopyShelfLocation(UUID id, UUID shelfLocationId);

    /** Thay toàn bộ thông tin nhập kho và tình trạng của bản sao. */
    CopyView updateCopyAcquisition(UUID id, lms.api.catalog.dto.UpdateCopyAcquisitionRequest request);
}
