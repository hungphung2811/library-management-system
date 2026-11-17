package lms.catalog.catalog.infrastructure.config;

import lms.catalog.catalog.application.CreateTitleUseCase;
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
    CreateTitleUseCase createTitleUseCase(TitleRepository repository) {
        return new CreateTitleUseCase(repository);
    }

    @Bean
    UpdateTitleUseCase updateTitleUseCase(TitleRepository repository, CopyAvailabilityQuery availability) {
        return new UpdateTitleUseCase(repository, availability);
    }

    @Bean
    GetTitleUseCase getTitleUseCase(TitleRepository repository, CopyAvailabilityQuery availability) {
        return new GetTitleUseCase(repository, availability);
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
    SearchTitlesUseCase searchTitlesUseCase(TitleRepository repository, CopyAvailabilityQuery availability) {
        return new SearchTitlesUseCase(repository, availability);
    }
}
