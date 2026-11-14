package lms.database.autoconfigure;

import lms.database.api.DatabaseProvider;
import lms.database.config.DatabaseProperties;
import lms.database.config.DatabasePropertiesValidator;
import lms.database.core.DatabaseConnectionManager;
import lms.database.core.DatabaseConnectionRegistry;
import lms.database.core.DatabaseLifecycleManager;
import lms.database.core.RetryExecutor;
import lms.database.health.DatabaseHealthChecker;
import lms.database.health.DatabaseHealthIndicator;
import lms.database.provider.mongo.MongoProvider;
import lms.database.provider.redis.RedisProvider;
import lms.database.provider.relational.OracleProvider;
import lms.database.provider.relational.PostgresProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Chỉ bật khi có database.connections. Provider nào chỉ xuất hiện nếu driver/starter tương ứng có trên classpath. */
@AutoConfiguration
@Conditional(DatabaseConnectionsCondition.class)
@EnableConfigurationProperties(DatabaseProperties.class)
public class DatabaseAutoConfiguration {

    static final String REGISTRY_BEAN = "databaseConnectionRegistry";
    static final String LIFECYCLE_BEAN = "databaseLifecycleManager";

    private static final String HIKARI = "com.zaxxer.hikari.HikariDataSource";
    private static final String JPA = "org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean";

    @Bean(REGISTRY_BEAN)
    @ConditionalOnMissingBean
    DatabaseConnectionRegistry databaseConnectionRegistry() {
        return new DatabaseConnectionRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    RetryExecutor databaseRetryExecutor() {
        return new RetryExecutor();
    }

    @Bean
    @ConditionalOnMissingBean
    DatabasePropertiesValidator databasePropertiesValidator() {
        return new DatabasePropertiesValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    DatabaseConnectionManager databaseConnectionManager(ObjectProvider<DatabaseProvider<?>> providers,
                                                        DatabaseConnectionRegistry registry, RetryExecutor retryExecutor) {
        return new DatabaseConnectionManager(providers.orderedStream().toList(), registry, retryExecutor);
    }

    @Bean(LIFECYCLE_BEAN)
    @ConditionalOnMissingBean
    DatabaseLifecycleManager databaseLifecycleManager(DatabaseConnectionManager manager, DatabaseProperties properties,
                                                      DatabasePropertiesValidator validator) {
        return new DatabaseLifecycleManager(manager, properties, validator);
    }

    @Bean
    @ConditionalOnMissingBean
    DatabaseHealthChecker databaseHealthChecker(DatabaseConnectionRegistry registry) {
        return new DatabaseHealthChecker(registry);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"org.postgresql.Driver", HIKARI, JPA})
    static class PostgresConfiguration {
        @Bean
        @ConditionalOnMissingBean(PostgresProvider.class)
        PostgresProvider postgresProvider() {
            return new PostgresProvider();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"oracle.jdbc.OracleDriver", HIKARI, JPA})
    static class OracleConfiguration {
        @Bean
        @ConditionalOnMissingBean(OracleProvider.class)
        OracleProvider oracleProvider() {
            return new OracleProvider();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"com.mongodb.client.MongoClient", "org.springframework.data.mongodb.core.MongoTemplate"})
    static class MongoConfiguration {
        @Bean
        @ConditionalOnMissingBean(MongoProvider.class)
        MongoProvider mongoProvider() {
            return new MongoProvider();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory")
    static class RedisConfiguration {
        @Bean
        @ConditionalOnMissingBean(RedisProvider.class)
        RedisProvider redisProvider() {
            return new RedisProvider();
        }
    }

    /** Đăng ký bean DataSource/EntityManagerFactory/TransactionManager có tên cho từng connection quan hệ. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {HIKARI, JPA})
    static class RelationalBeansConfiguration {
        @Bean
        static DatabaseBeanRegistrar databaseBeanRegistrar(Environment environment) {
            return new DatabaseBeanRegistrar(environment);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    static class HealthConfiguration {
        @Bean
        @ConditionalOnMissingBean(name = "databaseConnectionsHealthIndicator")
        DatabaseHealthIndicator databaseConnectionsHealthIndicator(DatabaseConnectionRegistry registry,
                                                                   DatabaseHealthChecker checker) {
            return new DatabaseHealthIndicator(registry, checker);
        }
    }
}
