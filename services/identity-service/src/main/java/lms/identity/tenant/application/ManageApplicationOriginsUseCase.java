package lms.identity.tenant.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.identity.tenant.domain.InvalidStorefrontOriginException;
import lms.identity.tenant.domain.StorefrontOrigin;
import lms.identity.tenant.domain.StorefrontOriginAction;
import lms.identity.tenant.domain.StorefrontOriginDuplicatedException;
import lms.identity.tenant.domain.StorefrontOriginEntry;
import lms.identity.tenant.domain.StorefrontOriginHistoryEntry;
import lms.identity.tenant.domain.StorefrontOriginLimitExceededException;
import lms.identity.tenant.domain.StorefrontOriginNotFoundException;
import lms.identity.tenant.domain.StorefrontOriginRepository;
import lms.identity.tenant.domain.TenantNotFoundException;
import lms.identity.tenant.domain.TenantRepository;
import lms.identity.tenant.domain.TenantStorefrontOrigins;

/**
 * Domain allowlist của một Application (cột application_id của tenant_storefront_origins, SaaS API Authentication D8):
 * tái dùng nguyên luật chuẩn hóa/kiểm origin, giới hạn số origin mỗi tenant, UNIQUE toàn hệ thống và lịch sử của
 * ADR-0013. Người gọi (use case của apiclient) chịu trách nhiệm xác nhận Application thuộc tenant; ở đây vẫn lọc
 * tường minh theo (tenantId, applicationId): id origin thuộc tenant khác hoặc Application khác luôn là NOT_FOUND.
 * Chạy trong transaction của người gọi (REQUIRED); gọi riêng lẻ thì tự mở transaction.
 */
public class ManageApplicationOriginsUseCase {

    private final TenantRepository tenants;
    private final StorefrontOriginRepository origins;
    private final PublishPlatformEventPort events;
    private final TransactionalRunner tx;
    private final StorefrontOriginPolicy policy;

    public ManageApplicationOriginsUseCase(TenantRepository tenants, StorefrontOriginRepository origins,
                                           PublishPlatformEventPort events, TransactionalRunner tx,
                                           StorefrontOriginPolicy policy) {
        this.tenants = tenants;
        this.origins = origins;
        this.events = events;
        this.tx = tx;
        this.policy = policy;
    }

    public int maxOriginsPerTenant() {
        return policy.maxOriginsPerTenant();
    }

    public List<StorefrontOriginResponse> list(UUID tenantId, UUID applicationId) {
        return origins.findByTenant(tenantId).stream()
                .filter(e -> applicationId.equals(e.applicationId()))
                .map(StorefrontOriginResponse::from).toList();
    }

    public StorefrontOriginResponse add(UUID tenantId, UUID applicationId, String rawOrigin, UUID actorId) {
        StorefrontOrigin origin;
        try {
            origin = StorefrontOrigin.parse(rawOrigin, policy.allowHttp());
        } catch (InvalidStorefrontOriginException e) {
            throw new ApplicationOriginException(ApplicationOriginException.Reason.INVALID, e.getMessage(),
                    e.reason().name());
        }
        StorefrontOriginEntry created;
        try {
            created = tx.inTransaction(() -> {
                lockTenant(tenantId);
                origins.findOwnerTenant(origin.value()).ifPresent(owner -> {
                    // không phân biệt tenant khác với tenant này: không lộ chủ sở hữu
                    throw new StorefrontOriginDuplicatedException("Origin đã được sử dụng: " + origin.value(), null);
                });
                TenantStorefrontOrigins set = new TenantStorefrontOrigins(tenantId, origins.findByTenant(tenantId),
                        policy.maxOriginsPerTenant());
                Instant at = StorefrontOriginOperations.now();
                StorefrontOriginEntry entry = set.add(origin, applicationId, actorId, at);
                origins.insert(entry);
                origins.appendHistory(
                        StorefrontOriginHistoryEntry.of(entry, StorefrontOriginAction.ADDED, actorId, at));
                return entry;
            });
        } catch (StorefrontOriginDuplicatedException e) {
            throw new ApplicationOriginException(ApplicationOriginException.Reason.DUPLICATED, e.getMessage(), null);
        } catch (StorefrontOriginLimitExceededException e) {
            throw new ApplicationOriginException(ApplicationOriginException.Reason.LIMIT_EXCEEDED, e.getMessage(),
                    null);
        }
        StorefrontOriginOperations.announce(events, StorefrontOriginAction.ADDED, actorId, created);
        return StorefrontOriginResponse.from(created);
    }

    /** @return chuỗi origin đã xóa (cho lịch sử của Application) */
    public String remove(UUID tenantId, UUID applicationId, UUID originId, UUID actorId) {
        StorefrontOriginEntry removed;
        try {
            removed = tx.inTransaction(() -> {
                lockTenant(tenantId);
                TenantStorefrontOrigins set = new TenantStorefrontOrigins(tenantId, origins.findByTenant(tenantId),
                        policy.maxOriginsPerTenant());
                StorefrontOriginEntry entry = set.get(originId);
                if (!applicationId.equals(entry.applicationId())) {
                    throw new StorefrontOriginNotFoundException("Không tìm thấy origin " + originId);
                }
                set.remove(originId);
                origins.delete(entry.id());
                origins.appendHistory(StorefrontOriginHistoryEntry.of(entry, StorefrontOriginAction.REMOVED, actorId,
                        StorefrontOriginOperations.now()));
                return entry;
            });
        } catch (StorefrontOriginNotFoundException e) {
            throw new ApplicationOriginException(ApplicationOriginException.Reason.NOT_FOUND, e.getMessage(), null);
        }
        StorefrontOriginOperations.announce(events, StorefrontOriginAction.REMOVED, actorId, removed);
        return removed.origin();
    }

    /** Xóa mọi origin của Application (khi Application bị xóa mềm); trả các chuỗi origin đã xóa. */
    public List<String> removeAll(UUID tenantId, UUID applicationId, UUID actorId) {
        List<StorefrontOriginEntry> entries = tx.inTransaction(() -> {
            lockTenant(tenantId);
            List<StorefrontOriginEntry> mine = origins.findByTenant(tenantId).stream()
                    .filter(e -> applicationId.equals(e.applicationId())).toList();
            Instant at = StorefrontOriginOperations.now();
            for (StorefrontOriginEntry entry : mine) {
                origins.delete(entry.id());
                origins.appendHistory(
                        StorefrontOriginHistoryEntry.of(entry, StorefrontOriginAction.REMOVED, actorId, at));
            }
            return mine;
        });
        entries.forEach(e -> StorefrontOriginOperations.announce(events, StorefrontOriginAction.REMOVED, actorId, e));
        return entries.stream().map(StorefrontOriginEntry::origin).toList();
    }

    private void lockTenant(UUID tenantId) {
        tenants.findByIdForUpdate(tenantId)
                .orElseThrow(() -> new TenantNotFoundException("Không tìm thấy tenant " + tenantId));
    }
}
