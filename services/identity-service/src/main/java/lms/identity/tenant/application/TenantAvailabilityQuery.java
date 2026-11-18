package lms.identity.tenant.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.domain.ModuleCode;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantModuleRepository;
import lms.identity.tenant.domain.TenantRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tra nhanh trạng thái hoạt động và module đang bật của một tenant theo id (bảng toàn cục, không cần TenantContext).
 * Dùng cho đường nóng cấp token tích hợp (OAuth2 client_credentials): luôn đúng hai truy vấn ở mọi nhánh, kể cả tenant
 * không tồn tại, để không tạo kênh phụ thời gian.
 */
public class TenantAvailabilityQuery {

    private static final UUID NO_TENANT = new UUID(0L, 0L);

    /** active=false khi tenant không tồn tại hoặc không ACTIVE. */
    public record Availability(boolean active, List<String> modules) {
        public Availability {
            modules = List.copyOf(modules);
        }
    }

    private final TenantRepository tenants;
    private final TenantModuleRepository modules;

    public TenantAvailabilityQuery(TenantRepository tenants, TenantModuleRepository modules) {
        this.tenants = tenants;
        this.modules = modules;
    }

    @Transactional(readOnly = true)
    public Availability execute(UUID tenantId) {
        UUID lookup = tenantId == null ? NO_TENANT : tenantId;
        Optional<Tenant> found = tenants.findById(lookup);
        List<String> enabled = modules.enabledOf(List.of(lookup)).getOrDefault(lookup, List.of()).stream()
                .map(ModuleCode::name).toList();
        boolean active = found.isPresent() && found.get().isActive();
        return new Availability(active, active ? enabled : List.of());
    }
}
