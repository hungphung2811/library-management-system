package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.DuplicateIsbnException;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleNotFoundException;
import lms.catalog.catalog.domain.TitleRepository;
import lms.catalog.inventory.application.CopyAvailabilityQuery;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/** Sửa thông tin đầu sách (tên, tác giả, ISBN, giá bìa). Không đụng bản sao (book_copies). */
public class UpdateTitleUseCase {

    private final TitleRepository repository;
    private final CopyAvailabilityQuery availability;
    private final TitleClassificationService classification;

    public UpdateTitleUseCase(TitleRepository repository, CopyAvailabilityQuery availability) {
        this(repository, availability, null);
    }

    public UpdateTitleUseCase(TitleRepository repository, CopyAvailabilityQuery availability,
                              TitleClassificationService classification) {
        this.repository = repository;
        this.availability = availability;
        this.classification = classification;
    }

    @Transactional
    public TitleResponse execute(UpdateTitleCommand command) {
        Title current = repository.findById(command.id()).orElseThrow(() -> new TitleNotFoundException(command.id()));
        Title updated = current.update(command.name(), command.author(), command.isbn(), command.coverPrice());
        if (updated.isbn() != null && repository.existsByIsbnAndIdNot(updated.isbn(), updated.id())) {
            throw new DuplicateIsbnException(updated.isbn());
        }
        if (classification != null) {
            // Thay toàn bộ phân loại chuẩn; giá trị đang có trên đầu sách vẫn được giữ dù mục đã ngừng dùng.
            updated = classification.classify(updated, command.publisherId(), command.categoryId(),
                    command.authorIds());
        }
        Title saved = repository.save(updated);
        long available = availability.countAvailable(List.of(saved.id())).getOrDefault(saved.id(), 0L);
        return classification == null ? TitleResponse.from(saved, available)
                : TitleResponse.from(saved, available, classification.describe(saved));
    }
}
