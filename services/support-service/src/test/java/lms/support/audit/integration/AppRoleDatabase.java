package lms.support.audit.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Cấu hình IT như môi trường thật (R7/R13): Flyway chạy bằng superuser của container (chủ bảng), ứng dụng kết nối
 * AUDIT_DB bằng vai trò {@value #APP_ROLE} (NOSUPERUSER NOBYPASSRLS, như infrastructure/postgres/init-databases.sql)
 * nên chịu RLS. Quyền dữ liệu của vai trò do callback afterMigrate.sql của migration audit cấp.
 *
 * <p>Gọi trong {@code @DynamicPropertySource} (container đã chạy).
 */
public final class AppRoleDatabase {

    public static final String APP_ROLE = "audit_app";

    private AppRoleDatabase() {
    }

    public static void register(PostgreSQLContainer<?> postgres, DynamicPropertyRegistry registry) {
        createAppRole(postgres);
        registry.add("database.connections.AUDIT_DB.url", postgres::getJdbcUrl);
        registry.add("database.connections.AUDIT_DB.username", () -> APP_ROLE);
        registry.add("database.connections.AUDIT_DB.password", () -> APP_ROLE);
    }

    /** Kết nối superuser (bỏ qua RLS): seed dữ liệu của nhiều tenant hoặc kiểm tra trực tiếp trong DB. */
    public static Connection superuser(PostgreSQLContainer<?> postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void createAppRole(PostgreSQLContainer<?> postgres) {
        try (Connection c = superuser(postgres); Statement s = c.createStatement()) {
            s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + APP_ROLE + "') THEN "
                    + "CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_ROLE + "' NOSUPERUSER NOBYPASSRLS; "
                    + "END IF; END $$");
        } catch (SQLException e) {
            throw new IllegalStateException("Không tạo được vai trò " + APP_ROLE, e);
        }
    }
}
