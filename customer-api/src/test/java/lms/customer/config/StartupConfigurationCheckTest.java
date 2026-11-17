package lms.customer.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class StartupConfigurationCheckTest {

    static final String JWK = "http://identity-service:8081/.well-known/jwks.json";

    @Test
    void devChoPhepBiMatRongVaMacDinh() {
        assertDoesNotThrow(() -> StartupConfigurationCheck.validate(true, "", "http://localhost:8081/jwks"));
        assertDoesNotThrow(() -> StartupConfigurationCheck.validate(true, StartupConfigurationCheck.DEV_DEFAULT_SECRET,
                "http://localhost:8081/jwks"));
    }

    @Test
    void ngoaiDevBiMatRongHoacMacDinhLaFailFast() {
        assertThrows(IllegalStateException.class, () -> StartupConfigurationCheck.validate(false, "", JWK));
        assertThrows(IllegalStateException.class, () -> StartupConfigurationCheck.validate(false, "  ", JWK));
        assertThrows(IllegalStateException.class, () -> StartupConfigurationCheck.validate(false, null, JWK));
        assertThrows(IllegalStateException.class,
                () -> StartupConfigurationCheck.validate(false, StartupConfigurationCheck.DEV_DEFAULT_SECRET, JWK));
    }

    @Test
    void ngoaiDevJwkLocalhostHoacRongLaFailFast() {
        for (String uri : new String[] {"http://localhost:8081/x", "http://127.0.0.1:8081/x", "http://[::1]:8081/x", "", null}) {
            assertThrows(IllegalStateException.class, () -> StartupConfigurationCheck.validate(false, "bi-mat-that", uri), String.valueOf(uri));
        }
        assertDoesNotThrow(() -> StartupConfigurationCheck.validate(false, "bi-mat-that", JWK));
    }

    @Test
    void thongBaoLoiKhongLoGiaTriBiMat() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> StartupConfigurationCheck.validate(false, StartupConfigurationCheck.DEV_DEFAULT_SECRET, "http://localhost/x"));
        assertFalse(ex.getMessage().contains("dev-storefront-secret"), ex.getMessage());
        assertTrue(ex.getMessage().contains("STOREFRONT_CLIENT_SECRET"));
    }

    @Test
    void timeoutDichVuLuonHuuHan() {
        ServicesProperties tooBig = new ServicesProperties("a", "b", Duration.ofDays(365), Duration.ofSeconds(-1));
        assertEquals(Duration.ofSeconds(30), tooBig.connectTimeout());
        assertEquals(ServicesProperties.DEFAULT_READ_TIMEOUT, tooBig.readTimeout());
        assertEquals(ResilienceProperties.DEFAULT_MAX_CONCURRENT_CALLS, new ResilienceProperties(0).maxConcurrentCalls());
    }
}
