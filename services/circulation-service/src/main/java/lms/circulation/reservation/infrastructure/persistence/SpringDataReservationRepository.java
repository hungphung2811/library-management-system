package lms.circulation.reservation.infrastructure.persistence;

import lms.circulation.reservation.domain.ReservationStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Mọi truy vấn là JPQL/derived nên Hibernate tự thêm điều kiện tenant (@TenantId); RLS ở DB lọc thêm. Không có SQL
 * native. Đọc theo id dùng {@link #findInTenantById} thay cho findById (find theo khóa chính không đi qua bộ lọc
 * tenant của Hibernate, chỉ còn RLS chặn).
 */
interface SpringDataReservationRepository extends JpaRepository<ReservationJpaEntity, UUID> {

    @Query("select r from ReservationJpaEntity r where r.id = :id")
    Optional<ReservationJpaEntity> findInTenantById(@Param("id") UUID id);

    boolean existsByReaderIdAndTitleIdAndStatusIn(UUID readerId, UUID titleId, Collection<ReservationStatus> statuses);

    List<ReservationJpaEntity> findByReaderIdOrderByReservedAtDesc(UUID readerId);

    /** Số đặt trước WAITING của đầu sách đứng trước hoặc bằng đặt trước này theo thứ tự (reservedAt, id). */
    @Query("select count(r) from ReservationJpaEntity r where r.titleId = :titleId and r.status = :status "
            + "and (r.reservedAt < :at or (r.reservedAt = :at and r.id <= :id))")
    long countAheadOrEqual(@Param("titleId") UUID titleId, @Param("status") ReservationStatus status,
                           @Param("at") Instant at, @Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ReservationJpaEntity> findFirstByTitleIdAndStatusOrderByReservedAtAscIdAsc(UUID titleId,
                                                                                       ReservationStatus status);

    List<ReservationJpaEntity> findByStatusAndHoldUntilBefore(ReservationStatus status, LocalDate date);

    boolean existsByCopyIdAndStatus(UUID copyId, ReservationStatus status);

    Optional<ReservationJpaEntity> findFirstByCopyIdAndStatus(UUID copyId, ReservationStatus status);
}
