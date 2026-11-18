package lms.catalog.catalog.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

/** Truy vấn chung cho bốn bảng danh mục (không dùng native query; tìm kiếm dùng Specification). */
@NoRepositoryBean
interface ReferenceJpaRepository<E extends ReferenceJpaEntity>
        extends JpaRepository<E, UUID>, JpaSpecificationExecutor<E> {

    boolean existsByNameKey(String nameKey);

    java.util.List<E> findByNameKeyIn(java.util.Collection<String> nameKeys);

    boolean existsByNameKeyAndIdNot(String nameKey, UUID id);
}
