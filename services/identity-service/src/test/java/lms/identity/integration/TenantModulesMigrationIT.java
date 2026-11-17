package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cần Docker. Migration V16 (ADR-0009): chạy V1-V15, chèn thêm tenant (ACTIVE và SUSPENDED), rồi chạy V16 để kiểm chứng
 * backfill LENDING cho MỌI tenant hiện có, cột role_templates.module và các ràng buộc của tenant_modules. Kết nối
 * bằng superuser. Chạy: mvn -pl services/identity-service -am test -Dtest=TenantModulesMigrationIT
 * -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44
 */
@Testcontainers
class TenantModulesMigrationIT {

    static final String DEFAULT_TENANT = "00000000-0000-0000-0000-0000000000d1";
    static final String TENANT_A = "00000000-0000-0000-0000-00000000aa01";
    static final String TENANT_SUSPENDED = "00000000-0000-0000-0000-00000000aa02";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @BeforeAll
    static void migrate() throws SQLException {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/identity").target("15").load().migrate();

        // tenant có sẵn trước V16, kể cả tenant đang tạm ngưng: tất cả phải được backfill LENDING
        exec("INSERT INTO tenants (id, code, name, status) VALUES "
                + "('" + TENANT_A + "', 'lib-a', 'Thư viện A', 'ACTIVE'), "
                + "('" + TENANT_SUSPENDED + "', 'lib-s', 'Thư viện S', 'SUSPENDED')");

        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/identity").load().migrate();
    }

    static Connection conn() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    static List<String> column(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = conn(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    static void exec(String sql) throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @Test
    void moiTenantHienCoDuocBackfillDungMotDongLendingDangBat() throws SQLException {
        assertThat(column("SELECT t.code || '|' || m.module || '|' || m.enabled FROM tenants t "
                + "JOIN tenant_modules m ON m.tenant_id = t.id ORDER BY t.code"))
                .containsExactly("default|LENDING|true", "lib-a|LENDING|true", "lib-s|LENDING|true");
        assertThat(column("SELECT count(*)::text FROM tenants t WHERE NOT EXISTS "
                + "(SELECT 1 FROM tenant_modules m WHERE m.tenant_id = t.id)")).containsExactly("0");
    }

    @Test
    void mauQuanTriGiuLoiConCacMauConLaiThuocLending() throws SQLException {
        assertThat(column("SELECT name FROM role_templates WHERE module IS NULL")).containsExactly("SUPER_ADMIN");
        assertThat(column("SELECT count(*)::text FROM role_templates WHERE module = 'LENDING'")).containsExactly("4");
        assertThat(column("SELECT count(*)::text FROM role_templates WHERE module IS NULL OR module = 'LENDING'")).containsExactly("5");
        // mẫu lõi trùng mẫu có cờ quản trị: tenant chỉ-SALES vẫn có role quản trị
        assertThat(column("SELECT name FROM role_templates WHERE is_admin AND module IS NULL"))
                .containsExactly("SUPER_ADMIN");
    }

    @Test
    void tenantModulesKhongBatRlsLaBangToanCuc() throws SQLException {
        assertThat(column("SELECT relrowsecurity::text FROM pg_class WHERE relname = 'tenant_modules'"))
                .containsExactly("false");
    }

    @Test
    void rangBuocMaModuleKhoaChinhVaKhoaNgoai() {
        assertThatThrownBy(() -> exec("INSERT INTO tenant_modules (tenant_id, module) VALUES ('" + TENANT_A
                + "', 'PAYROLL')")).hasMessageContaining("tenant_modules_module_check");
        assertThatThrownBy(() -> exec("INSERT INTO tenant_modules (tenant_id, module) VALUES ('" + TENANT_A
                + "', 'LENDING')")).hasMessageContaining("tenant_modules_pkey");
        assertThatThrownBy(() -> exec("INSERT INTO tenant_modules (tenant_id, module) VALUES (gen_random_uuid(), "
                + "'SALES')")).hasMessageContaining("tenant_modules_tenant_id_fkey");
        assertThatThrownBy(() -> exec("UPDATE role_templates SET module = 'PAYROLL' WHERE name = 'SUPER_ADMIN'"))
                .hasMessageContaining("role_templates_module_check");
    }

    @Test
    void themModuleSalesChoTenantHienCoDuocVaGiuDongKhiTat() throws SQLException {
        exec("INSERT INTO tenant_modules (tenant_id, module) VALUES ('" + TENANT_A + "', 'SALES')");
        exec("UPDATE tenant_modules SET enabled = FALSE WHERE tenant_id = '" + TENANT_A + "' AND module = 'SALES'");

        assertThat(column("SELECT module || '|' || enabled FROM tenant_modules WHERE tenant_id = '" + TENANT_A
                + "' ORDER BY module")).containsExactly("LENDING|true", "SALES|false");
        exec("DELETE FROM tenant_modules WHERE tenant_id = '" + TENANT_A + "' AND module = 'SALES'");
    }
}
