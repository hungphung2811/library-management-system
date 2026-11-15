package lms.system.menu.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.system.menu.domain.MenuItem;

/**
 * Cache cây menu chưa lọc theo tenant (ADR-0007 mục 2.5). Adapter nên nuốt lỗi hạ tầng
 * (get trả rỗng, put/evict không ném) và hoãn {@code evict} tới sau commit khi có transaction.
 */
public interface MenuCachePort {

    Optional<List<MenuItem>> get(UUID tenantId);

    void put(UUID tenantId, List<MenuItem> items);

    void evict(UUID tenantId);
}
