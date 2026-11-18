package lms.circulation.borrowing.application;

import lms.circulation.borrowing.domain.BorrowingItem;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.BorrowingRejectedException;
import lms.circulation.borrowing.domain.BorrowingRepository;
import lms.circulation.borrowing.domain.CopyStatus;
import lms.circulation.borrowing.domain.ItemCondition;
import lms.circulation.borrowing.domain.NotFoundException;
import lms.circulation.fine.application.FineRefundResponse;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi nhận tìm lại sách đã báo mất: tìm lượt mượn LOST gần nhất của bản sao, kiểm bản sao đang LOST ở catalog, rồi tạo
 * đề nghị hoàn (fine kiểm hạn foundWindowDays, mỗi khoản một lần). Không đổi trạng thái bản sao ở bước này; chỉ khi người
 * khác duyệt thì mới đưa bản sao về kho. Chỉ ghi circulation_db.
 */
public class ReportFoundUseCase {

    private final BorrowingRepository repository;
    private final CopyInventoryPort inventory;
    private final RefundRequestPort refunds;

    public ReportFoundUseCase(BorrowingRepository repository, CopyInventoryPort inventory, RefundRequestPort refunds) {
        this.repository = repository;
        this.inventory = inventory;
        this.refunds = refunds;
    }

    @Transactional
    public FineRefundResponse execute(ReportFoundRequest request, UUID requestedBy) {
        UUID copyId = CopyResolver.resolve(request.bookCopyId(), request.barcode(), inventory);
        CopyInventoryPort.CopyInfo copy = CopyResolver.requireCopy(copyId, inventory);
        if (copy.status() != CopyStatus.LOST) {
            throw new BorrowingRejectedException("COPY_NOT_LOST",
                    "Bản sao không ở trạng thái đã mất (hiện " + copy.status() + ")");
        }
        BorrowingRecord record = repository.findLatestLostByCopyId(copyId).orElseThrow(() -> new NotFoundException(
                "LOST_BORROWING_NOT_FOUND", "Không có lượt mượn nào báo mất bản sao này: " + copyId));
        BorrowingItem item = record.items().stream()
                .filter(i -> i.bookCopyId().equals(copyId) && i.condition() == ItemCondition.LOST)
                .findFirst().orElseThrow();
        return refunds.request(record.id().value(), copyId, item.returnDate(), request.restoreStatus(),
                request.checkNote(), request.refundMethod(), request.referenceCode(), requestedBy);
    }
}
