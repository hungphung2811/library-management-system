package lms.sales.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Cấu hình IT như môi trường thật: Flyway chạy bằng superuser của container (chủ bảng), ứng dụng kết nối bằng vai trò
 * {@value #APP_ROLE} (NOSUPERUSER NOBYPASSRLS, như infrastructure/postgres/init-databases.sql) nên chịu RLS.
 * Quyền dữ liệu của vai trò do callback afterMigrate.sql của migration sales cấp. Vai trò phải có trước khi Flyway chạy.
 *
 * <p>Gọi trong {@code @DynamicPropertySource} (container đã chạy). Không dùng {@code @ServiceConnection} cùng lúc vì nó
 * ghi đè thông tin kết nối datasource.
 */
public final class AppRoleDatabase {

    public static final String APP_ROLE = "sales_app";

    private AppRoleDatabase() {
    }

    public static void register(PostgreSQLContainer<?> postgres, DynamicPropertyRegistry registry) {
        createAppRole(postgres);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_ROLE);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    /** Kết nối superuser (bỏ qua RLS): seed dữ liệu của nhiều tenant hoặc kiểm tra trực tiếp trong DB. */
    public static Connection superuser(PostgreSQLContainer<?> postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /** Kết nối bằng vai trò ứng dụng, chưa đặt app.tenant_id (để kiểm tra RLS fail-closed). */
    public static Connection appRole(PostgreSQLContainer<?> postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), APP_ROLE, APP_ROLE);
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
