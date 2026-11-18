package lms.circulation.fine.infrastructure.persistence;

import lms.circulation.fine.domain.FineReason;
import lms.circulation.fine.domain.FineStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataFineRepository extends JpaRepository<FineJpaEntity, UUID> {

    boolean existsByBorrowingIdAndBookCopyIdAndReason(UUID borrowingId, UUID bookCopyId, FineReason reason);

    boolean existsByDamageLevelCode(String damageLevelCode);

    Optional<FineJpaEntity> findByBorrowingIdAndBookCopyIdAndReason(UUID borrowingId, UUID bookCopyId, FineReason reason);

    List<FineJpaEntity> findByReaderIdOrderByCreatedAtDesc(UUID readerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FineJpaEntity f where f.id = :id")
    Optional<FineJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    /** Khoản còn phải thu: UNPAID và REDUCED (idx_fines_reader_collectible). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FineJpaEntity f where f.readerId = :readerId and f.status in :statuses order by f.createdAt")
    List<FineJpaEntity> findByReaderAndStatusInForUpdate(@Param("readerId") UUID readerId,
                                                         @Param("statuses") Collection<FineStatus> statuses);

    /** Tổng số phải thu: payable_amount, NULL (khoản cũ) là amount. */
    @Query("select coalesce(sum(coalesce(f.payableAmount, f.amount)), 0) from FineJpaEntity f "
            + "where f.readerId = :readerId and f.status in :statuses")
    BigDecimal sumPayableByReaderAndStatusIn(@Param("readerId") UUID readerId,
                                             @Param("statuses") Collection<FineStatus> statuses);
}
