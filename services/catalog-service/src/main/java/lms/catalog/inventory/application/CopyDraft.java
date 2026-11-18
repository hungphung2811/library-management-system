package lms.catalog.inventory.application;

import java.time.LocalDate;

/**
 * Dữ liệu một bản sao cần nhập hàng loạt (chưa kiểm). Kiểu liệt kê và ngày ở dạng chuỗi/{@link LocalDate} thô để tầng
 * gọi (catalog) không phải dùng domain của inventory; {@link RegisterCopiesInBatchUseCase#check} kiểm bằng domain.
 *
 * @param acquisitionSource PURCHASE, DONATION, EXCHANGE, OTHER (rỗng = không có)
 * @param conditionGrade    NEW, GOOD, FAIR, WORN (rỗng = không có)
 */
public record CopyDraft(String barcode, java.util.UUID shelfLocationId, LocalDate acquiredDate,
                        String acquisitionSource, Long purchasePrice, String conditionGrade, String conditionNote,
                        LocalDate lastInventoryDate) {
}
