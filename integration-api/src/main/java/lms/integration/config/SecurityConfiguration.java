package lms.integration.config;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * lms-integration, {@link IntegrationJwtConfiguration}), hiện chưa có endpoint nào nên đã đăng nhập cũng chỉ gặp 404;
 * MỌI thứ khác denyAll. Không CORS (API cho backend, không dùng trình duyệt), không session, không CSRF.
 */
@Configuration
public class SecurityConfiguration {

    public static final String TOKEN_PATH = "/api/integration/v1/oauth/token";

    static final RequestMatcher TOKEN_ENDPOINT = AntPathRequestMatcher.antMatcher(HttpMethod.POST, TOKEN_PATH);
    static final RequestMatcher API = AntPathRequestMatcher.antMatcher("/api/integration/v1/**");

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
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonErrorWriter writer) throws Exception {
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
        AccessDeniedHandler denied = (request, response, ex) ->
                writer.write(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Không được phép truy cập");
        return http.csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(TOKEN_ENDPOINT).permitAll()
                        .requestMatchers(API).authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.bearerTokenResolver(resolver).authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(denied).jwt(j -> { }))
                .build();
    }
}
