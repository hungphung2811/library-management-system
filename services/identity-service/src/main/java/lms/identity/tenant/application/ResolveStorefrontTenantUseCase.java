package lms.identity.tenant.application;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.domain.ModuleCode;
import lms.identity.tenant.domain.StorefrontOriginRepository;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantModuleRepository;
import lms.identity.tenant.domain.TenantRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tra mã tenant cho storefront công khai (ADR-0011 2.1, 2.5). Chỉ trả tenant tồn tại, ACTIVE và có module SALES bật;
 * mọi trường hợp khác trả rỗng (không phân biệt lý do để chống dò tenant). Luôn đọc tenant theo mã trước rồi mới kiểm
 * trạng thái và module; truy vấn module và origin luôn chạy đúng một lần ở mọi nhánh. allowedOrigins là origin đang bật
 * của tenant (đã chuẩn hóa) để customer-api quyết CORS (ADR-0013).
 */
public class ResolveStorefrontTenantUseCase {

    private static final UUID NO_TENANT = new UUID(0L, 0L);

    private final TenantRepository tenants;
    private final TenantModuleRepository modules;
    private final StorefrontOriginRepository origins;

    public ResolveStorefrontTenantUseCase(TenantRepository tenants, TenantModuleRepository modules) {
        this(tenants, modules, null);
    }

    /** origins null = không có kho origin (allowedOrigins luôn rỗng): chỉ dùng cho test cũ. */
    public ResolveStorefrontTenantUseCase(TenantRepository tenants, TenantModuleRepository modules,
                                          StorefrontOriginRepository origins) {
        this.tenants = tenants;
        this.modules = modules;
        this.origins = origins;
    }

    @Transactional(readOnly = true)
    public Optional<StorefrontTenant> execute(String tenantCode) {
        String code = tenantCode == null ? "" : tenantCode.trim().toLowerCase(Locale.ROOT);
        Optional<Tenant> found = tenants.findByCode(code);
        // Luôn truy vấn module đúng một lần (mã không tồn tại thì dùng id giả) để mọi nhánh 404 có cùng chi phí,
        // tránh kênh phụ thời gian phân biệt mã tồn tại/không tồn tại/bị ngưng (ADR-0011 2.5).
        UUID lookupId = found.map(Tenant::id).orElse(NO_TENANT);
        List<ModuleCode> enabled = modules.enabledOf(List.of(lookupId)).getOrDefault(lookupId, List.of());
        // Origin cũng truy vấn đúng một lần ở mọi nhánh (cùng lý do); chỉ origin ĐANG BẬT (ADR-0013 2.3).
        List<String> allowedOrigins = origins == null ? List.of() : origins.findEnabledOrigins(lookupId);
        if (found.isEmpty() || !found.get().isActive() || !enabled.contains(ModuleCode.SALES)) {
            return Optional.empty();
        }
        Tenant t = found.get();
        return Optional.of(new StorefrontTenant(t.id(), t.code(), t.name(),
                enabled.stream().map(ModuleCode::name).toList(), allowedOrigins));
    }
}
