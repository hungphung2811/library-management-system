package lms.api.catalog.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Bản sao kèm tên đầu sách (ấn bản), giá bìa, vị trí kệ (nếu có) và workId (tác phẩm của ấn bản, null nếu chưa gắn),
 * trả cho FE khi tra theo id hoặc mã vạch.
 */
public record CopyDetailsView(UUID id, UUID titleId, String titleName, String barcode, String status,
                              BigDecimal coverPrice, UUID shelfLocationId, String shelfLocationCode,
                              UUID workId) {

    /** Dạng 8 trường (chưa có workId, ADR-0026). */
    public CopyDetailsView(UUID id, UUID titleId, String titleName, String barcode, String status,
                           BigDecimal coverPrice, UUID shelfLocationId, String shelfLocationCode) {
        this(id, titleId, titleName, barcode, status, coverPrice, shelfLocationId, shelfLocationCode, null);
    }

    public CopyDetailsView(UUID id, UUID titleId, String titleName, String barcode, String status,
                           BigDecimal coverPrice) {
        this(id, titleId, titleName, barcode, status, coverPrice, null, null, null);
    }
}
