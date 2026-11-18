package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Migration identity V34 (khóa tác phẩm/bộ sách + backfill role đang chạy), V35 (đền bù), V36 (nhập bộ sách) trên Postgres
 * thật, chạy bằng vai trò KHÔNG bypass RLS (role_permissions có FORCE RLS theo tenant): backfill đúng khóa đọc cho role không
 * phải admin theo từng tenant, không động tới role admin hoặc role khác tenant, không backfill khóa ghi, mẫu role nhận đúng
 * khóa, chạy lại Flyway không đổi. Chạy: -Dtest=IdentityPermissionMigrationIT -DargLine=-Dapi.version=1.44
 * -Dsurefire.failIfNoSpecifiedTests=false
 */
@Testcontainers
class IdentityPermissionMigrationIT {

    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000ee01");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000ee02");
    static final UUID R_SEARCH = UUID.fromString("00000000-0000-0000-0000-0000000f0001");
    static final UUID R_GET = UUID.fromString("00000000-0000-0000-0000-0000000f0002");
    static final UUID R_ADMIN = UUID.fromString("00000000-0000-0000-0000-0000000f0003");
    static final UUID R_OTHER = UUID.fromString("00000000-0000-0000-0000-0000000f0004");
    static final UUID R_B = UUID.fromString("00000000-0000-0000-0000-0000000f0005");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static Flyway flyway(String target) {
        var cfg = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/identity").initSql("SET ROLE mig_runner");
        if (target != null) cfg.target(target);
        return cfg.load();
    }

    static Connection su() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    static void exec(String sql) throws SQLException {
        try (Connection c = su(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    static List<String> col(String sql) throws SQLException {
        try (Connection c = su(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getString(1));
            return out;
        }
    }

    static void role(UUID tenant, UUID id, String name, boolean admin, String... keys) throws SQLException {
        exec("INSERT INTO roles (id, tenant_id, name, description, is_admin) VALUES ('" + id + "', '" + tenant + "', '" + name
                + "', 'd', " + admin + ")");
        for (String k : keys) {
            exec("INSERT INTO role_permissions (role_id, permission_code) VALUES ('" + id + "', '" + k + "')");
        }
    }

    @BeforeAll
    static void migrate() throws SQLException {
        exec("CREATE ROLE mig_runner NOSUPERUSER NOBYPASSRLS");
        exec("GRANT ALL ON SCHEMA public TO mig_runner");
        flyway("32").migrate(); // trước V34 (V33 bỏ trống)
        exec("INSERT INTO permissions (code, description) VALUES ('TitleController/search', 'x') ON CONFLICT DO NOTHING");
        exec("INSERT INTO permissions (code, description) VALUES ('TitleController/get', 'x') ON CONFLICT DO NOTHING");
        exec("INSERT INTO permissions (code, description) VALUES ('Other/thing', 'x') ON CONFLICT DO NOTHING");
        exec("INSERT INTO tenants (id, code, name, status) VALUES ('" + A + "', 'ta', 'A', 'ACTIVE'), ('" + B + "', 'tb', 'B', 'ACTIVE')");
        role(A, R_SEARCH, "Search", false, "TitleController/search");
        role(A, R_GET, "Get", false, "TitleController/get");
        role(A, R_ADMIN, "Adm", true, "TitleController/search", "TitleController/get");
        role(A, R_OTHER, "Other", false, "Other/thing");
        role(B, R_B, "BRole", false, "TitleController/get");
        flyway(null).migrate(); // V34..V37 dưới vai trò không bypass RLS
    }

    private static List<String> keysOf(UUID role) throws SQLException {
        return col("SELECT permission_code FROM role_permissions WHERE role_id = '" + role + "' ORDER BY 1");
    }

    @Test
    void backfillKhoaDocChoRoleCoTitleSearchVaGetTheoTungTenant() throws Exception {
        assertThat(keysOf(R_SEARCH)).containsExactlyInAnyOrder("TitleController/search", "WorkController/search",
                "SeriesController/search", "SeriesController/options");
        assertThat(keysOf(R_GET)).containsExactlyInAnyOrder("TitleController/get", "WorkController/get", "SeriesController/get");
        assertThat(keysOf(R_B)).containsExactlyInAnyOrder("TitleController/get", "WorkController/get", "SeriesController/get");
    }

    @Test
    void khongBackfillRoleAdminRoleKhongLienQuanVaKhongBaoGioKhoaGhi() throws Exception {
        assertThat(keysOf(R_ADMIN)).containsExactlyInAnyOrder("TitleController/search", "TitleController/get");
        assertThat(keysOf(R_OTHER)).containsExactly("Other/thing");
        assertThat(col("SELECT DISTINCT permission_code FROM role_permissions WHERE permission_code ~ "
                + "'^(WorkController/(create|update|merge)|SeriesController/(create|update|setStatus)|TitleController/changeWork"
                + "|SeriesImportController/.*|DamageLevelController/.*|CompensationPolicyController/.*|FineAdjustmentController/.*"
                + "|FineRefundController/.*|BorrowingController/reportFound)$'")).isEmpty();
    }

    @Test
    void khoaDuocDangKyDungMetadataVaFlywayChayLaiKhongDoi() throws Exception {
        assertThat(col("SELECT service || ' ' || http_method || ' ' || path_pattern FROM permissions WHERE code = 'WorkController/merge'"))
                .containsExactly("catalog-service POST /api/works/{id}/merge");
        assertThat(col("SELECT service || ' ' || http_method || ' ' || path_pattern FROM permissions WHERE code = 'BorrowingController/reportFound'"))
                .containsExactly("circulation-service POST /api/borrowings/found");
        assertThat(col("SELECT count(*) FROM permissions WHERE code ~ '^(Work|Series|SeriesImport|DamageLevel|CompensationPolicy|FineAdjustment|FineRefund)Controller/'"))
                .containsExactly("26"); // Work 5 + Series 6 + SeriesImport 2 + DamageLevel 4 + CompensationPolicy 2 + FineAdjustment 4 + FineRefund 3
        long before = Long.parseLong(col("SELECT count(*) FROM role_permissions").get(0));
        flyway(null).migrate();
        flyway(null).validate();
        assertThat(col("SELECT count(*) FROM role_permissions")).containsExactly(String.valueOf(before));
    }

    /**
     * RISK-1: duyệt hoàn khoản chuyển bản sao LOST về kho bằng chính token của người duyệt (CatalogCopyRestClientAdapter dùng
     * BearerTokens) nên catalog đòi khóa BookCopyController/changeStatus (và get cho giá). Mẫu LIBRARY_ADMIN (a2, không phải
     * is_admin) được gán FineRefundController/approve nhưng V35 không có hai khóa catalog này: duyệt bị catalog 403 và
     * rollback. Đã sửa bằng V38; kiểm mọi mẫu có approve đều có hai khóa.
     */
    @Test
    void RISK1_mauLibraryAdminCoDuKhoaCatalogDeDuyetHoan() throws Exception {
        assertThat(col("SELECT permission_code FROM role_template_permissions WHERE template_id = "
                + "'00000000-0000-0000-0000-0000000000a2' AND permission_code IN "
                + "('BookCopyController/changeStatus','BookCopyController/get')")).hasSize(2);
        assertThat(col("SELECT t.template_id::text FROM (SELECT DISTINCT template_id FROM role_template_permissions "
                + "WHERE permission_code = 'FineRefundController/approve') t WHERE (SELECT count(*) FROM role_template_permissions r "
                + "WHERE r.template_id = t.template_id AND r.permission_code IN "
                + "('BookCopyController/changeStatus','BookCopyController/get')) <> 2")).isEmpty();
    }

    @Test
    void mauRoleNhanDungKhoa() throws Exception {
        String a2 = "'00000000-0000-0000-0000-0000000000a2'";
        String a3 = "'00000000-0000-0000-0000-0000000000a3'";
        String c3 = "'00000000-0000-0000-0000-0000000000c3'";
        assertThat(col("SELECT count(*) FROM role_template_permissions WHERE template_id = " + a2
                + " AND permission_code ~ '^(Work|Series|SeriesImport|DamageLevel|CompensationPolicy|FineAdjustment|FineRefund)Controller/'"))
                .containsExactly("26");
        assertThat(col("SELECT permission_code FROM role_template_permissions WHERE template_id = " + a3
                + " AND permission_code ~ '^(DamageLevel|CompensationPolicy|FineAdjustment|FineRefund)Controller/|reportFound' ORDER BY 1"))
                .containsExactlyInAnyOrder("DamageLevelController/list", "CompensationPolicyController/get",
                        "FineAdjustmentController/request", "FineAdjustmentController/list", "FineRefundController/list",
                        "BorrowingController/reportFound");
        // thủ thư KHÔNG có khóa duyệt/cấu hình: tách nhiệm vụ đề nghị và duyệt
        assertThat(col("SELECT count(*) FROM role_template_permissions WHERE template_id = " + a3
                + " AND permission_code ~ '(approve|reject|update|create|delete)$' AND permission_code ~ "
                + "'^(DamageLevel|CompensationPolicy|FineAdjustment|FineRefund)Controller/'")).containsExactly("0");
        assertThat(col("SELECT count(*) FROM role_template_permissions WHERE template_id = " + c3
                + " AND permission_code IN ('SeriesImportController/upload','SeriesImportController/confirm',"
                + "'WorkController/merge','SeriesController/setStatus')")).containsExactly("4");
        // không mẫu nào gán khóa duyệt trừ a2
        assertThat(col("SELECT DISTINCT template_id::text FROM role_template_permissions WHERE permission_code IN "
                + "('FineAdjustmentController/approve','FineRefundController/approve')")).containsExactly("00000000-0000-0000-0000-0000000000a2");
    }
}
