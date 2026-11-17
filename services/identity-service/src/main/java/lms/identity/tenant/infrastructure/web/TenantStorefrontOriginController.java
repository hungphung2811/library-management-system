package lms.identity.tenant.infrastructure.web;

import lms.identity.tenant.application.ListMyStorefrontOriginsUseCase;
import lms.identity.tenant.application.MyStorefrontOriginsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant admin xem origin storefront của tenant mình (ADR-0013 2.8). Khóa động {@code TenantStorefrontOriginController/list}
 * (ADR-0003, không {@code @RequirePermission}); role {@code is_admin} có {@code admin=true} nên xem được ngay. Tenant lấy
 * từ token (TenantContext, trong use case), endpoint không nhận id; token không có tenantId thì 403 TENANT_REQUIRED. Chỉ đọc.
 */
@RestController
@RequestMapping("/internal/storefront-origins")
public class TenantStorefrontOriginController {

    private final ListMyStorefrontOriginsUseCase listMine;

    public TenantStorefrontOriginController(ListMyStorefrontOriginsUseCase listMine) {
        this.listMine = listMine;
    }

    @GetMapping
    public MyStorefrontOriginsResponse list() {
        return listMine.execute();
    }
}
