package lms.customer.config;

import lms.customer.storefront.StorefrontAccessCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Đăng ký {@link PublicCorsFilter} ở tầng servlet, trước bộ lọc kích thước thân ({@code HIGHEST_PRECEDENCE + 10}) và
 * trước chuỗi Spring Security, để mọi phản hồi lỗi của /public/** đều mang header CORS khi origin được phép.
 */
@Configuration
@EnableConfigurationProperties(PublicCorsProperties.class)
public class PublicCorsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PublicCorsConfiguration.class);

    @Bean
    FilterRegistrationBean<PublicCorsFilter> publicCorsFilter(PublicCorsProperties properties,
                                                              StorefrontAccessCache storefronts) {
        if (properties.mode() == PublicCorsMode.ENFORCE && properties.platformOrigins().isEmpty()) {
            log.warn("app.public-cors.mode=ENFORCE nhưng platform-origins rỗng: trang SaaS sẽ không gọi /public/** "
                    + "được từ trình duyệt (đặt PUBLIC_CORS_PLATFORM_ORIGINS)");
        }
        if (properties.mode() == PublicCorsMode.OFF) {
            log.warn("app.public-cors.mode=OFF: /public/** cho mọi origin (chỉ dùng cho dev)");
        }
        FilterRegistrationBean<PublicCorsFilter> registration =
                new FilterRegistrationBean<>(new PublicCorsFilter(properties, storefronts));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        return registration;
    }
}
