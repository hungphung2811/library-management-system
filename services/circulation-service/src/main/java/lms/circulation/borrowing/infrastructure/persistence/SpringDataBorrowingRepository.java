package lms.circulation.borrowing.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataBorrowingRepository extends JpaRepository<BorrowingJpaEntity, UUID> {

    @Query("select count(i) from BorrowingJpaEntity b join b.items i where b.userId = :userId and i.returnDate is null")
    long countActiveItemsByUserId(@Param("userId") UUID userId);
}
