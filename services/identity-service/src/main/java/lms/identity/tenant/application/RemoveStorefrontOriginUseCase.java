package lms.identity.tenant.application;

import java.time.Instant;
import java.util.UUID;
import lms.identity.tenant.domain.StorefrontOriginAction;
import lms.identity.tenant.domain.StorefrontOriginEntry;
import lms.identity.tenant.domain.StorefrontOriginHistoryEntry;
import lms.identity.tenant.domain.StorefrontOriginRepository;
import lms.identity.tenant.domain.TenantNotFoundException;
import lms.identity.tenant.domain.TenantRepository;
import lms.identity.tenant.domain.TenantStorefrontOrigins;

/** Platform admin xóa origin. Dòng lịch sử REMOVED giữ chuỗi origin (dòng gốc bị xóa). originId tenant khác: 404. */
public class RemoveStorefrontOriginUseCase {

    private final TenantRepository tenants;
    private final StorefrontOriginRepository origins;
    private final PublishPlatformEventPort events;
    private final TransactionalRunner tx;
    private final StorefrontOriginPolicy policy;

    public RemoveStorefrontOriginUseCase(TenantRepository tenants, StorefrontOriginRepository origins,
                                         PublishPlatformEventPort events, TransactionalRunner tx,
                                         StorefrontOriginPolicy policy) {
        this.tenants = tenants;
        this.origins = origins;
        this.events = events;
        this.tx = tx;
        this.policy = policy;
    }

    public void execute(UUID tenantId, UUID originId, UUID actorId) {
        StorefrontOriginEntry removed = tx.inTransaction(() -> {
            tenants.findByIdForUpdate(tenantId)
                    .orElseThrow(() -> new TenantNotFoundException("Không tìm thấy tenant " + tenantId));
            TenantStorefrontOrigins set = new TenantStorefrontOrigins(tenantId, origins.findByTenant(tenantId),
                    policy.maxOriginsPerTenant());
            StorefrontOriginEntry entry = set.remove(originId);
            Instant at = StorefrontOriginOperations.now();
            origins.delete(entry.id());
            origins.appendHistory(StorefrontOriginHistoryEntry.of(entry, StorefrontOriginAction.REMOVED, actorId, at));
            return entry;
        });
        StorefrontOriginOperations.announce(events, StorefrontOriginAction.REMOVED, actorId, removed);
    }
}
