package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Cần Docker. Migration V17 (B1.6, dừng ở V17, các migration sau có IT riêng): chạy V1-V16, dựng tenant đã bật SALES (A), tắt SALES (B), chỉ LENDING (C) và tenant
 * đã có sẵn role tên CASHIER do người dùng tạo (D), rồi chạy V17 bằng vai trò KHÔNG phải superuser (chịu RLS FORCE như
 * chủ bảng thật) để kiểm chứng backfill đặt đúng app.tenant_id, bỏ qua tên đã có và không nhân bản.
 * Chạy: mvn -pl services/identity-service -am test -Dtest=SalesTemplatesMigrationIT
 * -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44
 */
@Testcontainers
class SalesTemplatesMigrationIT {

    static final String A = "00000000-0000-0000-0000-00000000bb01";
    static final String B = "00000000-0000-0000-0000-00000000bb02";
    static final String C = "00000000-0000-0000-0000-00000000bb03";
    static final String D = "00000000-0000-0000-0000-00000000bb04";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @BeforeAll
    static void migrate() throws SQLException {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/identity").target("16").load().migrate();

        exec("INSERT INTO tenants (id, code, name, status) VALUES "
                + "('" + A + "', 'shop-a', 'A', 'ACTIVE'), ('" + B + "', 'shop-b', 'B', 'ACTIVE'), "
                + "('" + C + "', 'lib-c', 'C', 'ACTIVE'), ('" + D + "', 'shop-d', 'D', 'ACTIVE')");
        exec("INSERT INTO tenant_modules (tenant_id, module, enabled) VALUES "
                + "('" + A + "', 'SALES', TRUE), ('" + B + "', 'SALES', FALSE), ('" + D + "', 'SALES', TRUE)");
        // D đã có role CASHIER tự tạo (mô tả riêng, một quyền): backfill phải bỏ qua, không ghi đè (superuser bỏ qua RLS)
        exec("INSERT INTO roles (id, name, description, is_admin, tenant_id) VALUES "
                + "('00000000-0000-0000-0000-00000000dd01', 'CASHIER', 'Do người dùng tạo', FALSE, '" + D + "')");
        exec("INSERT INTO role_permissions (role_id, permission_code) VALUES "
                + "('00000000-0000-0000-0000-00000000dd01', 'TitleController/search')");

        // chủ bảng thật (identity) không phải superuser nên chịu FORCE RLS: mô phỏng bằng SET ROLE
        exec("CREATE ROLE mig_runner NOSUPERUSER NOBYPASSRLS");
        exec("GRANT ALL ON ALL TABLES IN SCHEMA public TO mig_runner");
        exec("GRANT ALL ON SCHEMA public TO mig_runner");
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .initSql("SET ROLE mig_runner").locations("classpath:db/migration/identity").target("17").load().migrate();
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

    static List<String> perms(String templateName) throws SQLException {
        return column("SELECT p.permission_code FROM role_template_permissions p JOIN role_templates t "
                + "ON t.id = p.template_id WHERE t.name = '" + templateName + "' ORDER BY 1");
    }

    static List<String> tenantRolePerms(String tenant, String role) throws SQLException {
        return column("SELECT rp.permission_code FROM role_permissions rp JOIN roles r ON r.id = rp.role_id "
                + "WHERE r.tenant_id = '" + tenant + "' AND r.name = '" + role + "' ORDER BY 1");
    }

    @Test
    void haiMuoiBaKhoaSalesVaKhoaLookupCoTrongPermissions() throws SQLException {
        assertThat(column("SELECT count(*)::text FROM permissions WHERE service = 'sales-service'"))
                .containsExactly("23");
        assertThat(column("SELECT service || '|' || http_method FROM permissions WHERE code = 'TitleController/lookup'"))
                .containsExactly("catalog-service|POST");
        assertThat(column("SELECT service || '|' || http_method || '|' || path_pattern FROM permissions "
                + "WHERE code = 'PosOrderController/createWithDiscount'"))
                .containsExactly("sales-service|POST|/api/sales/pos-orders/discounted");
    }

    @Test
    void bonMauSalesThuocModuleSalesKhongLaQuanTri() throws SQLException {
        assertThat(column("SELECT name || '|' || module || '|' || is_admin FROM role_templates WHERE module = 'SALES' "
                + "ORDER BY name")).containsExactly("CASHIER|SALES|false", "STOCK_KEEPER|SALES|false",
                "STORE_MANAGER|SALES|false");
        assertThat(column("SELECT count(*)::text FROM role_templates")).containsExactly("8");
    }

    @Test
    void mauThuNganKhongCoGiamGiaVaLienHeKhach() throws SQLException {
        assertThat(perms("CASHIER")).containsExactly("PosOrderController/create", "SalesOrderController/get",
                "SalesOrderController/receipt", "SalesOrderController/search", "StockItemController/get",
                "StockItemController/search", "TitleController/lookup");
        assertThat(perms("STORE_MANAGER")).hasSize(24).contains("PosOrderController/createWithDiscount",
                "SalesOrderController/getContact", "TitleController/lookup");
        assertThat(perms("STOCK_KEEPER")).hasSize(16).doesNotContain("PosOrderController/create",
                "SalesOrderController/getContact");
    }

    @Test
    void backfillChepRoleChoTenantDangBatSalesDungQuyen() throws SQLException {
        assertThat(column("SELECT name FROM roles WHERE tenant_id = '" + A + "' ORDER BY name"))
                .containsExactly("CASHIER", "STOCK_KEEPER", "STORE_MANAGER");
        assertThat(tenantRolePerms(A, "CASHIER")).isEqualTo(perms("CASHIER"));
        assertThat(tenantRolePerms(A, "STOCK_KEEPER")).isEqualTo(perms("STOCK_KEEPER"));
        assertThat(tenantRolePerms(A, "STORE_MANAGER")).isEqualTo(perms("STORE_MANAGER"));
    }

    @Test
    void tenantTatSalesHoacChiLendingKhongNhanRole() throws SQLException {
        assertThat(column("SELECT count(*)::text FROM roles WHERE tenant_id IN ('" + B + "', '" + C + "')"))
                .containsExactly("0");
    }

    @Test
    void tenantDaCoRoleTrungTenDuocBoQuaKhongGhiDe() throws SQLException {
        assertThat(column("SELECT description FROM roles WHERE tenant_id = '" + D + "' AND name = 'CASHIER'"))
                .containsExactly("Do người dùng tạo");
        assertThat(tenantRolePerms(D, "CASHIER")).containsExactly("TitleController/search");
        assertThat(column("SELECT name FROM roles WHERE tenant_id = '" + D + "' ORDER BY name"))
                .containsExactly("CASHIER", "STOCK_KEEPER", "STORE_MANAGER");
        assertThat(tenantRolePerms(D, "STOCK_KEEPER")).isEqualTo(perms("STOCK_KEEPER"));
    }
}
