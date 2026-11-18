package lms.support.tenantdata.infrastructure.config;

import javax.sql.DataSource;
import lms.support.tenantdata.application.SupportTenantDataTables;
import lms.tenant.export.JdbcTenantTableReader;
import lms.tenant.export.TenantDataExporter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Composition root của exporter dữ liệu tenant (ADR-0031 F1) trên AUDIT_DB (DataSource/TransactionManager do
 * database-common đăng ký: {@code auditDbDataSource}, {@code auditDbTransactionManager}; DataSource đã bọc RLS).
 */
@Configuration
public class TenantDataBeanConfiguration {

    @Bean
    TenantDataExporter supportTenantDataExporter(@Qualifier("auditDbDataSource") DataSource auditDataSource,
            @Qualifier("auditDbTransactionManager") PlatformTransactionManager auditTransactionManager,
            @Value("${lms.tenant-data.export.batch-size:" + TenantDataExporter.DEFAULT_BATCH + "}") int batchSize) {
        TransactionTemplate snapshot = new TransactionTemplate(auditTransactionManager);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return new TenantDataExporter(SupportTenantDataTables.SERVICE, SupportTenantDataTables.TABLES,
                SupportTenantDataTables.EXCLUDED, new JdbcTenantTableReader(new JdbcTemplate(auditDataSource)),
                snapshot, batchSize);
    }
}
