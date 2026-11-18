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

    /** Dòng có work_id NULL (jar cũ chèn trong cửa sổ triển khai, ADR-0026 pha expand) không được các truy vấn theo tác phẩm thấy. */
    boolean existsByReaderIdAndWorkIdAndStatusIn(UUID readerId, UUID workId, Collection<ReservationStatus> statuses);

    List<ReservationJpaEntity> findByTitleIdAndStatusIn(UUID titleId, Collection<ReservationStatus> statuses);

    List<ReservationJpaEntity> findByWorkIdAndReaderIdInAndStatusIn(UUID workId, Collection<UUID> readerIds,
                                                                    Collection<ReservationStatus> statuses);

    List<ReservationJpaEntity> findByReaderIdOrderByReservedAtDesc(UUID readerId);

    /**
     * Số đặt trước WAITING cùng tác phẩm đứng trước hoặc bằng đặt trước này theo thứ tự (reservedAt, id) mà có thể tranh
     * cùng bản sao với nó (nhất quán với {@link #findNextInWork}): {@code lang} = "" (đặt trước mọi ngôn ngữ) thì đếm tất
     * cả; ngược lại đếm đặt trước không khai ngôn ngữ (nhận mọi ngôn ngữ) và đặt trước cùng ngôn ngữ (idx_reservations_work_queue).
     */
    @Query("select count(r) from ReservationJpaEntity r where r.workId = :workId "
            + "and (:lang = '' or r.language is null or r.language = :lang) "
            + "and r.status = :status and (r.reservedAt < :at or (r.reservedAt = :at and r.id <= :id))")
    long countAheadOrEqualInWork(@Param("workId") UUID workId, @Param("lang") String lang,
                                 @Param("status") ReservationStatus status, @Param("at") Instant at, @Param("id") UUID id);

    /** Hàng đợi WAITING của nhiều tác phẩm theo thứ tự (reservedAt, id): phục vụ tính vị trí theo lô (idx_reservations_work_queue). */
    @Query("select r.workId, coalesce(r.language, ''), r.id from ReservationJpaEntity r where r.workId in :workIds "
            + "and r.status = :status order by r.workId, r.reservedAt, r.id")
    List<Object[]> findQueueOrderByWork(@Param("workIds") Collection<UUID> workIds, @Param("status") ReservationStatus status);

    /** Hàng đợi của tác phẩm, chỉ đặt trước nhận được ngôn ngữ {@code language} (null trong dòng = mọi ngôn ngữ). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReservationJpaEntity r where r.workId = :workId and r.status = :status "
            + "and (r.language is null or r.language = :language) order by r.reservedAt asc, r.id asc")
    List<ReservationJpaEntity> findNextInWork(@Param("workId") UUID workId, @Param("language") String language,
                                              @Param("status") ReservationStatus status,
                                              org.springframework.data.domain.Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ReservationJpaEntity> findFirstByTitleIdAndStatusOrderByReservedAtAscIdAsc(UUID titleId,
                                                                                       ReservationStatus status);

    List<ReservationJpaEntity> findByStatusAndHoldUntilBefore(ReservationStatus status, LocalDate date);

    boolean existsByCopyIdAndStatus(UUID copyId, ReservationStatus status);

    Optional<ReservationJpaEntity> findFirstByCopyIdAndStatus(UUID copyId, ReservationStatus status);
}
