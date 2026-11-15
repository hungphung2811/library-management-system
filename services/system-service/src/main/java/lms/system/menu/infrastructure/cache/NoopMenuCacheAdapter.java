package lms.system.menu.infrastructure.cache;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.system.menu.application.MenuCachePort;
import lms.system.menu.domain.MenuItem;

/** Không cache (lms.system.menu.cache.enabled=false): luôn đọc DB. */
public class NoopMenuCacheAdapter implements MenuCachePort {

    @Override
    public Optional<List<MenuItem>> get(UUID tenantId) {
        return Optional.empty();
    }

    @Override
    public void put(UUID tenantId, List<MenuItem> items) {
    }

    @Override
    public void evict(UUID tenantId) {
    }
}
