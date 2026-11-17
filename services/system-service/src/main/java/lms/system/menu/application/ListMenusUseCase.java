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

    /** {@code usingDefaults}: tenant chưa có dòng nào nên {@code /me} đang dùng {@link DefaultMenus} ({@code items} rỗng). */
    public record Result(List<MenuItem> items, boolean usingDefaults) {}

    @Transactional(readOnly = true)
    public List<MenuItem> execute(UUID tenantId) {
        return result(tenantId).items();
    }

    @Transactional(readOnly = true)
    public Result result(UUID tenantId) {
        List<MenuItem> ordered = new MenuTree(tenantId, repository.findAll(tenantId)).ordered();
        return new Result(ordered, ordered.isEmpty());
    }
}
