package lms.identity.config;

import lms.security.PermissionJwtConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Một SecurityFilterChain cho cả service, chỉ lo XÁC THỰC: đường dẫn công khai (đăng nhập, làm mới, đăng xuất, JWKS,
 * đăng nhập ngoài, health, API nội bộ), còn lại phải có token hợp lệ. PHÂN QUYỀN theo endpoint do
 * {@code PermissionInterceptor} (security-common) đảm nhiệm: mỗi controller method có một khóa quyền
 * (customKey bằng {@code @RequirePermission} hoặc khóa tự sinh {@code TênController/tênPhươngThức}),
 * khóa được đăng ký vào bảng permissions và gán cho role. Đường dẫn ngoài /api và các đường dẫn công khai bị từ chối.
 */
@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/.well-known/jwks.json").permitAll()
                        // API nội bộ giữa các service: controller tự kiểm khóa chia sẻ; không route qua gateway
                        .requestMatchers("/internal/**").permitAll()

                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh", "/api/auth/logout")
                        .permitAll()
                        // đăng nhập ngoài (OIDC): trình duyệt chưa có token ở các bước này
                        .requestMatchers(HttpMethod.GET, "/api/auth/external/providers",
                                "/api/auth/external/*/start", "/api/auth/external/*/callback").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/external/exchange").permitAll()

                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(PermissionJwtConverter.create())))
                .build();
    }
}
