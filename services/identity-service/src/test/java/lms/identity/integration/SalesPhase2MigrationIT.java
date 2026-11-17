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
 * Cần Docker. Migration V20 (B2.3): chạy V1-V19 bằng superuser, dựng các tenant đại diện (đã bật SALES nguyên mẫu, role
 * Thu ngân bị chỉnh, đã có role signup_default khác, đã có role tên CUSTOMER, tắt SALES, chỉ LENDING), rồi chạy V20 bằng
 * vai trò KHÔNG phải superuser (chịu RLS FORCE như chủ bảng thật). Kiểm: 21 khóa mới, khóa theo role mẫu (createLate chỉ
 * Quản lý cửa hàng, Khách hàng chỉ khóa "của tôi"), backfill không trùng và không ghi đè role đã chỉnh, chỉ mục duy nhất
 * signup_default không bị vi phạm.
 * Chạy: mvn -pl services/identity-service -am test -Dtest=SalesPhase2MigrationIT
 * -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44
 */
@Testcontainers
class SalesPhase2MigrationIT {

    static final String A = "00000000-0000-0000-0000-00000000cc01"; // SALES, role nguyên mẫu
    static final String B = "00000000-0000-0000-0000-00000000cc02"; // SALES tắt
    static final String C = "00000000-0000-0000-0000-00000000cc03"; // chỉ LENDING
    static final String E = "00000000-0000-0000-0000-00000000cc05"; // SALES, CASHIER bị chỉnh
    static final String F = "00000000-0000-0000-0000-00000000cc06"; // SALES, đã có role signup_default khác
    static final String G = "00000000-0000-0000-0000-00000000cc07"; // SALES, đã có role tên CUSTOMER

