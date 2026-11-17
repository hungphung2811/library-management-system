package lms.security.permission;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.UUID;
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
    private final TenantScopeBinder tenantScopeBinder;
    private final boolean legacyModulesFallback;

    public PermissionInterceptor(PermissionKeyResolver resolver, PermissionKeyRegistrar registrar, String serviceName) {
        this(resolver, registrar, serviceName, false);
    }

    /** requireTenant: khi true, endpoint thường mà token thiếu claim tenantId bị từ chối 403 TENANT_REQUIRED. */
    public PermissionInterceptor(PermissionKeyResolver resolver, PermissionKeyRegistrar registrar, String serviceName,
                                 boolean requireTenant) {
        this(resolver, registrar, serviceName, requireTenant, TenantScopeBinder.NOOP);
    }

    public PermissionInterceptor(PermissionKeyResolver resolver, PermissionKeyRegistrar registrar, String serviceName,
                                 boolean requireTenant, TenantScopeBinder tenantScopeBinder) {
        this(resolver, registrar, serviceName, requireTenant, tenantScopeBinder, true);
    }

    /**
     * legacyModulesFallback (ADR-0009): token thiếu hẳn claim {@code modules} được coi là {@code [LENDING]}
     * (chuyển tiếp sau triển khai); false thì thiếu claim nghĩa là không có module nào.
     */
    public PermissionInterceptor(PermissionKeyResolver resolver, PermissionKeyRegistrar registrar, String serviceName,
                                 boolean requireTenant, TenantScopeBinder tenantScopeBinder,
                                 boolean legacyModulesFallback) {
        this.legacyModulesFallback = legacyModulesFallback;
        this.resolver = resolver;
        this.registrar = registrar;
        this.serviceName = serviceName;
        this.requireTenant = requireTenant;
        this.tenantScopeBinder = tenantScopeBinder != null ? tenantScopeBinder : TenantScopeBinder.NOOP;
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
        TenantModule module = resolved.requiredModule();
        // ADR-0011: token dịch vụ storefront chỉ dùng được ở endpoint @StorefrontAccessible
        if (isServiceToken(authentication)) {
            return handleServiceToken(response, resolved, authentication);
        }
        // Endpoint chung không thuộc module nào: chỉ cần đăng nhập
        if (resolved.mode() == PermissionKeyResolver.Mode.AUTHENTICATED && module == null) return true;
        if (resolved.mode() == PermissionKeyResolver.Mode.PLATFORM_ADMIN) {
            if (isPlatformAdmin(authentication)) return true;
            reject(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Chỉ dành cho quản trị nền tảng");
            return false;
        }
        if (resolved.mode() == PermissionKeyResolver.Mode.PERMISSION
                && isCrossTenantRead(handlerMethod, request, authentication)) {
            return handleCrossTenantRead(request, response, resolved, (JwtAuthenticationToken) authentication);
        }
        if ((requireTenant || module != null) && !hasTenant(authentication)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "TENANT_REQUIRED", "Token không thuộc tenant nào");
            return false;
        }

        // Đăng ký trước khi quyết định, kể cả khi sắp từ chối: để khóa mới hiện ra cho người quản trị gán quyền
        if (resolved.mode() == PermissionKeyResolver.Mode.PERMISSION) register(request, resolved);

        // ADR-0009: module là hợp đồng thuê, admin của tenant cũng bị chặn
        if (module != null && !hasModule(authentication, module)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "MODULE_NOT_ENABLED",
                    "Tenant chưa bật module " + module);
            return false;
        }
        if (resolved.mode() == PermissionKeyResolver.Mode.AUTHENTICATED) return true;

        if (isAdmin(authentication) || hasKey(authentication, resolved.key())) return true;

        reject(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Thiếu quyền: " + resolved.key());
        return false;
    }

    /**
     * Token {@code svc}: không @StorefrontAccessible thì 403 FORBIDDEN; thiếu tenantId thì 403 TENANT_REQUIRED; thiếu
     * module thì 403 MODULE_NOT_ENABLED (không áp dụng fallback token cũ); còn lại cho qua, không đăng ký/kiểm khóa.
     */
    private boolean handleServiceToken(HttpServletResponse response, PermissionKeyResolver.Resolved resolved,
                                       Authentication authentication) throws Exception {
        if (!resolved.storefrontAccessible()) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Token dịch vụ không được dùng ở endpoint này");
            return false;
        }
        if (!hasTenant(authentication)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "TENANT_REQUIRED", "Token không thuộc tenant nào");
            return false;
        }
        TenantModule module = resolved.requiredModule();
        if (module != null) {
            Object claim = ((JwtAuthenticationToken) authentication).getToken().getClaim(JwtClaims.MODULES);
            boolean has = false;
            if (claim instanceof Collection<?> values) {
                for (Object value : values) {
                    if (module.name().equals(String.valueOf(value))) has = true;
                }
            }
            if (!has) {
                reject(response, HttpServletResponse.SC_FORBIDDEN, "MODULE_NOT_ENABLED", "Tenant chưa bật module " + module);
                return false;
            }
        }
        return true;
    }

    private static boolean isServiceToken(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken jwt && jwt.getToken().hasClaim(JwtClaims.SERVICE);
    }

    /** ADR-0008: GET + @CrossTenantReadable + token platformAdmin không có tenantId. Mọi trường hợp khác giữ luồng cũ. */
    private static boolean isCrossTenantRead(HandlerMethod handlerMethod, HttpServletRequest request,
                                              Authentication authentication) {
        return handlerMethod.hasMethodAnnotation(CrossTenantReadable.class)
                && "GET".equals(request.getMethod())
                && isPlatformAdmin(authentication)
                && !hasTenant(authentication);
    }

    private boolean handleCrossTenantRead(HttpServletRequest request, HttpServletResponse response,
                                          PermissionKeyResolver.Resolved resolved, JwtAuthenticationToken jwt)
            throws Exception {
        String raw = request.getParameter("tenantId");
        UUID tenantId = null;
        if (raw != null && !raw.isBlank()) {
            try {
                tenantId = UUID.fromString(raw.trim());
            } catch (IllegalArgumentException e) {
                reject(response, HttpServletResponse.SC_BAD_REQUEST, "INVALID_TENANT_ID", "tenantId không phải UUID hợp lệ");
                return false;
            }
        }
        // Đăng ký khóa như thường để khóa vẫn hiện cho quản trị, nhưng không kiểm khóa permission
        register(request, resolved);
        if (tenantId == null) {
            tenantScopeBinder.bindAll();
        } else {
            tenantScopeBinder.bindSingle(tenantId);
        }
        log.info("platform-audit operation=cross-tenant-read actor={} tenantId={} path={}",
                safe(jwt.getToken().getSubject()), tenantId != null ? tenantId : "ALL", safe(pathOf(request)));
        return true;
    }

    private static String pathOf(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : request.getRequestURI();
    }

    /** Bỏ ký tự điều khiển để chuỗi từ client không giả dòng log. */
    private static String safe(String value) {
        return value == null ? "-" : value.replaceAll("[\\p{Cntrl}\\s]+", "_");
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

    private boolean hasModule(Authentication authentication, TenantModule module) {
        if (!(authentication instanceof JwtAuthenticationToken jwt)) return false;
        Object claim = jwt.getToken().getClaim(JwtClaims.MODULES);
        if (claim == null) return legacyModulesFallback && module == TenantModule.LENDING;
        if (!(claim instanceof Collection<?> values)) return false;
        for (Object value : values) {
            if (module.name().equals(String.valueOf(value))) return true;
        }
        return false;
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
