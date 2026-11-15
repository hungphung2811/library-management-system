package lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class CurrentUserTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEPT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TENANT = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static Jwt jwt(Consumer<Jwt.Builder> customizer) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        customizer.accept(b);
        return b.build();
    }

    @Test
    void tokenHopLeTrichDayDuDanhTinh() {
        CurrentUser u = CurrentUser.from(jwt(b -> b.subject(USER.toString()).claim(JwtClaims.USERNAME, "an")
                .claim(JwtClaims.DEPARTMENT_ID, DEPT.toString()).claim(JwtClaims.TENANT_ID, TENANT.toString())));

        assertThat(u.userId()).isEqualTo(USER);
        assertThat(u.username()).isEqualTo("an");
        assertThat(u.departmentId()).isEqualTo(DEPT);
        assertThat(u.tenantId()).isEqualTo(TENANT);
        assertThat(u.inDepartment(DEPT)).isTrue();
    }

    @Test
    void claimVangThiNull() {
        CurrentUser u = CurrentUser.from(jwt(b -> b.subject(USER.toString())));

        assertThat(u.userId()).isEqualTo(USER);
        assertThat(u.username()).isNull();
        assertThat(u.departmentId()).isNull();
        assertThat(u.tenantId()).isNull();
        assertThat(u.inDepartment(DEPT)).isFalse();
    }

    @Test
    void subKhongPhaiUuidLaTokenKhongHopLe() {
        assertThatThrownBy(() -> CurrentUser.from(jwt(b -> b.subject("khong-phai-uuid"))))
                .isInstanceOf(InvalidBearerTokenException.class)
                .hasMessageNotContaining("khong-phai-uuid");
    }

    @Test
    void subNullLaTokenKhongHopLe() {
        assertThatThrownBy(() -> CurrentUser.from(jwt(b -> b.claim("foo", "bar"))))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void departmentIdSaiDinhDangLaTokenKhongHopLe() {
        assertThatThrownBy(() -> CurrentUser.from(
                        jwt(b -> b.subject(USER.toString()).claim(JwtClaims.DEPARTMENT_ID, "xyz"))))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void tenantIdSaiDinhDangLaTokenKhongHopLe() {
        assertThatThrownBy(() -> CurrentUser.from(
                        jwt(b -> b.subject(USER.toString()).claim(JwtClaims.TENANT_ID, "xyz"))))
                .isInstanceOf(InvalidBearerTokenException.class);
    }
}
