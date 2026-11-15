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
 * Cần Docker. Chạy V1-V10, chèn dữ liệu có sẵn, rồi chạy các script sau (V11 mô hình tenant, V13 cách ly dữ liệu
 * identity theo tenant) để kiểm chứng việc nâng cấp dữ liệu. Kết nối bằng superuser nên không bị RLS lọc.
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
            s.execute("INSERT INTO reader_profiles (user_id, card_no, full_name, email, reader_type, card_expiry_date, "
                    + "status) VALUES ('" + U_BOTH + "', 'CARD-1', 'Both', 'both@x.vn', 'STUDENT', '2030-01-01', 'ACTIVE')");
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

    // ---- V13: cách ly dữ liệu identity theo tenant ----

    static final List<String> TENANT_TABLES = List.of("roles", "user_roles", "departments", "reader_profiles");

    private static String otherTenant(String code) throws SQLException {
        String id = java.util.UUID.randomUUID().toString();
        exec("INSERT INTO tenants (id, code, name, status) VALUES ('" + id + "', '" + code + "', 'T', 'ACTIVE')");
        return id;
    }

    @Test
    void duLieuCoSanCuaBangThuocTenantVeTenantMacDinh() throws SQLException {
        for (String table : TENANT_TABLES) {
            // ngoài tenant mặc định chỉ có dòng do các test khác của lớp này thêm (tenant mã mig-*)
            assertThat(column("SELECT count(*)::text FROM " + table + " WHERE tenant_id <> '" + DEFAULT_TENANT
                    + "' AND tenant_id NOT IN (SELECT id FROM tenants WHERE code LIKE 'mig-%')")).as(table)
                    .containsExactly("0");
            assertThat(column("SELECT is_nullable FROM information_schema.columns WHERE table_name = '" + table
                    + "' AND column_name = 'tenant_id'")).as(table).containsExactly("NO");
        }
        assertThat(column("SELECT DISTINCT tenant_id::text FROM roles WHERE id::text LIKE '00000000-0000-0000-0000-0000000000a%'"))
                .containsExactly(DEFAULT_TENANT);
        assertThat(column("SELECT tenant_id::text FROM departments WHERE id = '" + DEPT + "'")).containsExactly(DEFAULT_TENANT);
        assertThat(column("SELECT tenant_id::text FROM reader_profiles WHERE user_id = '" + U_BOTH + "' AND card_no = 'CARD-1' "
                + "AND tenant_id NOT IN (SELECT id FROM tenants WHERE code LIKE 'mig-%')")).containsExactly(DEFAULT_TENANT);
        assertThat(column("SELECT count(*)::text FROM user_roles WHERE tenant_id = '" + DEFAULT_TENANT + "'"))
                .isNotEqualTo(List.of("0"));
    }

    @Test
    void rlsBatVaForceTrenBangThuocTenantNhungKhongTrenBangToanCuc() throws SQLException {
        assertThat(column("SELECT relname FROM pg_class WHERE relrowsecurity AND relforcerowsecurity "
                + "AND relnamespace = 'public'::regnamespace"))
                .containsExactlyInAnyOrder("roles", "user_roles", "departments", "reader_profiles", "role_permissions");
        assertThat(column("SELECT relname FROM pg_class WHERE relrowsecurity AND relname IN ('tenant_memberships', "
                + "'refresh_tokens', 'permissions', 'credentials', 'users', 'tenants')")).isEmpty();
    }

    @Test
    void rangBuocUniqueTheoTenant() throws SQLException {
        String other = otherTenant("mig-uniq");
        // cùng tên role, cùng mã phòng ban, cùng số thẻ và cùng user ở tenant khác: được
        exec("INSERT INTO roles (id, tenant_id, name, description) VALUES (gen_random_uuid(), '" + other
                + "', 'MEMBER', 'x')");
        exec("INSERT INTO departments (id, tenant_id, code, name, status) VALUES (gen_random_uuid(), '" + other
                + "', 'IT', 'CNTT B', 'ACTIVE')");
        exec("INSERT INTO reader_profiles (tenant_id, user_id, card_no, full_name, email, reader_type, "
                + "card_expiry_date, status) VALUES ('" + other + "', '" + U_BOTH + "', 'CARD-1', 'Both', 'both@x.vn', "
                + "'STUDENT', '2030-01-01', 'ACTIVE')");
        // trùng trong cùng tenant: bị từ chối
        assertThatThrownBy(() -> exec("INSERT INTO roles (id, tenant_id, name, description) VALUES (gen_random_uuid(), '"
                + DEFAULT_TENANT + "', 'MEMBER', 'x')")).hasMessageContaining("uq_roles_tenant_name");
        assertThatThrownBy(() -> exec("INSERT INTO departments (id, tenant_id, code, name, status) VALUES "
                + "(gen_random_uuid(), '" + DEFAULT_TENANT + "', 'IT', 'Trùng', 'ACTIVE')"))
                .hasMessageContaining("uq_departments_tenant_code");
        assertThatThrownBy(() -> exec("INSERT INTO reader_profiles (tenant_id, user_id, card_no, full_name, email, "
                + "reader_type, card_expiry_date, status) VALUES ('" + DEFAULT_TENANT + "', gen_random_uuid(), 'CARD-1', "
                + "'X', 'x@x.vn', 'STUDENT', '2030-01-01', 'ACTIVE')"))
                .hasMessageContaining("uq_reader_profiles_tenant_card_no");
        assertThatThrownBy(() -> exec("INSERT INTO reader_profiles (tenant_id, user_id, card_no, full_name, email, "
                + "reader_type, card_expiry_date, status) VALUES ('" + DEFAULT_TENANT + "', '" + U_BOTH + "', 'CARD-9', "
                + "'X', 'x@x.vn', 'STUDENT', '2030-01-01', 'ACTIVE')"))
                .hasMessageContaining("reader_profiles_pkey");
    }

    @Test
    void ganRoleVaPhongBanChaPhaiCungTenant() throws SQLException {
        String other = otherTenant("mig-fk");
        String adminRole = "00000000-0000-0000-0000-0000000000a1"; // role seed, thuộc tenant mặc định sau V13
        assertThatThrownBy(() -> exec("INSERT INTO user_roles (user_id, role_id, tenant_id) VALUES (gen_random_uuid(), '"
                + adminRole + "', '" + other + "')")).hasMessageContaining("fk_user_roles_role_tenant");
        assertThatThrownBy(() -> exec("INSERT INTO departments (id, tenant_id, code, name, parent_id, status) VALUES "
                + "(gen_random_uuid(), '" + other + "', 'CON', 'Con', '" + DEPT + "', 'ACTIVE')"))
                .hasMessageContaining("fk_departments_parent_tenant");
    }

    @Test
    void vaiTroKhongBypassRlsKhongCoTenantThiKhongThayGiCoTenantThiChiThayTenantDo() throws SQLException {
        exec("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mig_rls_probe') THEN "
                + "CREATE ROLE mig_rls_probe LOGIN PASSWORD 'probe' NOSUPERUSER NOBYPASSRLS; END IF; END $$");
        exec("GRANT SELECT ON ALL TABLES IN SCHEMA public TO mig_rls_probe");
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "mig_rls_probe", "probe");
                Statement s = c.createStatement()) {
            for (String table : List.of("roles", "user_roles", "departments", "reader_profiles", "role_permissions")) {
                try (ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
                    rs.next();
                    assertThat(rs.getInt(1)).as(table).isZero();
                }
            }
            s.execute("SELECT set_config('app.tenant_id', '" + DEFAULT_TENANT + "', false)");
            try (ResultSet rs = s.executeQuery("SELECT count(*) FROM roles")) {
                rs.next();
                assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(5);
            }
            try (ResultSet rs = s.executeQuery("SELECT count(*) FROM roles WHERE tenant_id <> '" + DEFAULT_TENANT + "'")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    // ---- V14: bỏ users.department_id, phòng ban chỉ còn ở membership ----

    @Test
    void v14BoCotPhongBanCuaUsersVaGiuPhongBanTrongMembership() throws SQLException {
        assertThat(column("SELECT column_name FROM information_schema.columns WHERE table_name = 'users' "
                + "AND column_name = 'department_id'")).isEmpty();
        assertThat(column("SELECT COALESCE(department_id::text, '-') FROM tenant_memberships WHERE user_id = '" + U_BOTH
                + "' AND tenant_id = '" + DEFAULT_TENANT + "'")).containsExactly(DEPT);
    }
}
