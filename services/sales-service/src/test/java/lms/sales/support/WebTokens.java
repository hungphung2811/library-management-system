package lms.sales.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Token giả cho test WebMvc/IT: tenant, người dùng (sub là UUID), khóa quyền, module. */
public final class WebTokens {

    public static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    public static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private WebTokens() {
    }

    /** Người dùng thường có đúng một khóa, tenant có SALES. */
    public static RequestPostProcessor withKey(String key) {
        return withKeys(TENANT, List.of("SALES"), key);
    }

    public static RequestPostProcessor withKeys(UUID tenant, List<String> modules, String... keys) {
        return jwt().jwt(j -> {
            j.subject(USER.toString());
            if (tenant != null) {
                j.claim("tenantId", tenant.toString());
            }
            j.claim("permissions", List.of(keys));
            if (modules != null) {
                j.claim("modules", modules);
            }
        }).authorities(java.util.Arrays.stream(keys).<GrantedAuthority>map(SimpleGrantedAuthority::new).toList());
    }

    /** Người dùng không có khóa nào (tenant có SALES). */
    public static RequestPostProcessor noPermission() {
        return withKeys(TENANT, List.of("SALES"));
    }

    /** Quản trị viên tenant (bỏ qua kiểm khóa, vẫn bị chặn module). */
    public static RequestPostProcessor admin(List<String> modules) {
        return jwt().jwt(j -> {
            j.subject(USER.toString()).claim("tenantId", TENANT.toString()).claim("admin", true);
            if (modules != null) {
                j.claim("modules", modules);
            }
        });
    }

    public static RequestPostProcessor admin() {
        return admin(List.of("SALES"));
    }

    /** Token dịch vụ storefront (claim svc, ADR-0011): không khóa, chỉ dùng được ở endpoint @StorefrontAccessible. */
    public static RequestPostProcessor storefrontService(UUID tenant, List<String> modules) {
        return jwt().jwt(j -> {
            j.subject("00000000-0000-0000-0000-00000000f001").claim("svc", "customer-api")
                    .claim("permissions", List.of());
            if (tenant != null) {
                j.claim("tenantId", tenant.toString());
            }
            if (modules != null) {
                j.claim("modules", modules);
            }
        });
    }

    /** Người dùng thường (khách) với id riêng và các khóa cho trước. */
    public static RequestPostProcessor userWithKeys(UUID user, String... keys) {
        return jwt().jwt(j -> j.subject(user.toString()).claim("tenantId", TENANT.toString())
                .claim("permissions", List.of(keys)).claim("modules", List.of("SALES")))
                .authorities(java.util.Arrays.stream(keys).<GrantedAuthority>map(SimpleGrantedAuthority::new)
                        .toList());
    }
}
