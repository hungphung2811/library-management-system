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
import java.util.Set;
import java.util.TreeSet;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cần Docker. Chạy V1-V10, chèn dữ liệu có sẵn, rồi chạy V11 để kiểm chứng việc nâng cấp dữ liệu.
 * Chạy: mvn test -Dtest=TenantMigrationIT -DargLine=-Dapi.version=1.44
 */
@Testcontainers
class TenantMigrationIT {

    static final String DEFAULT_TENANT = "00000000-0000-0000-0000-0000000000d1";
    static final String DEPT = "00000000-0000-0000-0000-00000000d001";
    static final String U_BOTH = "00000000-0000-0000-0000-00000000e001";
    static final String U_ONLY_USERS = "00000000-0000-0000-0000-00000000e002";
    static final String U_ONLY_CRED = "00000000-0000-0000-0000-00000000e003";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @BeforeAll
    static void migrate() throws SQLException {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/identity").target("10").load().migrate();

        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO departments (id, code, name, status) VALUES ('" + DEPT + "', 'IT', 'CNTT', 'ACTIVE')");
            s.execute("INSERT INTO users (id, username, full_name, email, department_id, status) VALUES "
                    + "('" + U_BOTH + "', 'both', 'Both', 'both@x.vn', '" + DEPT + "', 'ACTIVE'), "
                    + "('" + U_ONLY_USERS + "', 'onlyusers', 'OnlyUsers', 'ou@x.vn', NULL, 'ACTIVE')");
            s.execute("INSERT INTO credentials (user_id, username, password_hash, active) VALUES "
                    + "('" + U_BOTH + "', 'both', 'h', true), ('" + U_ONLY_CRED + "', 'onlycred', 'h', true)");
        }

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
    void tenantMacDinhTonTai() throws SQLException {
        assertThat(column("SELECT code || '|' || name || '|' || status FROM tenants WHERE id = '" + DEFAULT_TENANT + "'"))
                .containsExactly("default|Thư viện mặc định|ACTIVE");
    }

    @Test
    void moiUserCoMembershipActiveTrongTenantMacDinhVaSaoChepPhongBan() throws SQLException {
        assertThat(column("SELECT user_id || '|' || status || '|' || COALESCE(department_id::text, '-') "
                + "FROM tenant_memberships WHERE tenant_id = '" + DEFAULT_TENANT + "'"))
                .containsExactlyInAnyOrder(
                        U_BOTH + "|ACTIVE|" + DEPT,
                        U_ONLY_USERS + "|ACTIVE|-",
                        U_ONLY_CRED + "|ACTIVE|-");
    }

    @Test
    void roleTemplatesCo5MauVoiDungMotMauQuanTri() throws SQLException {
        assertThat(column("SELECT name FROM role_templates"))
                .containsExactlyInAnyOrder("SUPER_ADMIN", "LIBRARY_ADMIN", "LIBRARIAN", "DEPARTMENT_MANAGER", "MEMBER");
        assertThat(column("SELECT name FROM role_templates WHERE is_admin")).containsExactly("SUPER_ADMIN");
    }

    @Test
    void roleTemplatePermissionsKhopQuyenHieuLucSauV9() throws SQLException {
        Set<String> member = new TreeSet<>(column("SELECT p.permission_code FROM role_template_permissions p "
                + "JOIN role_templates t ON t.id = p.template_id WHERE t.name = 'MEMBER'"));
        assertThat(member).containsExactlyInAnyOrder(
                "TitleController/search", "TitleController/get", "BookCopyController/list",
                "BorrowingController/borrow", "BorrowingController/renew",
                "ReservationController/reserve", "ReservationController/mine", "ReservationController/cancel");

        // Mẫu khớp từng role seed gốc (quyền hiệu lực không đổi)
        assertThat(column("SELECT t.name FROM role_templates t WHERE "
                + "(SELECT count(*) FROM role_template_permissions p WHERE p.template_id = t.id) <> "
                + "(SELECT count(*) FROM role_permissions r WHERE r.role_id = t.id)")).isEmpty();
        assertThat(column("SELECT count(*)::text FROM role_template_permissions p "
                + "JOIN role_templates t ON t.id = p.template_id WHERE t.name = 'LIBRARIAN' "
                + "AND p.permission_code IN ('AccessControlController/assignRole', 'AuditLogController/search')"))
                .containsExactly("0");
        assertThat(column("SELECT count(*)::text FROM role_template_permissions p "
                + "JOIN role_templates t ON t.id = p.template_id WHERE t.name = 'SUPER_ADMIN' "
                + "AND p.permission_code = 'AuditLogController/search'")).containsExactly("1");
    }

    @Test
    void platformAdminMacDinhFalse() throws SQLException {
        assertThat(column("SELECT platform_admin::text FROM credentials")).isNotEmpty().containsOnly("false");
    }

    @Test
    void lastTenantIdMacDinhNull() throws SQLException {
        assertThat(column("SELECT count(*)::text FROM credentials WHERE last_tenant_id IS NOT NULL")).containsExactly("0");
    }

    @Test
    void membershipKhongChoPhepTrungKhoaChinh() {
        assertThatThrownBy(() -> exec("INSERT INTO tenant_memberships (user_id, tenant_id, status) VALUES ('"
                + U_BOTH + "', '" + DEFAULT_TENANT + "', 'ACTIVE')")).hasMessageContaining("tenant_memberships_pkey");
    }

    @Test
    void maTenantPhaiDuyNhat() throws SQLException {
        exec("INSERT INTO tenants (id, code, name, status) VALUES (gen_random_uuid(), 'abc', 'A', 'ACTIVE')");
        assertThatThrownBy(() -> exec("INSERT INTO tenants (id, code, name, status) "
                + "VALUES (gen_random_uuid(), 'abc', 'B', 'ACTIVE')")).hasMessageContaining("tenants_code_key");
    }

    @Test
    void trangThaiTenantVaMembershipBiRangBuoc() {
        assertThatThrownBy(() -> exec("INSERT INTO tenants (id, code, name, status) "
                + "VALUES (gen_random_uuid(), 'zz', 'Z', 'BAD')")).hasMessageContaining("check");
        assertThatThrownBy(() -> exec("INSERT INTO tenant_memberships (user_id, tenant_id, status) VALUES "
                + "(gen_random_uuid(), '" + DEFAULT_TENANT + "', 'BAD')")).hasMessageContaining("check");
    }
}
