package lms.circulation.borrowing.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataBorrowingRepository extends JpaRepository<BorrowingJpaEntity, UUID> {

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

    @Query("select b from BorrowingJpaEntity b where b.userId = :userId order by b.borrowDate desc, b.id")
    List<BorrowingJpaEntity> findByUserIdOrdered(@Param("userId") UUID userId);
}
