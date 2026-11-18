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
