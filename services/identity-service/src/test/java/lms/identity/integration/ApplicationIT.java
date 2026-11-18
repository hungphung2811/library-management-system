package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import lms.identity.apiclient.application.ApplicationViews.ApplicationDetail;
import lms.identity.apiclient.application.ApplicationViews.CreatedCredential;
import lms.identity.apiclient.application.CreateApplicationUseCase;
import lms.identity.apiclient.application.ManageApplicationDomainsUseCase;
import lms.identity.apiclient.application.ManageApplicationUseCase;
import lms.identity.apiclient.application.ManageCredentialsUseCase;
import lms.identity.apiclient.application.QueryApplicationUseCase;
import lms.identity.apiclient.domain.Application;
import lms.identity.apiclient.domain.ApplicationException;
import lms.identity.apiclient.domain.ApplicationException.Reason;
import lms.identity.apiclient.domain.ApplicationKind;
import lms.identity.apiclient.domain.ApplicationRepository;
import lms.identity.tenant.application.ApplicationOriginException;
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
 * Pha P1 (SaaS API Authentication) trên PostgreSQL thật, chạy bằng vai trò identity_app: migration V32, adapter
 * persistence (không RLS, lọc (tenant_id, id)), IDOR hai tenant, giới hạn 2 credential, secret chỉ lưu hash, lịch sử
 * chỉ thêm (trigger + REVOKE), domain gắn application_id, FK kép chống lệch tenant. Cần Docker. Chạy:
 * mvn -pl services/identity-service -am test -Dtest=ApplicationIT -Dsurefire.failIfNoSpecifiedTests=false
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
class ApplicationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired TestRestTemplate rest;
    @Autowired DataSource dataSource;
    @Autowired CreateApplicationUseCase create;
    @Autowired QueryApplicationUseCase query;
    @Autowired ManageApplicationUseCase manage;
    @Autowired ManageCredentialsUseCase credentials;
    @Autowired ManageApplicationDomainsUseCase domains;
    @Autowired ApplicationRepository applications;

    private final UUID actor = UUID.randomUUID();

    @SuppressWarnings("rawtypes")
    private UUID createTenant(String code) {
        HttpHeaders login = new HttpHeaders();
        ResponseEntity<Map> r = rest.exchange("/internal/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", "platform-root", "password", "PlatformRoot@123"), login),
                Map.class);
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

    @Test
    void vongDoiDayDuTrenDbThatVoiCoLapHaiTenant() {
        UUID a = createTenant("app-a");
        UUID b = createTenant("app-b");

        // website + domain (cột application_id) và tên duy nhất trong tenant
        ApplicationDetail web = create.execute(a, new CreateApplicationUseCase.Command("Web", "PUBLIC_SITE", null), actor);
        assertThat(web.siteKey()).startsWith("lms_pk_");
        assertThatThrownBy(() -> create.execute(a, new CreateApplicationUseCase.Command("web", "PUBLIC_SITE", null),
                actor)).isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.APPLICATION_NAME_DUPLICATED));
        create.execute(b, new CreateApplicationUseCase.Command("Web", "PUBLIC_SITE", null), actor);

        var domain = domains.add(a, web.id(), "https://Shop-A.vn", actor);
        assertThat(domains.list(a, web.id()).domains()).extracting(d -> d.origin()).containsExactly("https://shop-a.vn");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("select application_id from tenant_storefront_origins where id = ?", UUID.class,
                domain.id())).isEqualTo(web.id());
        // trùng toàn hệ thống từ tenant khác: không lộ chủ
        UUID bApp = applications.findByTenant(b).get(0).id();
        assertThatThrownBy(() -> domains.add(b, bApp, "https://shop-a.vn", actor))
                .isInstanceOfSatisfying(ApplicationOriginException.class,
                        e -> assertThat(e.reason()).isEqualTo(ApplicationOriginException.Reason.DUPLICATED));

        // INTEGRATION lưu thẳng qua repository (hợp đồng ACTIVE được kiểm ở unit test)
        Application erp = Application.create(a, "ERP", ApplicationKind.INTEGRATION, 600, null, actor, Instant.now());
        applications.save(erp);
        manage.replaceScopes(a, erp.id(), List.of("books:read", "orders:read"), actor);
        assertThat(query.get(a, erp.id()).scopes()).containsExactly("books:read", "orders:read");
        manage.replaceScopes(a, erp.id(), List.of("inventory:read"), actor);
        assertThat(query.get(a, erp.id()).scopes()).containsExactly("inventory:read");

        // credential: chỉ lưu hash, tối đa 2 dùng được
        CreatedCredential c1 = credentials.create(a, erp.id(), null, actor);
        CreatedCredential c2 = credentials.create(a, erp.id(), 30, actor);
        String hash = jdbc.queryForObject("select secret_hash from application_credentials where id = ?", String.class,
                c1.credentialId());
        assertThat(hash).hasSize(64).isNotEqualTo(c1.clientSecret());
        assertThat(jdbc.queryForObject("select count(*) from application_credentials where secret_hash = ?",
                Integer.class, c1.clientSecret())).isZero();
        assertThatThrownBy(() -> credentials.create(a, erp.id(), null, actor))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.CREDENTIAL_LIMIT_EXCEEDED));
        credentials.revoke(a, erp.id(), c1.credentialId(), "ROTATED", actor);
        assertThat(c2.credentialId()).isNotNull();

        // IDOR: tenant B không thấy/sửa/thu hồi dữ liệu của A (404 cùng thân)
        assertThatThrownBy(() -> query.get(b, erp.id())).isInstanceOfSatisfying(ApplicationException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.APPLICATION_NOT_FOUND));
        assertThatThrownBy(() -> credentials.revoke(b, erp.id(), c2.credentialId(), null, actor))
                .isInstanceOf(ApplicationException.class);
        assertThat(query.list(b)).extracting(s -> s.name()).containsExactly("Web");

        // lịch sử chỉ thêm: UPDATE/DELETE bị trigger/REVOKE chặn dưới vai trò app
        assertThatThrownBy(() -> jdbc.update("update application_history set detail = 'x'")).isNotNull();
        assertThatThrownBy(() -> jdbc.update("delete from application_history")).isNotNull();
        assertThat(query.history(a, erp.id())).isNotEmpty();

        // FK kép: origin không thể gắn application của tenant khác
        assertThatThrownBy(() -> jdbc.update("update tenant_storefront_origins set application_id = ? where id = ?",
                bApp, domain.id())).isNotNull();

        // xóa mềm: thu hồi credential, xóa domain, biến mất khỏi API
        manage.delete(a, erp.id(), actor);
        manage.delete(a, web.id(), actor);
        assertThat(query.list(a)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from application_credentials where application_id = ? "
                + "and status = 'ACTIVE'", Integer.class, erp.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tenant_storefront_origins where application_id = ?",
                Integer.class, web.id())).isZero();
    }
}
