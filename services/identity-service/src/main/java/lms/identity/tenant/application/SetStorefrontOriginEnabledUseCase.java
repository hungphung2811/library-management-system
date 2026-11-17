package lms.identity.tenant.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.domain.StorefrontOriginAction;
import lms.identity.tenant.domain.StorefrontOriginEntry;
import lms.identity.tenant.domain.StorefrontOriginHistoryEntry;
import lms.identity.tenant.domain.StorefrontOriginRepository;
import lms.identity.tenant.domain.TenantNotFoundException;
import lms.identity.tenant.domain.TenantRepository;
import lms.identity.tenant.domain.TenantStorefrontOrigins;

/**
 * Platform admin bật/tắt origin (PUT {enabled}). originId của tenant khác coi như không có (404). Giá trị không đổi là
 * no-op: không ghi lịch sử, không phát event.
 */
public class SetStorefrontOriginEnabledUseCase {

    private final TenantRepository tenants;
    private final StorefrontOriginRepository origins;
    private final PublishPlatformEventPort events;
    private final TransactionalRunner tx;
    private final StorefrontOriginPolicy policy;

    public SetStorefrontOriginEnabledUseCase(TenantRepository tenants, StorefrontOriginRepository origins,
                                             PublishPlatformEventPort events, TransactionalRunner tx,
                                             StorefrontOriginPolicy policy) {
        this.tenants = tenants;
        this.origins = origins;
        this.events = events;
        this.tx = tx;
        this.policy = policy;
    }

    public StorefrontOriginResponse execute(UUID tenantId, UUID originId, boolean enabled, UUID actorId) {
        record Result(StorefrontOriginEntry entry, boolean changed) {
        }
        Result result = tx.inTransaction(() -> {
            tenants.findByIdForUpdate(tenantId)
                    .orElseThrow(() -> new TenantNotFoundException("Không tìm thấy tenant " + tenantId));
            TenantStorefrontOrigins set = new TenantStorefrontOrigins(tenantId, origins.findByTenant(tenantId),
                    policy.maxOriginsPerTenant());
            Instant at = StorefrontOriginOperations.now();
            Optional<StorefrontOriginEntry> changed = set.setEnabled(originId, enabled, actorId, at);
            if (changed.isEmpty()) return new Result(set.get(originId), false);
            origins.update(changed.get());
            origins.appendHistory(StorefrontOriginHistoryEntry.of(changed.get(),
                    enabled ? StorefrontOriginAction.ENABLED : StorefrontOriginAction.DISABLED, actorId, at));
            return new Result(changed.get(), true);
        });
        if (result.changed()) {
            StorefrontOriginOperations.announce(events,
                    enabled ? StorefrontOriginAction.ENABLED : StorefrontOriginAction.DISABLED, actorId,
                    result.entry());
        }
        return StorefrontOriginResponse.from(result.entry());
    }
}
