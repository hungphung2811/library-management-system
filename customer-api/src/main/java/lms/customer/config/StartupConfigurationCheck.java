package lms.customer.config;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kiểm cấu hình lúc khởi động (thay cho cảnh báo mỗi request):
 *
 * <ul>
 *   <li>Ngoài profile dev: {@code STOREFRONT_CLIENT_SECRET} rỗng hoặc đúng giá trị mặc định dev, hoặc {@code jwk-set-uri}
 *       trống/trỏ localhost thì không khởi động (fail-fast).</li>
 *   <li>Profile dev: chỉ cảnh báo một lần khi bí mật rỗng (identity sẽ từ chối cấp token storefront).</li>
 * </ul>
 *
 * Thông báo lỗi không bao giờ chứa giá trị của bí mật.
 */
public final class StartupConfigurationCheck {

    /** Giá trị mặc định của docker-compose cho dev; không được dùng ngoài dev. */
    public static final String DEV_DEFAULT_SECRET = "dev-storefront-secret";

    private static final Logger log = LoggerFactory.getLogger(StartupConfigurationCheck.class);

    private StartupConfigurationCheck() {
    }

    /** Các vấn đề chặn khởi động ngoài dev. Rỗng nếu cấu hình ổn. */
    static List<String> blockingProblems(String secret, String jwkSetUri) {
        List<String> problems = new ArrayList<>();
        if (secret == null || secret.isBlank()) {
            problems.add("STOREFRONT_CLIENT_SECRET rỗng");
        } else if (DEV_DEFAULT_SECRET.equals(secret)) {
            problems.add("STOREFRONT_CLIENT_SECRET đang là giá trị mặc định dành cho dev");
        }
        if (jwkSetUri == null || jwkSetUri.isBlank()) {
            problems.add("jwk-set-uri (JWT_JWK_SET_URI) rỗng");
        } else if (pointsToLocalhost(jwkSetUri)) {
            problems.add("jwk-set-uri (JWT_JWK_SET_URI) trỏ về localhost");
        }
        return problems;
    }

    /** @throws IllegalStateException ngoài dev khi cấu hình không an toàn. */
    public static void validate(boolean dev, String secret, String jwkSetUri) {
        if (dev) {
            if (secret == null || secret.isBlank()) {
                log.warn("STOREFRONT_CLIENT_SECRET rỗng (profile dev): identity sẽ từ chối cấp token storefront, "
                        + "các đường /public/t/{code}/** sẽ trả 503");
            }
            return;
        }
        List<String> problems = blockingProblems(secret, jwkSetUri);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Cấu hình customer-api không an toàn ngoài profile dev: "
                    + String.join("; ", problems));
        }
    }

    private static boolean pointsToLocalhost(String uri) {
        String lower = uri.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("//localhost") || lower.contains("//127.") || lower.contains("//[::1]")
                || lower.contains("//0.0.0.0");
    }
}
