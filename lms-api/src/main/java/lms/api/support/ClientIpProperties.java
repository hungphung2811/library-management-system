package lms.api.support;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình lms.api.client-ip. trustedProxies: IP hoặc CIDR của proxy được tin khi đọc X-Real-IP; để trống thì chỉ tin
 * loopback. Khi chạy sau nginx trong Docker, thêm dải mạng Docker (ví dụ 172.16.0.0/12).
 */
@ConfigurationProperties(prefix = "lms.api.client-ip")
public record ClientIpProperties(List<String> trustedProxies) {

    public static final List<String> LOOPBACK = List.of("127.0.0.0/8", "::1/128");

    public ClientIpProperties {
        trustedProxies = trustedProxies == null || trustedProxies.isEmpty() ? LOOPBACK : List.copyOf(trustedProxies);
    }
}
