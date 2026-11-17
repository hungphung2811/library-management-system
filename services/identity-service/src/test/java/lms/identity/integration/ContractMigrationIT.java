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
 * Cần Docker. Migration V19 (ADR-0012): chạy tới V18, chèn tenant (ACTIVE có module, SUSPENDED có module, ACTIVE không
 * module), rồi chạy V19 + afterMigrate để kiểm chứng backfill hợp đồng legacy, nguyên nhân tạm ngưng MANUAL, các ràng
 * buộc (EXCLUDE chồng lấn, dấu thanh toán), trigger chặn UPDATE/DELETE và REVOKE cho vai trò ứng dụng. Chạy:
 * mvn -pl services/identity-service -am test -Dtest=ContractMigrationIT -Dsurefire.failIfNoSpecifiedTests=false
 * -DargLine=-Dapi.version=1.44
 */
@Testcontainers
class ContractMigrationIT {

    static final String DEFAULT_TENANT = "00000000-0000-0000-0000-0000000000d1";
    static final String TENANT_A = "00000000-0000-0000-0000-00000000cc01";
    static final String TENANT_SUSPENDED = "00000000-0000-0000-0000-00000000cc02";
    static final String TENANT_NO_MODULE = "00000000-0000-0000-0000-00000000cc03";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @BeforeAll
    static void migrate() throws SQLException {
        // vai trò ứng dụng phải có TRƯỚC để afterMigrate cấp quyền rồi REVOKE
        exec("CREATE ROLE identity_app LOGIN PASSWORD 'identity_app' NOSUPERUSER NOBYPASSRLS");
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/identity").target("18").load().migrate();

        exec("INSERT INTO tenants (id, code, name, status) VALUES "
                + "('" + TENANT_A + "', 'lib-a', 'Thư viện A', 'ACTIVE'), "
                + "('" + TENANT_SUSPENDED + "', 'lib-s', 'Thư viện S', 'SUSPENDED'), "
                + "('" + TENANT_NO_MODULE + "', 'lib-n', 'Thư viện N', 'ACTIVE')");
        exec("INSERT INTO tenant_modules (tenant_id, module, enabled) VALUES "
                + "('" + TENANT_A + "', 'LENDING', TRUE), ('" + TENANT_A + "', 'SALES', TRUE), "
                + "('" + TENANT_SUSPENDED + "', 'LENDING', TRUE), ('" + TENANT_NO_MODULE + "', 'LENDING', FALSE)");

        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/identity").load().migrate();
    }

