package lms.database.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import lms.database.api.ConnectionSpec;
import lms.database.api.DatabaseType;
import lms.database.api.HealthStatus;
import lms.database.api.RelationalConnection;
import lms.database.provider.relational.OracleProvider;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Kết nối tới một Oracle đã chạy sẵn (không dùng Testcontainers). Tự bỏ qua nếu chưa đặt biến:
 * ORACLE_TEST_URL (ví dụ jdbc:oracle:thin:@//localhost:1521/FREEPDB1), ORACLE_TEST_USERNAME,
 * ORACLE_TEST_PASSWORD (có thể đặt bằng biến môi trường hoặc -D). Chỉ tạo một bảng tạm tên duy nhất
 * TMP_DBCOMMON_IT_&lt;random&gt; và luôn DROP trong finally.
 */
@Tag("external-db")
class OracleProviderIT {

    private static String setting(String name) {
        String value = System.getProperty(name);
        return value != null && !value.isBlank() ? value : System.getenv(name);
    }

    private ConnectionSpec specOrSkip() {
        String url = setting("ORACLE_TEST_URL");
        String username = setting("ORACLE_TEST_USERNAME");
        String password = setting("ORACLE_TEST_PASSWORD");
        assumeTrue(url != null && username != null && password != null,
                "Bỏ qua IT Oracle: chưa đặt ORACLE_TEST_URL, ORACLE_TEST_USERNAME, ORACLE_TEST_PASSWORD");

        ConnectionSpec spec = new ConnectionSpec();
        spec.setType(DatabaseType.ORACLE);
        spec.setUrl(url);
        spec.setUsername(username);
        spec.setPassword(password);
        spec.getPool().setMaxSize(3);
        spec.getPool().setMinIdle(1);
        spec.getTimeout().setConnect(java.time.Duration.ofSeconds(30));
        return spec;
    }

    @Test
    void healthUpVaCommitRollbackTrenBangTam() {
        ConnectionSpec spec = specOrSkip();
        String table = "TMP_DBCOMMON_IT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

        try (RelationalConnection connection = new OracleProvider().create("ORA_DB", spec)) {
            assertThat(connection.type()).isEqualTo(DatabaseType.ORACLE);
            assertThat(connection.health().status()).isEqualTo(HealthStatus.Status.UP);
            JdbcTemplate jdbc = new JdbcTemplate(connection.dataSource());
            TransactionTemplate tx = new TransactionTemplate(connection.transactionManager());

            jdbc.execute("CREATE TABLE " + table + " (id VARCHAR2(20) PRIMARY KEY, content VARCHAR2(100))");
            try {
                tx.executeWithoutResult(s -> jdbc.update("INSERT INTO " + table + " VALUES ('n1', 'đã commit')"));
                assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                    jdbc.update("INSERT INTO " + table + " VALUES ('n2', 'sẽ bị rollback')");
                    throw new IllegalStateException("lỗi nghiệp vụ");
                })).hasMessage("lỗi nghiệp vụ");

                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = 'n2'", Integer.class)).isZero();
            } finally {
                jdbc.execute("DROP TABLE " + table + " PURGE");
            }
        }
    }
}
