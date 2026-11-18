package lms.catalog.tenantdata.infrastructure.config;

import lms.catalog.tenantdata.application.CatalogTenantDataTables;
import lms.tenant.export.JdbcTenantTableReader;
import lms.tenant.export.TenantDataExporter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Composition root của exporter dữ liệu tenant (ADR-0031 F1): đọc keyset bằng JDBC trong một transaction chỉ đọc. */
@Configuration
public class TenantDataBeanConfiguration {

    @Bean
    TenantDataExporter catalogTenantDataExporter(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            @Value("${lms.tenant-data.export.batch-size:" + TenantDataExporter.DEFAULT_BATCH + "}") int batchSize) {
        TransactionTemplate snapshot = new TransactionTemplate(transactionManager);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return new TenantDataExporter(CatalogTenantDataTables.SERVICE, CatalogTenantDataTables.TABLES,
                CatalogTenantDataTables.EXCLUDED, new JdbcTenantTableReader(jdbc), snapshot, batchSize);
    }
}
