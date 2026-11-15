package lms.identity.tenant.application;

import java.util.List;
import java.util.UUID;
import lms.identity.tenant.domain.PlatformOperationEvent;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantNotFoundException;
import lms.identity.tenant.domain.TenantQueryRepository;
import lms.identity.tenant.domain.TenantRepository;
import lms.identity.tenant.domain.TenantStatus;
import lms.identity.tenant.domain.TenantStatusChangedEvent;
import lms.tenant.TenantContext;

/**
 * Tạm ngưng / kích hoạt lại tenant. SUSPENDED chặn cấp và làm mới token vào tenant (đăng nhập, refresh, đổi tenant đã
 * kiểm tenant ACTIVE); access token đang sống hết hạn trong tối đa 15 phút. Dữ liệu giữ nguyên. Phát
 * {@code TenantStatusChanged} {tenantId, status} cho circulation và bản ghi audit.
 */
public class SetTenantStatusUseCase {

    private final TenantRepository tenants;
    private final TenantQueryRepository queries;
    private final PublishPlatformEventPort events;
    private final TransactionalRunner tx;

    public SetTenantStatusUseCase(TenantRepository tenants, TenantQueryRepository queries,
                                  PublishPlatformEventPort events, TransactionalRunner tx) {
        this.tenants = tenants;
        this.queries = queries;
        this.events = events;
        this.tx = tx;
    }

    public TenantResponse suspend(UUID tenantId, UUID actorId) {
        return change(tenantId, TenantStatus.SUSPENDED, actorId);
    }

    public TenantResponse activate(UUID tenantId, UUID actorId) {
        return change(tenantId, TenantStatus.ACTIVE, actorId);
    }

    private TenantResponse change(UUID tenantId, TenantStatus status, UUID actorId) {
        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException("Không tìm thấy tenant " + tenantId));
        if (status == TenantStatus.SUSPENDED) tenant.suspend();
        else tenant.activate();
        return TenantContext.callAs(tenantId, () -> {
            tx.inTransaction(() -> tenants.save(tenant));
            events.publish(new TenantStatusChangedEvent(tenantId, status));
            events.publish(PlatformOperationEvent.of(status == TenantStatus.SUSPENDED ? "TENANT_SUSPENDED"
                    : "TENANT_ACTIVATED", actorId, tenantId, tenantId, "status=" + status));
            return TenantResponse.from(tenant, queries.countActiveMembers(List.of(tenantId)).getOrDefault(tenantId, 0L));
        });
    }
}
