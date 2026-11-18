package lms.identity.apiclient.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataApplicationHistoryRepository extends JpaRepository<ApplicationHistoryJpaEntity, UUID> {

    @Query("select h from ApplicationHistoryJpaEntity h where h.tenantId = :tenantId "
            + "and h.applicationId = :applicationId order by h.occurredAt desc, h.id")
    List<ApplicationHistoryJpaEntity> findByApplication(@Param("tenantId") UUID tenantId,
                                                        @Param("applicationId") UUID applicationId,
                                                        Pageable pageable);
}
