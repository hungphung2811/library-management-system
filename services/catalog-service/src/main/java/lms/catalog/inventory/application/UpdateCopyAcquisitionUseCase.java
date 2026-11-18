package lms.catalog.inventory.application;

import lms.catalog.inventory.domain.BookCopy;
import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.CopyAcquisition;
import lms.catalog.inventory.domain.CopyNotFoundException;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Thay toàn bộ thông tin nhập kho và tình trạng của bản sao; không đụng trạng thái mượn trả và không phát sự kiện. */
public class UpdateCopyAcquisitionUseCase {

    private final BookCopyRepository repository;

    public UpdateCopyAcquisitionUseCase(BookCopyRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public CopyResponse execute(UUID copyId, CopyAcquisition acquisition) {
        BookCopy copy = repository.findByIdForUpdate(copyId).orElseThrow(() -> new CopyNotFoundException(copyId));
        copy.updateAcquisition(acquisition);
        BookCopy saved = repository.save(copy);
        return CopyResponse.from(saved, ShelfLocationCheck.code(repository, saved.shelfLocationId()));
    }
}
