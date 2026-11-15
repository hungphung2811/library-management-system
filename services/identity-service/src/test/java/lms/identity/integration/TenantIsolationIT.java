package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cách ly dữ liệu identity giữa hai tenant: A = tenant mặc định (quản trị viên khởi tạo), B = tenant tạo trong test.
 * Người dùng (users toàn cục) lọc qua tenant_memberships; role, phòng ban, hồ sơ độc giả lọc bằng @TenantId + RLS.
 * Cần Docker. Chạy: mvn test -Dtest=TenantIsolationIT -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/identity",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.producer.properties.max.block.ms=300",
        "spring.kafka.admin.properties.request.timeout.ms=500",
        "spring.kafka.admin.properties.default.api.timeout.ms=500",
        "auth.bootstrap-admin.password=Admin@12345",
        "app.internal-api-key=test-internal-key"
})
@Testcontainers
class TenantIsolationIT {

    static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    private <T> ResponseEntity<T> call(HttpMethod method, String url, String token, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange(url, method, new HttpEntity<>(body, headers), type);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> login(String username, String password) {
        ResponseEntity<Map> r = rest.postForEntity("/internal/auth/login",
                Map.of("username", username, "password", password), Map.class);
        assertThat(r.getStatusCode()).as("login %s", username).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private static void asSuperuser(String sql, Object... args) {
        try (Connection c = AppRoleDatabase.superuser(postgres); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID createUserIn(String token, String username) {
        Map<String, Object> body = new HashMap<>(Map.of("username", username, "fullName", "Họ Tên " + username,
                "email", username + "@x.vn", "password", "Passw0rd@123"));
        ResponseEntity<Map> r = call(HttpMethod.POST, "/internal/users", token, body, Map.class);
        assertThat(r.getStatusCode()).as("tạo %s", username).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    /** Tenant B với một quản trị viên (role is_admin của B); trả token của quản trị viên B. */
    private String tenantBAdmin(UUID tenantB, String code, String adminA) {
        UUID bAdmin = createUserIn(adminA, code + "-admin");
        jdbc.update("INSERT INTO tenant_memberships (user_id, tenant_id, status, joined_at) VALUES (?, ?, 'ACTIVE', "
                + "now() + interval '1 hour')", bAdmin, tenantB);
        UUID role = UUID.randomUUID();
        asSuperuser("INSERT INTO roles (id, tenant_id, name, description, is_admin) VALUES (?, ?, 'B_ADMIN', 'QT B', TRUE)",
                role, tenantB);
        asSuperuser("INSERT INTO user_roles (user_id, role_id, tenant_id) VALUES (?, ?, ?)", bAdmin, role, tenantB);
        Map<String, Object> session = login(code + "-admin", "Passw0rd@123");
        ResponseEntity<Map> switched = call(HttpMethod.POST, "/internal/auth/switch-tenant",
                (String) session.get("accessToken"),
                Map.of("tenantId", tenantB, "refreshToken", session.get("refreshToken")), Map.class);
        assertThat(switched.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) switched.getBody().get("accessToken");
    }

    private UUID newTenant(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, code, name, status) VALUES (?, ?, ?, 'ACTIVE')", id, code, "Thư viện " + code);
        return id;
    }

    @Test
    @SuppressWarnings("unchecked")
    void quanTriVienAKhongThayNguoiDungChiThuocB() {
        String adminA = (String) login("admin", "Admin@12345").get("accessToken");
        UUID tenantB = newTenant("iso-users-b");
        String adminB = tenantBAdmin(tenantB, "iso-users-b", adminA);
        UUID onlyB = createUserIn(adminB, "only-in-b");
        UUID inA = createUserIn(adminA, "only-in-a");

        Map<String, Object> listA = call(HttpMethod.GET, "/internal/users?size=100", adminA, null, Map.class).getBody();
        List<Map<String, Object>> items = (List<Map<String, Object>>) listA.get("items");
        assertThat(items).extracting(u -> u.get("id")).contains(inA.toString()).doesNotContain(onlyB.toString());
        Map<String, Object> searched = call(HttpMethod.GET, "/internal/users?keyword=only-in-b", adminA, null, Map.class)
                .getBody();
        assertThat(((Number) searched.get("total")).intValue()).isZero();

        assertThat(call(HttpMethod.GET, "/internal/users/" + onlyB, adminA, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.PUT, "/internal/users/" + onlyB, adminA,
                Map.of("fullName", "Chiếm", "email", "only-in-b@x.vn"), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST, "/internal/users/" + onlyB + "/password-reset", adminA,
                Map.of("temporaryPassword", "Chiem@12345"), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // B vẫn thấy người của mình
        assertThat(call(HttpMethod.GET, "/internal/users/" + onlyB, adminB, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void trungUsernameOTenantKhacCungLoiVaThongDiepNhuTrungTrongTenant() {
        String adminA = (String) login("admin", "Admin@12345").get("accessToken");
        UUID tenantB = newTenant("iso-dup-b");
        String adminB = tenantBAdmin(tenantB, "iso-dup-b", adminA);
        createUserIn(adminB, "dup-in-b");
        createUserIn(adminA, "dup-in-a");

        Map<String, Object> other = new HashMap<>(Map.of("username", "dup-in-b", "fullName", "X", "email", "x1@x.vn",
                "password", "Passw0rd@123"));
        Map<String, Object> same = new HashMap<>(Map.of("username", "dup-in-a", "fullName", "X", "email", "x2@x.vn",
                "password", "Passw0rd@123"));
        ResponseEntity<Map> otherTenant = call(HttpMethod.POST, "/internal/users", adminA, other, Map.class);
        ResponseEntity<Map> sameTenant = call(HttpMethod.POST, "/internal/users", adminA, same, Map.class);
        assertThat(otherTenant.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(sameTenant.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(otherTenant.getBody().get("code")).isEqualTo(sameTenant.getBody().get("code"));
        assertThat(otherTenant.getBody().get("message")).isEqualTo(sameTenant.getBody().get("message"));
    }

    @Test
    void nguoiThuocHaiTenantKhongBiQuanTriVienASuaHayDatLaiMatKhau() {
        String adminA = (String) login("admin", "Admin@12345").get("accessToken");
        UUID tenantB = newTenant("iso-shared-b");
        UUID shared = createUserIn(adminA, "shared-ab");
        jdbc.update("INSERT INTO tenant_memberships (user_id, tenant_id, status) VALUES (?, ?, 'ACTIVE')", shared, tenantB);
        UUID onlyA = createUserIn(adminA, "single-a");

        ResponseEntity<Map> edit = call(HttpMethod.PUT, "/internal/users/" + shared, adminA,
                Map.of("fullName", "Đổi tên", "email", "shared-ab@x.vn"), Map.class);
        ResponseEntity<Map> reset = call(HttpMethod.POST, "/internal/users/" + shared + "/password-reset", adminA,
                Map.of("temporaryPassword", "Temp@123456"), Map.class);
        assertThat(edit.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(edit.getBody().get("code")).isEqualTo("USER_OPERATION_NOT_ALLOWED");
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(call(HttpMethod.PUT, "/internal/users/" + onlyA, adminA,
                Map.of("fullName", "Đổi tên", "email", "single-a@x.vn"), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST, "/internal/users/" + onlyA + "/password-reset", adminA,
                Map.of("temporaryPassword", "Temp@123456"), Map.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat((Boolean) login("single-a", "Temp@123456").get("mustChangePassword")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void roleCungTenTaoDuocOHaiTenantVaRolePhongBanHoSoCuaBKhongThayTuAKeCaSqlNative() {
        String adminA = (String) login("admin", "Admin@12345").get("accessToken");
        UUID tenantB = newTenant("iso-data-b");
        String adminB = tenantBAdmin(tenantB, "iso-data-b", adminA);

        Map<String, Object> role = Map.of("name", "SHARED_NAME", "description", "Trùng tên", "isAdmin", false);
        assertThat(call(HttpMethod.POST, "/internal/roles", adminA, role, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(call(HttpMethod.POST, "/internal/roles", adminB, role, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        UUID bDept = UUID.randomUUID();
        asSuperuser("INSERT INTO departments (id, tenant_id, code, name, status) VALUES (?, ?, 'B-ONLY', 'Phòng B', 'ACTIVE')",
                bDept, tenantB);
        UUID bReader = UUID.randomUUID();
        asSuperuser("INSERT INTO reader_profiles (tenant_id, user_id, card_no, full_name, email, reader_type, "
                + "card_expiry_date, status) VALUES (?, ?, 'B-CARD', 'Độc giả B', 'rb@x.vn', 'STUDENT', '2030-01-01', "
                + "'ACTIVE')", tenantB, bReader);

        List<Map<String, Object>> rolesA = call(HttpMethod.GET, "/internal/roles", adminA, null, List.class).getBody();
        assertThat(rolesA).extracting(r -> r.get("name")).doesNotContain("B_ADMIN");
        assertThat(rolesA.stream().filter(r -> "SHARED_NAME".equals(r.get("name")))).hasSize(1);
        assertThat(call(HttpMethod.GET, "/internal/departments", adminA, null, String.class).getBody())
                .doesNotContain("B-ONLY");
        assertThat(call(HttpMethod.GET, "/internal/users/" + bReader + "/reader-profile", adminA, null, Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // phòng ban của B không dùng được khi tạo người dùng ở A
        Map<String, Object> withForeignDept = new HashMap<>(Map.of("username", "foreign-dept", "fullName", "X",
                "email", "fd@x.vn", "departmentId", bDept));
        assertThat(call(HttpMethod.POST, "/internal/users", adminA, withForeignDept, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // truy vấn native dưới RLS trong ngữ cảnh tenant A: không thấy dòng nào của B
        TenantContext.runAs(TENANT_A, () -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM roles WHERE tenant_id = ?", Integer.class, tenantB)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM departments WHERE id = ?", Integer.class, bDept)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reader_profiles WHERE user_id = ?", Integer.class, bReader))
                    .isZero();
        });
    }
}
