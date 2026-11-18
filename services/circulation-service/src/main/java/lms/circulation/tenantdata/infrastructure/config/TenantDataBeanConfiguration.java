package lms.circulation.tenantdata.infrastructure.config;

import lms.circulation.tenantdata.application.CirculationTenantDataTables;
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
    TenantDataExporter circulationTenantDataExporter(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            @Value("${lms.tenant-data.export.batch-size:" + TenantDataExporter.DEFAULT_BATCH + "}") int batchSize) {
        TransactionTemplate snapshot = new TransactionTemplate(transactionManager);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return new TenantDataExporter(CirculationTenantDataTables.SERVICE, CirculationTenantDataTables.TABLES,
                CirculationTenantDataTables.EXCLUDED, new JdbcTenantTableReader(jdbc), snapshot, batchSize);
    }
}
