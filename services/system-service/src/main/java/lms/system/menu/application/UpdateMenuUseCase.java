package lms.system.menu.application;

import java.time.Clock;
import java.util.UUID;
import lms.system.menu.domain.MenuDetails;
import lms.system.menu.domain.MenuItem;
import lms.system.menu.domain.MenuTree;
import org.springframework.transaction.annotation.Transactional;

public class UpdateMenuUseCase {

    public record Command(UUID id, MenuDetails details, long version) {}

    private final MenuRepositoryPort repository;
    private final MenuCachePort cache;
    private final Clock clock;

    public UpdateMenuUseCase(MenuRepositoryPort repository, MenuCachePort cache, Clock clock) {
        this.repository = repository;
        this.cache = cache;
        this.clock = clock;
    }

    @Transactional
    public MenuItem execute(UUID tenantId, Command command) {
        MenuTree tree = new MenuTree(tenantId, repository.findAll(tenantId));
        MenuItem updated = tree.update(command.id(), command.details(), command.version(), clock.instant());
        MenuItem saved = repository.save(updated);
        cache.evict(tenantId);
        return saved;
    }
}
