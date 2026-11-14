package lms.catalog.inventory.infrastructure.persistence;

import lms.catalog.inventory.domain.CopyStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataBookCopyRepository extends JpaRepository<BookCopyJpaEntity, UUID> {

    boolean existsByBarcode(String barcode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from BookCopyJpaEntity c where c.id = :id")
    Optional<BookCopyJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    interface DetailsRow {
        UUID getId();

        UUID getTitleId();

        String getTitleName();

        String getBarcode();

        String getStatus();

        BigDecimal getCoverPrice();
    }

    /** Cùng database với context catalog nên đọc giá bìa bằng join trực tiếp, tránh phụ thuộc giữa hai context. */
    @Query(value = "select c.id as id, c.title_id as titleId, t.name as titleName, c.barcode as barcode, "
            + "c.status as status, t.cover_price as coverPrice from book_copies c join titles t on t.id = c.title_id "
            + "where c.id = :id", nativeQuery = true)
    Optional<DetailsRow> findDetailsById(@Param("id") UUID id);

    @Query(value = "select c.id as id, c.title_id as titleId, t.name as titleName, c.barcode as barcode, "
            + "c.status as status, t.cover_price as coverPrice from book_copies c join titles t on t.id = c.title_id "
            + "where c.barcode = :barcode", nativeQuery = true)
    Optional<DetailsRow> findDetailsByBarcode(@Param("barcode") String barcode);

    List<BookCopyJpaEntity> findByTitleIdOrderByBarcode(UUID titleId);

    /** Cùng database với context catalog nên kiểm tra tồn tại đầu sách bằng truy vấn trực tiếp, tránh phụ thuộc vòng giữa hai context. */
    @Query(value = "select exists (select 1 from titles where id = :titleId)", nativeQuery = true)
    boolean titleExists(@Param("titleId") UUID titleId);

    interface TitleCount {
        UUID getTitleId();

        long getCnt();
    }

    @Query("select c.titleId as titleId, count(c) as cnt from BookCopyJpaEntity c "
            + "where c.titleId in :titleIds and c.status = :status group by c.titleId")
    List<TitleCount> countByTitleIds(@Param("titleIds") Collection<UUID> titleIds, @Param("status") CopyStatus status);

    @Query("select distinct c.titleId from BookCopyJpaEntity c where c.status = :status")
    Set<UUID> findDistinctTitleIdsByStatus(@Param("status") CopyStatus status);
}
