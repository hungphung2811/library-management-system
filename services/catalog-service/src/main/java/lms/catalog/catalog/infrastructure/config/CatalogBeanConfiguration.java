package lms.catalog.catalog.infrastructure.config;

import lms.catalog.catalog.application.CreateReferenceUseCase;
import lms.catalog.catalog.application.CreateTitleUseCase;
import lms.catalog.catalog.application.GetReferenceUseCase;
import lms.catalog.catalog.application.ReferenceOptionsUseCase;
import lms.catalog.catalog.application.ReferenceUseCases;
import lms.catalog.catalog.application.SearchReferencesUseCase;
import lms.catalog.catalog.application.SetReferenceStatusUseCase;
import lms.catalog.catalog.application.TitleClassificationService;
import lms.catalog.catalog.application.UpdateReferenceUseCase;
import lms.catalog.catalog.domain.ReferenceRepository;
import lms.catalog.catalog.application.GetTitlePreviewUseCase;
import lms.catalog.catalog.application.GetTitleUseCase;
import lms.catalog.catalog.application.LookupTitlesByKeywordUseCase;
import lms.catalog.catalog.application.UpdateTitlePreviewUseCase;
import lms.catalog.catalog.application.SearchTitlesUseCase;
import lms.catalog.catalog.application.UpdateTitleUseCase;
import lms.catalog.catalog.domain.TitleRepository;
import lms.catalog.inventory.application.CopyAvailabilityQuery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root của context catalog. */
@Configuration
public class CatalogBeanConfiguration {

    @Bean
    TitleClassificationService titleClassificationService(ReferenceRepository references) {
        return new TitleClassificationService(references);
    }

    @Bean
    ReferenceUseCases referenceUseCases(ReferenceRepository references) {
        return new ReferenceUseCases(new CreateReferenceUseCase(references), new UpdateReferenceUseCase(references),
                new SetReferenceStatusUseCase(references), new GetReferenceUseCase(references),
                new SearchReferencesUseCase(references), new ReferenceOptionsUseCase(references));
    }

    @Bean
    CreateTitleUseCase createTitleUseCase(TitleRepository repository, TitleClassificationService classification) {
        return new CreateTitleUseCase(repository, classification);
    }

    @Bean
    UpdateTitleUseCase updateTitleUseCase(TitleRepository repository, CopyAvailabilityQuery availability,
                                          TitleClassificationService classification) {
        return new UpdateTitleUseCase(repository, availability, classification);
    }

    @Bean
    GetTitleUseCase getTitleUseCase(TitleRepository repository, CopyAvailabilityQuery availability,
                                    TitleClassificationService classification) {
        return new GetTitleUseCase(repository, availability, classification);
    }

    @Bean
    lms.catalog.catalog.application.LookupTitlesUseCase lookupTitlesUseCase(TitleRepository repository) {
        return new lms.catalog.catalog.application.LookupTitlesUseCase(repository);
    }

    @Bean
    GetTitlePreviewUseCase getTitlePreviewUseCase(TitleRepository repository) {
        return new GetTitlePreviewUseCase(repository);
    }

    @Bean
    UpdateTitlePreviewUseCase updateTitlePreviewUseCase(TitleRepository repository) {
        return new UpdateTitlePreviewUseCase(repository);
    }

    @Bean
    LookupTitlesByKeywordUseCase lookupTitlesByKeywordUseCase(TitleRepository repository) {
        return new LookupTitlesByKeywordUseCase(repository);
    }

    @Bean
    SearchTitlesUseCase searchTitlesUseCase(TitleRepository repository, CopyAvailabilityQuery availability,
                                            TitleClassificationService classification) {
        return new SearchTitlesUseCase(repository, availability, classification);
    }
}
