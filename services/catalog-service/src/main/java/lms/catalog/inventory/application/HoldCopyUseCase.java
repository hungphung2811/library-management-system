package lms.catalog.inventory.application;

import lms.catalog.inventory.domain.BookCopy;
import lms.catalog.inventory.domain.BookCopyRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/** Phản ứng với ReservationReadyEvent: bản sao AVAILABLE chuyển sang RESERVED. Idempotent. */
public class HoldCopyUseCase {

    private static final Logger log = LoggerFactory.getLogger(HoldCopyUseCase.class);

    private final BookCopyRepository repository;

    public HoldCopyUseCase(BookCopyRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void execute(UUID copyId) {
        Optional<BookCopy> found = repository.findById(copyId);
        if (found.isEmpty()) {
            log.warn("Không giữ được bản sao {}: không tồn tại", copyId);
            return;
        }
        BookCopy copy = found.get();
        if (!copy.hold()) {
            // Đã bị mượn trực tiếp giữa lúc event đang đi: ghi nhận để xử lý tay, tránh mất dấu.
            log.warn("Bản sao {} không còn AVAILABLE ({}), không giữ được cho người đặt trước", copyId, copy.status());
            return;
        }
        repository.save(copy);
    }
}
