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
 * Cần Docker. Migration V22 (ADR-0015 B3): chạy V1-V21 bằng superuser, dựng tenant đại diện (nguyên mẫu, CUSTOMER bị
 * chỉnh, STORE_MANAGER bị chỉnh, mất role CUSTOMER, tắt SALES, chỉ LENDING), rồi chạy V22 bằng vai trò KHÔNG phải
 * superuser (chịu RLS FORCE như chủ bảng thật). Kiểm: 6 khóa mới đúng khuôn, mẫu c3/c4 nhận khóa, c1/c2 không, backfill
 * chỉ cho role còn nguyên mẫu (không ghi đè role đã chỉnh), tenant tắt SALES/LENDING không bị đụng.
 * Chạy: mvn -o -pl services/identity-service -am test -Dtest=SalesShopContactMigrationIT
 * -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44
 */
@Testcontainers
class SalesShopContactMigrationIT {

    static final String A = "00000000-0000-0000-0000-00000000dd01"; // SALES, role nguyên mẫu
    static final String B = "00000000-0000-0000-0000-00000000dd02"; // SALES tắt
    static final String C = "00000000-0000-0000-0000-00000000dd03"; // chỉ LENDING
    static final String E = "00000000-0000-0000-0000-00000000dd05"; // SALES, CUSTOMER bị chỉnh (bỏ lookup)
    static final String F = "00000000-0000-0000-0000-00000000dd06"; // SALES, STORE_MANAGER bị chỉnh (bỏ một khóa)
    static final String G = "00000000-0000-0000-0000-00000000dd07"; // SALES, đã xóa role CUSTOMER

    static final List<String> STAFF_KEYS = List.of("SalesContactController/anonymize", "SalesContactController/get",
            "SalesContactController/getContact", "SalesContactController/search",
            "SalesContactController/updateStatus");
    static final String CUSTOMER_KEY = "ShopContactController/create";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static Flyway flyway(String target, String initSql) {
        var cfg = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/identity").target(target);
        if (initSql != null) {
            cfg.initSql(initSql);
        }
        return cfg.load();
    }

