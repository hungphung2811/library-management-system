package lms.customer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Configuration;

/** Mặc định trong application.yml và kiểm cấu hình origin lúc khởi động (ADR-0013 2.6, 2.7). */
class PublicCorsConfigurationTest {

    @Configuration
    @EnableConfigurationProperties({PublicCorsProperties.class, StorefrontProperties.class, ServicesProperties.class})
    static class Props {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(Props.class);

    @Test
    void macDinhApplicationYmlLaEnforceDevOriginsLocalhostVaNenTangRong() {
        runner.run(ctx -> {
            PublicCorsProperties p = ctx.getBean(PublicCorsProperties.class);
            assertThat(p.mode()).isEqualTo(PublicCorsMode.ENFORCE);
            assertThat(p.platformOrigins()).isEmpty();
            assertThat(p.devOrigins()).containsExactly("http://localhost:3000", "http://localhost:5173");
            assertThat(p.maxAge()).isEqualTo(Duration.ofSeconds(600));
            StorefrontProperties s = ctx.getBean(StorefrontProperties.class);
            assertThat(s.originTtl()).isEqualTo(Duration.ofSeconds(60));
            assertThat(s.refreshTenantIdEnabled()).isTrue();
            assertThat(ctx.getBean(ServicesProperties.class).salesUrl()).isEqualTo("http://localhost:8087");
        });
    }

    @Test
    void bienMoiTruongGhiDeCheDoVaOrigin() {
        runner.withPropertyValues("PUBLIC_CORS_MODE=report", "PUBLIC_CORS_PLATFORM_ORIGINS=https://saas.vn,https://www.saas.vn",
                        "PUBLIC_CORS_DEV_ORIGINS=")
                .run(ctx -> {
                    PublicCorsProperties p = ctx.getBean(PublicCorsProperties.class);
                    assertThat(p.mode()).isEqualTo(PublicCorsMode.REPORT);
                    assertThat(p.platformOrigins()).containsExactly("https://saas.vn", "https://www.saas.vn");
                    assertThat(p.devOrigins()).isEmpty();
                });
    }

    @Test
    void originSaiDangLamUngDungKhongKhoiDong() {
        for (String bad : List.of("https://saas.vn/", "*", "https://*.saas.vn", "HTTPS://saas.vn", "saas.vn",
                "ftp://saas.vn", "https://saas.vn/path", "null")) {
            runner.withPropertyValues("PUBLIC_CORS_PLATFORM_ORIGINS=" + bad)
                    .run(ctx -> assertThat(ctx).as(bad).hasFailed());
        }
    }

    @Test
    void propertiesTrucTiepKiemTraVaDatMacDinh() {
        assertThatThrownBy(() -> new PublicCorsProperties(PublicCorsMode.ENFORCE, List.of("https://a.vn/"), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        PublicCorsProperties defaults = new PublicCorsProperties(null, null, null, null);
        assertThat(defaults.mode()).isEqualTo(PublicCorsMode.ENFORCE);
        assertThat(defaults.platformOrigins()).isEmpty();
        assertThat(defaults.maxAge()).isEqualTo(Duration.ofSeconds(600));
    }
}
