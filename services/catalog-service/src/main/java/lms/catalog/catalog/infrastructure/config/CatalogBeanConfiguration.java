package lms.catalog.catalog.infrastructure.config;

import lms.catalog.catalog.application.CreateTitleUseCase;
import lms.catalog.catalog.application.GetTitleUseCase;
import lms.catalog.catalog.application.SearchTitlesUseCase;
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
    GetTitleUseCase getTitleUseCase(TitleRepository repository, CopyAvailabilityQuery availability) {
        return new GetTitleUseCase(repository, availability);
    }

    @Bean
    SearchTitlesUseCase searchTitlesUseCase(TitleRepository repository, CopyAvailabilityQuery availability) {
        return new SearchTitlesUseCase(repository, availability);
    }
}
