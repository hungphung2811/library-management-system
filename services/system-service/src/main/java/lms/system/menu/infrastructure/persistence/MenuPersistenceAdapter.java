package lms.system.menu.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import lms.system.menu.application.MenuRepositoryPort;
import lms.system.menu.domain.MenuErrorCode;
import lms.system.menu.domain.MenuException;
import lms.system.menu.domain.MenuItem;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

/**
 * Adapter JPA của {@link MenuRepositoryPort}. {@code save} flush ngay để xung đột {@code @Version} nổ trong adapter
 * và được đổi thành {@code MENU_VERSION_CONFLICT} (nếu chờ tới commit thì lỗi nổ ngoài use case).
 */
@Repository
class MenuPersistenceAdapter implements MenuRepositoryPort {

    private final SpringDataMenuRepository jpa;

    MenuPersistenceAdapter(SpringDataMenuRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<MenuItem> findAll(UUID tenantId) {
        // @TenantId đã lọc theo tenant của phiên; lọc lại theo tenantId được hỏi để không trả nhầm tenant khác
        return jpa.findAll().stream().map(MenuJpaEntity::toDomain)
                .filter(i -> i.tenantId().equals(tenantId)).toList();
    }

    @Override
    public MenuItem save(MenuItem item) {
        try {
            MenuJpaEntity entity = jpa.findById(item.id()).orElse(null);
            if (entity == null) {
                entity = MenuJpaEntity.create(item);
            } else {
                entity.apply(item);
            }
            return jpa.saveAndFlush(entity).toDomain();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new MenuException(MenuErrorCode.MENU_VERSION_CONFLICT, "Menu đã bị người khác sửa, hãy tải lại");
        }
    }

    @Override
    public void deleteById(UUID tenantId, UUID id) {
        try {
            jpa.findById(id).ifPresent(jpa::delete);
            jpa.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new MenuException(MenuErrorCode.MENU_VERSION_CONFLICT, "Menu đã bị người khác sửa, hãy tải lại");
        }
    }
}
