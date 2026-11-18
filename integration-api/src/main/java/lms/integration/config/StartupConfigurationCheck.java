package lms.integration.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kiểm cấu hình lúc khởi động: ngoài profile dev, {@code jwk-set-uri} trống/trỏ localhost hoặc {@code iss} rỗng thì không
 * khởi động (fail-fast, nếu không mọi token đều bị từ chối hoặc đáng ngờ). Thông báo lỗi không chứa giá trị bí mật.
 */
public final class StartupConfigurationCheck {

    private static final Logger log = LoggerFactory.getLogger(StartupConfigurationCheck.class);

    private StartupConfigurationCheck() {
    }

    static List<String> blockingProblems(String jwkSetUri, String issuer) {
        List<String> problems = new ArrayList<>();
        if (jwkSetUri == null || jwkSetUri.isBlank()) {
            problems.add("jwk-set-uri (JWT_JWK_SET_URI) rỗng");
        } else if (pointsToLocalhost(jwkSetUri)) {
            problems.add("jwk-set-uri (JWT_JWK_SET_URI) trỏ về localhost");
        }
        if (issuer == null || issuer.isBlank()) {
            problems.add("issuer (JWT_ISSUER) rỗng");
        }
        return problems;
    }

    /** @throws IllegalStateException ngoài dev khi cấu hình không an toàn. */
    public static void validate(boolean dev, String jwkSetUri, String issuer) {
        List<String> problems = blockingProblems(jwkSetUri, issuer);
        if (problems.isEmpty()) {
            return;
        }
        if (dev) {
            log.warn("Cấu hình integration-api (profile dev) sẽ bị từ chối ngoài dev: {}", String.join("; ", problems));
            return;
        }
        throw new IllegalStateException("Cấu hình integration-api không an toàn ngoài profile dev: "
                + String.join("; ", problems));
    }

    private static boolean pointsToLocalhost(String uri) {
        String lower = uri.toLowerCase(Locale.ROOT);
        return lower.contains("//localhost") || lower.contains("//127.") || lower.contains("//[::1]")
                || lower.contains("//0.0.0.0");
    }
}
