package lms.circulation.reporting.infrastructure.config;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import lms.circulation.reporting.application.CopyLabelLookupPort;
import lms.circulation.reporting.application.ExportOverdueBorrowingsCsvUseCase;
import lms.circulation.reporting.application.GetBorrowingTrendUseCase;
import lms.circulation.reporting.application.GetLendingSummaryUseCase;
import lms.circulation.reporting.application.GetTopBorrowedTitlesUseCase;
import lms.circulation.reporting.application.LendingStatsPort;
import lms.circulation.reporting.application.ReaderNameLookupPort;
import lms.circulation.reporting.infrastructure.client.CatalogCopyLabelRestClientAdapter;
import lms.circulation.reporting.infrastructure.client.IdentityUserNameRestClientAdapter;
import lms.circulation.reporting.infrastructure.persistence.LendingStatsQueryAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Composition root của context reporting (thống kê/báo cáo mượn trả, ADR-0018). */
@Configuration
public class ReportingBeanConfiguration {

    private static RestClient restClient(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Bean
    LendingStatsPort lendingStatsPort(EntityManager entityManager) {
        return new LendingStatsQueryAdapter(entityManager);
    }

    @Bean
    ReaderNameLookupPort readerNameLookupPort(@Value("${app.clients.identity.base-url}") String baseUrl) {
        return new IdentityUserNameRestClientAdapter(restClient(baseUrl));
    }

    @Bean
    CopyLabelLookupPort copyLabelLookupPort(@Value("${app.clients.catalog.base-url}") String baseUrl) {
        return new CatalogCopyLabelRestClientAdapter(restClient(baseUrl));
    }

    @Bean
    GetLendingSummaryUseCase getLendingSummaryUseCase(LendingStatsPort stats, Clock clock) {
        return new GetLendingSummaryUseCase(stats, clock);
    }

    @Bean
    GetBorrowingTrendUseCase getBorrowingTrendUseCase(LendingStatsPort stats, Clock clock) {
        return new GetBorrowingTrendUseCase(stats, clock);
    }

    @Bean
    GetTopBorrowedTitlesUseCase getTopBorrowedTitlesUseCase(LendingStatsPort stats, Clock clock) {
        return new GetTopBorrowedTitlesUseCase(stats, clock);
    }

    /** Trần dòng CSV: {@code lms.reports.max-rows} (mặc định 10000). */
    @Bean
    ExportOverdueBorrowingsCsvUseCase exportOverdueBorrowingsCsvUseCase(
            LendingStatsPort stats, ReaderNameLookupPort readers, CopyLabelLookupPort copies, Clock clock,
            @Value("${lms.reports.max-rows:10000}") int maxRows) {
        return new ExportOverdueBorrowingsCsvUseCase(stats, readers, copies, clock, maxRows);
    }
}
