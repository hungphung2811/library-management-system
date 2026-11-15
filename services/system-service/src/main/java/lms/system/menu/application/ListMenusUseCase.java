package lms.system.menu.application;

import java.util.List;
import java.util.UUID;
import lms.system.menu.domain.MenuItem;
import lms.system.menu.domain.MenuTree;
import org.springframework.transaction.annotation.Transactional;

/** Danh sách quản trị: mọi mục (kể cả tắt) theo thứ tự cây, đọc thẳng DB. */
public class ListMenusUseCase {

    private final MenuRepositoryPort repository;

    public ListMenusUseCase(MenuRepositoryPort repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<MenuItem> execute(UUID tenantId) {
        return new MenuTree(tenantId, repository.findAll(tenantId)).ordered();
    }
}
