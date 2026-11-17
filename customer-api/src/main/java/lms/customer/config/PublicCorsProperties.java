package lms.customer.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.public-cors.* (ADR-0013 2.7): chế độ CORS của /public/** và các origin luôn được phép.
 *
 * <ul>
 *   <li>{@code mode}: OFF (cho mọi origin hợp lệ cú pháp, không resolve), REPORT (quyết như ENFORCE nhưng chỉ ghi log
 *       "would reject"), ENFORCE (mặc định).</li>
 *   <li>{@code platformOrigins}: domain nền tảng (trang SaaS) gọi /public/**.</li>
 *   <li>{@code devOrigins}: origin phát triển (localhost). Production nên để rỗng.</li>
 * </ul>
 *
 * Mỗi origin phải đúng dạng {@code scheme://host[:port]} chữ thường, không {@code /} cuối, không {@code *}: sai thì ứng
 * dụng KHÔNG khởi động (lỗi bind cấu hình), tránh một cấu hình lệch làm mở CORS âm thầm.
 */
@ConfigurationProperties(prefix = "app.public-cors")
public record PublicCorsProperties(PublicCorsMode mode, List<String> platformOrigins, List<String> devOrigins,
                                   Duration maxAge) {

    private static final Pattern ORIGIN = Pattern.compile("^https?://[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:[0-9]{1,5})?$");

    public PublicCorsProperties {
        mode = mode == null ? PublicCorsMode.ENFORCE : mode;
        platformOrigins = validated("app.public-cors.platform-origins", platformOrigins);
        devOrigins = validated("app.public-cors.dev-origins", devOrigins);
        maxAge = maxAge == null || maxAge.isNegative() ? Duration.ofSeconds(600) : maxAge;
    }

    private static List<String> validated(String property, List<String> origins) {
        if (origins == null) {
            return List.of();
        }
        for (String origin : origins) {
            if (origin == null || !ORIGIN.matcher(origin).matches() || !origin.equals(origin.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(property + " chứa origin sai dạng (cần scheme://host[:port], chữ thường, "
                        + "không '/' cuối, không '*'): '" + origin + "'");
            }
        }
        return List.copyOf(origins);
    }
}
