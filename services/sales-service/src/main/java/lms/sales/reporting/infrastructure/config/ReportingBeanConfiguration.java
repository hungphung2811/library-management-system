package lms.sales.reporting.infrastructure.config;

import java.time.Clock;
import lms.sales.reporting.application.OrdersCsvReportUseCase;
import lms.sales.reporting.application.SalesOrderReportPort;
import lms.sales.reporting.application.SalesStatsQueryPort;
import lms.sales.reporting.application.SalesStatsUseCases;
import lms.sales.reporting.application.StockCsvReportUseCase;
import lms.sales.reporting.application.StorefrontTopSoldUseCase;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.StockPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root của context reporting (ADR-0018): use case là POJO. Trần dòng CSV: lms.reports.max-rows. */
@Configuration
public class ReportingBeanConfiguration {

    @Bean
    SalesStatsUseCases salesStatsUseCases(SalesStatsQueryPort query, StockPort stock, GetSalesSettingsUseCase settings,
                                          CatalogLookupPort catalog, Clock clock) {
        return new SalesStatsUseCases(query, stock, settings, catalog, clock);
    }

    @Bean
    StorefrontTopSoldUseCase storefrontTopSoldUseCase(SalesStatsQueryPort query, Clock clock) {
        return new StorefrontTopSoldUseCase(query, clock);
    }

    @Bean
    StockCsvReportUseCase stockCsvReportUseCase(StockPort stock, CatalogLookupPort catalog,
                                                GetSalesSettingsUseCase settings, Clock clock,
                                                @Value("${lms.reports.max-rows:10000}") int maxRows) {
        return new StockCsvReportUseCase(stock, catalog, settings, clock, maxRows);
    }

    @Bean
    OrdersCsvReportUseCase ordersCsvReportUseCase(SalesOrderReportPort orders, Clock clock,
                                                  @Value("${lms.reports.max-rows:10000}") int maxRows) {
        return new OrdersCsvReportUseCase(orders, clock, maxRows);
    }
}
