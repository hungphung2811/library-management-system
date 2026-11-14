package lms.support.config;

import lms.security.PermissionJwtConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Một chain cho cả service: đường dẫn không khai báo bị denyAll. RBAC theo claim "permissions" của JWT.
 * Tắt bằng app.security.enabled=false (chỉ dùng cho profile dev).
 */
@Configuration
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
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // Chỉ xác thực ở đây; quyền theo endpoint (Controller/method) do PermissionInterceptor kiểm
                        .requestMatchers("/internal/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(PermissionJwtConverter.create())))
                .build();
    }
}
