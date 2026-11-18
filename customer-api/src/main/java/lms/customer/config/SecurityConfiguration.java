package lms.customer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Chỉ xác thực JWT (ADR-0011 2.9); sales mới kiểm khóa/module/sở hữu. CORS do {@link PublicCorsFilter} (servlet filter
 * chạy trước chuỗi này, ADR-0013), không phải nginx. Danh sách đường dẫn công khai là DANH SÁCH ĐÓNG (liệt kê từng mẫu,
 * không {@code /public/t/*}{@code /**}), MỌI thứ còn lại (gồm /internal/**, /api/**) là denyAll. Với đường dẫn công
 * khai, header Authorization bị bỏ qua để token cũ/hết hạn không làm hỏng yêu cầu công khai. HEAD đi cùng GET công khai.
 *
 * <p>Nhóm cần đăng nhập ({@link #AUTHENTICATED_API}, đơn hàng của khách): phải có JWT hợp lệ có {@code aud} chứa
 * lms-storefront ({@link StorefrontJwtConfiguration}); {@link ServiceTokenRejectionFilter} chặn token dịch vụ, và
 * controller dùng {@code ShopSessionGuard} so tenant của token với {@code {code}}.
 */
@Configuration
public class SecurityConfiguration {

    static final RequestMatcher PUBLIC_API = new OrRequestMatcher(
            // Trang giới thiệu SaaS và form liên hệ
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/public/site/**"),
            AntPathRequestMatcher.antMatcher(HttpMethod.HEAD, "/public/site/**"),
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/public/site/contact-requests"),
            // Storefront: thông tin cửa hàng và danh mục sách (đọc công khai, dùng token dịch vụ phía sau)
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/public/t/*"),
            AntPathRequestMatcher.antMatcher(HttpMethod.HEAD, "/public/t/*"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/public/t/*/books"),
            AntPathRequestMatcher.antMatcher(HttpMethod.HEAD, "/public/t/*/books"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/public/t/*/books/suggestions"),
            AntPathRequestMatcher.antMatcher(HttpMethod.HEAD, "/public/t/*/books/suggestions"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/public/t/*/banners"),
            AntPathRequestMatcher.antMatcher(HttpMethod.HEAD, "/public/t/*/banners"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/public/t/*/books/*"),
            AntPathRequestMatcher.antMatcher(HttpMethod.HEAD, "/public/t/*/books/*"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/public/t/*/books/*/preview"),
            AntPathRequestMatcher.antMatcher(HttpMethod.HEAD, "/public/t/*/books/*/preview"),
            // Phiên khách theo cửa hàng
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/public/t/*/auth/signup"),
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/public/t/*/auth/login"),
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/public/t/*/auth/refresh"),
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/public/t/*/auth/logout"),
            // Đường dẫn cũ không có mã cửa hàng (giữ tương thích, không khuyến nghị)
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/public/auth/refresh"),
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/public/auth/logout"));

    /** Cần đăng nhập (JWT storefront): đơn hàng của khách. */
    static final RequestMatcher AUTHENTICATED_API = new OrRequestMatcher(
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/public/t/*/orders"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/public/t/*/orders"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/public/t/*/orders/*"),
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/public/t/*/orders/*/cancellation"),
            // Liên hệ của khách gửi cửa hàng (ADR-0015); các phương thức khác trên đường dẫn này vẫn denyAll
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/public/t/*/contact-requests"),
            // Cổng độc giả (ADR-0023 2.5): danh sách đóng từng mẫu, KHÔNG dùng /public/t/*/me/**. Hủy đặt trước là POST.
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/public/t/*/me/borrowings"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/public/t/*/me/borrowings/*"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/public/t/*/me/fines"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/public/t/*/me/reservations"),
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/public/t/*/me/reservations/*/cancellation"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/public/t/*/me/reader-profile"));

    /**
     * Public API v1 (Public Site Key, SaaS API Authentication P2): danh sách đóng từng mẫu, tenant xác định bằng header
     * X-Site-Key (do controller kiểm: thiếu/sai thì 404 SHOP_NOT_AVAILABLE). Site key không phải xác thực; ghi chỉ qua phiên khách.
     */
    static final RequestMatcher PUBLIC_API_V1 = new OrRequestMatcher(
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/api/public/v1/books"),
            AntPathRequestMatcher.antMatcher(HttpMethod.HEAD, "/api/public/v1/books"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/api/public/v1/books/suggestions"),
            AntPathRequestMatcher.antMatcher(HttpMethod.HEAD, "/api/public/v1/books/suggestions"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/api/public/v1/books/*"),
            AntPathRequestMatcher.antMatcher(HttpMethod.HEAD, "/api/public/v1/books/*"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/api/public/v1/books/*/preview"),
            AntPathRequestMatcher.antMatcher(HttpMethod.HEAD, "/api/public/v1/books/*/preview"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/api/public/v1/banners"),
            AntPathRequestMatcher.antMatcher(HttpMethod.HEAD, "/api/public/v1/banners"),
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/public/v1/sessions"),
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/public/v1/sessions/signup"),
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/public/v1/sessions/refresh"),
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/public/v1/sessions/logout"));

    /** Cần phiên khách (JWT storefront) ở Public API v1: đơn hàng. Không có giỏ hàng server, không đặt đơn vãng lai (Q4). */
    static final RequestMatcher AUTHENTICATED_API_V1 = new OrRequestMatcher(
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/public/v1/orders"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/api/public/v1/orders"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/api/public/v1/orders/*"),
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/public/v1/orders/*/cancellation"));

    @Bean
    JsonErrorWriter jsonErrorWriter(ObjectMapper mapper) {
        return new JsonErrorWriter(mapper);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonErrorWriter writer) throws Exception {
        DefaultBearerTokenResolver defaultResolver = new DefaultBearerTokenResolver();
        BearerTokenResolver resolver = request -> PUBLIC_API.matches(request) || PUBLIC_API_V1.matches(request)
                ? null : defaultResolver.resolve(request);
        // Chưa xác thực: 401 ở /public/** (cần đăng nhập), còn lại (denyAll) trả 403 để không lộ gì về đường dẫn nội bộ.
        AuthenticationEntryPoint entryPoint = (request, response, ex) -> {
            if (request.getRequestURI().startsWith("/public/") || request.getRequestURI().startsWith("/api/public/")) {
                writer.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Cần đăng nhập");
            } else {
                writer.write(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Không được phép truy cập");
            }
        };
        AccessDeniedHandler denied = (request, response, ex) ->
                writer.write(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Không được phép truy cập");
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS).permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(PUBLIC_API).permitAll()
                        .requestMatchers(PUBLIC_API_V1).permitAll()
                        .requestMatchers(AUTHENTICATED_API).authenticated()
                        .requestMatchers(AUTHENTICATED_API_V1).authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.bearerTokenResolver(resolver).authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(denied).jwt(j -> { }))
                .addFilterAfter(new ServiceTokenRejectionFilter(writer), BearerTokenAuthenticationFilter.class)
                .build();
    }
}
