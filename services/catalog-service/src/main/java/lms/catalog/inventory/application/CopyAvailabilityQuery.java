package lms.catalog.inventory.application;

import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.CopyStatus;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** API công khai của context inventory cho context khác (catalog): số bản sao sẵn có theo đầu sách. */
public class CopyAvailabilityQuery {

    private final BookCopyRepository repository;

    public CopyAvailabilityQuery(BookCopyRepository repository) {
        this.repository = repository;
    }

    public Map<UUID, Long> countAvailable(Collection<UUID> titleIds) {
        if (titleIds.isEmpty()) return Map.of();
        return repository.countByTitleIds(titleIds, CopyStatus.AVAILABLE);
    }
}
