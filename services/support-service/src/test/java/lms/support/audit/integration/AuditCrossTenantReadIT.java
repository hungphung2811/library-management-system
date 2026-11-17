package lms.support.audit.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.database.core.DatabaseConnectionRegistry;
import lms.security.permission.PermissionKeyRegistrar;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-0008 cho audit-logs trên PostgreSQL thật, vai trò audit_app (NOBYPASSRLS), JWT qua Spring Security
 * (JwtDecoder giả theo chuỗi token), pool Hikari 1 connection (đồng thời kiểm chứng không rò biến phiên).
 * Không cần Kafka/Mongo. Chạy: mvn test -Dtest=AuditCrossTenantReadIT -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44
 */
@Tag("docker")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.producer.properties.max.block.ms=300",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "database.connections.AUDIT_DB.pool.max-size=1",
        "database.connections.AUDIT_DB.pool.min-idle=0",
        "database.connections.LOG_DB.uri=mongodb://localhost:1/none?serverSelectionTimeoutMS=200&connectTimeoutMS=200"
})
@Testcontainers
class AuditCrossTenantReadIT {

    static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    static final int PER_TENANT = 3;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void databases(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @TestConfiguration
    static class AuditFlywayTestConfiguration {
        @Bean(name = "auditFlyway", initMethod = "migrate")
        @DependsOn("databaseLifecycleManager")
        Flyway auditFlyway(DatabaseConnectionRegistry registry) {
            return Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration/audit")
                    .load();
        }
    }

    @Autowired TestRestTemplate rest;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final Map<String, Map<String, Object>> TOKENS = Map.of(
            "platform", Map.of("platformAdmin", true),
            "platform-in-A", Map.of("platformAdmin", true, "tenantId", TENANT_A.toString()),
            "user-A", Map.of("tenantId", TENANT_A.toString(), "permissions", List.of("AuditLogController/search")),
            "admin-A", Map.of("tenantId", TENANT_A.toString(), "admin", true),
            "user-none", Map.of("permissions", List.of("AuditLogController/search")));

    private void stubDecoder() {
        when(jwtDecoder.decode(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            Map<String, Object> claims = new HashMap<>(TOKENS.get(name));
            claims.put("sub", name);
            return new Jwt(name, Instant.now(), Instant.now().plusSeconds(600), Map.of("alg", "none"), claims);
        });
    }

    private void seed() throws Exception {
        try (Connection c = AppRoleDatabase.superuser(postgres)) {
            c.createStatement().execute("DELETE FROM audit_logs");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO audit_logs (id, tenant_id, event_id, event_type, "
                    + "aggregate_id, occurred_at, recorded_at, payload) VALUES (?,?,?,?,?,?,?,?)")) {
                Instant base = Instant.parse("2026-01-01T00:00:00Z");
                for (UUID tenant : List.of(TENANT_A, TENANT_B)) {
                    for (int i = 0; i < PER_TENANT; i++) {
                        ps.setObject(1, UUID.randomUUID());
                        ps.setObject(2, tenant);
                        ps.setObject(3, UUID.randomUUID());
                        ps.setString(4, "BookBorrowedEvent");
                        ps.setString(5, "agg-" + i);
                        ps.setTimestamp(6, Timestamp.from(base.plusSeconds((tenant.equals(TENANT_A) ? 0 : 100) + i)));
                        ps.setTimestamp(7, Timestamp.from(base));
                        ps.setString(8, "{}");
                        ps.executeUpdate();
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> get(String token, String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) rest.exchange(
                "/internal/audit-logs" + query, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<String> tenants(ResponseEntity<Map<String, Object>> r) {
        return ((List<Map<String, Object>>) r.getBody().get("items")).stream()
                .map(m -> (String) m.get("tenantId")).toList();
    }

    @Test
    void platformAdminDocXuyenTenantVaPhanTrangDung() throws Exception {
        stubDecoder();
        seed();

        // (a) không tenantId: cả hai tenant, total = 6, phân trang đúng (trang 0 gồm 4 dòng, trang 1 gồm 2 dòng)
        ResponseEntity<Map<String, Object>> all = get("platform", "?size=4&page=0");
        assertThat(all.getStatusCode().value()).isEqualTo(200);
        assertThat(all.getBody().get("total")).isEqualTo(2 * PER_TENANT);
        // sắp occurredAt DESC: 3 dòng B (100..102) rồi 1 dòng A
        assertThat(tenants(all)).containsExactly(TENANT_B.toString(), TENANT_B.toString(), TENANT_B.toString(),
                TENANT_A.toString());
        ResponseEntity<Map<String, Object>> page1 = get("platform", "?size=4&page=1");
        assertThat(tenants(page1)).containsExactly(TENANT_A.toString(), TENANT_A.toString());
        assertThat(page1.getBody().get("page")).isEqualTo(1);
        assertThat(page1.getBody().get("size")).isEqualTo(4);

        // (b) tenantId=A: chỉ A
        ResponseEntity<Map<String, Object>> onlyA = get("platform", "?tenantId=" + TENANT_A);
        assertThat(onlyA.getBody().get("total")).isEqualTo(PER_TENANT);
        assertThat(tenants(onlyA)).hasSize(PER_TENANT).containsOnly(TENANT_A.toString());

        // (c) tenant không tồn tại: 200 rỗng
        ResponseEntity<Map<String, Object>> none = get("platform", "?tenantId=" + UUID.randomUUID());
        assertThat(none.getStatusCode().value()).isEqualTo(200);
        assertThat(none.getBody().get("total")).isEqualTo(0);
        assertThat(tenants(none)).isEmpty();

        // (d) sai định dạng
        ResponseEntity<Map<String, Object>> bad = get("platform", "?tenantId=khong-phai-uuid");
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        assertThat(bad.getBody().get("code")).isEqualTo("INVALID_TENANT_ID");
    }

    @Test
    void nguoiKhacBoQuaTenantIdVaKhongRoBienPhien() throws Exception {
        stubDecoder();
        seed();
        String other = "?tenantId=" + TENANT_B;

        for (String who : List.of("user-A", "admin-A")) {
            // Pool 1 connection: gọi ALL ngay trước để chứng minh biến phiên không rò sang request sau
            assertThat(get("platform", "").getBody().get("total")).isEqualTo(2 * PER_TENANT);
            ResponseEntity<Map<String, Object>> r = get(who, other);
            assertThat(r.getStatusCode().value()).as(who).isEqualTo(200);
            assertThat(r.getBody().get("total")).as(who).isEqualTo(PER_TENANT);
            assertThat(tenants(r)).as(who).containsOnly(TENANT_A.toString());
        }
        // platform admin đã chọn tenant (token có tenantId) bị coi như người dùng tenant đó: không mang khóa, không admin -> 403
        assertThat(get("platform", "").getBody().get("total")).isEqualTo(2 * PER_TENANT);
        assertThat(get("platform-in-A", other).getStatusCode().value()).isEqualTo(403);
        // tenantId sai định dạng từ user thường: bị bỏ qua, không 400
        assertThat(get("user-A", "?tenantId=xyz").getStatusCode().value()).isEqualTo(200);
        // user thường không tenant: 403 TENANT_REQUIRED như cũ
        ResponseEntity<Map<String, Object>> noTenant = get("user-none", "");
        assertThat(noTenant.getStatusCode().value()).isEqualTo(403);
        assertThat(noTenant.getBody().get("code")).isEqualTo("TENANT_REQUIRED");
    }
}
