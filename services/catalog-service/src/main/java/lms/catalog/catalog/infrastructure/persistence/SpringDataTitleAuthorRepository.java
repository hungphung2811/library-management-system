package lms.catalog.catalog.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataTitleAuthorRepository extends JpaRepository<TitleAuthorJpaEntity, TitleAuthorJpaEntity.Key> {

    List<TitleAuthorJpaEntity> findByTitleIdInOrderBySortOrder(Collection<UUID> titleIds);

    /** Xóa hàng loạt thực thi ngay (flush trước, xóa context sau) để ghi lại cùng khóa không đụng dòng cũ. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from TitleAuthorJpaEntity t where t.titleId = :titleId")
    void deleteByTitleId(@Param("titleId") UUID titleId);
}
