package lms.circulation.fine.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import lms.circulation.fine.domain.ApprovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataFineRefundRepository extends JpaRepository<FineRefundJpaEntity, UUID>,
        JpaSpecificationExecutor<FineRefundJpaEntity> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from FineRefundJpaEntity r where r.id = :id")
    Optional<FineRefundJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByFineIdAndStatusIn(UUID fineId, Collection<ApprovalStatus> statuses);
}
