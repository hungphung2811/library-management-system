package lms.system.menu.application;

import java.util.UUID;
import lms.system.menu.domain.MenuTree;
import org.springframework.transaction.annotation.Transactional;

public class DeleteMenuUseCase {

    private final MenuRepositoryPort repository;
    private final MenuCachePort cache;

    public DeleteMenuUseCase(MenuRepositoryPort repository, MenuCachePort cache) {
        this.repository = repository;
        this.cache = cache;
    }

    @Transactional
    public void execute(UUID tenantId, UUID id) {
        MenuTree tree = new MenuTree(tenantId, repository.findAll(tenantId));
        tree.remove(id);
        repository.deleteById(tenantId, id);
        cache.evict(tenantId);
    }
}
