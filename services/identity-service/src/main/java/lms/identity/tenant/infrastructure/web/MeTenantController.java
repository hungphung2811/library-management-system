package lms.identity.tenant.infrastructure.web;

import java.util.List;
import lms.identity.tenant.application.ListMyTenantsUseCase;
import lms.identity.tenant.application.TenantSummary;
import lms.security.CurrentUser;
import lms.security.permission.AuthenticatedOnly;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tenant của người đang đăng nhập (dùng để hiển thị và đổi tenant). Token không có tenantId vẫn gọi được. */
@RestController
@RequestMapping("/internal/me")
public class MeTenantController {

    private final ListMyTenantsUseCase listMine;

    public MeTenantController(ListMyTenantsUseCase listMine) {
        this.listMine = listMine;
    }

    @AuthenticatedOnly
    @GetMapping("/tenants")
    public List<TenantSummary> myTenants(@AuthenticationPrincipal Jwt jwt) {
        return listMine.execute(CurrentUser.from(jwt).userId());
    }
}
