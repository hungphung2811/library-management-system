package lms.database.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import lms.database.api.DatabaseProvider;
import lms.database.api.DatabaseType;
import lms.database.api.exception.InvalidDatabaseConfigurationException;
import lms.database.core.DatabaseConnectionManager;
import lms.database.core.DatabaseConnectionRegistry;
import lms.database.testsupport.FakeProvider;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Kiểm tra điều kiện bật và vòng đời, dùng provider giả nên không cần Docker. */
class DatabaseAutoConfigurationTest {

    static final List<String> CLOSE_LOG = new ArrayList<>();

    @Configuration(proxyBeanMethods = false)
    static class FakeRedisProviderConfig {
        @Bean
        DatabaseProvider<?> fakeRedisProvider() {
            return new FakeProvider(DatabaseType.REDIS, CLOSE_LOG);
        }
    }

    /** Ẩn driver thật để chỉ còn provider giả. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DatabaseAutoConfiguration.class))
            .withClassLoader(new FilteredClassLoader(org.postgresql.Driver.class,
                    com.mongodb.client.MongoClient.class,
                    org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory.class));

    @Test
    void khongCoCauHinhThiKhongBatGi() {
        runner.run(context -> assertThat(context).doesNotHaveBean(DatabaseConnectionRegistry.class));
    }

    @Test
    void coCauHinhThiMoConnectionVaDongKhiDungContext() {
        CLOSE_LOG.clear();
        runner.withUserConfiguration(FakeRedisProviderConfig.class)
                .withPropertyValues(
                        "database.connections.CACHE_DB.type=REDIS",
                        "database.connections.CACHE_DB.host=localhost",
                        "database.connections.OFF_DB.type=REDIS",
                        "database.connections.OFF_DB.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(DatabaseConnectionManager.class);
                    DatabaseConnectionRegistry registry = context.getBean(DatabaseConnectionRegistry.class);
                    assertThat(registry.names()).containsExactly("CACHE_DB", "OFF_DB");
                    assertThat(registry.find("cache_db")).isPresent();
                    assertThat(registry.find("OFF_DB")).isEmpty();
                    assertThat(context).hasBean("databaseConnectionsHealthIndicator");
                });
        assertThat(CLOSE_LOG).containsExactly("CACHE_DB");
    }

    @Test
    void cauHinhSaiLamContextKhoiDongThatBaiVoiMoiLoi() {
        runner.withUserConfiguration(FakeRedisProviderConfig.class)
                .withPropertyValues("database.connections.CACHE_DB.type=REDIS") // thiếu host
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable root = context.getStartupFailure();
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    assertThat(root).isInstanceOf(InvalidDatabaseConfigurationException.class)
                            .hasMessageContaining("[CACHE_DB]").hasMessageContaining("host");
                });
    }

    @Test
    void tenBeanTheoQuyUoc() {
        assertThat(BeanNames.transactionManager("ORDER_DB")).isEqualTo("orderDbTransactionManager");
        assertThat(BeanNames.dataSource("user-db")).isEqualTo("userDbDataSource");
        assertThat(BeanNames.entityManagerFactory("REPORT")).isEqualTo("reportEntityManagerFactory");
    }
}
