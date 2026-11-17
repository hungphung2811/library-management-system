package lms.catalog.inventory.application;

import lms.catalog.inventory.domain.BookCopyRepository;
import java.util.List;
import java.util.UUID;

public class ListCopiesUseCase {

    private final BookCopyRepository repository;

    public ListCopiesUseCase(BookCopyRepository repository) {
        this.repository = repository;
    }

    public List<CopyResponse> execute(UUID titleId) {
        var copies = repository.findByTitleId(titleId);
        var shelfIds = copies.stream().map(c -> c.shelfLocationId()).filter(java.util.Objects::nonNull)
                .distinct().toList();
        var codes = shelfIds.isEmpty() ? java.util.Map.<UUID, String>of() : repository.findShelfLocationCodes(shelfIds);
        return copies.stream()
                .map(c -> CopyResponse.from(c, c.shelfLocationId() == null ? null : codes.get(c.shelfLocationId())))
                .toList();
    }
}
