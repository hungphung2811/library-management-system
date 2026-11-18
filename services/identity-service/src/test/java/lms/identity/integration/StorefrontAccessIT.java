package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T-C1/T-C2 (ADR-0011) trên PostgreSQL thật, chạy bằng vai trò identity_app (chịu RLS): token dịch vụ storefront và
 * 404 SHOP_NOT_AVAILABLE đồng nhất. Cần Docker. Chạy: mvn -pl services/identity-service -am test
 * -Dtest=StorefrontAccessIT -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44
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
        "app.internal-api-key=test-internal-key",
        "lms.identity.storefront.client-secret=it-storefront-secret"
})
@Testcontainers
class StorefrontAccessIT {

    static final String PATH = "/internal/storefront/access-tokens";
    static final String SECRET = "it-storefront-secret";
    static final String ADMIN_PASSWORD = "TempAdmin@123";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired TestRestTemplate rest;
    @Autowired JwtDecoder jwtDecoder;

    private ResponseEntity<String> access(String secret, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (secret != null) headers.set("X-Storefront-Client-Secret", secret);
        return rest.exchange(PATH, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /** Bỏ trường timestamp (khác nhau giữa các lần gọi, không mang thông tin). */
    private static String norm(String body) {
        return body == null ? null : body.replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"-\"");
    }

    private ResponseEntity<String> access(String code) {
        return access(SECRET, "{\"tenantCode\":" + (code == null ? "null" : "\"" + code + "\"") + "}");
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> call(HttpMethod method, String url, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange(url, method, new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private String platformToken() {
        ResponseEntity<Map> r = rest.postForEntity("/internal/auth/login",
                Map.of("username", "platform-root", "password", "PlatformRoot@123"), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("accessToken");
    }

    @SuppressWarnings("unchecked")
    private UUID createTenant(String code, List<String> modules) {
        Map<String, Object> body = new HashMap<>(Map.of("code", code, "name", "Cửa hàng " + code, "newAdmin",
                Map.of("username", code + "-admin", "fullName", "Quản trị " + code, "email", code + "@x.vn",
                        "temporaryPassword", ADMIN_PASSWORD)));
        if (modules != null) body.put("modules", modules);
        ResponseEntity<Map> created = call(HttpMethod.POST, "/api/platform/tenants", platformToken(), body);
        assertThat(created.getStatusCode()).as("tạo tenant %s", code).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) created.getBody().get("id"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> okBody(ResponseEntity<String> r) throws Exception {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(r.getBody(), Map.class);
    }

    @Test
    void tenantActiveCoSalesCapTokenSvcDungClaim() throws Exception {
        UUID tenantId = createTenant("sf-ok", List.of("SALES"));

        Map<String, Object> body = okBody(access("sf-ok"));
        assertThat(body.get("tenantId")).isEqualTo(tenantId.toString());
        assertThat(body.get("code")).isEqualTo("sf-ok");
        assertThat(body.get("expiresIn")).isEqualTo(300);
        assertThat(body.keySet()).containsExactlyInAnyOrder("tenantId", "code", "name", "accessToken", "expiresIn",
                "allowedOrigins", "modules", "applicationId");
        assertThat(body.get("applicationId")).as("tenantCode thì applicationId null").isNull();
        assertThat(body.get("modules")).isEqualTo(List.of("SALES"));

        Jwt jwt = jwtDecoder.decode((String) body.get("accessToken"));
        assertThat(jwt.getClaimAsString("svc")).isEqualTo("customer-api");
        assertThat(jwt.getClaimAsString("tenantId")).isEqualTo(tenantId.toString());
        assertThat(jwt.getClaimAsStringList("modules")).containsExactly("SALES");
        assertThat(jwt.getClaimAsStringList("permissions")).isNullOrEmpty();
        assertThat(jwt.hasClaim("admin")).as("không có admin").isFalse();
        assertThat(jwt.hasClaim("platformAdmin")).as("không có platformAdmin").isFalse();
        assertThat(jwt.hasClaim("roles")).as("không có roles").isFalse();
        assertThat(jwt.hasClaim("mustChangePassword")).isFalse();
        assertThat(jwt.getSubject()).isEqualTo("00000000-0000-0000-0000-000000005f01");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void maVietHoaVaKhoangTrangDuocChuanHoa() throws Exception {
        createTenant("sf-norm", List.of("SALES"));
        assertThat(okBody(access("  SF-NORM "))).containsEntry("code", "sf-norm");
    }

    @Test
    void moiLyDoKhongKhaDungCungMotStatusVaCungMotThan() {
        createTenant("sf-lend-only", List.of("LENDING"));
        UUID suspended = createTenant("sf-suspended", List.of("SALES"));
        assertThat(call(HttpMethod.POST, "/api/platform/tenants/" + suspended + "/suspend", platformToken(), null)
                .getStatusCode().is2xxSuccessful()).isTrue();

        List<String> codes = java.util.Arrays.asList("sf-lend-only", "sf-suspended", "khong-ton-tai", "BAD CODE!",
                "a", "x".repeat(300), "", "   ", null);
        ResponseEntity<String> first = access(codes.get(0));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        for (String code : codes) {
            ResponseEntity<String> r = access(code);
            assertThat(r.getStatusCode()).as("mã %s", code).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(norm(r.getBody())).as("thân của mã %s", code).isEqualTo(norm(first.getBody())).contains("SHOP_NOT_AVAILABLE");
            assertThat(r.getHeaders().getContentType()).isEqualTo(first.getHeaders().getContentType());
        }
        assertThat(first.getBody()).doesNotContain("tenant").doesNotContain("SALES").doesNotContain("LENDING")
                .doesNotContain("SUSPENDED");
    }

    @Test
    void ngungRoiKichHoatLaiVaTatSalesCoHieuLucNgay() throws Exception {
        UUID id = createTenant("sf-toggle", List.of("SALES"));
        String operator = platformToken();
        assertThat(access("sf-toggle").getStatusCode()).isEqualTo(HttpStatus.OK);

        call(HttpMethod.POST, "/api/platform/tenants/" + id + "/suspend", operator, null);
        assertThat(access("sf-toggle").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        call(HttpMethod.POST, "/api/platform/tenants/" + id + "/activate", operator, null);
        assertThat(access("sf-toggle").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void biMatSaiThieuHoacTokenNguoiDungKhongThayDuoc() {
        createTenant("sf-secret", List.of("SALES"));
        assertThat(access(null, "{\"tenantCode\":\"sf-secret\"}").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(access("sai", "{\"tenantCode\":\"sf-secret\"}").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(access("test-internal-key", "{\"tenantCode\":\"sf-secret\"}").getStatusCode())
                .as("INTERNAL_API_KEY không thay được bí mật storefront").isEqualTo(HttpStatus.UNAUTHORIZED);
        // sai bí mật không được phân biệt tenant tồn tại hay không (không lộ gì)
        assertThat(norm(access("sai", "{\"tenantCode\":\"khong-co\"}").getBody()))
                .isEqualTo(norm(access("sai", "{\"tenantCode\":\"sf-secret\"}").getBody()));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(platformToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        assertThat(rest.exchange(PATH, HttpMethod.POST, new HttpEntity<>("{\"tenantCode\":\"sf-secret\"}", headers),
                String.class).getStatusCode()).as("JWT platform admin không thay được bí mật")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** T-C1 trên service thật: token svc bị 403 ở mọi endpoint nội bộ của identity, kể cả @AuthenticatedOnly/@PlatformAdminOnly. */
    @Test
    void tokenSvcBi403OMoiEndpointKhacCuaIdentity() throws Exception {
        createTenant("sf-svc", List.of("SALES"));
        String svc = (String) okBody(access("sf-svc")).get("accessToken");

        // @AuthenticatedOnly
        assertThat(call(HttpMethod.GET, "/internal/users/me", svc, null).getStatusCode())
                .as("@AuthenticatedOnly /internal/users/me").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/me/tenants", svc, null).getStatusCode().value())
                .as("/api/me/tenants").isIn(403, 404);
        // @PlatformAdminOnly
        assertThat(call(HttpMethod.GET, "/api/platform/tenants", svc, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/api/platform/tenants", svc, Map.of()).getStatusCode().value())
                .isIn(400, 403).isEqualTo(403);
        // endpoint thường theo khóa
        ResponseEntity<Map> users = call(HttpMethod.GET, "/internal/users", svc, null);
        assertThat(users.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // thân lỗi 403 không cần lộ tên khóa nội bộ cho khách
        assertThat(String.valueOf(users.getBody())).doesNotContain("UserController/");
        // không cấp thêm token bằng token svc
        assertThat(rest.exchange(PATH, HttpMethod.POST, bearerJson(svc, "{\"tenantCode\":\"sf-svc\"}"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // refresh/switch-tenant với token svc không đổi được thành phiên thật
        assertThat(call(HttpMethod.POST, "/api/auth/switch-tenant", svc,
                Map.of("tenantId", UUID.randomUUID().toString(), "refreshToken", "x")).getStatusCode().is2xxSuccessful())
                .isFalse();
    }

    private static HttpEntity<String> bearerJson(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
