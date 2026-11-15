package lms.circulation.borrowing.application;

import lms.circulation.borrowing.domain.BorrowingId;
import lms.circulation.borrowing.domain.BorrowingNotFoundException;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.BorrowingRepository;
import lms.circulation.borrowing.domain.CopyStatus;
import lms.circulation.borrowing.domain.ItemCondition;
import lms.circulation.borrowing.domain.NotFoundException;
import lms.circulation.fine.application.AssessFineCommand;
import lms.circulation.fine.application.FineResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC07 - Nhận trả sách (QĐ3, QĐ4, QĐ8). Ghi ngày trả, lập phí trễ/hỏng, rồi đánh dấu bản sao AVAILABLE hoặc
 * DAMAGED ở catalog (bước cuối; lỗi catalog thì rollback cả phiếu và phạt). Catalog phát CopyAvailableEvent khi
 * bản sao về AVAILABLE để giữ cho người đầu hàng đợi (QĐ9).
 */
public class ReturnBookUseCase {

    private final BorrowingRepository repository;
    private final SaveBorrowingPort savePort;
    private final CopyInventoryPort inventory;
    private final FineLedgerPort fines;
    private final PublishBorrowingEventPort eventPort;
    private final Clock clock;

    public ReturnBookUseCase(BorrowingRepository repository, SaveBorrowingPort savePort, CopyInventoryPort inventory,
                             FineLedgerPort fines, PublishBorrowingEventPort eventPort, Clock clock) {
        this.repository = repository;
        this.savePort = savePort;
        this.inventory = inventory;
        this.fines = fines;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    @Transactional
    public BorrowingResponse execute(ReturnBookCommand command) {
        UUID copyId = CopyResolver.resolve(command.bookCopyId(), command.barcode(), inventory);
        ItemCondition condition = command.condition() == null ? ItemCondition.GOOD : command.condition();
        LocalDate today = LocalDate.now(clock);

        BorrowingRecord record = command.borrowingId() != null
                ? repository.findByIdForUpdate(new BorrowingId(command.borrowingId()))
                        .orElseThrow(() -> new BorrowingNotFoundException(command.borrowingId()))
                : repository.findOpenByCopyIdForUpdate(copyId).orElseThrow(() -> new NotFoundException(
                        "COPY_NOT_ON_LOAN", "Bản sao không ở trạng thái đang mượn: " + copyId));

        long overdueDays = record.overdueDays(today);
        BigDecimal coverPrice = condition == ItemCondition.DAMAGED
                ? CopyResolver.requireCopy(copyId, inventory).coverPrice() : null;

        record.returnCopy(copyId, condition, today);
        BorrowingRecord saved = savePort.save(record);

        List<FineResponse> issued = fines.assess(new AssessFineCommand(record.userId(), record.id().value(), copyId,
                coverPrice, overdueDays, condition == ItemCondition.DAMAGED
                        ? AssessFineCommand.Outcome.RETURNED_DAMAGED : AssessFineCommand.Outcome.RETURNED_GOOD));

        inventory.changeStatus(copyId, condition == ItemCondition.DAMAGED ? CopyStatus.DAMAGED : CopyStatus.AVAILABLE);

        record.pullEvents().forEach(eventPort::publish);
        return BorrowingMapper.toResponse(saved, issued);
    }
}
