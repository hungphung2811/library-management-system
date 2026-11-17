package lms.catalog.catalog.infrastructure.persistence;

import java.util.UUID;

interface SpringDataCategoryRepository extends ReferenceJpaRepository<CategoryJpaEntity> {

    boolean existsByParentId(UUID parentId);
}
