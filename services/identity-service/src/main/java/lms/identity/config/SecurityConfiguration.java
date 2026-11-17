package lms.identity.config;

import lms.security.PermissionJwtConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Một SecurityFilterChain cho cả service, chỉ lo XÁC THỰC. Mọi API nghiệp vụ nằm dưới /internal/** (chỉ lms-api gọi,
 * nginx chặn không cho ra ngoài), còn lại phải có token hợp lệ. Chỉ các đường dẫn cụ thể sau là công khai: đăng nhập,
 * làm mới, đăng xuất, đăng nhập ngoài (trình duyệt chưa có token ở các bước này), JWKS, health và đăng ký khóa quyền
 * (controller tự kiểm khóa chia sẻ). PHÂN QUYỀN theo endpoint do {@code PermissionInterceptor} (security-common) đảm
 * nhiệm: mỗi controller method có một khóa quyền (customKey bằng {@code @RequirePermission} hoặc khóa tự sinh
 * {@code TênController/tênPhươngThức}). Đường dẫn ngoài /internal và các đường dẫn công khai bị từ chối.
 *
 * Chỉ khi chạy như ứng dụng web servlet: tiến trình seed demo một lần (profile demo-seed, ADR-0018) chạy với
 * {@code spring.main.web-application-type=none} nên không có HttpSecurity.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/.well-known/jwks.json").permitAll()
                        // Đúng một đường dẫn nội bộ có khóa chia sẻ riêng; KHÔNG mở cả /internal/**
                        .requestMatchers(HttpMethod.POST, "/internal/permissions/registrations").permitAll()
                        // Cấp token dịch vụ storefront cho customer-api (ADR-0011): bí mật riêng STOREFRONT_CLIENT_SECRET
                        .requestMatchers(HttpMethod.POST, "/internal/storefront/access-tokens").permitAll()

                        .requestMatchers(HttpMethod.POST, "/internal/auth/login", "/internal/auth/refresh",
                                "/internal/auth/logout").permitAll()
                        // tự đăng ký của khách (ADR-0010 B2.1): đúng một đường dẫn, body allowlist, chống lạm dụng ở nginx
                        .requestMatchers(HttpMethod.POST, "/internal/auth/signup").permitAll()
                        // quên mật khẩu: yêu cầu (luôn 202) và xác nhận bằng token một lần; chống lạm dụng ở nginx + theo tài khoản
                        .requestMatchers(HttpMethod.POST, "/internal/auth/password-reset-requests",
                                "/internal/auth/password-reset-confirmations").permitAll()
                        // đăng nhập ngoài (OIDC): trình duyệt chưa có token ở các bước này
                        .requestMatchers(HttpMethod.GET, "/internal/auth/external/providers",
                                "/internal/auth/external/*/start", "/internal/auth/external/*/callback").permitAll()
                        .requestMatchers(HttpMethod.POST, "/internal/auth/external/exchange").permitAll()

                        .requestMatchers("/internal/**").authenticated()
                        // API nền tảng cho người vận hành (nginx/lms-api định tuyến thẳng tới identity); claim
                        // platformAdmin do PermissionInterceptor kiểm qua @PlatformAdminOnly
                        .requestMatchers("/api/platform/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(PermissionJwtConverter.create())))
                .build();
    }
}
