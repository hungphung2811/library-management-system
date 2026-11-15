package lms.api.catalog.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Bản sao kèm tên đầu sách và giá bìa, trả cho FE khi tra theo id hoặc mã vạch. */
public record CopyDetailsView(UUID id, UUID titleId, String titleName, String barcode, String status,
                              BigDecimal coverPrice) {
}
