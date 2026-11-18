package lms.catalog.inventory.application;

import lms.catalog.inventory.domain.BookCopy;
import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.CopyAvailableEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phản ứng với CopyReleasedEvent (đặt trước hết hạn/bị hủy và không còn ai chờ): RESERVED -> AVAILABLE.
 * Phát lại CopyAvailableEvent để nếu giữa chừng có người mới vào hàng đợi thì được giữ ngay. Idempotent.
 */
public class ReleaseCopyUseCase {

    private final BookCopyRepository repository;
    private final PublishInventoryEventPort eventPort;
    private final Clock clock;

    public ReleaseCopyUseCase(BookCopyRepository repository, PublishInventoryEventPort eventPort, Clock clock) {
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
    public void execute(UUID copyId) {
        repository.findById(copyId).ifPresent((BookCopy copy) -> {
            if (copy.release()) {
                repository.save(copy);
                BookCopyRepository.TitleWork work = workOf(copy.titleId());
                eventPort.publish(new CopyAvailableEvent(UUID.randomUUID(), copy.id(), copy.titleId(),
                        Instant.now(clock), work.workId(), work.language()));
            }
        });
    }
}
