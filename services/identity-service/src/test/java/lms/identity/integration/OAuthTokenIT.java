package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import lms.identity.apiclient.application.ApplicationViews.CreatedCredential;
import lms.identity.apiclient.application.ManageApplicationUseCase;
import lms.identity.apiclient.application.ManageCredentialsUseCase;
import lms.identity.apiclient.domain.Application;
import lms.identity.apiclient.domain.ApplicationKind;
import lms.identity.apiclient.domain.ApplicationRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Token endpoint OAuth2 client_credentials (pha I2) trên PostgreSQL thật, chạy bằng vai trò identity_app: cấp token qua
 * HTTP với Basic và form, claim thực, last_used_at ghi được bằng vai trò app, hai credential cùng dùng được, thu hồi
 * credential/tạm ngưng application/secret sai đều {@code invalid_client}. Cần Docker. Chạy:
 * mvn -pl services/identity-service -am test -Dtest=OAuthTokenIT -Dsurefire.failIfNoSpecifiedTests=false
 * -DargLine=-Dapi.version=1.44
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
class OAuthTokenIT {

    private static final String TOKEN = "/internal/oauth2/token";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired TestRestTemplate rest;
    @Autowired DataSource dataSource;
    @Autowired ApplicationRepository applications;
    @Autowired ManageApplicationUseCase manage;
    @Autowired ManageCredentialsUseCase credentials;

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID actor = UUID.randomUUID();

    @SuppressWarnings("rawtypes")
    private UUID createTenant(String code) {
        ResponseEntity<Map> r = rest.exchange("/internal/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", "platform-root", "password", "PlatformRoot@123")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth((String) r.getBody().get("accessToken"));
        Map<String, Object> body = new HashMap<>(Map.of("code", code, "name", "Cửa hàng " + code, "newAdmin",
                Map.of("username", code + "-admin", "fullName", "Quản trị " + code, "email", code + "@x.vn",
                        "temporaryPassword", "TempAdmin@123")));
        body.put("modules", List.of("SALES"));
        ResponseEntity<Map> created = rest.exchange("/api/platform/tenants", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) created.getBody().get("id"));
    }

    private ResponseEntity<String> basic(UUID clientId, String secret, String scope) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8)));
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        if (scope != null) form.add("scope", scope);
        return rest.exchange(TOKEN, HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
    }

    private JsonNode payload(String jwt) throws Exception {
        return mapper.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
    }

    @Test
    void capTokenThatVoiCredentialTrenDbVaTuChoiMoiLyDoCungMotMa() throws Exception {
        UUID tenant = createTenant("oauth-a");
        Application erp = Application.create(tenant, "ERP", ApplicationKind.INTEGRATION, 600, null, actor,
                Instant.now());
        applications.save(erp);
        manage.replaceScopes(tenant, erp.id(), List.of("books:read", "inventory:read", "orders:read"), actor);
        CreatedCredential c1 = credentials.create(tenant, erp.id(), null, actor);
        CreatedCredential c2 = credentials.create(tenant, erp.id(), null, actor);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Basic hợp lệ, scope con
        ResponseEntity<String> ok = basic(erp.id(), c1.clientSecret(), "books:read orders:read");
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(ok.getHeaders().getFirst("Pragma")).isEqualTo("no-cache");
        JsonNode body = mapper.readTree(ok.getBody());
        assertThat(body.get("token_type").asText()).isEqualTo("Bearer");
        assertThat(body.get("expires_in").asLong()).isEqualTo(300);
        assertThat(body.get("scope").asText()).isEqualTo("books:read orders:read");
        JsonNode claims = payload(body.get("access_token").asText());
        assertThat(claims.get("sub").asText()).isEqualTo(erp.id().toString());
        // Nimbus ghi aud một phần tử dạng chuỗi (RFC 7519), nhiều phần tử dạng mảng
        JsonNode aud = claims.get("aud");
        assertThat(aud.isArray() ? aud.get(0).asText() : aud.asText()).isEqualTo("lms-integration");
        assertThat(claims.get("tenantId").asText()).isEqualTo(tenant.toString());
        assertThat(claims.get("application_id").asText()).isEqualTo(erp.id().toString());
        assertThat(claims.get("scope").asText()).isEqualTo("books:read orders:read");
        assertThat(claims.has("admin") || claims.has("platformAdmin") || claims.has("permissions")
                || claims.has("roles") || claims.has("svc")).isFalse();
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(300);

        // last_used_at được ghi bằng vai trò app (một lần mỗi giờ)
        assertThat(jdbc.queryForObject("select last_used_at from application_credentials where id = ?",
                java.sql.Timestamp.class, c1.credentialId())).isNotNull();

        // không có scope: cấp mọi scope đã cấp; credential thứ hai cũng dùng được
        ResponseEntity<String> all = basic(erp.id(), c2.clientSecret(), null);
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(all.getBody()).get("scope").asText())
                .isEqualTo("books:read inventory:read orders:read");

        // scope chưa cấp
        ResponseEntity<String> badScope = basic(erp.id(), c1.clientSecret(), "books:write");
        assertThat(badScope.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(badScope.getBody()).get("error").asText()).isEqualTo("invalid_scope");

        // mọi lý do thất bại cùng một phản hồi invalid_client 401
        ResponseEntity<String> wrongSecret = basic(erp.id(), c1.clientSecret() + "x", null);
        ResponseEntity<String> unknownClient = basic(UUID.randomUUID(), c1.clientSecret(), null);
        assertThat(wrongSecret.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongSecret.getHeaders().getFirst("WWW-Authenticate")).startsWith("Basic");
        assertThat(unknownClient.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownClient.getBody()).isEqualTo(wrongSecret.getBody());

        // thu hồi credential 1: chỉ credential đó bị từ chối
        credentials.revoke(tenant, erp.id(), c1.credentialId(), "ROTATED", actor);
        assertThat(basic(erp.id(), c1.clientSecret(), null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(basic(erp.id(), c2.clientSecret(), null).getStatusCode()).isEqualTo(HttpStatus.OK);

        // tạm ngưng application: từ chối; kích hoạt lại: dùng được
        manage.suspend(tenant, erp.id(), actor);
        ResponseEntity<String> suspended = basic(erp.id(), c2.clientSecret(), null);
        assertThat(suspended.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(suspended.getBody()).isEqualTo(wrongSecret.getBody());
        manage.reactivate(tenant, erp.id(), actor);
        assertThat(basic(erp.id(), c2.clientSecret(), null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
