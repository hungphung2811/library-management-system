package lms.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * lms-api chỉ xác thực (JWT hợp lệ, chưa hết hạn). Phân quyền theo permission do service nội bộ kiểm, vì lms-api
 * chuyển tiếp token của người dùng. CORS do nginx xử lý.
 *
 * <p>Đường dẫn công khai (trình duyệt chưa có token): đăng nhập, làm mới, đăng xuất, quên mật khẩu (yêu cầu/xác nhận) và các bước đăng nhập ngoài. Với các
 * đường dẫn này lms-api bỏ qua header Authorization, để FE gửi kèm access token đã hết hạn khi làm mới vẫn không bị 401.
 *
 * <p>Kênh token: token mang aud=lms-storefront (đăng nhập qua customer-api) bị {@link ChannelAudienceFilter} từ chối 401.
 */
@Configuration
public class SecurityConfiguration {

    static final RequestMatcher PUBLIC_API = new OrRequestMatcher(
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/auth/login"),
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/auth/refresh"),
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/auth/logout"),
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/auth/password-reset-requests"),
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/auth/password-reset-confirmations"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/api/auth/external/providers"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/api/auth/external/*/start"),
            AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/api/auth/external/*/callback"),
            AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/auth/external/exchange"));

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            @Value("${lms.api.security.require-staff-audience:false}")
                                            boolean requireStaffAudience) throws Exception {
        DefaultBearerTokenResolver defaultResolver = new DefaultBearerTokenResolver();
        BearerTokenResolver resolver = request -> PUBLIC_API.matches(request) ? null : defaultResolver.resolve(request);
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS).permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(PUBLIC_API).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.bearerTokenResolver(resolver).jwt(j -> { }))
                // Token kênh storefront bị từ chối ở lms-api (A1); token không aud vẫn nhận trong giai đoạn chuyển tiếp
                .addFilterAfter(new ChannelAudienceFilter(requireStaffAudience), BearerTokenAuthenticationFilter.class)
                .build();
    }
}
