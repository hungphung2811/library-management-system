package lms.security.permission;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.regex.Pattern;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;

/** Xác định chế độ kiểm tra và khóa quyền của một handler (controller method). */
public class PermissionKeyResolver {

    public enum Mode { PUBLIC, AUTHENTICATED, PLATFORM_ADMIN, PERMISSION }

    public static final String SOURCE_AUTO = "AUTO";
    public static final String SOURCE_CUSTOM = "CUSTOM";

    private static final Pattern VALID_KEY = Pattern.compile("^[A-Za-z0-9_./:-]{1,200}$");

    /** key và source chỉ có nghĩa khi mode là PERMISSION. */
    public record Resolved(Mode mode, String key, String source, String description, TenantModule requiredModule,
                           boolean storefrontAccessible, String integrationScope) {
        public Resolved(Mode mode, String key, String source, String description, TenantModule requiredModule,
                        boolean storefrontAccessible) {
            this(mode, key, source, description, requiredModule, storefrontAccessible, null);
        }

        public Resolved(Mode mode, String key, String source, String description, TenantModule requiredModule) {
            this(mode, key, source, description, requiredModule, false, null);
        }

        public Resolved(Mode mode, String key, String source, String description) {
            this(mode, key, source, description, null, false, null);
        }
    }

    private static final Resolved PUBLIC = new Resolved(Mode.PUBLIC, null, null, null);
    private static final Resolved AUTHENTICATED = new Resolved(Mode.AUTHENTICATED, null, null, null);
    private static final Resolved PLATFORM_ADMIN = new Resolved(Mode.PLATFORM_ADMIN, null, null, null);

    public Resolved resolve(HandlerMethod handler) {
        Resolved base = resolveMode(handler);
        TenantModule module = requiredModule(handler);
        boolean storefront = storefrontAccessible(handler);
        String scope = integrationScope(handler);
        return module == null && !storefront && scope == null ? base
                : new Resolved(base.mode(), base.key(), base.source(), base.description(), module, storefront, scope);
    }

    /** {@code @IntegrationScope} trên method thắng class; null nếu không có hoặc giá trị trống (cộng thêm, không đổi chế độ). */
    private static String integrationScope(HandlerMethod handler) {
        Class<?> type = ClassUtils.getUserClass(handler.getBeanType());
        IntegrationScope onMethod = find(handler.getMethod(), IntegrationScope.class);
        IntegrationScope found = onMethod != null ? onMethod : find(type, IntegrationScope.class);
        return found == null || found.value().isBlank() ? null : found.value().trim();
    }

    /** ADR-0011: {@code @StorefrontAccessible} trên method hoặc class (cộng thêm, không đổi chế độ). */
    private static boolean storefrontAccessible(HandlerMethod handler) {
        Class<?> type = ClassUtils.getUserClass(handler.getBeanType());
        return find(handler.getMethod(), StorefrontAccessible.class) != null
                || find(type, StorefrontAccessible.class) != null;
    }

    /** ADR-0009: {@code @RequiresModule} trên method thắng class; null nếu không có. */
    private static TenantModule requiredModule(HandlerMethod handler) {
        Class<?> type = ClassUtils.getUserClass(handler.getBeanType());
        RequiresModule onMethod = find(handler.getMethod(), RequiresModule.class);
        if (onMethod != null) return onMethod.value();
        RequiresModule onClass = find(type, RequiresModule.class);
        return onClass != null ? onClass.value() : null;
    }

    private Resolved resolveMode(HandlerMethod handler) {
        Method method = handler.getMethod();
        Class<?> type = ClassUtils.getUserClass(handler.getBeanType());

        // Method thắng class; trong cùng một cấp: Public, rồi AuthenticatedOnly, rồi RequirePermission
        Resolved onMethod = fromElement(method, type);
        if (onMethod != null) return onMethod;
        Resolved onClass = fromElement(type, type);
        if (onClass != null) return onClass;

        // Mặc định: khóa tự sinh TênController/tênPhươngThức
        return new Resolved(Mode.PERMISSION, type.getSimpleName() + "/" + method.getName(), SOURCE_AUTO, "");
    }

    private Resolved fromElement(java.lang.reflect.AnnotatedElement element, Class<?> controller) {
        if (find(element, PublicEndpoint.class) != null) return PUBLIC;
        if (find(element, AuthenticatedOnly.class) != null) return AUTHENTICATED;
        if (find(element, PlatformAdminOnly.class) != null) return PLATFORM_ADMIN;
        RequirePermission require = find(element, RequirePermission.class);
        if (require == null) return null;
        if (require.value().isBlank()) {
            // @RequirePermission không có customKey: vẫn dùng khóa tự sinh nhưng giữ mô tả
            if (element instanceof Method m) {
                return new Resolved(Mode.PERMISSION, controller.getSimpleName() + "/" + m.getName(), SOURCE_AUTO,
                        require.description());
            }
            return null;
        }
        String key = require.value().trim();
        if (!VALID_KEY.matcher(key).matches()) {
            throw new IllegalStateException("customKey không hợp lệ trên " + controller.getSimpleName() + ": '" + key
                    + "' (chỉ gồm chữ, số và _ . / : -, tối đa 200 ký tự)");
        }
        return new Resolved(Mode.PERMISSION, key, SOURCE_CUSTOM, require.description());
    }

    private static <A extends Annotation> A find(java.lang.reflect.AnnotatedElement element, Class<A> type) {
        return AnnotatedElementUtils.findMergedAnnotation(element, type);
    }
}
