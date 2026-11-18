package lms.sales.config;

import lms.security.PermissionJwtConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Chỉ lo xác thực JWT. Phân quyền theo endpoint do PermissionInterceptor (security-common) kiểm theo khóa
 * Controller/method. Tắt bằng app.security.enabled=false và lms.security.permission.enabled=false (chỉ profile dev).
 */
@Configuration
// Không có chuỗi lọc khi tiến trình chạy không web (profile demo-seed, ADR-0018): HttpSecurity chỉ có ở ứng dụng servlet
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            @Value("${app.security.enabled:true}") boolean enabled) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (!enabled) {
            return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
        }

        return http
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html").permitAll()
                        // Webhook cổng thanh toán giả lập (ADR-0020): không có JWT; chỉ xác thực bằng chữ ký HMAC
                        // (X-Signature) trong SimulatedWebhookProcessor, và 404 khi sandbox tắt
                        .requestMatchers(HttpMethod.POST, "/internal/payments/webhook/simulated").permitAll()
                        // Chỉ xác thực ở đây; quyền theo endpoint (Controller/method) do PermissionInterceptor kiểm
                        .requestMatchers("/internal/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(PermissionJwtConverter.create())))
                .build();
    }
}
