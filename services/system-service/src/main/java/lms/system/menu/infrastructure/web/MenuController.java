package lms.system.menu.infrastructure.web;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lms.security.JwtClaims;
import lms.security.permission.AuthenticatedOnly;
import lms.system.menu.application.GetMyMenuUseCase;
import lms.system.menu.domain.MenuViewer;
import lms.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Menu của người đăng nhập. Tên lớp/method (khóa MenuController/myMenu) đặt xong không đổi. */
@RestController
@RequestMapping("/internal/menus")
public class MenuController {

    private final GetMyMenuUseCase getMyMenu;
    /** Cùng cờ với PermissionInterceptor: token thiếu claim modules coi là [LENDING] (ADR-0009). */
    private final boolean legacyModulesFallback;

    public MenuController(GetMyMenuUseCase getMyMenu,
                          @Value("${lms.security.permission.legacy-modules-fallback:true}") boolean legacyModulesFallback) {
        this.getMyMenu = getMyMenu;
        this.legacyModulesFallback = legacyModulesFallback;
    }

    /** Token không có tenantId (quản trị nền tảng) hoặc không có JWT (dev) trả danh sách rỗng. */
    @AuthenticatedOnly
    @GetMapping("/me")
    public List<MenuResponses.Node> myMenu(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            return List.of();
        }
        List<String> permissions = jwt.getClaimAsStringList(JwtClaims.PERMISSIONS);
        MenuViewer viewer = new MenuViewer(Boolean.TRUE.equals(jwt.getClaim(JwtClaims.ADMIN)),
                permissions == null ? Set.of() : Set.copyOf(permissions), modulesOf(jwt));
        return getMyMenu.execute(TenantContext.current().orElse(null), viewer).stream()
                .map(MenuResponses.Node::from).toList();
    }

    /** Claim {@code modules}; thiếu hẳn claim thì [LENDING] khi cờ legacy bật, ngược lại rỗng (chỉ mục lõi). */
    private Set<String> modulesOf(Jwt jwt) {
        Object claim = jwt.getClaim(JwtClaims.MODULES);
        if (claim == null) {
            return legacyModulesFallback ? Set.of("LENDING") : Set.of();
        }
        if (!(claim instanceof Collection<?> values)) {
            return Set.of();
        }
        return values.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
    }
}
