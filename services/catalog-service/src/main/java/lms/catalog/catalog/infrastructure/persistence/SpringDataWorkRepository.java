package lms.catalog.catalog.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataWorkRepository extends JpaRepository<WorkJpaEntity, UUID>, JpaSpecificationExecutor<WorkJpaEntity> {

    @Query("select w.id from WorkJpaEntity w where w.seriesId = :seriesId")
    List<UUID> findIdsBySeriesId(@Param("seriesId") UUID seriesId);
}
