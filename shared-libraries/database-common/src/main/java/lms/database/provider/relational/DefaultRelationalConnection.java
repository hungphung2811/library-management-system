package lms.database.provider.relational;

import lms.database.api.DatabaseType;
import lms.database.api.HealthStatus;
import lms.database.api.RelationalConnection;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

class DefaultRelationalConnection implements RelationalConnection {

    private final String name;
    private final DatabaseType type;
    private final HikariDataSource dataSource;
    private final LocalContainerEntityManagerFactoryBean emfBean;
    private final PlatformTransactionManager transactionManager;
    private final String testQuery;
    private final int validationSeconds;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    DefaultRelationalConnection(String name, DatabaseType type, HikariDataSource dataSource,
                                LocalContainerEntityManagerFactoryBean emfBean,
                                PlatformTransactionManager transactionManager, String testQuery,
                                int validationSeconds) {
        this.name = name;
        this.type = type;
        this.dataSource = dataSource;
        this.emfBean = emfBean;
        this.transactionManager = transactionManager;
        this.testQuery = testQuery;
        this.validationSeconds = validationSeconds;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public DatabaseType type() {
        return type;
    }

    @Override
    public DataSource dataSource() {
        return dataSource;
    }

    @Override
    public Optional<EntityManagerFactory> entityManagerFactory() {
        return Optional.ofNullable(emfBean).map(LocalContainerEntityManagerFactoryBean::getObject);
    }

    @Override
    public PlatformTransactionManager transactionManager() {
        return transactionManager;
    }

    @Override
    public HealthStatus health() {
        if (closed.get()) {
            return HealthStatus.down("Connection đã đóng");
        }
        try (Connection connection = dataSource.getConnection()) {
            if (testQuery != null) {
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(validationSeconds);
                    statement.execute(testQuery);
                }
            } else if (!connection.isValid(validationSeconds)) {
                return HealthStatus.down("Connection không hợp lệ");
            }
            return HealthStatus.up(type.name());
        } catch (SQLException | RuntimeException e) {
            return HealthStatus.down(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                if (emfBean != null) {
                    emfBean.destroy();
                }
            } finally {
                dataSource.close();
            }
        }
    }
}
