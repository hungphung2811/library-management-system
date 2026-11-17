package lms.customer.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Địa chỉ và thời gian chờ khi gọi service nội bộ (app.services.*). sales-url cho storefront/đơn hàng (C1/C2).
 *
 * <p>Timeout luôn hữu hạn: thiếu, 0 hoặc âm thì dùng mặc định (connect 2s, read 5s); giá trị quá lớn bị chặn ở
 * 30 giây. Một service phía sau treo không bao giờ giữ thread Tomcat vô hạn.
 */
@ConfigurationProperties(prefix = "app.services")
public record ServicesProperties(String identityUrl, String systemUrl, Duration connectTimeout, Duration readTimeout,
                                 String salesUrl) {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(5);
    static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);

    /** Không khai báo sales-url (test, cấu hình cũ): mặc định cổng sales-service. */
    public static final String DEFAULT_SALES_URL = "http://localhost:8087";

    @ConstructorBinding
    public ServicesProperties {
        salesUrl = salesUrl == null || salesUrl.isBlank() ? DEFAULT_SALES_URL : salesUrl;
        connectTimeout = sanitize(connectTimeout, DEFAULT_CONNECT_TIMEOUT);
        readTimeout = sanitize(readTimeout, DEFAULT_READ_TIMEOUT);
    }

    public ServicesProperties(String identityUrl, String systemUrl, Duration connectTimeout, Duration readTimeout) {
        this(identityUrl, systemUrl, connectTimeout, readTimeout, null);
    }

    private static Duration sanitize(Duration value, Duration fallback) {
        if (value == null || value.isZero() || value.isNegative()) {
            return fallback;
        }
        return value.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : value;
    }
}
