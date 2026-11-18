package lms.circulation.fine.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import lms.circulation.fine.domain.ApprovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataFineAdjustmentRepository extends JpaRepository<FineAdjustmentJpaEntity, UUID>,
        JpaSpecificationExecutor<FineAdjustmentJpaEntity> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from FineAdjustmentJpaEntity a where a.id = :id")
    Optional<FineAdjustmentJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByFineIdAndStatus(UUID fineId, ApprovalStatus status);
}
