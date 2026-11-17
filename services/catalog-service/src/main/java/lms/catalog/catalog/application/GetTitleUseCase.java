package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.TitleNotFoundException;
import lms.catalog.catalog.domain.TitleRepository;
import lms.catalog.inventory.application.CopyAvailabilityQuery;
import java.util.List;
import java.util.UUID;

public class GetTitleUseCase {

    private final TitleRepository repository;
    private final CopyAvailabilityQuery availability;
    private final TitleClassificationService classification;

    public GetTitleUseCase(TitleRepository repository, CopyAvailabilityQuery availability) {
        this(repository, availability, null);
    }

    public GetTitleUseCase(TitleRepository repository, CopyAvailabilityQuery availability,
                           TitleClassificationService classification) {
        this.repository = repository;
        this.availability = availability;
        this.classification = classification;
    }

    public TitleResponse execute(UUID id) {
        var title = repository.findById(id).orElseThrow(() -> new TitleNotFoundException(id));
        long available = availability.countAvailable(List.of(id)).getOrDefault(id, 0L);
        return classification == null ? TitleResponse.from(title, available)
                : TitleResponse.from(title, available, classification.describe(title));
    }
}
