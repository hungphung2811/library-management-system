package lms.system.menu.application;

import java.time.Clock;
import java.util.UUID;
import lms.system.menu.domain.MenuDetails;
import lms.system.menu.domain.MenuItem;
import lms.system.menu.domain.MenuTree;
import org.springframework.transaction.annotation.Transactional;

public class CreateMenuUseCase {

    public record Command(String code, MenuDetails details) {}

    private final MenuRepositoryPort repository;
    private final MenuCachePort cache;
    private final Clock clock;

    public CreateMenuUseCase(MenuRepositoryPort repository, MenuCachePort cache, Clock clock) {
        this.repository = repository;
        this.cache = cache;
        this.clock = clock;
    }

    /** Transaction ghi; adapter cache hoãn evict tới sau commit. */
    @Transactional
    public MenuItem execute(UUID tenantId, Command command) {
        MenuTree tree = new MenuTree(tenantId, repository.findAll(tenantId));
        MenuItem created = tree.add(UUID.randomUUID(), command.code(), command.details(), clock.instant());
        MenuItem saved = repository.save(created);
        cache.evict(tenantId);
        return saved;
    }
}