    static Connection conn() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    static void exec(String sql) throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    static List<String> column(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = conn(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    static String one(String sql) throws SQLException {
        List<String> rows = column(sql);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Test
    void moiTenantCoModuleBatCoDungMotHopDongLegacyKhopTenantModules() throws Exception {
        for (String tenant : List.of(DEFAULT_TENANT, TENANT_A, TENANT_SUSPENDED)) {
            assertThat(one("SELECT count(*) FROM tenant_contracts WHERE tenant_id = '" + tenant + "' AND legacy"))
                    .as("legacy của %s", tenant).isEqualTo("1");
            assertThat(column("SELECT m.module FROM tenant_contract_modules m JOIN tenant_contracts c ON c.id = m.contract_id "
                    + "WHERE c.tenant_id = '" + tenant + "' ORDER BY m.module"))
                    .isEqualTo(column("SELECT module FROM tenant_modules WHERE tenant_id = '" + tenant
                            + "' AND enabled ORDER BY module"));
        }
        assertThat(column("SELECT module FROM tenant_contract_modules m JOIN tenant_contracts c ON c.id = m.contract_id "
                + "WHERE c.tenant_id = '" + TENANT_A + "' ORDER BY module")).containsExactly("LENDING", "SALES");
        assertThat(one("SELECT count(*) FROM tenant_contracts WHERE legacy AND tenant_id = '" + TENANT_NO_MODULE + "'")).isEqualTo("0");

        // đặc tính legacy: ACTIVE, không hết hạn, tổng 0, đã áp dụng (job không tắt gì), lịch sử SYSTEM actor NULL
        assertThat(one("SELECT status || '/' || (ends_at IS NULL) || '/' || total_amount || '/' || (modules_applied_at IS NOT NULL) "
                + "FROM tenant_contracts WHERE tenant_id = '" + TENANT_A + "'")).isEqualTo("ACTIVE/true/0/true");
        assertThat(one("SELECT type || '/' || actor_type || '/' || (actor_id IS NULL) FROM tenant_contract_history "
                + "WHERE tenant_id = '" + TENANT_A + "'")).isEqualTo("LEGACY_BACKFILLED/SYSTEM/true");
    }

    @Test
    void tenantDangSuspendedTruocMigrateDuocBackfillManualCacTenantKhacNull() throws Exception {
        assertThat(one("SELECT suspension_cause FROM tenants WHERE id = '" + TENANT_SUSPENDED + "'")).isEqualTo("MANUAL");
        assertThat(one("SELECT suspended_by_contract_id FROM tenants WHERE id = '" + TENANT_SUSPENDED + "'")).isNull();
        assertThat(one("SELECT suspension_cause FROM tenants WHERE id = '" + TENANT_A + "'")).isNull();
    }

    @Test
    void duongGhiTayDatStatusKhongBietCotMoiVanNhatQuanQuaTrigger() throws Exception {
        exec("UPDATE tenants SET status = 'SUSPENDED' WHERE id = '" + TENANT_NO_MODULE + "'");
        assertThat(one("SELECT suspension_cause FROM tenants WHERE id = '" + TENANT_NO_MODULE + "'")).isEqualTo("MANUAL");
        exec("UPDATE tenants SET status = 'ACTIVE' WHERE id = '" + TENANT_NO_MODULE + "'");
        assertThat(one("SELECT suspension_cause FROM tenants WHERE id = '" + TENANT_NO_MODULE + "'")).isNull();
        // MANUAL không được kèm hợp đồng gây ra
        assertThatThrownBy(() -> exec("UPDATE tenants SET status = 'SUSPENDED', suspension_cause = 'MANUAL', "
                + "suspended_by_contract_id = (SELECT id FROM tenant_contracts LIMIT 1) WHERE id = '" + TENANT_A + "'"))
                .hasMessageContaining("ck_tenants_suspended_by_contract");
    }

    @Test
    void rangBuocChongLanHopDongActiveCuaCungTenant() throws Exception {
        assertThat(one("SELECT count(*) FROM pg_extension WHERE extname = 'btree_gist'")).isEqualTo("1");
        String insert = "INSERT INTO tenant_contracts (id, tenant_id, name, status, starts_at, ends_at, total_amount) VALUES "
                + "(gen_random_uuid(), '" + TENANT_NO_MODULE + "', 'X', '%s', '%s', '%s', 0)";
        exec(String.format(insert, "ACTIVE", "2030-01-01T00:00:00Z", "2030-02-01T00:00:00Z"));
        assertThatThrownBy(() -> exec(String.format(insert, "ACTIVE", "2030-01-15T00:00:00Z", "2030-03-01T00:00:00Z")))
                .hasMessageContaining("ex_tenant_contracts_active_overlap");
        // nối tiếp (nửa mở) và hợp đồng không ACTIVE thì được
        exec(String.format(insert, "ACTIVE", "2030-02-01T00:00:00Z", "2030-03-01T00:00:00Z"));
        exec(String.format(insert, "DRAFT", "2030-01-15T00:00:00Z", "2030-03-01T00:00:00Z"));
        // kiểm ends_at NULL chỉ khi legacy, hủy cần lý do
        assertThatThrownBy(() -> exec("INSERT INTO tenant_contracts (id, tenant_id, name, status, starts_at, ends_at) "
                + "VALUES (gen_random_uuid(), '" + TENANT_NO_MODULE + "', 'Y', 'DRAFT', now(), NULL)"))
                .hasMessageContaining("ck_contract_ends_when_not_legacy");
        assertThatThrownBy(() -> exec("INSERT INTO tenant_contracts (id, tenant_id, name, status, starts_at, ends_at) "
                + "VALUES (gen_random_uuid(), '" + TENANT_NO_MODULE + "', 'Y', 'CANCELLED', now(), now() + interval '1 day')"))
                .hasMessageContaining("ck_contract_cancel_reason");
    }

    @Test
    void thanhToanVaLichSuChiThemTriggerChanKeCaChuBang() throws Exception {
        String contract = one("SELECT id FROM tenant_contracts WHERE tenant_id = '" + TENANT_A + "'");
        String payment = "00000000-0000-0000-0000-00000000ee01";
        exec("INSERT INTO tenant_contract_payments (id, contract_id, tenant_id, paid_on, amount) VALUES ('" + payment
                + "', '" + contract + "', '" + TENANT_A + "', current_date, 100)");
        assertThatThrownBy(() -> exec("UPDATE tenant_contract_payments SET amount = 1 WHERE id = '" + payment + "'"))
                .hasMessageContaining("chỉ thêm");
        assertThatThrownBy(() -> exec("DELETE FROM tenant_contract_payments WHERE id = '" + payment + "'"))
                .hasMessageContaining("chỉ thêm");
        assertThatThrownBy(() -> exec("UPDATE tenant_contract_history SET type = 'X'")).hasMessageContaining("chỉ thêm");
        assertThatThrownBy(() -> exec("DELETE FROM tenant_contract_history")).hasMessageContaining("chỉ thêm");

        // dòng đảo: âm và trỏ gốc; gốc âm không có reverses bị CHECK chặn; đảo hai lần bị UNIQUE chặn
        exec("INSERT INTO tenant_contract_payments (id, contract_id, tenant_id, paid_on, amount, reverses_payment_id) "
                + "VALUES ('00000000-0000-0000-0000-00000000ee02', '" + contract + "', '" + TENANT_A + "', current_date, -100, '"
                + payment + "')");
        assertThatThrownBy(() -> exec("INSERT INTO tenant_contract_payments (id, contract_id, tenant_id, paid_on, amount, "
                + "reverses_payment_id) VALUES (gen_random_uuid(), '" + contract + "', '" + TENANT_A + "', current_date, -100, '"
                + payment + "')")).hasMessageContaining("uq_payment_reversed_once");
        assertThatThrownBy(() -> exec("INSERT INTO tenant_contract_payments (id, contract_id, tenant_id, paid_on, amount) "
                + "VALUES (gen_random_uuid(), '" + contract + "', '" + TENANT_A + "', current_date, -5)"))
                .hasMessageContaining("ck_payment_reversal_sign");
        assertThatThrownBy(() -> exec("INSERT INTO tenant_contract_payments (id, contract_id, tenant_id, paid_on, amount) "
                + "VALUES (gen_random_uuid(), '" + contract + "', '" + TENANT_A + "', current_date, 0)"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void vaiTroUngDungKhongCoQuyenUpdateDeleteThanhToanVaLichSuNhungConCacBangKhac() throws Exception {
        assertThat(one("SELECT has_table_privilege('identity_app', 'tenant_contract_payments', 'INSERT')")).isEqualTo("t");
        assertThat(one("SELECT has_table_privilege('identity_app', 'tenant_contract_payments', 'SELECT')")).isEqualTo("t");
        for (String table : List.of("tenant_contract_payments", "tenant_contract_history")) {
            assertThat(one("SELECT has_table_privilege('identity_app', '" + table + "', 'UPDATE')")).as(table).isEqualTo("f");
            assertThat(one("SELECT has_table_privilege('identity_app', '" + table + "', 'DELETE')")).as(table).isEqualTo("f");
        }
        assertThat(one("SELECT has_table_privilege('identity_app', 'tenant_contracts', 'UPDATE')")).isEqualTo("t");
        assertThat(one("SELECT has_table_privilege('identity_app', 'tenant_contract_reminders', 'INSERT')")).isEqualTo("t");

        // chạy lại migrate (afterMigrate chạy lại GRANT ALL) không làm quyền quay lại
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/identity").load().migrate();
        assertThat(one("SELECT has_table_privilege('identity_app', 'tenant_contract_payments', 'UPDATE')")).isEqualTo("f");
    }

    /** Chủ DB không phải superuser (như vai trò {@code identity} thật) vẫn tạo được btree_gist (extension trusted PG13+). */
    @Test
    void chuDbKhongPhaiSuperuserTaoDuocBtreeGist() throws Exception {
        exec("CREATE ROLE owner_x LOGIN PASSWORD 'owner_x' NOSUPERUSER NOCREATEDB NOCREATEROLE");
        exec("CREATE DATABASE owner_db OWNER owner_x");
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/owner_db");
        try (Connection c = DriverManager.getConnection(url, "owner_x", "owner_x"); Statement s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS btree_gist");
            try (ResultSet rs = s.executeQuery("SELECT count(*) FROM pg_extension WHERE extname = 'btree_gist'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }
}
