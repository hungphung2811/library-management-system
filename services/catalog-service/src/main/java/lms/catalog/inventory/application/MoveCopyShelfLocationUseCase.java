package lms.catalog.inventory.application;

import lms.catalog.inventory.domain.BookCopy;
import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.CopyNotFoundException;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Gán/đổi/gỡ vị trí kệ của bản sao; không đụng trạng thái mượn trả và không phát sự kiện. */
public class MoveCopyShelfLocationUseCase {

    private final BookCopyRepository repository;

    public MoveCopyShelfLocationUseCase(BookCopyRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public CopyResponse execute(UUID copyId, UUID shelfLocationId) {
        BookCopy copy = repository.findByIdForUpdate(copyId).orElseThrow(() -> new CopyNotFoundException(copyId));
        ShelfLocationCheck.require(repository, shelfLocationId, copy.shelfLocationId());
        copy.moveToShelfLocation(shelfLocationId);
        BookCopy saved = repository.save(copy);
        return CopyResponse.from(saved, ShelfLocationCheck.code(repository, saved.shelfLocationId()));
    }
}
