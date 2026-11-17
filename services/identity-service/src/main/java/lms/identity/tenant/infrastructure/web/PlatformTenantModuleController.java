package lms.identity.tenant.infrastructure.web;

import java.util.List;
import java.util.UUID;
import lms.identity.tenant.application.DisableTenantModuleUseCase;
import lms.identity.tenant.application.EnableTenantModuleUseCase;
import lms.identity.tenant.application.ListTenantModulesUseCase;
import lms.identity.tenant.application.TenantModuleResponse;
import lms.security.CurrentUser;
import lms.security.permission.PlatformAdminOnly;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bật/tắt module của tenant (ADR-0009) cho người vận hành (claim {@code platformAdmin=true}); không dùng khóa
 * Controller/method và không cần tenantId. lms-api chuyển nguyên {@code /api/platform/**} nên không cần sửa.
 */
@RestController
@RequestMapping("/api/platform/tenants/{id}/modules")
@PlatformAdminOnly
public class PlatformTenantModuleController {

    private final ListTenantModulesUseCase list;
    private final EnableTenantModuleUseCase enable;
    private final DisableTenantModuleUseCase disable;

    public PlatformTenantModuleController(ListTenantModulesUseCase list, EnableTenantModuleUseCase enable,
                                          DisableTenantModuleUseCase disable) {
        this.list = list;
        this.enable = enable;
        this.disable = disable;
    }

    @GetMapping
    public List<TenantModuleResponse> list(@PathVariable UUID id) {
        return list.execute(id);
    }

    @PutMapping("/{module}")
    public TenantModuleResponse enable(@PathVariable UUID id, @PathVariable String module,
                                       @AuthenticationPrincipal Jwt jwt) {
        return enable.execute(id, module, actor(jwt));
    }

    @DeleteMapping("/{module}")
    public TenantModuleResponse disable(@PathVariable UUID id, @PathVariable String module,
                                        @AuthenticationPrincipal Jwt jwt) {
        return disable.execute(id, module, actor(jwt));
    }

    private static UUID actor(Jwt jwt) {
        return CurrentUser.from(jwt).userId();
    }
}
