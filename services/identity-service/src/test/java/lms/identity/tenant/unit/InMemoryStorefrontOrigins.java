package lms.identity.tenant.unit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.domain.StorefrontOriginDuplicatedException;
import lms.identity.tenant.domain.StorefrontOriginEntry;
import lms.identity.tenant.domain.StorefrontOriginHistoryEntry;
import lms.identity.tenant.domain.StorefrontOriginRepository;

/** Kho origin storefront trong bộ nhớ (mô phỏng UNIQUE(origin) toàn hệ thống) cho test đơn vị. */
class InMemoryStorefrontOrigins implements StorefrontOriginRepository {

    final Map<UUID, StorefrontOriginEntry> rows = new LinkedHashMap<>();
    final List<StorefrontOriginHistoryEntry> history = new ArrayList<>();

    @Override
    public List<StorefrontOriginEntry> findByTenant(UUID tenantId) {
        return rows.values().stream().filter(e -> e.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(StorefrontOriginEntry::createdAt)).toList();
    }

    @Override
    public List<String> findEnabledOrigins(UUID tenantId) {
        return findByTenant(tenantId).stream().filter(StorefrontOriginEntry::enabled)
                .map(StorefrontOriginEntry::origin).toList();
    }

    @Override
    public List<String> findEnabledOriginsOfApplication(UUID tenantId, UUID applicationId) {
        return findByTenant(tenantId).stream().filter(StorefrontOriginEntry::enabled)
                .filter(e -> applicationId.equals(e.applicationId())).map(StorefrontOriginEntry::origin).toList();
    }

    @Override
    public Optional<UUID> findOwnerTenant(String origin) {
        return rows.values().stream().filter(e -> e.origin().equals(origin)).map(StorefrontOriginEntry::tenantId)
                .findFirst();
    }

    @Override
    public void insert(StorefrontOriginEntry entry) {
        if (findOwnerTenant(entry.origin()).isPresent()) {
            throw new StorefrontOriginDuplicatedException("trùng", null);
        }
        rows.put(entry.id(), entry);
    }

    @Override
    public void update(StorefrontOriginEntry entry) {
        rows.put(entry.id(), entry);
    }

    @Override
    public void delete(UUID originId) {
        rows.remove(originId);
    }

    @Override
    public void appendHistory(StorefrontOriginHistoryEntry entry) {
        history.add(entry);
    }
}
