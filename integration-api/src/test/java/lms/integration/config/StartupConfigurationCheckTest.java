package lms.integration.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StartupConfigurationCheckTest {

    private static final String REMOTE = "http://identity-service:8081/.well-known/jwks.json";

    @Test
    void cauHinhDungKhongCoVanDe() {
        assertThat(StartupConfigurationCheck.blockingProblems(REMOTE, "library-auth-service")).isEmpty();
        assertThatCode(() -> StartupConfigurationCheck.validate(false, REMOTE, "library-auth-service"))
                .doesNotThrowAnyException();
    }

    @Test
    void ngoaiDevJwkTrongHoacLocalhostHoacIssRongLaLoiKhoiDong() {
        for (String uri : new String[]{"", " ", null, "http://localhost:8081/x", "http://127.0.0.1/x",
                "http://[::1]/x", "http://0.0.0.0/x"}) {
            assertThatThrownBy(() -> StartupConfigurationCheck.validate(false, uri, "iss"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("jwk-set-uri");
        }
        assertThatThrownBy(() -> StartupConfigurationCheck.validate(false, REMOTE, " "))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("issuer");
    }

    @Test
    void oDevChiCanhBaoKhongNem() {
        assertThatCode(() -> StartupConfigurationCheck.validate(true, "http://localhost:8081/x", ""))
                .doesNotThrowAnyException();
    }
}
