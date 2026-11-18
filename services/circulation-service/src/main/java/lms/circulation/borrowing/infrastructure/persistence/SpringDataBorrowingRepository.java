package lms.circulation.borrowing.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Mọi truy vấn là JPQL nên Hibernate tự thêm điều kiện tenant (@TenantId, kể cả truy vấn con); RLS ở DB lọc thêm.
 * Không có SQL native. Đọc theo id dùng {@link #findInTenantById} hoặc {@link #findByIdForUpdate} thay cho findById
 * (find theo khóa chính không đi qua bộ lọc tenant của Hibernate, chỉ còn RLS chặn).
 */
public interface SpringDataBorrowingRepository extends JpaRepository<BorrowingJpaEntity, UUID>,
        JpaSpecificationExecutor<BorrowingJpaEntity> {

    @Query("select b from BorrowingJpaEntity b where b.id = :id")
    Optional<BorrowingJpaEntity> findInTenantById(@Param("id") UUID id);

    @Query("select b from BorrowingJpaEntity b where b.id = :id and b.userId = :userId")
    Optional<BorrowingJpaEntity> findInTenantByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("select count(i) from BorrowingJpaEntity b join b.items i where b.userId = :userId and i.returnDate is null")
    long countActiveItemsByUserId(@Param("userId") UUID userId);

    @Query("select count(i) from BorrowingJpaEntity b join b.items i "
            + "where b.userId = :userId and i.returnDate is null and b.dueDate < :today")
    long countOverdueItemsByUserId(@Param("userId") UUID userId, @Param("today") LocalDate today);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BorrowingJpaEntity b where b.id = :id")
    Optional<BorrowingJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BorrowingJpaEntity b where b.id = "
            + "(select b2.id from BorrowingJpaEntity b2 join b2.items i where i.bookCopyId = :copyId and i.returnDate is null)")
    Optional<BorrowingJpaEntity> findOpenByCopyIdForUpdate(@Param("copyId") UUID copyId);

    /** Lượt mượn đã đóng bằng LOST gần nhất của bản sao (sắp theo ngày đóng giảm dần); lấy dòng đầu bằng Pageable. */
    @Query("select b from BorrowingJpaEntity b join b.items i where i.bookCopyId = :copyId and i.condition = :lost "
            + "order by i.returnDate desc, b.id")
    List<BorrowingJpaEntity> findLostByCopyIdNewestFirst(@Param("copyId") UUID copyId,
                                                         @Param("lost") lms.circulation.borrowing.domain.ItemCondition lost,
                                                         org.springframework.data.domain.Pageable pageable);

    @Query("select b from BorrowingJpaEntity b where b.userId = :userId order by b.borrowDate desc, b.id")
    List<BorrowingJpaEntity> findByUserIdOrdered(@Param("userId") UUID userId);
}
