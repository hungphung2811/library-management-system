package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.DuplicateIsbnException;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleRepository;
import org.springframework.transaction.annotation.Transactional;

public class CreateTitleUseCase {

    private final TitleRepository repository;

    public CreateTitleUseCase(TitleRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public TitleResponse execute(CreateTitleCommand command) {
        Title title = Title.create(command.name(), command.author(), command.isbn(), command.coverPrice());
        if (title.isbn() != null && repository.existsByIsbn(title.isbn())) {
            throw new DuplicateIsbnException(title.isbn());
        }
        return TitleResponse.from(repository.save(title), 0);
    }
}
