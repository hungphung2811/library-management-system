package lms.support.audit.infrastructure.config;

import lms.database.api.RelationalConnection;
import lms.database.core.DatabaseConnectionRegistry;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * Chạy Flyway thủ công cho AUDIT_DB (auto-configuration của Spring Boot bị tắt vì có nhiều nguồn dữ liệu).
 * Phải chạy sau khi database-common mở connection. Hibernate không validate schema (hbm2ddl=none) vì
 * EntityManagerFactory được tạo trước bước migrate này.
 */
@Configuration
public class AuditFlywayConfiguration {

    @Bean(name = "auditFlyway", initMethod = "migrate")
    @DependsOn("databaseLifecycleManager")
    Flyway auditFlyway(DatabaseConnectionRegistry registry) {
        return Flyway.configure()
                .dataSource(registry.require("AUDIT_DB", RelationalConnection.class).dataSource())
                .locations("classpath:db/migration/audit")
                .load();
    }
}
