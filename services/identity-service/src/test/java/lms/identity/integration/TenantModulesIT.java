package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Module theo tenant (ADR-0009, B0.2) trên PostgreSQL thật, ứng dụng chạy bằng vai trò identity_app (chịu RLS): API
 * platform bật/tắt, tạo tenant theo module, chép role mẫu, claim {@code modules} ở đăng nhập, làm mới, đổi tenant.
 * Cần Docker. Chạy: mvn -pl services/identity-service -am test -Dtest=TenantModulesIT
 * -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44
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
        "auth.bootstrap.platform-admin-username=platform-root",
        "auth.bootstrap.platform-admin-password=PlatformRoot@123",
        "app.internal-api-key=test-internal-key"
})
@Testcontainers
class TenantModulesIT {

    static final String DEFAULT_TENANT = "00000000-0000-0000-0000-0000000000d1";
    static final String ADMIN_PASSWORD = "TempAdmin@123";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired TestRestTemplate rest;
    @Autowired JwtDecoder jwtDecoder;
    @Autowired JdbcTemplate jdbc;

    // ---------------------------------------------------------------- helpers

    private <T> ResponseEntity<T> call(HttpMethod method, String url, String token, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange(url, method, new HttpEntity<>(body, headers), type);
    }

    @SuppressWarnings("unchecked")
    private String accessToken(String username, String password) {
        ResponseEntity<Map> response = rest.postForEntity("/internal/auth/login",
                Map.of("username", username, "password", password), Map.class);
        assertThat(response.getStatusCode()).as("login %s", username).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("accessToken");
    }

    private String platformToken() {
        return accessToken("platform-root", "PlatformRoot@123");
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> login(String username, String tenantCode) {
        Map<String, Object> body = new HashMap<>(Map.of("username", username, "password", ADMIN_PASSWORD));
        if (tenantCode != null) body.put("tenantCode", tenantCode);
        return rest.postForEntity("/internal/auth/login", body, Map.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> refresh(String refreshToken) {
        return rest.postForEntity("/internal/auth/refresh", Map.of("refreshToken", refreshToken), Map.class);
    }

    private List<String> modulesClaim(Object accessToken) {
        return jwtDecoder.decode((String) accessToken).getClaimAsStringList("modules");
    }

    private static Map<String, Object> createBody(String code, List<String> modules) {
        Map<String, Object> body = new HashMap<>(Map.of("code", code, "name", "Thư viện " + code, "newAdmin",
                Map.of("username", code + "-admin", "fullName", "Quản trị " + code, "email", code + "@x.vn",
                        "temporaryPassword", ADMIN_PASSWORD)));
        if (modules != null) body.put("modules", modules);
        return body;
    }

    @SuppressWarnings("unchecked")
    private UUID createTenant(String code, List<String> modules) {
        ResponseEntity<Map> created = call(HttpMethod.POST, "/api/platform/tenants", platformToken(),
                createBody(code, modules), Map.class);
        assertThat(created.getStatusCode()).as("tạo tenant %s", code).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) created.getBody().get("id"));
    }

    private List<String> enabledModules(UUID tenantId) {
        return jdbc.queryForList("SELECT module FROM tenant_modules WHERE tenant_id = ? AND enabled ORDER BY module",
                String.class, tenantId);
    }

    private List<String> roleNames(UUID tenantId) {
        return TenantContext.callAs(tenantId, () -> jdbc.queryForList("SELECT name FROM roles ORDER BY name",
                String.class));
    }

    /** Mẫu role của module SALES (chưa có seed thật ở pha 0): chèn bằng superuser, quyền dùng mã đã có trong catalog. */
    private void seedSalesTemplates() throws Exception {
        try (java.sql.Connection c = AppRoleDatabase.superuser(postgres); java.sql.Statement s = c.createStatement()) {
            s.execute("INSERT INTO role_templates (id, name, description, is_admin, module) VALUES "
                    + "('00000000-0000-0000-0000-0000000000b1', 'CASHIER_IT', 'Thu ngân (IT)', FALSE, 'SALES'), "
                    + "('00000000-0000-0000-0000-0000000000b2', 'STOCKKEEPER_IT', 'Thủ kho (IT)', FALSE, 'SALES') "
                    + "ON CONFLICT DO NOTHING");
            s.execute("INSERT INTO role_template_permissions (template_id, permission_code) VALUES "
                    + "('00000000-0000-0000-0000-0000000000b1', 'TitleController/search') ON CONFLICT DO NOTHING");
        }
    }

    // ---------------------------------------------------------------- tests

    @Test
    void tenantMacDinhCoLendingVaTokenMangClaimModules() {
        assertThat(enabledModules(UUID.fromString(DEFAULT_TENANT))).containsExactly("LENDING");

        String token = accessToken("admin", "Admin@12345");
        assertThat(modulesClaim(token)).containsExactly("LENDING");
    }

    @Test
    @SuppressWarnings("unchecked")
    void taoTenantMacDinhLendingTaoDuRoleMauLendingVaTokenMangModules() {
        ResponseEntity<Map> created = call(HttpMethod.POST, "/api/platform/tenants", platformToken(),
                createBody("it-mod-default", null), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((List<String>) created.getBody().get("modules")).containsExactly("LENDING");
        UUID tenantId = UUID.fromString((String) created.getBody().get("id"));

        assertThat(enabledModules(tenantId)).containsExactly("LENDING");
        assertThat(roleNames(tenantId)).hasSize(jdbc.queryForObject("SELECT count(*) FROM role_templates "
                + "WHERE module IS NULL OR module = 'LENDING'", Integer.class));
        ResponseEntity<Map> login = login("it-mod-default-admin", "it-mod-default");
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(modulesClaim(login.getBody().get("accessToken"))).containsExactly("LENDING");
    }

    @Test
    void taoTenantModulesRongHoacMaLaTra400KhongTaoGi() {
        String operator = platformToken();

        ResponseEntity<Map> empty = call(HttpMethod.POST, "/api/platform/tenants", operator,
                createBody("it-mod-empty", List.of()), Map.class);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(empty.getBody().get("code")).isEqualTo("INVALID_MODULES");
        ResponseEntity<Map> unknown = call(HttpMethod.POST, "/api/platform/tenants", operator,
                createBody("it-mod-unknown", List.of("PAYROLL")), Map.class);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getBody().get("code")).isEqualTo("UNKNOWN_MODULE");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants WHERE code IN ('it-mod-empty', 'it-mod-unknown')",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credentials WHERE username LIKE 'it-mod-empty%' "
                + "OR username LIKE 'it-mod-unknown%'", Integer.class)).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void taoTenantChiSalesChepMauLoiCongMauSalesKhongChepMauLending() throws Exception {
        seedSalesTemplates();
        UUID tenantId = createTenant("it-mod-shop", List.of("SALES"));

        assertThat(enabledModules(tenantId)).containsExactly("SALES");
        assertThat(roleNames(tenantId)).containsExactlyInAnyOrderElementsOf(
                jdbc.queryForList("SELECT name FROM role_templates WHERE module IS NULL OR module = 'SALES'",
                        String.class));
        assertThat(roleNames(tenantId)).contains("CASHIER_IT", "STOCKKEEPER_IT").doesNotContain("MEMBER");
        // quyền của mẫu được chép theo
        assertThat(TenantContext.callAs(tenantId, () -> jdbc.queryForList("SELECT rp.permission_code FROM role_permissions "
                + "rp JOIN roles r ON r.id = rp.role_id WHERE r.name = 'CASHIER_IT'", String.class)))
                .containsExactly("TitleController/search");
        ResponseEntity<Map> login = login("it-mod-shop-admin", "it-mod-shop");
        assertThat(modulesClaim(login.getBody().get("accessToken"))).containsExactly("SALES");
    }

