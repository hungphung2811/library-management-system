package lms.catalog.inventory.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** Thông tin bản sao kèm tên và giá bìa của đầu sách (circulation dùng để tính phí phạt, QĐ4). */
public record CopyDetails(UUID id, UUID titleId, String titleName, String barcode, CopyStatus status,
                          BigDecimal coverPrice) {
}
