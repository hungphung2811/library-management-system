package lms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.security.CallerIdentity.AuthType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CallerIdentityTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("sub-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(b::claim);
        return b.build();
    }

    @Test
    void khongXacThucThiRong() {
        assertThat(CallerIdentity.current()).isEmpty();
    }

    @Test
    void phanLoaiTheoClaimDaKy() {
        UUID tenant = UUID.randomUUID();
        UUID app = UUID.randomUUID();
        String t = tenant.toString();

        CallerIdentity integration = CallerIdentity.from(jwt(Map.of(JwtClaims.AUDIENCE,
                List.of(JwtClaims.AUDIENCE_INTEGRATION), JwtClaims.TENANT_ID, t, JwtClaims.APPLICATION_ID, app.toString(),
                JwtClaims.SCOPE, "books:read  orders:read")));
        assertThat(integration.type()).isEqualTo(AuthType.INTEGRATION);
        assertThat(integration.tenantId()).isEqualTo(tenant);
        assertThat(integration.applicationId()).isEqualTo(app);
        assertThat(integration.scopes()).containsExactlyInAnyOrder("books:read", "orders:read");
        assertThat(integration.subject()).isEqualTo("sub-1");

        // aud integration thắng mọi claim khác (kể cả svc)
        assertThat(CallerIdentity.from(jwt(Map.of(JwtClaims.AUDIENCE, List.of(JwtClaims.AUDIENCE_INTEGRATION),
                JwtClaims.SERVICE, "customer-api"))).type()).isEqualTo(AuthType.INTEGRATION);
        assertThat(CallerIdentity.from(jwt(Map.of(JwtClaims.SERVICE, "customer-api", JwtClaims.TENANT_ID, t))).type())
                .isEqualTo(AuthType.SERVICE);
        assertThat(CallerIdentity.from(jwt(Map.of(JwtClaims.AUDIENCE, List.of(JwtClaims.AUDIENCE_STOREFRONT)))).type())
                .isEqualTo(AuthType.STOREFRONT);
        assertThat(CallerIdentity.from(jwt(Map.of(JwtClaims.AUDIENCE, List.of(JwtClaims.AUDIENCE_STAFF)))).type())
                .isEqualTo(AuthType.STAFF);
        CallerIdentity staff = CallerIdentity.from(jwt(Map.of(JwtClaims.USERNAME, "alice")));
        assertThat(staff.type()).isEqualTo(AuthType.STAFF);
        assertThat(staff.tenantId()).isNull();
        assertThat(staff.applicationId()).isNull();
        assertThat(staff.scopes()).isEmpty();
    }

    @Test
    void claimUuidSaiDinhDangThanhNull() {
        assertThat(CallerIdentity.from(jwt(Map.of(JwtClaims.TENANT_ID, "khong-phai-uuid"))).tenantId()).isNull();
    }

    @Test
    void currentDocTuSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt(Map.of(JwtClaims.SERVICE, "customer-api")), AuthorityUtils.NO_AUTHORITIES));

        assertThat(CallerIdentity.current()).get().extracting(CallerIdentity::type).isEqualTo(AuthType.SERVICE);
    }
}
