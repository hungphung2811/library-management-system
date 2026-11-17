package lms.system.cms.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.system.cms.domain.CmsPage;
import lms.system.cms.domain.CmsPageStatus;

public interface CmsPageRepositoryPort {

    Optional<CmsPage> findById(UUID id);

    Optional<CmsPage> findBySlug(String slug);

    /** Mọi trang (kể cả nháp), theo sortOrder rồi slug. Chỉ dùng cho quản trị. */
    List<CmsPage> findAll();

    /** Chỉ trang đã xuất bản, theo sortOrder rồi slug. */
    List<CmsPage> findPublished();

    /** Tóm tắt trang đã xuất bản (không kéo nội dung), theo sortOrder rồi slug. */
    default List<PublishedSummary> findPublishedSummaries() {
        return findPublished().stream()
                .map(p -> new PublishedSummary(p.slug(), p.publishedTitle(), p.sortOrder())).toList();
    }

    /** Tóm tắt mọi trang cho quản trị (không kéo nội dung), theo sortOrder rồi slug. */
    default List<AdminSummary> findAllSummaries() {
        return findAll().stream().map(p -> new AdminSummary(p.id(), p.slug(), p.title(), p.status(), p.sortOrder(),
                p.publishedAt(), p.version(), p.updatedAt())).toList();
    }

    record PublishedSummary(String slug, String title, int sortOrder) {
    }

    record AdminSummary(UUID id, String slug, String title, CmsPageStatus status, int sortOrder, Instant publishedAt,
                        long version, Instant updatedAt) {
    }

    /** Trang theo slug, chỉ khi đã xuất bản. */
    Optional<CmsPage> findPublishedBySlug(String slug);

    /** Lưu; slug trùng ném CmsException(CMS_SLUG_DUPLICATED), xung đột phiên bản ném CMS_VERSION_CONFLICT. */
    CmsPage save(CmsPage page);

    void deleteById(UUID id);
}
