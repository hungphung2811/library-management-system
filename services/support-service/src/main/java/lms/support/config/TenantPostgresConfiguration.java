package lms.support.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import lms.database.provider.relational.PostgresProvider;
import lms.tenant.TenantRlsConnectionInitializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lớp 2 (RLS) cho AUDIT_DB: connection do database-common dựng (không phải bean DataSource của Spring Boot) nên
 * TenantRlsConnectionInitializer không tự bọc được. Provider này bọc DataSource trước khi đưa cho
 * EntityManagerFactory, để mọi kết nối mượn ra đều có app.tenant_id theo TenantContext. Tắt cùng
 * {@code lms.tenant.enabled=false} / {@code lms.tenant.rls.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
public class TenantPostgresConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "lms.tenant", name = "enabled", havingValue = "true", matchIfMissing = true)
    PostgresProvider postgresProvider(
            @Value("${lms.tenant.rls.enabled:true}") boolean rls) {
        return new PostgresProvider() {
            @Override
            protected DataSource decorate(HikariDataSource pool) {
                return rls ? TenantRlsConnectionInitializer.wrap(pool) : pool;
            }
        };
    }
}
