package lms.circulation.fine.infrastructure.persistence;

import lms.circulation.fine.domain.FineReason;
import lms.circulation.fine.domain.FineStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataFineRepository extends JpaRepository<FineJpaEntity, UUID> {

    boolean existsByBorrowingIdAndBookCopyIdAndReason(UUID borrowingId, UUID bookCopyId, FineReason reason);

    List<FineJpaEntity> findByReaderIdOrderByCreatedAtDesc(UUID readerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FineJpaEntity f where f.readerId = :readerId and f.status = :status order by f.createdAt")
    List<FineJpaEntity> findByReaderAndStatusForUpdate(@Param("readerId") UUID readerId,
                                                       @Param("status") FineStatus status);

    @Query("select coalesce(sum(f.amount), 0) from FineJpaEntity f where f.readerId = :readerId and f.status = :status")
    BigDecimal sumByReaderAndStatus(@Param("readerId") UUID readerId, @Param("status") FineStatus status);
}
