package lms.catalog.catalog.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataTitleRepository extends JpaRepository<TitleJpaEntity, UUID> {

    boolean existsByIsbn(String isbn);

    boolean existsByIsbnAndIdNot(String isbn, UUID id);

    @Query("select t from TitleJpaEntity t where lower(t.name) like lower(concat('%', :keyword, '%')) "
            + "or lower(t.author) like lower(concat('%', :keyword, '%')) order by t.name")
    List<TitleJpaEntity> search(@Param("keyword") String keyword);

    List<TitleJpaEntity> findAllByOrderByName();
}
