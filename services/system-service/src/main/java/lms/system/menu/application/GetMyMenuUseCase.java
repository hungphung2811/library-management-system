package lms.system.menu.application;

import java.util.List;
import java.util.UUID;
import lms.system.menu.domain.MenuItem;
import lms.system.menu.domain.MenuNode;
import lms.system.menu.domain.MenuTree;
import lms.system.menu.domain.MenuViewer;

/**
 * Cache-aside: cache lưu cây chưa lọc của tenant, lọc theo quyền ở mỗi request. Cố ý KHÔNG có @Transactional: khi
 * cache trúng thì không mượn kết nối DB; khi trượt, findAll của adapter tự chạy trong transaction chỉ đọc.
 */
public class GetMyMenuUseCase {

    private final MenuRepositoryPort repository;
    private final MenuCachePort cache;

    public GetMyMenuUseCase(MenuRepositoryPort repository, MenuCachePort cache) {
        this.repository = repository;
        this.cache = cache;
    }

    /** {@code tenantId == null} (quản trị nền tảng, token không có tenant) trả danh sách rỗng. */
    public List<MenuNode> execute(UUID tenantId, MenuViewer viewer) {
        if (tenantId == null) {
            return List.of();
        }
        return new MenuTree(tenantId, load(tenantId)).visibleFor(viewer);
    }

    private List<MenuItem> load(UUID tenantId) {
        List<MenuItem> cached = readCache(tenantId);
        if (cached != null) {
            return cached;
        }
        List<MenuItem> items = repository.findAll(tenantId);
        try {
            cache.put(tenantId, items);
        } catch (RuntimeException ignored) {
            // cache là phụ: lỗi không làm hỏng request
        }
        return items;
    }

    private List<MenuItem> readCache(UUID tenantId) {
        try {
            return cache.get(tenantId).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
