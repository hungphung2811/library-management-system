package lms.circulation.borrowing.application;

import lms.circulation.borrowing.domain.CopyStatus;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Cổng sang catalog-service (chủ sở hữu trạng thái bản sao): tra cứu đồng bộ để kiểm tra trước khi mượn và lấy
 * giá bìa; đổi trạng thái idempotent qua PUT /internal/book-copies/{id}/status.
 */
public interface CopyInventoryPort {

    Optional<CopyInfo> findById(UUID copyId);

    Optional<CopyInfo> findByBarcode(String barcode);

    void changeStatus(UUID copyId, CopyStatus target);

    /**
     * conditionGrade (NEW, GOOD, FAIR, WORN), conditionNote, purchasePrice là của chính bản sao (catalog trả thêm, null nếu
     * chưa ghi nhận): chụp tình trạng lúc mượn và tính đền bù theo giá mua. coverPrice là giá bìa của đầu sách.
     */
    record CopyInfo(UUID id, UUID titleId, String titleName, String barcode, CopyStatus status, BigDecimal coverPrice,
                    String conditionGrade, String conditionNote, BigDecimal purchasePrice) {

        public CopyInfo(UUID id, UUID titleId, String titleName, String barcode, CopyStatus status, BigDecimal coverPrice) {
            this(id, titleId, titleName, barcode, status, coverPrice, null, null, null);
        }
    }
}
