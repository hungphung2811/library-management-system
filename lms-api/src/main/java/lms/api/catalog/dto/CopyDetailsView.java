package lms.api.catalog.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Bản sao kèm tên đầu sách, giá bìa và vị trí kệ (nếu có), trả cho FE khi tra theo id hoặc mã vạch. */
public record CopyDetailsView(UUID id, UUID titleId, String titleName, String barcode, String status,
                              BigDecimal coverPrice, UUID shelfLocationId, String shelfLocationCode) {

    public CopyDetailsView(UUID id, UUID titleId, String titleName, String barcode, String status,
                           BigDecimal coverPrice) {
        this(id, titleId, titleName, barcode, status, coverPrice, null, null);
    }
}
