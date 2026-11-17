package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
 * Không rò biến phiên app.tenant_scope trong pool (ADR-0008): Hikari chỉ có 1 connection nên request phạm vi ALL của
 * platform admin rồi request của người dùng tenant A chạy trên cùng connection; người dùng chỉ được thấy tenant A.
 * Chạy bằng vai trò identity_app (chịu RLS). Cần Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/identity",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.producer.properties.max.block.ms=300",
        "spring.kafka.admin.properties.request.timeout.ms=500",
        "spring.kafka.admin.properties.default.api.timeout.ms=500",
        "auth.bootstrap-admin.password=Admin@12345",
        "auth.bootstrap.platform-admin-username=platform-root",
        "auth.bootstrap.platform-admin-password=PlatformRoot@123",
        "app.internal-api-key=test-internal-key"
})
@Testcontainers
class PlatformScopeLeakIT {

    static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(CrossTenantFixture fx, String url, String token) {
        ResponseEntity<List> r = fx.call(HttpMethod.GET, url, token, null, List.class);
        assertThat(r.getStatusCode()).as(url).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestAllRoiRequestNguoiDungTenantATrenCungConnectionChiThayA() throws Exception {
        CrossTenantFixture fx = new CrossTenantFixture(rest, jdbc, postgres);
        // datasource có thể bọc Hikari (TenantRlsDataSource): unwrap để chắc pool chỉ có 1 connection
        assertThat(dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class).getMaximumPoolSize()).isEqualTo(1);

        String adminA = fx.token("admin", "Admin@12345");
        UUID tenantB = fx.newTenant("leak-b");
        String adminB = fx.tenantBAdmin(tenantB, "leak-b", adminA);
        assertThat(fx.call(HttpMethod.POST, "/internal/departments", adminA, Map.of("code", "LEAK-A", "name", "A"), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(fx.call(HttpMethod.POST, "/internal/departments", adminB, Map.of("code", "LEAK-B", "name", "B"), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        fx.call(HttpMethod.GET, "/internal/departments", adminA, null, List.class); // đăng ký khóa
        fx.call(HttpMethod.GET, "/internal/roles", adminA, null, List.class);
        String plainA = fx.plainUserOfA(adminA, "leak-plain-a", "DepartmentController/list",
                "AccessControlController/listRoles");
        String platform = fx.token("platform-root", "PlatformRoot@123");

        for (int round = 0; round < 3; round++) {
            List<Map<String, Object>> all = list(fx, "/internal/departments", platform);
            assertThat(all).extracting(d -> d.get("code")).contains("LEAK-A", "LEAK-B");

            // cùng connection duy nhất của pool: người dùng tenant A, admin A và admin B chỉ thấy tenant của mình
            for (String token : List.of(plainA, adminA)) {
                List<Map<String, Object>> depts = list(fx, "/internal/departments", token);
                assertThat(depts).extracting(d -> d.get("code")).contains("LEAK-A").doesNotContain("LEAK-B");
                assertThat(depts).extracting(d -> d.get("tenantId")).containsOnly(TENANT_A.toString());
                List<Map<String, Object>> roles = list(fx, "/internal/roles", token);
                assertThat(roles).extracting(d -> d.get("tenantId")).containsOnly(TENANT_A.toString());
            }
            List<Map<String, Object>> deptsB = list(fx, "/internal/departments", adminB);
            assertThat(deptsB).extracting(d -> d.get("code")).contains("LEAK-B").doesNotContain("LEAK-A");

            // đọc trực tiếp trên connection duy nhất sau request ALL: biến phiên không còn 'all' (identity_app, chịu RLS)
            assertThat(jdbc.queryForObject("SELECT coalesce(current_setting('app.tenant_scope', true), '')", String.class))
                    .isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM departments", Long.class)).isZero();
        }
    }
}
