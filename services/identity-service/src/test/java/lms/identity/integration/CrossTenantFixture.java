package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** Dữ liệu và lời gọi dùng chung cho IT đọc xuyên tenant (ADR-0008): hai tenant A (mặc định) và B, các loại token. */
final class CrossTenantFixture {

    static final String PASSWORD = "Passw0rd@123";

    private final TestRestTemplate rest;
    private final JdbcTemplate jdbc;
    private final PostgreSQLContainer<?> postgres;

    CrossTenantFixture(TestRestTemplate rest, JdbcTemplate jdbc, PostgreSQLContainer<?> postgres) {
        this.rest = rest;
        this.jdbc = jdbc;
        this.postgres = postgres;
    }

    <T> ResponseEntity<T> call(HttpMethod method, String url, String token, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange(url, method, new HttpEntity<>(body, headers), type);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> login(String username, String password) {
        ResponseEntity<Map> r = rest.postForEntity("/internal/auth/login",
                Map.of("username", username, "password", password), Map.class);
        assertThat(r.getStatusCode()).as("login %s", username).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    String token(String username, String password) {
        return (String) login(username, password).get("accessToken");
    }

    void asSuperuser(String sql, Object... args) {
        try (Connection c = AppRoleDatabase.superuser(postgres); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    long countSuperuser(String sql, Object... args) {
        try (Connection c = AppRoleDatabase.superuser(postgres); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    UUID createUserIn(String token, String username) {
        Map<String, Object> body = new HashMap<>(Map.of("username", username, "fullName", "Họ Tên " + username,
                "email", username + "@x.vn", "password", PASSWORD));
        ResponseEntity<Map> r = call(HttpMethod.POST, "/internal/users", token, body, Map.class);
        assertThat(r.getStatusCode()).as("tạo %s", username).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    UUID newTenant(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, code, name, status) VALUES (?, ?, ?, 'ACTIVE')", id, code, "Thư viện " + code);
        return id;
    }

    /** Tenant B với một quản trị viên (role is_admin của B); trả token của quản trị viên B (đã switch-tenant). */
    String tenantBAdmin(UUID tenantB, String code, String adminA) {
        UUID bAdmin = createUserIn(adminA, code + "-admin");
        jdbc.update("INSERT INTO tenant_memberships (user_id, tenant_id, status, joined_at) VALUES (?, ?, 'ACTIVE', "
                + "now() + interval '1 hour')", bAdmin, tenantB);
        UUID role = UUID.randomUUID();
        asSuperuser("INSERT INTO roles (id, tenant_id, name, description, is_admin) VALUES (?, ?, 'B_ADMIN', 'QT B', TRUE)",
                role, tenantB);
        asSuperuser("INSERT INTO user_roles (user_id, role_id, tenant_id) VALUES (?, ?, ?)", bAdmin, role, tenantB);
        Map<String, Object> session = login(code + "-admin", PASSWORD);
        ResponseEntity<Map> switched = call(HttpMethod.POST, "/internal/auth/switch-tenant",
                (String) session.get("accessToken"),
                Map.of("tenantId", tenantB, "refreshToken", session.get("refreshToken")), Map.class);
        assertThat(switched.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) switched.getBody().get("accessToken");
    }

    /** Người có cờ platform_admin và membership ở tenant A: token mang tenantId (đã chọn tenant), không phải phiên nền tảng. */
    String platformAdminWithTenant(String adminA, String username, String... permissionKeys) {
        plainUserOfA(adminA, username, permissionKeys);
        jdbc.update("UPDATE credentials SET platform_admin = TRUE WHERE username = ?", username);
        return token(username, PASSWORD);
    }

    /**
     * Người thường của tenant A (không admin) được cấp đúng các khóa endpoint cho role của A. Các khóa phải đã được
     * đăng ký (gọi endpoint bằng admin trước).
     */
    String plainUserOfA(String adminA, String username, String... permissionKeys) {
        UUID id = createUserIn(adminA, username);
        jdbc.update("UPDATE credentials SET must_change_password = FALSE WHERE user_id = ?", id);
        UUID tenantA = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
        UUID role = UUID.randomUUID();
        asSuperuser("INSERT INTO roles (id, tenant_id, name, description, is_admin) VALUES (?, ?, ?, 'Đọc', FALSE)",
                role, tenantA, "PLAIN_" + username.toUpperCase().replace('-', '_'));
        for (String key : permissionKeys) {
            asSuperuser("INSERT INTO role_permissions (role_id, permission_code) VALUES (?, ?)", role, key);
        }
        asSuperuser("INSERT INTO user_roles (user_id, role_id, tenant_id) VALUES (?, ?, ?)", id, role, tenantA);
        return token(username, PASSWORD);
    }
}
