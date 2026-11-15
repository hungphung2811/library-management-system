package lms.circulation.borrowing.application;

import lms.circulation.borrowing.domain.InvalidBorrowingException;
import lms.circulation.borrowing.domain.NotFoundException;
import java.util.UUID;

/** Xác định bản sao từ id hoặc mã vạch (thủ thư quét mã vạch khi trả/báo mất). */
final class CopyResolver {

    private CopyResolver() {
    }

    static UUID resolve(UUID copyId, String barcode, CopyInventoryPort inventory) {
        if (copyId != null) {
            return copyId;
        }
        if (barcode == null || barcode.isBlank()) {
            throw new InvalidBorrowingException("Cần bookCopyId hoặc barcode của bản sao");
        }
        return inventory.findByBarcode(barcode.trim()).map(CopyInventoryPort.CopyInfo::id)
                .orElseThrow(() -> new NotFoundException("COPY_NOT_FOUND", "Không tìm thấy bản sao có mã vạch " + barcode));
    }

    static CopyInventoryPort.CopyInfo requireCopy(UUID copyId, CopyInventoryPort inventory) {
        return inventory.findById(copyId)
                .orElseThrow(() -> new NotFoundException("COPY_NOT_FOUND", "Không tìm thấy bản sao: " + copyId));
    }
}