    @BeforeAll
    static void migrate() throws SQLException {
        flyway("16", null).migrate();
        exec("INSERT INTO tenants (id, code, name, status) VALUES "
                + "('" + A + "', 'shop-a', 'A', 'ACTIVE'), ('" + B + "', 'shop-b', 'B', 'ACTIVE'), "
                + "('" + C + "', 'lib-c', 'C', 'ACTIVE'), ('" + E + "', 'shop-e', 'E', 'ACTIVE'), "
                + "('" + F + "', 'shop-f', 'F', 'ACTIVE'), ('" + G + "', 'shop-g', 'G', 'ACTIVE')");
        exec("INSERT INTO tenant_modules (tenant_id, module, enabled) VALUES "
                + "('" + A + "', 'SALES', TRUE), ('" + B + "', 'SALES', FALSE), ('" + E + "', 'SALES', TRUE), "
                + "('" + F + "', 'SALES', TRUE), ('" + G + "', 'SALES', TRUE)");
        flyway("21", null).migrate();

        // E: tenant bỏ khóa lookup của role CUSTOMER; F: STORE_MANAGER thiếu một khóa; G: đã xóa role CUSTOMER
        exec("DELETE FROM role_permissions WHERE permission_code = 'TitleController/lookup' AND role_id = "
                + "(SELECT id FROM roles WHERE tenant_id = '" + E + "' AND name = 'CUSTOMER')");
        exec("DELETE FROM role_permissions WHERE permission_code = 'RefundController/search' AND role_id = "
                + "(SELECT id FROM roles WHERE tenant_id = '" + F + "' AND name = 'STORE_MANAGER')");
        exec("DELETE FROM role_permissions WHERE role_id = "
                + "(SELECT id FROM roles WHERE tenant_id = '" + G + "' AND name = 'CUSTOMER')");
        exec("DELETE FROM roles WHERE tenant_id = '" + G + "' AND name = 'CUSTOMER'");

        // chủ bảng thật (identity) không phải superuser nên chịu FORCE RLS: mô phỏng V22 bằng SET ROLE
        exec("CREATE ROLE mig_runner NOSUPERUSER NOBYPASSRLS");
        exec("GRANT ALL ON ALL TABLES IN SCHEMA public TO mig_runner");
        exec("GRANT ALL ON SCHEMA public TO mig_runner");
        flyway("22", "SET ROLE mig_runner").migrate();
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
    void sauKhoaMoiCoTrongPermissionsVoiDuongDanDungKhuon() throws SQLException {
        assertThat(column("SELECT count(*)::text FROM permissions WHERE service = 'sales-service'"))
                .containsExactly("50");
        assertThat(column("SELECT http_method || '|' || path_pattern FROM permissions WHERE code = '" + CUSTOMER_KEY
                + "'")).containsExactly("POST|/internal/sales/shop/contact-requests");
        assertThat(column("SELECT http_method || '|' || path_pattern FROM permissions "
                + "WHERE code = 'SalesContactController/updateStatus'"))
                .containsExactly("PUT|/api/sales/contact-requests/{id}/status");
        assertThat(column("SELECT http_method || '|' || path_pattern FROM permissions "
                + "WHERE code = 'SalesContactController/anonymize'"))
                .containsExactly("POST|/api/sales/contact-requests/{id}/anonymization");
        assertThat(column("SELECT count(*)::text FROM permissions WHERE code LIKE 'SalesContactController/%' "
                + "OR code LIKE 'ShopContactController/%'")).containsExactly("6");
    }

    @Test
    void mauKhachHangCoKhoaGuiVaMauQuanLyCoNamKhoaNhanVienKhoaKhongVaoThuNganThuKho() throws SQLException {
        assertThat(perms("CUSTOMER")).contains(CUSTOMER_KEY).hasSize(6)
                .noneMatch(k -> k.startsWith("SalesContactController/"));
        assertThat(perms("STORE_MANAGER")).containsAll(STAFF_KEYS).doesNotContain(CUSTOMER_KEY);
        for (String name : List.of("CASHIER", "STOCK_KEEPER")) {
            assertThat(perms(name)).noneMatch(k -> k.startsWith("SalesContactController/")
                    || k.startsWith("ShopContactController/"));
        }
        assertThat(column("SELECT t.name FROM role_template_permissions p JOIN role_templates t ON t.id = p.template_id "
                + "WHERE p.permission_code LIKE 'SalesContactController/%' OR p.permission_code = '" + CUSTOMER_KEY
                + "' GROUP BY t.name ORDER BY 1")).containsExactly("CUSTOMER", "STORE_MANAGER");
    }

    @Test
    void backfillTenantNguyenMauNhanKhoaMoi() throws SQLException {
        assertThat(tenantRolePerms(A, "CUSTOMER")).isEqualTo(perms("CUSTOMER")).contains(CUSTOMER_KEY);
        assertThat(tenantRolePerms(A, "STORE_MANAGER")).isEqualTo(perms("STORE_MANAGER")).containsAll(STAFF_KEYS);
        assertThat(tenantRolePerms(A, "CASHIER")).noneMatch(k -> k.startsWith("SalesContactController/"));
        assertThat(tenantRolePerms(A, "STOCK_KEEPER")).noneMatch(k -> k.startsWith("SalesContactController/"));
    }

    @Test
    void roleDaChinhKhongBiGanKhoaMoiVaRoleConNguyenCuaCungTenantVanDuocNangCap() throws SQLException {
        // E: CUSTOMER bị chỉnh -> không có khóa gửi; STORE_MANAGER nguyên mẫu -> có 5 khóa
        assertThat(tenantRolePerms(E, "CUSTOMER")).doesNotContain(CUSTOMER_KEY, "TitleController/lookup");
        assertThat(tenantRolePerms(E, "STORE_MANAGER")).containsAll(STAFF_KEYS);
        // F: STORE_MANAGER bị chỉnh -> không có khóa nhân viên; CUSTOMER nguyên mẫu -> có khóa gửi
        assertThat(tenantRolePerms(F, "STORE_MANAGER")).noneMatch(k -> k.startsWith("SalesContactController/"))
                .doesNotContain("RefundController/search");
        assertThat(tenantRolePerms(F, "CUSTOMER")).contains(CUSTOMER_KEY);
    }

    @Test
    void tenantMatRoleCustomerKhongBiTaoLaiRole() throws SQLException {
        assertThat(column("SELECT count(*)::text FROM roles WHERE tenant_id = '" + G + "' AND name = 'CUSTOMER'"))
                .containsExactly("0");
        assertThat(tenantRolePerms(G, "STORE_MANAGER")).containsAll(STAFF_KEYS);
    }

    @Test
    void tenantTatSalesHoacChiLendingKhongBiDung() throws SQLException {
        assertThat(column("SELECT count(*)::text FROM roles WHERE tenant_id IN ('" + B + "', '" + C + "')"))
                .containsExactly("0");
    }

    @Test
    void khongRoleNaoNgoaiCustomerVaStoreManagerNhanKhoaLienHe() throws SQLException {
        assertThat(column("SELECT DISTINCT r.name FROM role_permissions rp JOIN roles r ON r.id = rp.role_id "
                + "WHERE rp.permission_code LIKE 'SalesContactController/%' OR rp.permission_code = '" + CUSTOMER_KEY
                + "' ORDER BY 1")).containsExactly("CUSTOMER", "STORE_MANAGER");
        assertThat(column("SELECT count(*)::text FROM role_permissions rp JOIN roles r ON r.id = rp.role_id "
                + "WHERE r.name = 'CUSTOMER' AND rp.permission_code LIKE 'SalesContactController/%'"))
                .containsExactly("0");
    }
}