    static final List<String> PHASE2_STAFF_ORDER_KEYS = List.of("SalesOrderController/confirm",
            "SalesOrderController/markPaid", "SalesOrderController/markReadyForPickup",
            "SalesOrderController/markShipping", "SalesOrderController/complete", "SalesOrderController/cancel");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @BeforeAll
    static void migrate() throws SQLException {
        // V1-V16, dựng tenant, rồi V17-V19 (V17 backfill role mẫu pha 1 cho tenant đang bật SALES)
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/identity").target("16").load().migrate();
        exec("INSERT INTO tenants (id, code, name, status) VALUES "
                + "('" + A + "', 'shop-a', 'A', 'ACTIVE'), ('" + B + "', 'shop-b', 'B', 'ACTIVE'), "
                + "('" + C + "', 'lib-c', 'C', 'ACTIVE'), ('" + E + "', 'shop-e', 'E', 'ACTIVE'), "
                + "('" + F + "', 'shop-f', 'F', 'ACTIVE'), ('" + G + "', 'shop-g', 'G', 'ACTIVE')");
        exec("INSERT INTO tenant_modules (tenant_id, module, enabled) VALUES "
                + "('" + A + "', 'SALES', TRUE), ('" + B + "', 'SALES', FALSE), ('" + E + "', 'SALES', TRUE), "
                + "('" + F + "', 'SALES', TRUE), ('" + G + "', 'SALES', TRUE)");
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/identity").target("19").load().migrate();

        // E: tenant bỏ một khóa của Thu ngân (role đã chỉnh); F: đã có role signup_default khác; G: đã có role CUSTOMER
        exec("DELETE FROM role_permissions WHERE permission_code = 'StockItemController/get' AND role_id = "
                + "(SELECT id FROM roles WHERE tenant_id = '" + E + "' AND name = 'CASHIER')");
        exec("INSERT INTO roles (id, name, description, is_admin, tenant_id, signup_default) VALUES "
                + "('00000000-0000-0000-0000-00000000ff01', 'MEMBER', 'Role đăng ký riêng', FALSE, '" + F + "', TRUE)");
        exec("INSERT INTO roles (id, name, description, is_admin, tenant_id) VALUES "
                + "('00000000-0000-0000-0000-00000000ff02', 'CUSTOMER', 'Do người dùng tạo', FALSE, '" + G + "')");
        exec("INSERT INTO role_permissions (role_id, permission_code) VALUES "
                + "('00000000-0000-0000-0000-00000000ff02', 'TitleController/search')");

        // chủ bảng thật (identity) không phải superuser nên chịu FORCE RLS: mô phỏng V20 bằng SET ROLE
        exec("CREATE ROLE mig_runner NOSUPERUSER NOBYPASSRLS");
        exec("GRANT ALL ON ALL TABLES IN SCHEMA public TO mig_runner");
        exec("GRANT ALL ON SCHEMA public TO mig_runner");
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .initSql("SET ROLE mig_runner").locations("classpath:db/migration/identity").target("20").load()
                .migrate();
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
    void bonMuoiBonKhoaSalesCoTrongPermissionsVoiDuongDanDungKhuon() throws SQLException {
        assertThat(column("SELECT count(*)::text FROM permissions WHERE service = 'sales-service'"))
                .containsExactly("44");
        assertThat(column("SELECT http_method || '|' || path_pattern FROM permissions "
                + "WHERE code = 'ShopController/placeOrder'")).containsExactly("POST|/internal/sales/shop/orders");
        assertThat(column("SELECT http_method || '|' || path_pattern FROM permissions "
                + "WHERE code = 'SalesReturnController/createLate'")).containsExactly("POST|/api/sales/returns/late");
        assertThat(column("SELECT http_method || '|' || path_pattern FROM permissions "
                + "WHERE code = 'StorefrontController/preview'"))
                .containsExactly("GET|/internal/sales/storefront/items/{titleId}/preview");
    }

    @Test
    void mauThuNganXuLyTraTrongHanVaDonTaiQuayKhongGiamGiaKhongTraQuaHan() throws SQLException {
        List<String> cashier = perms("CASHIER");
        assertThat(cashier).hasSize(16).contains("SalesReturnController/create", "SalesReturnController/get",
                "SalesReturnController/listByOrder").containsAll(PHASE2_STAFF_ORDER_KEYS)
                .doesNotContain("SalesOrderController/applyDiscount", "SalesReturnController/createLate",
                        "RefundController/search", "RefundController/markRefunded", "SalesAuditController/search")
                .noneMatch(k -> k.startsWith("ShopController/") || k.startsWith("StorefrontController/"));
        assertThat(perms("STOCK_KEEPER")).hasSize(16).noneMatch(k -> k.startsWith("SalesOrderController/"))
                .noneMatch(k -> k.startsWith("SalesReturnController/"));
    }

    @Test
    void createLateChiCoOMauQuanLyCuaHang() throws SQLException {
        assertThat(column("SELECT t.name FROM role_template_permissions p JOIN role_templates t ON t.id = p.template_id "
                + "WHERE p.permission_code = 'SalesReturnController/createLate' ORDER BY 1"))
                .containsExactly("STORE_MANAGER");
        List<String> manager = perms("STORE_MANAGER");
        assertThat(manager).hasSize(41).contains("SalesOrderController/applyDiscount", "RefundController/search",
                "RefundController/markRefunded", "SalesAuditController/search", "PosOrderController/createWithDiscount")
                .noneMatch(k -> k.startsWith("ShopController/"));
        assertThat(column("SELECT t.name FROM role_template_permissions p JOIN role_templates t ON t.id = p.template_id "
                + "WHERE p.permission_code = 'SalesOrderController/applyDiscount' ORDER BY 1"))
                .containsExactly("STORE_MANAGER");
    }

    @Test
    void mauKhachHangChiKhoaCuaToiVaLookupVaMangCoSignupDefault() throws SQLException {
        assertThat(column("SELECT name || '|' || module || '|' || is_admin || '|' || signup_default FROM role_templates "
                + "WHERE name = 'CUSTOMER'")).containsExactly("CUSTOMER|SALES|false|true");
        assertThat(perms("CUSTOMER")).containsExactly("ShopController/cancelMyOrder", "ShopController/myOrder",
                "ShopController/myOrders", "ShopController/placeOrder", "TitleController/lookup");
        // đúng một mẫu signup_default cho module SALES
        assertThat(column("SELECT count(*)::text FROM role_templates WHERE signup_default AND module = 'SALES'"))
                .containsExactly("1");
    }

    @Test
    void backfillTenantNguyenMauNhanKhoaMoiVaRoleCustomerCoCo() throws SQLException {
        assertThat(tenantRolePerms(A, "CASHIER")).isEqualTo(perms("CASHIER"));
        assertThat(tenantRolePerms(A, "STORE_MANAGER")).isEqualTo(perms("STORE_MANAGER"));
        assertThat(tenantRolePerms(A, "STOCK_KEEPER")).isEqualTo(perms("STOCK_KEEPER"));
        assertThat(tenantRolePerms(A, "CUSTOMER")).isEqualTo(perms("CUSTOMER"));
        assertThat(column("SELECT signup_default::text FROM roles WHERE tenant_id = '" + A + "' AND name = 'CUSTOMER'"))
                .containsExactly("true");
        assertThat(column("SELECT name FROM roles WHERE tenant_id = '" + A + "' ORDER BY name"))
                .containsExactly("CASHIER", "CUSTOMER", "STOCK_KEEPER", "STORE_MANAGER");
    }

    @Test
    void roleThuNganDaChinhKhongBiGanKhoaMoiVaKhongBiGhiDe() throws SQLException {
        List<String> cashier = tenantRolePerms(E, "CASHIER");
        assertThat(cashier).hasSize(6).doesNotContain("StockItemController/get", "SalesReturnController/create",
                "SalesOrderController/confirm");
        // role còn nguyên của cùng tenant vẫn được nâng cấp
        assertThat(tenantRolePerms(E, "STORE_MANAGER")).isEqualTo(perms("STORE_MANAGER"));
        assertThat(column("SELECT signup_default::text FROM roles WHERE tenant_id = '" + E + "' AND name = 'CUSTOMER'"))
                .containsExactly("true");
    }

    @Test
    void tenantDaCoRoleSignupDefaultKhacNhanCustomerKhongCoCoVaChiMucDuyNhatNguyenVen() throws SQLException {
        assertThat(column("SELECT signup_default::text FROM roles WHERE tenant_id = '" + F + "' AND name = 'CUSTOMER'"))
                .containsExactly("false");
        assertThat(tenantRolePerms(F, "CUSTOMER")).isEqualTo(perms("CUSTOMER"));
        assertThat(column("SELECT name FROM roles WHERE tenant_id = '" + F + "' AND signup_default"))
                .containsExactly("MEMBER");
        // mọi tenant có tối đa một role signup_default
        assertThat(column("SELECT count(*)::text FROM (SELECT tenant_id FROM roles WHERE signup_default "
                + "GROUP BY tenant_id HAVING count(*) > 1) x")).containsExactly("0");
    }

    @Test
    void tenantDaCoRoleTenCustomerDuocGiuNguyenKhongNhanBan() throws SQLException {
        assertThat(column("SELECT description || '|' || signup_default FROM roles WHERE tenant_id = '" + G
                + "' AND name = 'CUSTOMER'")).containsExactly("Do người dùng tạo|false");
        assertThat(tenantRolePerms(G, "CUSTOMER")).containsExactly("TitleController/search");
        assertThat(column("SELECT count(*)::text FROM roles WHERE tenant_id = '" + G + "' AND name = 'CUSTOMER'"))
                .containsExactly("1");
    }

    @Test
    void tenantTatSalesHoacChiLendingKhongNhanRoleMoi() throws SQLException {
        assertThat(column("SELECT count(*)::text FROM roles WHERE tenant_id IN ('" + B + "', '" + C + "')"))
                .containsExactly("0");
    }

    @Test
    void roleQuanTriKhongBaoGioMangCoSignupDefault() throws SQLException {
        assertThat(column("SELECT count(*)::text FROM roles WHERE is_admin AND signup_default")).containsExactly("0");
        assertThat(column("SELECT count(*)::text FROM role_templates WHERE is_admin AND signup_default"))
                .containsExactly("0");
    }
}
