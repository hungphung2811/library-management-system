package lms.catalog.catalog.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataTitleRepository extends JpaRepository<TitleJpaEntity, UUID> {

    boolean existsByIsbn(String isbn);

    List<TitleJpaEntity> findByIsbnIn(java.util.Collection<String> isbns);

    interface IsbnId {
        String getIsbn();

        UUID getId();
    }

    /** Chỉ cột isbn và id (không nạp cả đầu sách) cho kiểm trùng/tra id theo lô khi nhập Excel. */
    @Query("select t.isbn as isbn, t.id as id from TitleJpaEntity t where t.isbn in :isbns")
    List<IsbnId> findIdsByIsbnIn(@Param("isbns") java.util.Collection<String> isbns);

    boolean existsByIsbnAndIdNot(String isbn, UUID id);

    List<TitleJpaEntity> findByWorkIdInOrderByNameAscIdAsc(java.util.Collection<UUID> workIds);

    @Query("select t.id from TitleJpaEntity t where t.workId = :workId")
    List<UUID> findIdsByWorkId(@Param("workId") UUID workId);

    /** Ghi gương trường cấp tác phẩm (ADR-0026 4.3); flush trước, xóa context sau để lần đọc kế tiếp thấy giá trị mới. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update TitleJpaEntity t set t.categoryId = :categoryId, t.series = :series, t.seriesNumber = :seriesNumber "
            + "where t.workId = :workId")
    int mirrorWorkFields(@Param("workId") UUID workId, @Param("categoryId") UUID categoryId,
                         @Param("series") String series, @Param("seriesNumber") Integer seriesNumber);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update TitleJpaEntity t set t.workId = :toWorkId where t.workId in :fromWorkIds")
    int moveToWork(@Param("fromWorkIds") java.util.Collection<UUID> fromWorkIds, @Param("toWorkId") UUID toWorkId);

    @Query("select t from TitleJpaEntity t where lower(t.name) like lower(concat('%', :keyword, '%')) "
            + "or lower(t.author) like lower(concat('%', :keyword, '%')) order by t.name")
    List<TitleJpaEntity> search(@Param("keyword") String keyword);

    List<TitleJpaEntity> findAllByOrderByName();

    @Query("select t from TitleJpaEntity t where lower(t.name) like lower(concat('%', :keyword, '%')) "
            + "or lower(t.author) like lower(concat('%', :keyword, '%')) order by t.name")
    List<TitleJpaEntity> searchPage(@Param("keyword") String keyword, org.springframework.data.domain.Pageable page);

    @Query("select t from TitleJpaEntity t order by t.name")
    List<TitleJpaEntity> findAllByOrderByName(org.springframework.data.domain.Pageable page);
}
