package lms.integration.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lms.integration.ratelimit.ApiRateLimiter;
import lms.integration.ratelimit.RateLimitFilter;
import lms.integration.revocation.RevocationFilter;
import lms.integration.revocation.TokenRevocationChecker;
import lms.integration.web.AccessLogFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
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
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Chỉ xác thực JWT (phân quyền theo scope do pha I3 và service phía sau). Danh sách đường dẫn là DANH SÁCH ĐÓNG:
 * công khai chỉ health và {@code POST /api/integration/v1/oauth/token} (client_credentials, xác thực bằng client_id +
 * secret ở identity); {@code /api/integration/v1/**} còn lại cần token integration (decoder bắt buộc aud
 * lms-integration, {@link IntegrationJwtConfiguration}). Pha I3: các endpoint đọc books/inventory/orders còn cần scope
 * tương ứng trong claim {@code scope} (lớp phòng thủ đầu, quyết định cuối vẫn ở catalog/sales); đường dẫn khác dưới
 * {@code /api/integration/v1/**} (kể cả {@code /orders/{id}/contact}) đã xác thực chỉ gặp 404; MỌI thứ khác denyAll.
 * Không CORS (API cho backend, không dùng trình duyệt), không session, không CSRF. Log truy cập đặt trước bước xác
 * thực (ghi cả 401); kiểm thu hồi token (tùy chọn) rồi giới hạn tốc độ đặt sau bước xác thực (cần claim tenant/application).
 */
@Configuration
public class SecurityConfiguration {

    public static final String TOKEN_PATH = "/api/integration/v1/oauth/token";

    static final RequestMatcher TOKEN_ENDPOINT = AntPathRequestMatcher.antMatcher(HttpMethod.POST, TOKEN_PATH);
    static final RequestMatcher API = AntPathRequestMatcher.antMatcher("/api/integration/v1/**");

    public static final String SCOPE_BOOKS_READ = "SCOPE_books:read";
    public static final String SCOPE_INVENTORY_READ = "SCOPE_inventory:read";
    public static final String SCOPE_ORDERS_READ = "SCOPE_orders:read";

    @Bean
    JsonErrorWriter jsonErrorWriter(ObjectMapper mapper) {
        return new JsonErrorWriter(mapper);
    }

    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter(JsonErrorWriter writer) {
        FilterRegistrationBean<RequestSizeLimitFilter> registration =
                new FilterRegistrationBean<>(new RequestSizeLimitFilter(writer));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonErrorWriter writer,
                                            ObjectProvider<ApiRateLimiter> rateLimiter,
                                            ObjectProvider<TokenRevocationChecker> revocation) throws Exception {
        DefaultBearerTokenResolver defaultResolver = new DefaultBearerTokenResolver();
        // Ở token endpoint header Authorization là Basic của client, không phải Bearer: bỏ qua để token cũ/hỏng
        // không làm hỏng yêu cầu xin token.
        BearerTokenResolver resolver = request -> TOKEN_ENDPOINT.matches(request) ? null
                : defaultResolver.resolve(request);
        AuthenticationEntryPoint entryPoint = (request, response, ex) -> {
            if (API.matches(request)) {
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                writer.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Token không hợp lệ hoặc thiếu");
            } else {
                writer.write(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Không được phép truy cập");
            }
        };
        AccessDeniedHandler denied = (request, response, ex) -> {
            if (API.matches(request)) {
                // Đã xác thực nhưng thiếu scope. Tên scope là hợp đồng công khai nên không có gì nội bộ bị lộ.
                writer.write(response, HttpStatus.FORBIDDEN, "INSUFFICIENT_SCOPE",
                        "Token thiếu scope cần thiết cho tài nguyên này");
            } else {
                writer.write(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Không được phép truy cập");
            }
        };
        HttpSecurity configured = http.csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(TOKEN_ENDPOINT).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/integration/v1/books/*").hasAuthority(SCOPE_BOOKS_READ)
                        .requestMatchers(HttpMethod.POST, "/api/integration/v1/books/lookup",
                                "/api/integration/v1/books/lookup-by-isbn").hasAuthority(SCOPE_BOOKS_READ)
                        .requestMatchers(HttpMethod.GET, "/api/integration/v1/inventory",
                                "/api/integration/v1/inventory/*").hasAuthority(SCOPE_INVENTORY_READ)
                        .requestMatchers(HttpMethod.GET, "/api/integration/v1/orders",
                                "/api/integration/v1/orders/*").hasAuthority(SCOPE_ORDERS_READ)
                        .requestMatchers(API).authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.bearerTokenResolver(resolver).authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(denied).jwt(j -> { }))
                .addFilterBefore(new AccessLogFilter(), BearerTokenAuthenticationFilter.class);
        // Thu hồi chạy trước giới hạn tốc độ (request của token đã thu hồi không đếm vào hạn mức).
        TokenRevocationChecker revocationChecker = revocation.getIfAvailable();
        if (revocationChecker != null) {
            configured.addFilterAfter(new RevocationFilter(revocationChecker, writer), BearerTokenAuthenticationFilter.class);
        }
        ApiRateLimiter limiter = rateLimiter.getIfAvailable();
        if (limiter != null) {
            configured.addFilterAfter(new RateLimitFilter(limiter, writer), BearerTokenAuthenticationFilter.class);
        }
        return configured.build();
    }
}
