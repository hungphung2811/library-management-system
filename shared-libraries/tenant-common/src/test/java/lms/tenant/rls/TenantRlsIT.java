package lms.tenant.rls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import lms.tenant.TenantContext;
import lms.tenant.TenantContextFilter;
import lms.tenant.TenantRlsConnectionInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cần Docker. Chạy bằng: mvn test -Dtest=TenantRlsIT -Dsurefire.failIfNoSpecifiedTests=false.
 * Hai lớp cách ly cùng lúc trong một context Spring Boot + Spring Data JPA thật: lớp 1 Hibernate {@code @TenantId},
 * lớp 2 RLS của PostgreSQL. Ứng dụng kết nối bằng vai trò {@code app} (không phải chủ bảng, NOBYPASSRLS); bảng do
 * {@code rls_owner} sở hữu và bật FORCE. Pool chỉ 1 kết nối để chứng minh kết nối dùng lại không rò tenant.
 */
@SpringBootTest(classes = RlsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class TenantRlsIT {

    static final UUID A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final UUID B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        taoVaiTroVaBang();
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", () -> "app");
        r.add("spring.datasource.password", () -> "app");
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.jpa.open-in-view", () -> "false");
        r.add("spring.flyway.enabled", () -> "false");
    }

    /** Chạy bằng superuser của container: tạo vai trò chủ bảng, vai trò app, bảng mẫu theo đúng quy ước RLS. */
    static void taoVaiTroVaBang() throws Exception {
        try (Connection c = superuser(); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE rls_owner LOGIN PASSWORD 'rls_owner' NOSUPERUSER");
            s.execute("CREATE ROLE app LOGIN PASSWORD 'app' NOSUPERUSER NOBYPASSRLS");
            s.execute("GRANT CREATE ON SCHEMA public TO rls_owner");
        }
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "rls_owner", "rls_owner");
                Statement s = c.createStatement()) {
            s.execute("CREATE TABLE rls_notes (id uuid PRIMARY KEY, tenant_id uuid NOT NULL, content text NOT NULL)");
            s.execute("ALTER TABLE rls_notes ENABLE ROW LEVEL SECURITY");
            s.execute("ALTER TABLE rls_notes FORCE ROW LEVEL SECURITY");
            s.execute("CREATE POLICY tenant_isolation ON rls_notes"
                    + " USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)"
                    + " WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)");
            s.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON rls_notes TO app");
        }
    }

    static Connection superuser() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Autowired
    RlsNoteRepository notes;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    DataSource dataSource;

    @Autowired
    ApplicationContext context;

    @PersistenceContext
    EntityManager entityManager;

    @BeforeEach
    void seed() throws Exception {
        // superuser bỏ qua RLS nên seed thẳng được dữ liệu của cả hai tenant
        try (Connection c = superuser(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE rls_notes");
            s.execute("INSERT INTO rls_notes VALUES (gen_random_uuid(), '" + A + "', 'A1'), (gen_random_uuid(), '"
                    + B + "', 'B1')");
        }
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void a_runAsAChiThayDongCuaAQuaRepository() {
        List<String> contents = TenantContext.callAs(A, () -> notes.findAll().stream().map(RlsNote::getContent).toList());

        assertThat(contents).containsExactly("A1");
    }

    @Test
    void a_hibernateTuDienTenantIdKhiLuu() throws Exception {
        UUID id = UUID.randomUUID();
        TenantContext.runAs(A, () -> notes.save(new RlsNote(id, "A2")));

        try (Connection c = superuser(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT tenant_id FROM rls_notes WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getObject(1, UUID.class)).isEqualTo(A);
        }
        assertThat(TenantContext.callAs(B, () -> notes.findById(id))).isEmpty();
    }

    @Test
    void b_khongCoTenantThiKhongThayDongNao() {
        assertThat(notes.findAll()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rls_notes", Integer.class)).isZero();
    }

    @Test
    void b_khongCoTenantThiKhongGhiDuoc() {
        assertThatThrownBy(() -> notes.saveAndFlush(new RlsNote(UUID.randomUUID(), "mo-coi")))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("row-level security");
    }

    @Test
    void c_insertVoiTenantKhacBiTuChoi() {
        assertThatThrownBy(() -> TenantContext.runAs(A, () -> jdbc.update(
                        "INSERT INTO rls_notes VALUES (gen_random_uuid(), ?, 'lan-sang-B')", B)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("row-level security");
        assertThatThrownBy(() -> TenantContext.runAs(A, () -> jdbc.update(
                        "UPDATE rls_notes SET tenant_id = ? WHERE content = 'A1'", B)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("row-level security");
        assertThat(TenantContext.callAs(B, () -> jdbc.queryForList("SELECT content FROM rls_notes", String.class)))
                .containsExactly("B1");
    }

    @Test
    void d_sqlNativeCungChiThayDongCuaTenantHienTai() {
        List<String> quaJdbc = TenantContext.callAs(A, () -> jdbc.queryForList("SELECT content FROM rls_notes",
                String.class));
        @SuppressWarnings("unchecked")
        List<String> quaJpaNative = TenantContext.callAs(A, () -> tx.execute(status ->
                (List<String>) entityManager.createNativeQuery("SELECT content FROM rls_notes").getResultList()));

        assertThat(quaJdbc).containsExactly("A1");
        assertThat(quaJpaNative).containsExactly("A1");
        assertThat(TenantContext.callAs(B, () -> jdbc.queryForList("SELECT content FROM rls_notes", String.class)))
                .containsExactly("B1");
    }

    @Test
    void e_chuBangVanBiRlsViForce() throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "rls_owner", "rls_owner");
                Statement s = c.createStatement()) {
            assertThat(contents(s)).isEmpty();
            s.execute("SELECT set_config('app.tenant_id', '" + A + "', false)");
            assertThat(contents(s)).containsExactly("A1");
        }
    }

    @Test
    void ketNoiDungLaiKhongRoTenantGiuaCacLanMuon() throws Exception {
        Integer pidA = TenantContext.callAs(A, () -> jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
        assertThat(TenantContext.callAs(A, () -> jdbc.queryForList("SELECT content FROM rls_notes", String.class)))
                .containsExactly("A1");
        // Cùng kết nối vật lý (pool = 1), không có tenant: không thấy gì của A
        assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(pidA);
        assertThat(jdbc.queryForList("SELECT content FROM rls_notes", String.class)).isEmpty();
        // Transaction JPA với A rồi transaction kế tiếp với B trên cùng kết nối
        Integer soDongA = TenantContext.callAs(A, () -> tx.execute(st -> notes.findAll().size()));
        List<String> dongB = TenantContext.callAs(B, () -> tx.execute(st -> jdbc.queryForList(
                "SELECT content FROM rls_notes", String.class)));
        assertThat(soDongA).isEqualTo(1);
        assertThat(dongB).containsExactly("B1");
        // Kết nối trả về pool đã được xóa app.tenant_id: lấy thẳng từ Hikari (bỏ qua lớp bọc) vẫn không thấy gì
        TenantContext.runAs(A, () -> jdbc.queryForList("SELECT content FROM rls_notes", String.class));
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        try (Connection raw = hikari.getConnection(); Statement s = raw.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT pg_backend_pid(), current_setting('app.tenant_id', true)")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(pidA);
                assertThat(rs.getString(2)).isNullOrEmpty();
            }
            assertThat(contents(s)).isEmpty();
        }
    }

    @Test
    void autoConfigBocDataSourceVaDangKyFilterSauSpringSecurity() throws Exception {
        assertThat(context.getBeansOfType(TenantRlsConnectionInitializer.class)).hasSize(1);
        assertThat(dataSource.isWrapperFor(HikariDataSource.class)).isTrue();
        assertThat(dataSource).isNotInstanceOf(HikariDataSource.class);
        @SuppressWarnings("unchecked")
        FilterRegistrationBean<TenantContextFilter> registration = context.getBean(FilterRegistrationBean.class);
        assertThat(registration.getFilter()).isInstanceOf(TenantContextFilter.class);
        assertThat(registration.getOrder()).isEqualTo(SecurityProperties.DEFAULT_FILTER_ORDER + 1);
    }

    private static List<String> contents(Statement s) throws Exception {
        List<String> result = new ArrayList<>();
        try (ResultSet rs = s.executeQuery("SELECT content FROM rls_notes ORDER BY content")) {
            while (rs.next()) result.add(rs.getString(1));
        }
        return result;
    }
}
