package lms.sales.tenantdata.infrastructure.config;

import lms.sales.tenantdata.application.ExportSalesTenantDataUseCase;
import lms.sales.tenantdata.application.TenantTableReader;
import lms.sales.tenantdata.domain.SalesExportCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root của context tenantdata (ADR-0031 F1). */
@Configuration
public class TenantDataBeanConfiguration {

    @Bean
    ExportSalesTenantDataUseCase exportSalesTenantDataUseCase(TenantTableReader reader,
            @Value("${lms.tenant-data.export.batch-size:1000}") int batchSize) {
        return new ExportSalesTenantDataUseCase(reader, SalesExportCatalog.tables(), SalesExportCatalog.excluded(),
                SalesExportCatalog.SERVICE, SalesExportCatalog.SCHEMA_VERSION, batchSize);
    }
}
