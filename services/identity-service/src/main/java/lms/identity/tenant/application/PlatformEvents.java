package lms.identity.tenant.application;

import java.util.UUID;
import lms.common.kernel.DomainEvent;
import lms.identity.tenant.domain.ModuleCode;
import lms.identity.tenant.domain.PlatformOperationEvent;
import lms.identity.tenant.domain.TenantModuleEvents;
import lms.identity.tenant.domain.TenantStatus;
import lms.identity.tenant.domain.TenantStatusChangedEvent;

/**
 * Nhà máy event nền tảng cho context khác (contract chỉ dùng tầng application của tenant, không biết domain event của
 * tenant). Cùng định dạng với đường bật/tắt/suspend tay: hợp đồng phát đúng các event đó khi tác động vào module hoặc
 * trạng thái tenant (ADR-0012 mục 2.6).
 */
public final class PlatformEvents {

    private PlatformEvents() {
    }

    /** {@code PlatformOperationPerformed}; {@code detail} chỉ chứa mã/id/trạng thái, không dữ liệu nhạy cảm. */
    public static DomainEvent operation(String operation, UUID actorId, UUID tenantId, UUID targetId, String detail) {
        return PlatformOperationEvent.of(operation, actorId, tenantId, targetId, detail);
    }

    public static DomainEvent moduleEnabled(UUID tenantId, String module, UUID actorId) {
        return new TenantModuleEvents.Enabled(tenantId, ModuleCode.parse(module), actorId);
    }

    public static DomainEvent moduleDisabled(UUID tenantId, String module, UUID actorId) {
        return new TenantModuleEvents.Disabled(tenantId, ModuleCode.parse(module), actorId);
    }

    public static DomainEvent tenantStatusChanged(UUID tenantId, boolean active) {
        return new TenantStatusChangedEvent(tenantId, active ? TenantStatus.ACTIVE : TenantStatus.SUSPENDED);
    }
}
