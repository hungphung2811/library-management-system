package lms.identity.config;

import lms.security.PermissionJwtConverter;
import lms.security.Permissions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Một SecurityFilterChain cho cả service. Auth: đăng nhập, làm mới, đăng xuất và JWKS công khai.
 * Access control: RBAC theo permission ROLE_VIEW / ROLE_CREATE / ROLE_UPDATE / ROLE_ASSIGN.
 * Đường dẫn nào không khai báo đều bị từ chối.
 */
@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()

                        // --- auth ---
                        .requestMatchers("/.well-known/jwks.json").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh", "/api/auth/logout")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/credentials")
                        .hasAuthority(Permissions.USER_CREATE)

                        // --- access control ---
                        .requestMatchers(HttpMethod.POST, "/api/permissions", "/api/roles")
                        .hasAuthority(Permissions.ROLE_CREATE)
                        .requestMatchers(HttpMethod.POST, "/api/roles/*/permissions")
                        .hasAuthority(Permissions.ROLE_UPDATE)
                        .requestMatchers(HttpMethod.DELETE, "/api/roles/*/permissions/*")
                        .hasAuthority(Permissions.ROLE_UPDATE)
                        .requestMatchers(HttpMethod.POST, "/api/access/users/*/roles")
                        .hasAuthority(Permissions.ROLE_ASSIGN)
                        .requestMatchers(HttpMethod.DELETE, "/api/access/users/*/roles/*")
                        .hasAuthority(Permissions.ROLE_ASSIGN)
                        .requestMatchers(HttpMethod.GET, "/api/permissions", "/api/roles", "/api/roles/*",
                                "/api/access/users/*")
                        .hasAuthority(Permissions.ROLE_VIEW)

                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(PermissionJwtConverter.create())))
                .build();
    }
}
