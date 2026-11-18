package lms.circulation.borrowing.infrastructure.integration;

import lms.circulation.borrowing.application.CopyInventoryPort;
import lms.circulation.borrowing.domain.CopyStatus;
import lms.circulation.borrowing.domain.NotFoundException;
import lms.circulation.fine.application.CopyRestorePort;
import lms.circulation.fine.application.PriceSource;
import java.util.UUID;

/**
 * Adapter hiện tại của cổng PriceSource và CopyRestorePort: hỏi/đổi trạng thái bản sao ở catalog qua CopyInventoryPort.
 * Giá theo ấn bản (sách ba tầng) sau này chỉ cần thay PriceSource, không đụng logic phạt.
 */
public class CatalogPriceSourceAdapter implements PriceSource, CopyRestorePort {

    private final CopyInventoryPort inventory;

    public CatalogPriceSourceAdapter(CopyInventoryPort inventory) {
        this.inventory = inventory;
    }

    @Override
    public Prices candidates(UUID bookCopyId) {
        CopyInventoryPort.CopyInfo copy = inventory.findById(bookCopyId).orElseThrow(
                () -> new NotFoundException("COPY_NOT_FOUND", "Không tìm thấy bản sao: " + bookCopyId));
        return new Prices(copy.coverPrice(), copy.purchasePrice());
    }

    @Override
    public void restore(UUID bookCopyId, Target target) {
        inventory.changeStatus(bookCopyId, target == Target.AVAILABLE ? CopyStatus.AVAILABLE : CopyStatus.DAMAGED);
    }
}
