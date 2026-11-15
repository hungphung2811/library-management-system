package lms.circulation.borrowing.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Mọi truy vấn là JPQL nên Hibernate tự thêm điều kiện tenant (@TenantId); RLS ở DB lọc thêm. Không có SQL native.
 * Đọc theo id dùng {@link #findInTenantById} thay cho findById (find theo khóa chính không đi qua bộ lọc tenant
 * của Hibernate, chỉ còn RLS chặn).
 */
public interface SpringDataBorrowingRepository extends JpaRepository<BorrowingJpaEntity, UUID> {

    @Query("select b from BorrowingJpaEntity b where b.id = :id")
    Optional<BorrowingJpaEntity> findInTenantById(@Param("id") UUID id);

    @Query("select count(i) from BorrowingJpaEntity b join b.items i where b.userId = :userId and i.returnDate is null")
    long countActiveItemsByUserId(@Param("userId") UUID userId);
}
