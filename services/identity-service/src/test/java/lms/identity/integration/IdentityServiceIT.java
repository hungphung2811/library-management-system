package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Khởi động nguyên identity-service (auth + accesscontrol) trên PostgreSQL thật và chạy luồng RBAC từ đầu đến cuối.
 * Kafka không chạy: broker giả không với tới được, listener tắt, producer chờ rất ngắn (chỉ log lỗi publish).
 * Cần Docker. Chạy: mvn test -Dtest=IdentityServiceIT -Dsurefire.failIfNoSpecifiedTests=false
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
@Import(IdentityServiceIT.ProbeConfig.class)
class IdentityServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired TestRestTemplate rest;
    @Autowired JwtDecoder jwtDecoder;

    @SuppressWarnings("unchecked")
    private Map<String, Object> login(String username, String password, HttpStatus expected) {
        ResponseEntity<Map> response = rest.postForEntity("/internal/auth/login",
                Map.of("username", username, "password", password), Map.class);
        assertThat(response.getStatusCode()).as("login %s", username).isEqualTo(expected);
        return response.getBody();
    }

    private <T> ResponseEntity<T> call(HttpMethod method, String url, String token, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange(url, method, new HttpEntity<>(body, headers), type);
    }

    // ---- phân quyền động theo endpoint ----

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    /** Endpoint thử nghiệm KHÔNG gắn annotation quyền nào, nên dùng khóa tự sinh ProbeController/ping. */
    @RestController
    static class ProbeController {
        @GetMapping("/internal/e2e-probe/ping")
        public Map<String, String> ping() {
            return Map.of("pong", "ok");
        }
    }

    private static final String PROBE = "/internal/e2e-probe/ping";

    private String accessToken(String username, String password) {
        return (String) login(username, password, HttpStatus.OK).get("accessToken");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> permissionCatalog(String adminToken) {
        return call(HttpMethod.GET, "/internal/permissions", adminToken, null, List.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private String roleId(String adminToken, String name) {
        List<Map<String, Object>> roles = call(HttpMethod.GET, "/internal/roles", adminToken, null, List.class).getBody();
        return (String) roles.stream().filter(r -> name.equals(r.get("name"))).findFirst().orElseThrow().get("id");
    }

    @SuppressWarnings("unchecked")
    private String createRole(String token, String name, boolean admin, HttpStatus expected) {
        ResponseEntity<Map> response = call(HttpMethod.POST, "/internal/roles", token,
                Map.of("name", name, "description", "Role thử nghiệm " + name, "isAdmin", admin), Map.class);
        assertThat(response.getStatusCode()).as("tạo role %s", name).isEqualTo(expected);
        return response.getStatusCode().is2xxSuccessful() ? (String) response.getBody().get("id") : null;
    }

    private UUID createUser(String adminToken, String username, String password) {
        UUID userId = UUID.randomUUID();
        assertThat(call(HttpMethod.POST, "/internal/auth/credentials", adminToken,
                Map.of("userId", userId, "username", username, "password", password), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        return userId;
    }

    private void assign(String token, UUID userId, String roleId, HttpStatus expected) {
        assertThat(call(HttpMethod.POST, "/internal/access/users/" + userId + "/roles", token,
                Map.of("roleId", roleId), String.class).getStatusCode()).isEqualTo(expected);
    }

    @Test
    void endpointMoiTuDongDangKyKhoaRoiAdminGanChoRoleVaNguoiDungCoQuyen() {
        String admin = accessToken("admin", "Admin@12345");
        UUID userId = createUser(admin, "probe-user", "Probe@12345");
        String before = accessToken("probe-user", "Probe@12345");

        // 1. chưa có khóa trong token: bị từ chối, nhưng khóa được đăng ký vào danh mục permission
        ResponseEntity<String> denied = call(HttpMethod.GET, PROBE, before, null, String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody()).contains("ProbeController/ping");
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(permissionCatalog(admin))
                .anySatisfy(p -> {
                    assertThat(p.get("code")).isEqualTo("ProbeController/ping");
                    assertThat(p.get("source")).isEqualTo("AUTO");
                    assertThat(p.get("service")).isEqualTo("identity-service");
                    assertThat(p.get("httpMethod")).isEqualTo("GET");
                    assertThat(p.get("pathPattern")).isEqualTo(PROBE);
                }));

        // 2. người dùng có role is_admin được bỏ qua kiểm tra
        assertThat(call(HttpMethod.GET, PROBE, admin, null, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // 3. admin tạo role, gán khóa cho role, gán role cho người dùng
        String role = createRole(admin, "PROBE_ROLE", false, HttpStatus.CREATED);
        assertThat(call(HttpMethod.POST, "/internal/roles/" + role + "/permissions", admin,
                Map.of("permissionCode", "ProbeController/ping"), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assign(admin, userId, role, HttpStatus.NO_CONTENT);

        // 4. đăng nhập lại nhận quyền mới; token cũ vẫn chưa có (quyền nằm trong token đến khi hết hạn/làm mới)
        String after = accessToken("probe-user", "Probe@12345");
        assertThat(call(HttpMethod.GET, PROBE, after, null, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, PROBE, before, null, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jwtDecoder.decode(after).getClaimAsStringList("permissions")).containsExactly("ProbeController/ping");

        // 5. gỡ khóa khỏi role: lần đăng nhập sau không còn quyền
        assertThat(call(HttpMethod.DELETE, "/internal/roles/" + role + "/permissions/ProbeController/ping", admin, null,
                Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        String revoked = accessToken("probe-user", "Probe@12345");
        assertThat(call(HttpMethod.GET, PROBE, revoked, null, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void chiQuanTriVienMoiTaoVaGanDuocRoleQuanTri() {
        String admin = accessToken("admin", "Admin@12345");
        // người có quyền tạo role và gán role nhưng KHÔNG phải quản trị viên
        String assignerRole = createRole(admin, "ASSIGNER_ROLE", false, HttpStatus.CREATED);
        for (String key : List.of("AccessControlController/assignRole", "AccessControlController/createRole")) {
            call(HttpMethod.POST, "/internal/roles/" + assignerRole + "/permissions", admin,
                    Map.of("permissionCode", key), Map.class);
        }
        UUID assignerId = createUser(admin, "assigner", "Assign@12345");
        assign(admin, assignerId, assignerRole, HttpStatus.NO_CONTENT);
        String assigner = accessToken("assigner", "Assign@12345");
        assertThat((Object) jwtDecoder.decode(assigner).getClaim("admin")).isNull();

        // không tạo được role quản trị; admin thì tạo được
        createRole(assigner, "SNEAKY_ADMIN", true, HttpStatus.FORBIDDEN);
        String sneaky = createRole(admin, "SNEAKY_ADMIN", true, HttpStatus.CREATED);

        // không tự nâng mình lên quản trị; admin thì gán được và token nhận claim admin
        assign(assigner, assignerId, sneaky, HttpStatus.FORBIDDEN);
        assign(admin, assignerId, sneaky, HttpStatus.NO_CONTENT);
        assertThat(jwtDecoder.decode(accessToken("assigner", "Assign@12345")).getClaimAsBoolean("admin")).isTrue();

        // role thường thì người có quyền gán role gán được
        UUID other = createUser(admin, "plain-user", "Plain@12345");
        assign(assigner, other, roleId(admin, "MEMBER"), HttpStatus.NO_CONTENT);
    }

    @Test
    void apiNoiBoDangKyKhoaCanKhoaChiaSeVaIdempotent() {
        Map<String, Object> body = Map.of("service", "svc-x", "key", "FooController/bar", "source", "AUTO",
                "httpMethod", "GET", "pathPattern", "/api/foo", "description", "GET /api/foo (svc-x)");
        String url = "/internal/permissions/registrations";
        HttpHeaders withKey = new HttpHeaders();
        withKey.set("X-Internal-Api-Key", "test-internal-key");

        assertThat(rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, withKey), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, withKey), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        String admin = accessToken("admin", "Admin@12345");
        assertThat(permissionCatalog(admin).stream().filter(p -> "FooController/bar".equals(p.get("code"))).toList())
                .hasSize(1).first().satisfies(p -> assertThat(p.get("service")).isEqualTo("svc-x"));

        Map<String, Object> invalid = Map.of("service", "svc-x", "key", "khoa sai", "source", "AUTO");
        assertThat(rest.exchange(url, HttpMethod.POST, new HttpEntity<>(invalid, withKey), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void adminDangNhapNhanTokenChuaRoleVaPermissionTuDatabase() {
        String token = (String) login("admin", "Admin@12345", HttpStatus.OK).get("accessToken");

        Jwt jwt = jwtDecoder.decode(token);

        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("SUPER_ADMIN");
        assertThat(jwt.getClaimAsStringList("permissions"))
                .contains("AccessControlController/assignRole", "AuthController/register", "BorrowingController/borrow");
        // SUPER_ADMIN được đánh dấu is_admin trong dữ liệu (migration V9), nên token mang claim admin
        assertThat(jwt.getClaimAsBoolean("admin")).isTrue();
        assertThat(jwt.getHeaders()).containsKey("kid");
    }

    @Test
    void jwksCongKhaiVaCoCungKid() {
        String token = (String) login("admin", "Admin@12345", HttpStatus.OK).get("accessToken");
        String kid = (String) jwtDecoder.decode(token).getHeaders().get("kid");

        ResponseEntity<Map> jwks = rest.getForEntity("/.well-known/jwks.json", Map.class);

        assertThat(jwks.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.getBody().get("keys");
        assertThat(keys).extracting(k -> k.get("kid")).contains(kid);
        assertThat(keys.get(0)).doesNotContainKey("d");
    }

    @Test
    void apiPhanQuyenCanTokenVaPermission() {
        String adminToken = (String) login("admin", "Admin@12345", HttpStatus.OK).get("accessToken");

        assertThat(call(HttpMethod.GET, "/internal/roles", null, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.GET, "/internal/roles", "token-gia", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<List> roles = call(HttpMethod.GET, "/internal/roles", adminToken, null, List.class);
        assertThat(roles.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(roles.getBody()).hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void taoNguoiDungGanRoleMemberRoiChiCoQuyenCuaMember() {
        String adminToken = (String) login("admin", "Admin@12345", HttpStatus.OK).get("accessToken");
        UUID userId = UUID.randomUUID();

        assertThat(call(HttpMethod.POST, "/internal/auth/credentials", adminToken,
                Map.of("userId", userId, "username", "member1", "password", "Member@1234"), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<Map<String, Object>> roles = call(HttpMethod.GET, "/internal/roles", adminToken, null, List.class).getBody();
        String memberRoleId = (String) roles.stream().filter(r -> "MEMBER".equals(r.get("name"))).findFirst()
                .orElseThrow().get("id");
        assertThat(call(HttpMethod.POST, "/internal/access/users/" + userId + "/roles", adminToken,
                Map.of("roleId", memberRoleId), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String memberToken = (String) login("member1", "Member@1234", HttpStatus.OK).get("accessToken");
        Jwt jwt = jwtDecoder.decode(memberToken);

        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("MEMBER");
        // Quyền cũ BOOK_VIEW, BORROW_BOOK, RESERVE_BOOK đã được migration V9 đổi thành các khóa endpoint tương ứng
        assertThat(jwt.getClaimAsStringList("permissions")).containsExactlyInAnyOrder(
                "TitleController/search", "TitleController/get", "BookCopyController/list",
                "BorrowingController/borrow", "BorrowingController/renew",
                "ReservationController/reserve", "ReservationController/mine", "ReservationController/cancel");
        assertThat((Object) jwt.getClaim("admin")).isNull();
        assertThat(call(HttpMethod.GET, "/internal/roles", memberToken, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/internal/auth/credentials", memberToken,
                Map.of("userId", UUID.randomUUID(), "username", "x1234", "password", "Whatever@123"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void lamMoiPhienXoayVongVaDungLaiTokenCuThuHoiMoiPhien() {
        Map<String, Object> first = login("admin", "Admin@12345", HttpStatus.OK);
        String oldRefresh = (String) first.get("refreshToken");

        ResponseEntity<Map> rotated = rest.postForEntity("/internal/auth/refresh", Map.of("refreshToken", oldRefresh), Map.class);
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        String newRefresh = (String) rotated.getBody().get("refreshToken");
        assertThat(newRefresh).isNotEqualTo(oldRefresh);

        // dùng lại token cũ: bị từ chối và mọi phiên của admin bị thu hồi, kể cả token mới
        assertThat(rest.postForEntity("/internal/auth/refresh", Map.of("refreshToken", oldRefresh), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.postForEntity("/internal/auth/refresh", Map.of("refreshToken", newRefresh), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void khoaTaiKhoanSauNamLanSaiMatKhauVaLuuDuocTrongDatabase() {
        String adminToken = (String) login("admin", "Admin@12345", HttpStatus.OK).get("accessToken");
        call(HttpMethod.POST, "/internal/auth/credentials", adminToken,
                Map.of("userId", UUID.randomUUID(), "username", "locky", "password", "Correct@123"), Map.class);

        for (int i = 0; i < 5; i++) login("locky", "sai-mat-khau", HttpStatus.UNAUTHORIZED);

        // số lần sai phải được lưu dù ném exception (noRollbackFor); lần thứ sáu dù đúng mật khẩu vẫn bị khóa
        login("locky", "Correct@123", HttpStatus.LOCKED);
    }

    // ---- đăng nhập, đổi tenant, refresh theo tenant (Task 4a) ----

    static final UUID DEFAULT_TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private UUID newTenant(String code, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, code, name, status) VALUES (?, ?, ?, ?)", id, code, "Thư viện " + code, status);
        return id;
    }

    private void joinTenant(UUID userId, UUID tenantId) {
        // tham gia sau tenant mặc định để thứ tự "tham gia sớm nhất" rõ ràng
        jdbc.update("INSERT INTO tenant_memberships (user_id, tenant_id, status, joined_at) VALUES (?, ?, 'ACTIVE', "
                + "now() + interval '1 hour')", userId, tenantId);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> loginTo(String username, String password, String tenantCode) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("username", username, "password", password));
        if (tenantCode != null) body.put("tenantCode", tenantCode);
        return rest.postForEntity("/internal/auth/login", body, Map.class);
    }

    private ResponseEntity<Map> switchTo(String token, UUID tenantId) {
        return call(HttpMethod.POST, "/internal/auth/switch-tenant", token, Map.of("tenantId", tenantId), Map.class);
    }

    private ResponseEntity<Map> refreshWith(String refreshToken) {
        return rest.postForEntity("/internal/auth/refresh", Map.of("refreshToken", refreshToken), Map.class);
    }

    private String tenantClaim(Object accessToken) {
        return jwtDecoder.decode((String) accessToken).getClaimAsString("tenantId");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dangNhapNhanTokenCuaTenantMacDinhKemDanhSachTenant() {
        Map<String, Object> body = login("admin", "Admin@12345", HttpStatus.OK);

        assertThat(tenantClaim(body.get("accessToken"))).isEqualTo(DEFAULT_TENANT.toString());
        assertThat((Object) jwtDecoder.decode((String) body.get("accessToken")).getClaim("platformAdmin")).isNull();
        assertThat(body.get("tenantId")).isEqualTo(DEFAULT_TENANT.toString());
        assertThat((List<Map<String, Object>>) body.get("availableTenants"))
                .anySatisfy(t -> assertThat(t.get("code")).isEqualTo("default"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void doiTenantLuuLastTenantVaLanDangNhapSauVaoDungTenant() {
        String admin = accessToken("admin", "Admin@12345");
        UUID userId = createUser(admin, "tenant-switcher", "Switch@12345");
        UUID second = newTenant("it-second", "ACTIVE");
        joinTenant(userId, second);

        Map<String, Object> first = login("tenant-switcher", "Switch@12345", HttpStatus.OK);
        assertThat(tenantClaim(first.get("accessToken"))).isEqualTo(DEFAULT_TENANT.toString());
        assertThat((List<Map<String, Object>>) first.get("availableTenants")).hasSize(2);

        ResponseEntity<Map> switched = switchTo((String) first.get("accessToken"), second);
        assertThat(switched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tenantClaim(switched.getBody().get("accessToken"))).isEqualTo(second.toString());
        assertThat(jdbc.queryForObject("SELECT last_tenant_id FROM credentials WHERE user_id = ?", UUID.class, userId))
                .isEqualTo(second);
        // refresh token mới gắn tenant vừa chọn
        ResponseEntity<Map> refreshed = refreshWith((String) switched.getBody().get("refreshToken"));
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tenantClaim(refreshed.getBody().get("accessToken"))).isEqualTo(second.toString());

        assertThat(tenantClaim(login("tenant-switcher", "Switch@12345", HttpStatus.OK).get("accessToken")))
                .isEqualTo(second.toString());
        // mã tenant tường minh thắng last_tenant_id
        assertThat(tenantClaim(loginTo("tenant-switcher", "Switch@12345", "default").getBody().get("accessToken")))
                .isEqualTo(DEFAULT_TENANT.toString());

        List<Map<String, Object>> mine = call(HttpMethod.GET, "/internal/me/tenants",
                (String) switched.getBody().get("accessToken"), null, List.class).getBody();
        assertThat(mine).extracting(t -> t.get("code")).containsExactly("default", "it-second");
    }

    @Test
    void doiHoacDangNhapVaoTenantKhongCoMembershipBi403CungThongDiep() {
        String admin = accessToken("admin", "Admin@12345");
        createUser(admin, "tenant-outsider", "Outside@12345");
        UUID foreign = newTenant("it-foreign", "ACTIVE");
        String token = accessToken("tenant-outsider", "Outside@12345");

        ResponseEntity<Map> denied = switchTo(token, foreign);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code")).isEqualTo("NO_TENANT_ACCESS");

        ResponseEntity<Map> byCode = loginTo("tenant-outsider", "Outside@12345", "it-foreign");
        ResponseEntity<Map> unknown = loginTo("tenant-outsider", "Outside@12345", "khong-ton-tai");
        assertThat(byCode.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        for (ResponseEntity<Map> r : List.of(byCode, unknown)) {
            assertThat(r.getBody().get("code")).isEqualTo("NO_TENANT_ACCESS");
            assertThat(r.getBody().get("message")).isEqualTo(denied.getBody().get("message"));
        }
    }

    @Test
    void refreshBiTuChoiKhiTenantBiTamNgungHoacMembershipBiTat() {
        String admin = accessToken("admin", "Admin@12345");
        UUID userId = createUser(admin, "tenant-refresher", "Refresh@12345");
        UUID suspendLater = newTenant("it-suspend", "ACTIVE");
        joinTenant(userId, suspendLater);
        String token = accessToken("tenant-refresher", "Refresh@12345");

        String inSuspended = (String) switchTo(token, suspendLater).getBody().get("refreshToken");
        jdbc.update("UPDATE tenants SET status = 'SUSPENDED' WHERE id = ?", suspendLater);
        ResponseEntity<Map> refused = refreshWith(inSuspended);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code")).isEqualTo("NO_TENANT_ACCESS");
        // tenant tạm ngưng không còn được chọn: đăng nhập rơi về tenant mặc định
        assertThat(tenantClaim(accessToken("tenant-refresher", "Refresh@12345"))).isEqualTo(DEFAULT_TENANT.toString());

        String inDefault = (String) login("tenant-refresher", "Refresh@12345", HttpStatus.OK).get("refreshToken");
        jdbc.update("UPDATE tenant_memberships SET status = 'DISABLED' WHERE user_id = ? AND tenant_id = ?",
                userId, DEFAULT_TENANT);
        ResponseEntity<Map> disabled = refreshWith(inDefault);
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(disabled.getBody().get("code")).isEqualTo("NO_TENANT_ACCESS");
        // không còn tenant nào: đăng nhập cũng bị từ chối
        assertThat(loginTo("tenant-refresher", "Refresh@12345", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void refreshTokenCuKhongGanTenantBiTuChoi() {
        String admin = accessToken("admin", "Admin@12345");
        UUID userId = createUser(admin, "tenant-legacy", "Legacy@12345");
        String refresh = (String) login("tenant-legacy", "Legacy@12345", HttpStatus.OK).get("refreshToken");
        jdbc.update("UPDATE refresh_tokens SET tenant_id = NULL WHERE user_id = ?", userId); // như token phát hành trước V12

        assertThat(refreshWith(refresh).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void platformAdminKhongCoMembershipNhanTokenPlatformAdminKhongTenantId() {
        String admin = accessToken("admin", "Admin@12345");
        UUID userId = createUser(admin, "platform-op", "Platform@12345");
        jdbc.update("UPDATE credentials SET platform_admin = TRUE WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM tenant_memberships WHERE user_id = ?", userId);

        Map<String, Object> body = login("platform-op", "Platform@12345", HttpStatus.OK);
        Jwt jwt = jwtDecoder.decode((String) body.get("accessToken"));
        assertThat(jwt.getClaimAsBoolean("platformAdmin")).isTrue();
        assertThat(jwt.hasClaim("tenantId")).isFalse();
        assertThat(body.get("tenantId")).isNull();
        assertThat((List<Object>) body.get("availableTenants")).isEmpty();

        // endpoint chỉ cần đăng nhập vẫn dùng được với token không có tenantId
        assertThat(call(HttpMethod.GET, "/internal/me/tenants", (String) body.get("accessToken"), null, List.class)
                .getBody()).isEmpty();
        // phiên nền tảng làm mới được khi credential còn cờ; mất cờ thì bị từ chối
        ResponseEntity<Map> refreshed = refreshWith((String) body.get("refreshToken"));
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jwtDecoder.decode((String) refreshed.getBody().get("accessToken")).getClaimAsBoolean("platformAdmin"))
                .isTrue();
        jdbc.update("UPDATE credentials SET platform_admin = FALSE WHERE user_id = ?", userId);
        assertThat(refreshWith((String) refreshed.getBody().get("refreshToken")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