    @Test
    @SuppressWarnings("unchecked")
    void batSalesCheMauMotLanKhongNhanBanKhongGhiDeVaClaimMoiSauRefresh() throws Exception {
        seedSalesTemplates();
        String operator = platformToken();
        UUID tenantId = createTenant("it-mod-enable", null);
        ResponseEntity<Map> before = login("it-mod-enable-admin", "it-mod-enable");
        String refreshToken = (String) before.getBody().get("refreshToken");
        assertThat(modulesClaim(before.getBody().get("accessToken"))).containsExactly("LENDING");

        ResponseEntity<Map> enabled = call(HttpMethod.PUT, "/api/platform/tenants/" + tenantId + "/modules/SALES",
                operator, null, Map.class);
        assertThat(enabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(enabled.getBody()).containsEntry("module", "SALES").containsEntry("enabled", true);
        assertThat(enabledModules(tenantId)).containsExactly("LENDING", "SALES");
        assertThat(roleNames(tenantId)).contains("CASHIER_IT", "STOCKKEEPER_IT");

        // quản trị tenant sửa mô tả role đã chép; bật lần hai không nhân bản, không ghi đè
        TenantContext.runAs(tenantId, () -> jdbc.update("UPDATE roles SET description = 'đã sửa' WHERE name = 'CASHIER_IT'"));
        int rolesBefore = roleNames(tenantId).size();
        ResponseEntity<Map> again = call(HttpMethod.PUT, "/api/platform/tenants/" + tenantId + "/modules/SALES",
                operator, null, Map.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("updatedAt")).isEqualTo(enabled.getBody().get("updatedAt"));
        assertThat(roleNames(tenantId)).hasSize(rolesBefore);
        assertThat(TenantContext.callAs(tenantId, () -> jdbc.queryForObject(
                "SELECT description FROM roles WHERE name = 'CASHIER_IT'", String.class))).isEqualTo("đã sửa");

        // token đã phát không đổi; làm mới mới nhận SALES
        assertThat(modulesClaim(before.getBody().get("accessToken"))).containsExactly("LENDING");
        ResponseEntity<Map> refreshed = refresh(refreshToken);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(modulesClaim(refreshed.getBody().get("accessToken"))).containsExactly("LENDING", "SALES");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tatModuleCuoiTra409TatModuleKhacGiuRoleVaClaimBoModuleSauRefresh() throws Exception {
        seedSalesTemplates();
        String operator = platformToken();
        UUID tenantId = createTenant("it-mod-disable", List.of("LENDING", "SALES"));
        String refreshToken = (String) login("it-mod-disable-admin", "it-mod-disable").getBody().get("refreshToken");
        int rolesBefore = roleNames(tenantId).size();

        ResponseEntity<Map> disabled = call(HttpMethod.DELETE, "/api/platform/tenants/" + tenantId + "/modules/SALES",
                operator, null, Map.class);
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(disabled.getBody()).containsEntry("module", "SALES").containsEntry("enabled", false);
        // giữ dòng (enabled=false) và không xóa role
        assertThat(jdbc.queryForObject("SELECT enabled FROM tenant_modules WHERE tenant_id = ? AND module = 'SALES'",
                Boolean.class, tenantId)).isFalse();
        assertThat(roleNames(tenantId)).hasSize(rolesBefore);

        ResponseEntity<Map> last = call(HttpMethod.DELETE, "/api/platform/tenants/" + tenantId + "/modules/LENDING",
                operator, null, Map.class);
        assertThat(last.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(last.getBody().get("code")).isEqualTo("TENANT_MODULE_REQUIRED");
        assertThat(enabledModules(tenantId)).containsExactly("LENDING");

        ResponseEntity<Map> refreshed = refresh(refreshToken);
        assertThat(modulesClaim(refreshed.getBody().get("accessToken"))).containsExactly("LENDING");
    }

    @Test
    @SuppressWarnings("unchecked")
    void danhSachModuleVaChiTietTenantCoModules() {
        String operator = platformToken();
        UUID tenantId = createTenant("it-mod-list", null);

        ResponseEntity<List> list = call(HttpMethod.GET, "/api/platform/tenants/" + tenantId + "/modules", operator,
                null, List.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> rows = list.getBody();
        assertThat(rows).extracting(r -> r.get("module")).containsExactly("LENDING", "SALES");
        assertThat(rows).extracting(r -> r.get("enabled")).containsExactly(true, false);

        ResponseEntity<Map> detail = call(HttpMethod.GET, "/api/platform/tenants/" + tenantId, operator, null, Map.class);
        assertThat((List<String>) detail.getBody().get("modules")).containsExactly("LENDING");
        ResponseEntity<Map> page = call(HttpMethod.GET, "/api/platform/tenants?keyword=it-mod-list", operator, null,
                Map.class);
        assertThat(((List<Map<String, Object>>) page.getBody().get("items")).get(0).get("modules"))
                .isEqualTo(List.of("LENDING"));

        assertThat(call(HttpMethod.GET, "/api/platform/tenants/" + UUID.randomUUID() + "/modules", operator, null,
                Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.PUT, "/api/platform/tenants/" + tenantId + "/modules/PAYROLL", operator, null,
                Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void quanTriVienTenantVaKhongTokenKhongGoiDuocApiModule() {
        UUID tenantId = createTenant("it-mod-denied", null);
        String tenantAdmin = (String) login("it-mod-denied-admin", "it-mod-denied").getBody().get("accessToken");

        assertThat(call(HttpMethod.PUT, "/api/platform/tenants/" + tenantId + "/modules/SALES", tenantAdmin, null,
                Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/platform/tenants/" + tenantId + "/modules", tenantAdmin, null,
                Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/platform/tenants/" + tenantId + "/modules", null, null, Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(enabledModules(tenantId)).containsExactly("LENDING");
    }

    @Test
    @SuppressWarnings("unchecked")
    void phienNenTangKhongCoClaimModulesVaDoiTenantNhanModulesCuaTenantDich() {
        String operator = platformToken();
        assertThat(jwtDecoder.decode(operator).hasClaim("modules")).isFalse();

        UUID a = createTenant("it-mod-sw-a", null);
        UUID b = createTenant("it-mod-sw-b", List.of("SALES"));
        UUID adminOfA = jdbc.queryForObject("SELECT user_id FROM credentials WHERE username = 'it-mod-sw-a-admin'",
                UUID.class);
        assertThat(call(HttpMethod.POST, "/api/platform/tenants/" + b + "/members", operator,
                Map.of("userId", adminOfA), Map.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> inA = login("it-mod-sw-a-admin", "it-mod-sw-a");
        assertThat(modulesClaim(inA.getBody().get("accessToken"))).containsExactly("LENDING");
        // availableTenants (hiển thị) cũng mang modules của từng tenant
        List<Map<String, Object>> available = (List<Map<String, Object>>) inA.getBody().get("availableTenants");
        assertThat(available).filteredOn(t -> b.toString().equals(t.get("id"))).singleElement()
                .satisfies(t -> assertThat(t.get("modules")).isEqualTo(List.of("SALES")));

        ResponseEntity<Map> switched = call(HttpMethod.POST, "/internal/auth/switch-tenant",
                (String) inA.getBody().get("accessToken"),
                Map.of("tenantId", b, "refreshToken", inA.getBody().get("refreshToken")), Map.class);
        assertThat(switched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(modulesClaim(switched.getBody().get("accessToken"))).containsExactly("SALES");
        assertThat(a).isNotEqualTo(b);
    }
}
