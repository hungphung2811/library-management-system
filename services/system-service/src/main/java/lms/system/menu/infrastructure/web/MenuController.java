package lms.system.menu.infrastructure.web;

import java.util.List;
import java.util.Set;
import lms.security.JwtClaims;
import lms.security.permission.AuthenticatedOnly;
import lms.system.menu.application.GetMyMenuUseCase;
import lms.system.menu.domain.MenuViewer;
import lms.tenant.TenantContext;
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

    public MenuController(GetMyMenuUseCase getMyMenu) {
        this.getMyMenu = getMyMenu;
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
                permissions == null ? Set.of() : Set.copyOf(permissions));
        return getMyMenu.execute(TenantContext.current().orElse(null), viewer).stream()
                .map(MenuResponses.Node::from).toList();
    }
}
