package lms.customer.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

/** Bắt buộc aud chứa lms-storefront (docs/api/token-audience.md): token nhân viên, không aud, svc không qua. */
class StorefrontJwtConfigurationTest {

    private final OAuth2TokenValidator<Jwt> validator = StorefrontJwtConfiguration.validator();

    private static Jwt token(List<String> audience, String claim) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("u")
                .issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(300));
        if (audience != null) {
            b.audience(audience);
        }
        if (claim != null) {
            b.claim(claim, "x");
        }
        return b.build();
    }

    @Test
    void tokenStorefrontHopLe() {
        assertThat(validator.validate(token(List.of("lms-storefront"), null)).hasErrors()).isFalse();
    }

    @Test
    void thieuAudHoacAudKhacBiTuChoi() {
        assertThat(validator.validate(token(null, null)).hasErrors()).isTrue();
        assertThat(validator.validate(token(List.of("lms-staff"), null)).hasErrors()).isTrue();
        assertThat(validator.validate(token(List.of("lms-staff", "khac"), null)).hasErrors()).isTrue();
    }

    @Test
    void tokenIntegrationAudLmsIntegrationBiTuChoi() {
        assertThat(validator.validate(token(List.of("lms-integration"), null)).hasErrors()).isTrue();
    }

    @Test
    void tokenHetHanBiTuChoiBoiValidatorMacDinh() {
        Jwt expired = Jwt.withTokenValue("t").header("alg", "RS256").subject("u").audience(List.of("lms-storefront"))
                .issuedAt(Instant.now().minusSeconds(900)).expiresAt(Instant.now().minusSeconds(600)).build();
        assertThat(validator.validate(expired).hasErrors()).isTrue();
    }
}
