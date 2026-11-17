package lms.catalog.inventory.application;

import lms.catalog.inventory.domain.BookCopy;
import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.CopyAvailableEvent;
import lms.catalog.inventory.domain.CopyTitleNotFoundException;
import lms.catalog.inventory.domain.DuplicateBarcodeException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** UC11 (một phần): nhập bản sao mới cho đầu sách đã có; phát CopyAvailableEvent để giữ cho người đặt trước (QĐ9). */
public class AddCopyUseCase {

    private final BookCopyRepository repository;
    private final PublishInventoryEventPort eventPort;
    private final Clock clock;

    public AddCopyUseCase(BookCopyRepository repository, PublishInventoryEventPort eventPort, Clock clock) {
        this.repository = repository;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    @Transactional
    public CopyResponse execute(AddCopyCommand command) {
        if (!repository.titleExists(command.titleId())) {
            throw new CopyTitleNotFoundException(command.titleId());
        }
        BookCopy copy = BookCopy.register(command.titleId(), command.barcode(), command.shelfLocationId());
        if (repository.existsByBarcode(copy.barcode())) {
            throw new DuplicateBarcodeException(copy.barcode());
        }
        ShelfLocationCheck.require(repository, command.shelfLocationId(), null);
        BookCopy saved = repository.save(copy);
        eventPort.publish(new CopyAvailableEvent(UUID.randomUUID(), saved.id(), saved.titleId(), Instant.now(clock)));
        return CopyResponse.from(saved, ShelfLocationCheck.code(repository, saved.shelfLocationId()));
    }
}
