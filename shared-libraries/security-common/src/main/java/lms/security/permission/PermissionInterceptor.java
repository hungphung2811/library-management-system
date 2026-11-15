package lms.security.permission;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import lms.security.JwtClaims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Middleware phân quyền theo endpoint. Với mỗi controller method: xác định khóa quyền (customKey hoặc
 * {@code TênController/tênPhươngThức}), bảo đảm khóa đã được đăng ký vào DB, rồi kiểm tra token có khóa đó không.
 *
 * <p>Quyết định dựa trên claim {@code permissions} của JWT (sinh từ DB lúc đăng nhập), không truy vấn DB mỗi request.
 * Người dùng có claim {@code admin=true} (có role đánh dấu is_admin) được bỏ qua kiểm tra.
 */
public class PermissionInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PermissionInterceptor.class);

    private final PermissionKeyResolver resolver;
    private final PermissionKeyRegistrar registrar;
    private final String serviceName;

    private final boolean requireTenant;

    public PermissionInterceptor(PermissionKeyResolver resolver, PermissionKeyRegistrar registrar, String serviceName) {
        this(resolver, registrar, serviceName, false);
    }

    /** requireTenant: khi true, endpoint thường mà token thiếu claim tenantId bị từ chối 403 TENANT_REQUIRED. */
    public PermissionInterceptor(PermissionKeyResolver resolver, PermissionKeyRegistrar registrar, String serviceName,
                                 boolean requireTenant) {
        this.resolver = resolver;
        this.registrar = registrar;
        this.serviceName = serviceName;
        this.requireTenant = requireTenant;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) return true;

        PermissionKeyResolver.Resolved resolved = resolver.resolve(handlerMethod);
        if (resolved.mode() == PermissionKeyResolver.Mode.PUBLIC) return true;

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "Cần đăng nhập");
            return false;
        }
        if (resolved.mode() == PermissionKeyResolver.Mode.AUTHENTICATED) return true;
        if (resolved.mode() == PermissionKeyResolver.Mode.PLATFORM_ADMIN) {
            if (isPlatformAdmin(authentication)) return true;
            reject(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Chỉ dành cho quản trị nền tảng");
            return false;
        }
        if (requireTenant && !hasTenant(authentication)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "TENANT_REQUIRED", "Token không thuộc tenant nào");
            return false;
        }

        // Đăng ký trước khi quyết định, kể cả khi sắp từ chối: để khóa mới hiện ra cho người quản trị gán quyền
        register(request, resolved);

        if (isAdmin(authentication) || hasKey(authentication, resolved.key())) return true;

        reject(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Thiếu quyền: " + resolved.key());
        return false;
    }

    private void register(HttpServletRequest request, PermissionKeyResolver.Resolved resolved) {
        try {
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String path = pattern != null ? pattern.toString() : request.getRequestURI();
            String description = resolved.description() != null && !resolved.description().isBlank()
                    ? resolved.description()
                    : request.getMethod() + " " + path + " (" + serviceName + ")";
            registrar.ensureRegistered(new EndpointDescriptor(serviceName, resolved.key(), resolved.source(),
                    request.getMethod(), path, description));
        } catch (RuntimeException e) {
            // Đăng ký là phụ trợ, không được làm hỏng request
            log.warn("Không đăng ký được khóa quyền {}: {}", resolved.key(), e.toString());
        }
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken jwt
                && Boolean.TRUE.equals(jwt.getToken().getClaim(JwtClaims.ADMIN));
    }

    private static boolean isPlatformAdmin(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken jwt
                && Boolean.TRUE.equals(jwt.getToken().getClaim(JwtClaims.PLATFORM_ADMIN));
    }

    private static boolean hasTenant(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt)) return false;
        String tenant = jwt.getToken().getClaimAsString(JwtClaims.TENANT_ID);
        return tenant != null && !tenant.isBlank();
    }

    private static boolean hasKey(Authentication authentication, String key) {
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (key.equals(authority.getAuthority())) return true;
        }
        return false;
    }

    private static void reject(HttpServletResponse response, int status, String code, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String json = "{\"code\":\"" + code + "\",\"message\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        response.getWriter().write(json);
    }
}
