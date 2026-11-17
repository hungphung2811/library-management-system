package lms.catalog.reporting.infrastructure.config;

import lms.catalog.reporting.application.CatalogStatsQuery;
import lms.catalog.reporting.application.GetCatalogSummaryUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root của context reporting. */
@Configuration
public class ReportingBeanConfiguration {

    @Bean
    GetCatalogSummaryUseCase getCatalogSummaryUseCase(CatalogStatsQuery query) {
        return new GetCatalogSummaryUseCase(query);
    }
}
