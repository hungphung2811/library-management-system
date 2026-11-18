package lms.integration.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** Decoder bắt buộc chữ ký, hạn dùng, iss khớp và aud chứa lms-integration (T-I1). */
class IntegrationJwtValidatorTest {

    private final TestTokens tokens = new TestTokens();
    private final JwtDecoder decoder = tokens.decoder();

    private void rejected(String token) {
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenIntegrationHopLeDuocChapNhan() {
        assertThat(decoder.decode(tokens.valid()).getAudience()).containsExactly("lms-integration");
    }

    @Test
    void audChuaLmsIntegrationVoiAudKhacCungDuocChapNhan() {
        String token = tokens.with(TestTokens.ISSUER, List.of("lms-integration", "khac"), Duration.ofMinutes(5),
                TestTokens.claims());

        assertThat(decoder.decode(token)).isNotNull();
    }

    @Test
    void thieuAudBiTuChoiNhuTokenNhanVien() {
        rejected(tokens.with(TestTokens.ISSUER, null, Duration.ofMinutes(5), TestTokens.claims()));
    }

    @Test
    void audKhacBiTuChoi() {
        rejected(tokens.with(TestTokens.ISSUER, List.of("lms-staff"), Duration.ofMinutes(5), TestTokens.claims()));
        rejected(tokens.with(TestTokens.ISSUER, List.of("lms-storefront"), Duration.ofMinutes(5), TestTokens.claims()));
    }

    @Test
    void tokenDichVuSvcBiTuChoiKeCaKhiCoAudDung() {
        Map<String, Object> extra = TestTokens.claims();
        extra.put("svc", "customer-api");
        rejected(tokens.with(TestTokens.ISSUER, List.of("lms-integration"), Duration.ofMinutes(5), extra));
        rejected(tokens.with(TestTokens.ISSUER, null, Duration.ofMinutes(5), extra));
    }

    @Test
    void claimDacQuyenAdminHoacPlatformAdminBiTuChoi() {
        for (String claim : List.of("admin", "platformAdmin")) {
            Map<String, Object> extra = TestTokens.claims();
            extra.put(claim, true);
            rejected(tokens.with(TestTokens.ISSUER, List.of("lms-integration"), Duration.ofMinutes(5), extra));
        }
    }

    @Test
    void issSaiBiTuChoi() {
        rejected(tokens.with("ke-tan-cong", List.of("lms-integration"), Duration.ofMinutes(5), TestTokens.claims()));
    }

    @Test
    void tokenHetHanBiTuChoi() {
        rejected(tokens.with(TestTokens.ISSUER, List.of("lms-integration"), Duration.ofMinutes(-5),
                TestTokens.claims()));
    }

    @Test
    void saiChuKyBiTuChoi() {
        // cùng kid, khóa khác: chữ ký không khớp khóa công khai của decoder
        rejected(new TestTokens().valid());
    }

    @Test
    void tokenRacHoacRongBiTuChoi() {
        rejected("khong-phai-jwt");
        rejected("a.b.c");
    }
}
