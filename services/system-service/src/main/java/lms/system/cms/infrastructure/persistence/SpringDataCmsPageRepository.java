package lms.system.cms.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Chỉ derived query. Truy vấn công khai luôn kèm điều kiện {@code publishedAt IS NOT NULL}. */
interface SpringDataCmsPageRepository extends JpaRepository<CmsPageJpaEntity, UUID> {

    Optional<CmsPageJpaEntity> findBySlug(String slug);

    List<CmsPageJpaEntity> findByPublishedAtIsNotNull(Sort sort);

    Optional<CmsPageJpaEntity> findBySlugAndPublishedAtIsNotNull(String slug);

    /** Chỉ 3 cột, không nạp body_markdown (tới 50 KB mỗi cột). */
    @Query("select new lms.system.cms.infrastructure.persistence.PublishedRow(p.slug, p.publishedTitle, p.sortOrder) "
            + "from CmsPageJpaEntity p where p.publishedAt is not null order by p.sortOrder asc, p.slug asc")
    List<PublishedRow> findPublishedRows();

    /** Cờ "chưa sửa sau xuất bản" so sánh ngay trong DB nên không phải kéo nội dung về. */
    @Query("select new lms.system.cms.infrastructure.persistence.AdminRow(p.id, p.slug, p.title, p.publishedAt, "
            + "p.sortOrder, p.version, p.updatedAt, "
            + "case when p.publishedAt is not null and p.title = p.publishedTitle "
            + "and p.bodyMarkdown = p.publishedBodyMarkdown then true else false end) "
            + "from CmsPageJpaEntity p order by p.sortOrder asc, p.slug asc")
    List<AdminRow> findAdminRows();
}
