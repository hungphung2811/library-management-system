package lms.database.provider.relational;

import lms.database.api.ConnectionSpec;
import lms.database.api.DatabaseProvider;
import lms.database.api.DatabaseType;
import lms.database.api.RelationalConnection;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Phần dùng chung của các provider quan hệ: HikariCP (pool có sẵn của framework, không tự viết),
 * EntityManagerFactory riêng (khi có entity-packages) và TransactionManager riêng. Các lớp con chỉ
 * khai báo driver, dialect mặc định và câu lệnh kiểm tra.
 */
public abstract class RelationalProviderSupport implements DatabaseProvider<RelationalConnection> {

    private static final long MIN_TIMEOUT_MS = 250;

    private final DatabaseType type;
    private final String driverClassName;
    private final String defaultDialect;
    private final String testQuery;

    protected RelationalProviderSupport(DatabaseType type, String driverClassName, String defaultDialect,
                                        String testQuery) {
        this.type = type;
        this.driverClassName = driverClassName;
        this.defaultDialect = defaultDialect;
        this.testQuery = testQuery;
    }

    @Override
    public DatabaseType type() {
        return type;
    }

    @Override
    public RelationalConnection create(String name, ConnectionSpec spec) {
        HikariDataSource pool = new HikariDataSource(hikariConfig(name, spec));
        DataSource dataSource = decorate(pool);
        LocalContainerEntityManagerFactoryBean emfBean = null;
        try {
            List<String> packages = spec.getEntityPackages();
            if (packages != null && !packages.isEmpty()) {
                emfBean = entityManagerFactory(name, spec, dataSource, packages);
            }
            PlatformTransactionManager transactionManager = emfBean != null
                    ? new JpaTransactionManager(emfBean.getObject())
                    : new DataSourceTransactionManager(dataSource);
            int validationSeconds = (int) Math.max(1, spec.getTimeout().getValidation().toSeconds());
            return new DefaultRelationalConnection(name, type, pool, dataSource, emfBean, transactionManager,
                    testQuery, validationSeconds);
        } catch (RuntimeException e) {
            // Không để rò pool nếu bước sau của việc khởi tạo thất bại.
            if (emfBean != null) {
                emfBean.destroy();
            }
            pool.close();
            throw e;
        }
    }

    /**
     * Hook cho service bọc DataSource (ví dụ đặt biến phiên cho RLS theo tenant). DataSource trả về dùng cho
     * EntityManagerFactory, TransactionManager và bean DataSource; pool Hikari gốc vẫn do connection đóng.
     */
    protected DataSource decorate(HikariDataSource pool) {
        return pool;
    }

    private HikariConfig hikariConfig(String name, ConnectionSpec spec) {
        var pool = spec.getPool();
        long connectMs = Math.max(MIN_TIMEOUT_MS, spec.getTimeout().getConnect().toMillis());
        long validationMs = Math.min(connectMs, Math.max(MIN_TIMEOUT_MS, spec.getTimeout().getValidation().toMillis()));

        HikariConfig config = new HikariConfig();
        config.setPoolName(name + "-pool");
        config.setJdbcUrl(spec.getUrl());
        config.setUsername(spec.getUsername());
        config.setPassword(spec.getPassword());
        config.setDriverClassName(driverClassName);
        config.setMaximumPoolSize(pool.getMaxSize());
        config.setMinimumIdle(pool.getMinIdle());
        config.setIdleTimeout(pool.getIdleTimeout().toMillis());
        config.setMaxLifetime(pool.getMaxLifetime().toMillis());
        config.setConnectionTimeout(connectMs);
        config.setValidationTimeout(validationMs);
        // Thất bại ngay lúc tạo pool để manager còn retry, thay vì để lỗi lộ ra ở request đầu tiên.
        config.setInitializationFailTimeout(connectMs);
        if (testQuery != null) {
            config.setConnectionTestQuery(testQuery);
        }
        return config;
    }

    private LocalContainerEntityManagerFactoryBean entityManagerFactory(String name, ConnectionSpec spec,
                                                                        DataSource dataSource,
                                                                        List<String> packages) {
        Map<String, Object> properties = new LinkedHashMap<>();
        // Giữ quy ước đặt tên cột giống auto-configuration của Spring Boot (camelCase -> snake_case).
        properties.put("hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        if (defaultDialect != null) {
            properties.put("hibernate.dialect", defaultDialect);
        }
        properties.putAll(spec.getJpaProperties());

        LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
        bean.setBeanName(name + "EntityManagerFactory");
        bean.setPersistenceUnitName(name);
        bean.setDataSource(dataSource);
        bean.setPackagesToScan(packages.toArray(String[]::new));
        bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        bean.setJpaPropertyMap(properties);
        bean.afterPropertiesSet();
        return bean;
    }
}
