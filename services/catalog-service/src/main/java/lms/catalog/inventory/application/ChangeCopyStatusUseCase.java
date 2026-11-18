package lms.catalog.inventory.application;

import lms.catalog.inventory.domain.BookCopy;
import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.CopyAvailableEvent;
import lms.catalog.inventory.domain.CopyNotFoundException;
import lms.catalog.inventory.domain.CopyStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catalog là chủ sở hữu trạng thái bản sao: circulation gọi đồng bộ để đánh dấu LOANED khi mượn,
 * AVAILABLE/DAMAGED khi trả, LOST khi báo mất. Idempotent; khi bản sao trở lại AVAILABLE thì phát
 * CopyAvailableEvent để giữ cho người đầu hàng đợi (QĐ9), tái dùng cơ chế sẵn có.
 */
public class ChangeCopyStatusUseCase {

    private final BookCopyRepository repository;
    private final PublishInventoryEventPort eventPort;
    private final Clock clock;

    public ChangeCopyStatusUseCase(BookCopyRepository repository, PublishInventoryEventPort eventPort, Clock clock) {
        this.repository = repository;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    /** Tác phẩm và ngôn ngữ của ấn bản để gắn vào sự kiện (một truy vấn nhẹ). */
    private BookCopyRepository.TitleWork workOf(UUID titleId) {
        BookCopyRepository.TitleWork w = repository.findTitleWorksByTitleIds(java.util.List.of(titleId)).get(titleId);
        return w == null ? new BookCopyRepository.TitleWork(null, null) : w;
    }

    @Transactional
    public CopyResponse execute(UUID copyId, CopyStatus target) {
        BookCopy copy = repository.findByIdForUpdate(copyId).orElseThrow(() -> new CopyNotFoundException(copyId));
        if (copy.changeStatus(target)) {
            repository.save(copy);
            if (target == CopyStatus.AVAILABLE) {
                BookCopyRepository.TitleWork work = workOf(copy.titleId());
                eventPort.publish(new CopyAvailableEvent(UUID.randomUUID(), copy.id(), copy.titleId(),
                        Instant.now(clock), work.workId(), work.language()));
            }
        }
        return CopyResponse.from(copy, ShelfLocationCheck.code(repository, copy.shelfLocationId()));
    }
}
