package lms.system.menu.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Mọi truy vấn là derived query; bộ lọc @TenantId của Hibernate tự thêm điều kiện tenant. */
interface SpringDataMenuRepository extends JpaRepository<MenuJpaEntity, UUID> {
}
