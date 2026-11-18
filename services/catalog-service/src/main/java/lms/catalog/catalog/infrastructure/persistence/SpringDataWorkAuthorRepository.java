package lms.catalog.catalog.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataWorkAuthorRepository extends JpaRepository<WorkAuthorJpaEntity, WorkAuthorJpaEntity.Key> {

    List<WorkAuthorJpaEntity> findByWorkIdInOrderBySortOrder(Collection<UUID> workIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from WorkAuthorJpaEntity w where w.workId = :workId")
    void deleteByWorkId(@Param("workId") UUID workId);
}
