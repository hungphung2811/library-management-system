package lms.circulation.borrowing.application;

import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.BorrowingRepository;
import lms.circulation.borrowing.domain.CopyStatus;
import lms.circulation.borrowing.domain.NotFoundException;
import lms.circulation.fine.application.AssessFineCommand;
import lms.circulation.fine.application.FineResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC08 - Báo mất sách (QĐ4, QĐ8): đóng lượt mượn với tình trạng LOST, lập phí bồi thường theo chính sách đền bù của
 * tenant (mặc định 150% giá bìa, không cộng phí trễ dù đã quá hạn), bản sao sang LOST ở catalog. Giá gốc lấy qua cổng
 * PriceSource của fine (lỗi không tìm thấy bản sao thì rollback như trước).
 */
public class ReportLostUseCase {

    private final BorrowingRepository repository;
    private final SaveBorrowingPort savePort;
    private final CopyInventoryPort inventory;
    private final FineLedgerPort fines;
    private final PublishBorrowingEventPort eventPort;
    private final Clock clock;

    public ReportLostUseCase(BorrowingRepository repository, SaveBorrowingPort savePort, CopyInventoryPort inventory,
                             FineLedgerPort fines, PublishBorrowingEventPort eventPort, Clock clock) {
        this.repository = repository;
        this.savePort = savePort;
        this.inventory = inventory;
        this.fines = fines;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    @Transactional
    public BorrowingResponse execute(ReportLostCommand command) {
        UUID copyId = CopyResolver.resolve(command.bookCopyId(), command.barcode(), inventory);
        BorrowingRecord record = repository.findOpenByCopyIdForUpdate(copyId).orElseThrow(() -> new NotFoundException(
                "COPY_NOT_ON_LOAN", "Bản sao không ở trạng thái đang mượn: " + copyId));

        record.reportLost(copyId, LocalDate.now(clock));
        BorrowingRecord saved = savePort.save(record);

        List<FineResponse> issued = fines.assess(new AssessFineCommand(record.userId(), record.id().value(), copyId,
                null, 0, AssessFineCommand.Outcome.LOST, null, command.damageNote(), command.evidenceUrls()));

        inventory.changeStatus(copyId, CopyStatus.LOST);

        record.pullEvents().forEach(eventPort::publish);
        return BorrowingMapper.toResponse(saved, issued);
    }
}
