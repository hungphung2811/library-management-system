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
        return repository.findByTitleId(titleId).stream().map(CopyResponse::from).toList();
    }
}
