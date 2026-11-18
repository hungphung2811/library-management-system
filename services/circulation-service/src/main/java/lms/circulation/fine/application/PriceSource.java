package lms.circulation.fine.application;

import java.util.UUID;

/**
 * Cổng nguồn giá gốc để tính đền bù hỏng/mất. Adapter hiện tại (ở borrowing) hỏi catalog: giá bìa của đầu sách và giá
 * mua của bản sao. Khi có mô hình sách ba tầng (giá theo ấn bản) chỉ thay adapter; logic chọn nguồn và tính phạt không đổi.
 */
public interface PriceSource {

    /** Các giá khả dụng của bản sao. Ném lỗi nghiệp vụ nếu không tìm thấy bản sao. */
    Prices candidates(UUID bookCopyId);

    /** Giá bìa (đầu sách) và giá mua (bản sao); null hoặc không dương coi như không có. */
    record Prices(java.math.BigDecimal coverPrice, java.math.BigDecimal purchasePrice) {
    }
}
