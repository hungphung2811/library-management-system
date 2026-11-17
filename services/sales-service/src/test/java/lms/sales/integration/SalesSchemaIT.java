package lms.sales.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import lms.security.permission.PermissionKeyRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cần Docker. Chạy bằng: mvn -pl services/sales-service -am test -Dtest=SalesSchemaIT
 * -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44.
 * Service khởi động trên DB trống đã chạy migration sales (V1 từ system-service), kết nối bằng vai trò sales_app
 * (chịu RLS); kiểm schema: RLS fail-closed, cách ly tenant, CHECK tồn kho, vai trò app không phải chủ bảng.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/sales",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "lms.sales.events.enabled=false"})
@Testcontainers
class SalesSchemaIT {

    private static final List<String> BANG_NGHIEP_VU = List.of("sales_settings", "sales_counters", "stock_items",
            "stock_ledger", "suppliers", "goods_receipts", "goods_receipt_lines", "sales_orders", "sales_order_lines",
            "payment_records", "sales_audit_log", "sales_returns", "sales_return_lines", "refunds");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired
    DataSource dataSource;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PermissionKeyRegistrar permissionKeyRegistrar;

    private static long count(Statement s, String from) throws SQLException {
        try (ResultSet rs = s.executeQuery("SELECT count(*) FROM " + from)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void contextKhoiDongTrenDbTrongVaValidateSchema() throws SQLException {
        // Context đã lên (ddl-auto: validate) bằng vai trò sales_app; migration V1 đã tạo mọi bảng
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            assertThat(count(s, "information_schema.tables WHERE table_schema = 'public' AND table_name IN ('"
                    + String.join("','", BANG_NGHIEP_VU) + "')")).isEqualTo(BANG_NGHIEP_VU.size());
        }
    }

    /** sales_tenants (danh bạ cho job ẩn danh) là bảng toàn cục: KHÔNG RLS, chỉ có mã tenant và thời điểm. */
    @Test
    void salesTenantsKhongRlsVaKhongCoDuLieuCaNhan() throws Exception {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT relrowsecurity FROM pg_class WHERE relname = 'sales_tenants'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isFalse();
            }
            try (ResultSet rs = st.executeQuery("SELECT column_name FROM information_schema.columns "
                    + "WHERE table_name = 'sales_tenants' ORDER BY ordinal_position")) {
                List<String> cols = new java.util.ArrayList<>();
                while (rs.next()) {
                    cols.add(rs.getString(1));
                }
                assertThat(cols).containsExactly("tenant_id", "first_seen_at");
            }
        }
    }

    @Test
    void moiBangCoRlsEnableForceVaHaiPolicy() throws Exception {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            for (String t : BANG_NGHIEP_VU) {
                try (ResultSet rs = s.executeQuery(
                        "SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = '" + t + "'")) {
                    rs.next();
                    assertThat(rs.getBoolean(1)).as(t + " ENABLE RLS").isTrue();
                    assertThat(rs.getBoolean(2)).as(t + " FORCE RLS").isTrue();
                }
                List<String> policies = new ArrayList<>();
                try (ResultSet rs = s.executeQuery(
                        "SELECT policyname FROM pg_policies WHERE tablename = '" + t + "' ORDER BY policyname")) {
                    while (rs.next()) policies.add(rs.getString(1));
                }
                assertThat(policies).as(t).containsExactly("platform_read_all", "tenant_isolation");
                try (ResultSet rs = s.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_name = '"
                        + t + "' AND column_name = 'tenant_id' AND is_nullable = 'NO'")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as(t + " tenant_id NOT NULL").isEqualTo(1);
                }
            }
        }
    }

    @Test
    void vaiTroAppChiuRlsCachLyTenantVaCheckTonKho() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        try (Connection c = AppRoleDatabase.appRole(postgres); Statement s = c.createStatement()) {
            // Không đặt tenant: fail-closed, không ghi và không thấy gì
            assertThat(count(s, "stock_items")).isZero();
            assertThatThrownBy(() -> s.execute("INSERT INTO stock_items (id, tenant_id, title_id) VALUES ('"
                    + UUID.randomUUID() + "', '" + a + "', '" + UUID.randomUUID() + "')"))
                    .hasMessageContaining("row-level security");

            s.execute("SELECT set_config('app.tenant_id', '" + a + "', false)");
            UUID item = UUID.randomUUID();
            s.execute("INSERT INTO stock_items (id, tenant_id, title_id, on_hand) VALUES ('" + item + "', '" + a
                    + "', '" + UUID.randomUUID() + "', 3)");
            // Ghi dòng của tenant khác bị chặn
            assertThatThrownBy(() -> s.execute("INSERT INTO stock_items (id, tenant_id, title_id) VALUES ('"
                    + UUID.randomUUID() + "', '" + b + "', '" + UUID.randomUUID() + "')"))
                    .hasMessageContaining("row-level security");
            // CHECK tồn kho: không âm, không giữ quá hàng có
            assertThatThrownBy(() -> s.execute("UPDATE stock_items SET reserved = 4 WHERE id = '" + item + "'"))
                    .hasMessageContaining("ck_stock_items_quantities");
            assertThatThrownBy(() -> s.execute("UPDATE stock_items SET on_hand = -1 WHERE id = '" + item + "'"))
                    .hasMessageContaining("ck_stock_items_quantities");
            // Sổ cái: ADJUSTMENT bắt buộc có lý do
            assertThatThrownBy(() -> s.execute("INSERT INTO stock_ledger (id, tenant_id, stock_item_id, type, "
                    + "delta_on_hand) VALUES ('" + UUID.randomUUID() + "', '" + a + "', '" + item
                    + "', 'ADJUSTMENT', 1)")).hasMessageContaining("ck_stock_ledger_reason");
            s.execute("INSERT INTO stock_ledger (id, tenant_id, stock_item_id, type, delta_on_hand, reason) VALUES ('"
                    + UUID.randomUUID() + "', '" + a + "', '" + item + "', 'ADJUSTMENT', 1, 'kiem kho')");

            // Tenant B không thấy dữ liệu của A
            s.execute("SELECT set_config('app.tenant_id', '" + b + "', false)");
            assertThat(count(s, "stock_items")).isZero();
            assertThat(count(s, "stock_ledger")).isZero();
            assertThat(s.executeUpdate("UPDATE stock_items SET active = false")).isZero();
            assertThat(s.executeUpdate("DELETE FROM stock_items")).isZero();
            s.execute("SELECT set_config('app.tenant_id', '" + a + "', false)");
            assertThat(count(s, "stock_items")).isEqualTo(1);

            // Vai trò app không sở hữu bảng nên không DDL được
            assertThatThrownBy(() -> s.execute("DROP TABLE stock_items")).hasMessageContaining("must be owner");
        }
    }
}
