package lms.identity.tenant.application;

import java.time.Instant;
import java.util.UUID;
import lms.identity.tenant.domain.StorefrontOrigin;
import lms.identity.tenant.domain.StorefrontOriginAction;
import lms.identity.tenant.domain.StorefrontOriginDuplicatedException;
import lms.identity.tenant.domain.StorefrontOriginEntry;
import lms.identity.tenant.domain.StorefrontOriginHistoryEntry;
import lms.identity.tenant.domain.StorefrontOriginRepository;
import lms.identity.tenant.domain.TenantNotFoundException;
import lms.identity.tenant.domain.TenantRepository;
import lms.identity.tenant.domain.TenantStorefrontOrigins;

/**
 * Platform admin thêm origin cho tenant (ADR-0013 2.8). Chuẩn hóa/kiểm tra (400 ORIGIN_INVALID) rồi trong MỘT
 * transaction: khóa dòng tenant (hai yêu cầu thêm đồng thời tuần tự hóa, giữ giới hạn), kiểm trùng toàn hệ thống (409
 * ORIGIN_DUPLICATED), kiểm giới hạn (409 ORIGIN_LIMIT_EXCEEDED), ghi origin + một dòng lịch sử. Sau commit phát event
 * audit và log (best-effort). Bảng toàn cục nên không cần {@code TenantContext.callAs}.
 */
public class AddStorefrontOriginUseCase {

    private final TenantRepository tenants;
    private final StorefrontOriginRepository origins;
    private final PublishPlatformEventPort events;
    private final TransactionalRunner tx;
    private final StorefrontOriginPolicy policy;

    public AddStorefrontOriginUseCase(TenantRepository tenants, StorefrontOriginRepository origins,
                                      PublishPlatformEventPort events, TransactionalRunner tx,
                                      StorefrontOriginPolicy policy) {
        this.tenants = tenants;
        this.origins = origins;
        this.events = events;
        this.tx = tx;
        this.policy = policy;
    }

    public StorefrontOriginResponse execute(UUID tenantId, String rawOrigin, UUID actorId) {
        StorefrontOrigin origin = StorefrontOrigin.parse(rawOrigin, policy.allowHttp());
        StorefrontOriginEntry created = tx.inTransaction(() -> {
            tenants.findByIdForUpdate(tenantId)
                    .orElseThrow(() -> new TenantNotFoundException("Không tìm thấy tenant " + tenantId));
            origins.findOwnerTenant(origin.value()).filter(owner -> !owner.equals(tenantId)).ifPresent(owner -> {
                throw new StorefrontOriginDuplicatedException("Origin đã được gán cho tenant khác: " + origin.value(),
                        owner);
            });
            TenantStorefrontOrigins set = new TenantStorefrontOrigins(tenantId, origins.findByTenant(tenantId),
                    policy.maxOriginsPerTenant());
            Instant at = StorefrontOriginOperations.now();
            StorefrontOriginEntry entry = set.add(origin, actorId, at);
            origins.insert(entry);
            origins.appendHistory(StorefrontOriginHistoryEntry.of(entry, StorefrontOriginAction.ADDED, actorId, at));
            return entry;
        });
        StorefrontOriginOperations.announce(events, StorefrontOriginAction.ADDED, actorId, created);
        return StorefrontOriginResponse.from(created);
    }
}
