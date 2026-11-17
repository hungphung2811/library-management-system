package lms.identity.tenant.application;

import java.util.List;
import java.util.UUID;
import lms.identity.tenant.domain.ModuleCode;
import lms.identity.tenant.domain.TenantModuleRepository;
import lms.identity.tenant.domain.TenantNotFoundException;
import lms.identity.tenant.domain.TenantRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đọc module của tenant. {@link #execute} cho API platform (mọi module của hệ thống kèm trạng thái);
 * {@link #enabledCodes} cho TokenService phát claim {@code modules} (chỉ module đang bật; chạy transaction đọc ngắn,
 * bảng toàn cục nên không cần TenantContext).
 */
public class ListTenantModulesUseCase {

    private final TenantRepository tenants;
    private final TenantModuleRepository modules;

    public ListTenantModulesUseCase(TenantRepository tenants, TenantModuleRepository modules) {
        this.tenants = tenants;
        this.modules = modules;
    }

    /** @throws TenantNotFoundException nếu tenant không tồn tại */
    @Transactional(readOnly = true)
    public List<TenantModuleResponse> execute(UUID tenantId) {
        tenants.findById(tenantId).orElseThrow(() -> new TenantNotFoundException("Không tìm thấy tenant " + tenantId));
        return modules.findByTenant(tenantId).all().stream().map(TenantModuleResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<String> enabledCodes(UUID tenantId) {
        return modules.findByTenant(tenantId).enabled().stream().map(ModuleCode::name).toList();
    }
}
