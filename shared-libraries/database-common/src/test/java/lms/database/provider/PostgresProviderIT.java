package lms.database.provider;

import static org.assertj.core.api.Assertions.assertThat;

import lms.database.api.ConnectionSpec;
import lms.database.api.DatabaseType;
import lms.database.api.RelationalConnection;
import lms.database.provider.relational.PostgresProvider;
import lms.database.testsupport.Note;
import lms.database.testsupport.RelationalAssertions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("docker")
@Testcontainers
class PostgresProviderIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private final PostgresProvider provider = new PostgresProvider();

    static ConnectionSpec spec(String jdbcUrl, boolean withEntities) {
        ConnectionSpec spec = new ConnectionSpec();
        spec.setType(DatabaseType.POSTGRESQL);
        spec.setUrl(jdbcUrl);
        spec.setUsername(postgres.getUsername());
        spec.setPassword(postgres.getPassword());
        spec.getPool().setMaxSize(4);
        spec.getPool().setMinIdle(1);
        if (withEntities) {
            spec.setEntityPackages(List.of(Note.class.getPackageName()));
            spec.getJpaProperties().put("hibernate.hbm2ddl.auto", "create-drop");
        }
        return spec;
    }

    @Test
    void healthUpVaGhiDocQuaJpaCoRollback() {
        try (RelationalConnection connection = provider.create("MAIN_DB", spec(postgres.getJdbcUrl(), true))) {
            assertThat(connection.type()).isEqualTo(DatabaseType.POSTGRESQL);
            RelationalAssertions.verifyRoundTripAndRollback(connection);
        }
    }

    @Test
    void chiDungJdbcKhiKhongCoEntityPackages() {
        try (RelationalConnection connection = provider.create("JDBC_DB", spec(postgres.getJdbcUrl(), false))) {
            assertThat(connection.entityManagerFactory()).isEmpty();
            assertThat(connection.health().isUp()).isTrue();
            assertThat(new JdbcTemplate(connection.dataSource()).queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
            assertThat(connection.transactionManager().getClass().getSimpleName()).isEqualTo("DataSourceTransactionManager");
        }
    }

    @Test
    void rollbackChiAnhHuongDatabaseCuaTransactionManagerDo() throws Exception {
        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE second_db");
        }
        String secondUrl = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/second_db");

        try (RelationalConnection first = provider.create("FIRST_DB", spec(postgres.getJdbcUrl(), true));
             RelationalConnection second = provider.create("SECOND_DB", spec(secondUrl, true))) {
            TransactionTemplate firstTx = new TransactionTemplate(first.transactionManager());
            TransactionTemplate secondTx = new TransactionTemplate(second.transactionManager());

            secondTx.executeWithoutResult(s -> EntityManagerFactoryUtils
                    .getTransactionalEntityManager(second.entityManagerFactory().orElseThrow()).persist(new Note("b1", "giữ lại")));
            try {
                firstTx.executeWithoutResult(s -> {
                    EntityManagerFactoryUtils.getTransactionalEntityManager(first.entityManagerFactory().orElseThrow())
                            .persist(new Note("a1", "bị rollback"));
                    throw new IllegalStateException("lỗi ở DB thứ nhất");
                });
            } catch (IllegalStateException expected) {
                // rollback đúng DB thứ nhất
            }

            assertThat(new JdbcTemplate(first.dataSource()).queryForObject("SELECT COUNT(*) FROM notes", Integer.class)).isZero();
            assertThat(new JdbcTemplate(second.dataSource()).queryForObject("SELECT COUNT(*) FROM notes", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void closeLaIdempotentVaHealthSauKhiDongLaDown() {
        RelationalConnection connection = provider.create("CLOSE_DB", spec(postgres.getJdbcUrl(), false));

        connection.close();
        connection.close();

        assertThat(connection.health().isUp()).isFalse();
    }

    @Test
    void sai_matKhauThiNemLoiDeManagerRetry() {
        ConnectionSpec bad = spec(postgres.getJdbcUrl(), false);
        bad.setPassword("sai-mat-khau");
        bad.getTimeout().setConnect(java.time.Duration.ofSeconds(2));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.create("BAD_DB", bad))
                .isInstanceOf(RuntimeException.class);
    }
}
