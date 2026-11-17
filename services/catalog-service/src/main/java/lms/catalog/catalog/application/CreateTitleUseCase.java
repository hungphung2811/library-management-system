package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.DuplicateIsbnException;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleRepository;
import org.springframework.transaction.annotation.Transactional;

public class CreateTitleUseCase {

    private final TitleRepository repository;
    private final TitleClassificationService classification;

    public CreateTitleUseCase(TitleRepository repository) {
        this(repository, null);
    }

    public CreateTitleUseCase(TitleRepository repository, TitleClassificationService classification) {
        this.repository = repository;
        this.classification = classification;
    }

    @Transactional
    public TitleResponse execute(CreateTitleCommand command) {
        Title title = Title.create(command.name(), command.author(), command.isbn(), command.coverPrice());
        if (title.isbn() != null && repository.existsByIsbn(title.isbn())) {
            throw new DuplicateIsbnException(title.isbn());
        }
        if (classification == null) {
            return TitleResponse.from(repository.save(title), 0);
        }
        Title classified = classification.classify(title, command.publisherId(), command.categoryId(),
                command.authorIds());
        Title saved = repository.save(classified);
        return TitleResponse.from(saved, 0, classification.describe(saved));
    }
}
