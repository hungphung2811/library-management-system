package lms.circulation.config;

import lms.security.Permissions;
import lms.security.PermissionJwtConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * RBAC dựa trên claim "permissions" trong JWT do auth-service phát hành.
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
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/borrowings").hasAuthority(Permissions.BORROW_BOOK)
                        .requestMatchers(HttpMethod.POST, "/api/borrowings/*/renewals").hasAuthority(Permissions.BORROW_BOOK)
                        .requestMatchers(HttpMethod.POST, "/api/borrowings/*/returns").hasAuthority(Permissions.RETURN_BOOK)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(PermissionJwtConverter.create())))
                .build();
    }
}
