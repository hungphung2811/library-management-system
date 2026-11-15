package lms.circulation.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Cấu hình IT như môi trường thật (R7/R13, cùng mẫu identity): Flyway chạy bằng superuser của container (chủ bảng),
 * ứng dụng kết nối bằng vai trò {@value #APP_ROLE} (NOSUPERUSER NOBYPASSRLS) nên chịu RLS. Quyền dữ liệu do callback
 * afterMigrate.sql của migration circulation cấp. Gọi trong {@code @DynamicPropertySource}; không dùng
 * {@code @ServiceConnection} cùng lúc.
 */
public final class AppRoleDatabase {

    public static final String APP_ROLE = "circulation_app";

    private AppRoleDatabase() {
    }

    public static void register(PostgreSQLContainer<?> postgres, DynamicPropertyRegistry registry) {
        createAppRole(postgres);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_ROLE);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/circulation");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    /** Kết nối superuser (bỏ qua RLS): seed dữ liệu nhiều tenant hoặc kiểm tra trực tiếp trong DB. */
    public static Connection superuser(PostgreSQLContainer<?> postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    public static void asSuperuser(PostgreSQLContainer<?> postgres, String sql) {
        try (Connection c = superuser(postgres); Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private static void createAppRole(PostgreSQLContainer<?> postgres) {
        asSuperuser(postgres, "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + APP_ROLE + "') THEN "
                + "CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_ROLE + "' NOSUPERUSER NOBYPASSRLS; END IF; END $$");
    }
}
