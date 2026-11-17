package lms.system.menu.application;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.system.menu.domain.MenuDetails;
import lms.system.menu.domain.MenuErrorCode;
import lms.system.menu.domain.MenuException;
import lms.system.menu.domain.MenuItem;
import lms.system.menu.domain.MenuTree;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chép {@link DefaultMenus} thành dữ liệu của tenant (một lần). Tenant đã có bất kỳ dòng nào thì
 * {@code MENU_ALREADY_INITIALIZED}. Hai lần gọi đồng thời: ràng buộc UNIQUE (tenant_id, code) chặn lần sau.
 */
public class InitializeDefaultMenusUseCase {

    private final MenuRepositoryPort repository;
    private final MenuCachePort cache;
    private final Clock clock;

    public InitializeDefaultMenusUseCase(MenuRepositoryPort repository, MenuCachePort cache, Clock clock) {
        this.repository = repository;
        this.cache = cache;
        this.clock = clock;
    }

    @Transactional
    public List<MenuItem> execute(UUID tenantId) {
        MenuTree tree = new MenuTree(tenantId, repository.findAll(tenantId));
        if (tree.size() > 0) {
            throw new MenuException(MenuErrorCode.MENU_ALREADY_INITIALIZED,
                    "Tenant đã có dữ liệu menu, không chép menu mặc định");
        }
        Map<String, UUID> idByCode = new HashMap<>();
        for (DefaultMenus.Entry e : DefaultMenus.ENTRIES) {
            UUID id = UUID.randomUUID();
            idByCode.put(e.code(), id);
            UUID parentId = e.parentCode() == null ? null : idByCode.get(e.parentCode());
            MenuItem created = tree.add(id, e.code(), new MenuDetails(e.label(), e.path(), e.icon(), parentId,
                    e.sortOrder(), true, e.permissionKey(), e.module()), clock.instant());
            repository.save(created);
        }
        cache.evict(tenantId);
        return tree.ordered();
    }
}
