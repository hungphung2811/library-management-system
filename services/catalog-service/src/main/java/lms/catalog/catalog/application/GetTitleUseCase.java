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
    private final TitleResponseAssembler assembler;

    public GetTitleUseCase(TitleRepository repository, CopyAvailabilityQuery availability) {
        this(repository, availability, null);
    }

    public GetTitleUseCase(TitleRepository repository, CopyAvailabilityQuery availability,
                           TitleClassificationService classification) {
        this(repository, availability, classification, new TitleResponseAssembler(availability, classification, null));
    }

    public GetTitleUseCase(TitleRepository repository, CopyAvailabilityQuery availability,
                           TitleClassificationService classification, TitleResponseAssembler assembler) {
        this.repository = repository;
        this.availability = availability;
        this.classification = classification;
        this.assembler = assembler;
    }

    public TitleResponse execute(UUID id) {
        var title = repository.findById(id).orElseThrow(() -> new TitleNotFoundException(id));
        if (classification == null) {
            long available = availability.countAvailable(List.of(id)).getOrDefault(id, 0L);
            return TitleResponse.from(title, available);
        }
        return assembler.assemble(title);
    }
}
