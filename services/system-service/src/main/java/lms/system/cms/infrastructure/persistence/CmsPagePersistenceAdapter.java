package lms.system.cms.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.system.cms.application.CmsPageRepositoryPort;
import lms.system.cms.domain.CmsErrorCode;
import lms.system.cms.domain.CmsException;
import lms.system.cms.domain.CmsPage;
import lms.system.cms.domain.CmsPageStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

/**
 * Adapter JPA của {@link CmsPageRepositoryPort}. {@code save} flush ngay để xung đột {@code @Version} và slug trùng
 * nổ trong adapter và được đổi thành mã lỗi nghiệp vụ.
 */
@Repository
class CmsPagePersistenceAdapter implements CmsPageRepositoryPort {

    private static final Sort ORDER = Sort.by(Sort.Order.asc("sortOrder"), Sort.Order.asc("slug"));

    private final SpringDataCmsPageRepository jpa;

    CmsPagePersistenceAdapter(SpringDataCmsPageRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<CmsPage> findById(UUID id) {
        return jpa.findById(id).map(CmsPageJpaEntity::toDomain);
    }

    @Override
    public Optional<CmsPage> findBySlug(String slug) {
        return jpa.findBySlug(slug).map(CmsPageJpaEntity::toDomain);
    }

    @Override
    public List<CmsPage> findAll() {
        return jpa.findAll(ORDER).stream().map(CmsPageJpaEntity::toDomain).toList();
    }

    @Override
    public List<CmsPage> findPublished() {
        return jpa.findByPublishedAtIsNotNull(ORDER).stream().map(CmsPageJpaEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PublishedSummary> findPublishedSummaries() {
        return jpa.findPublishedRows().stream()
                .map(r -> new PublishedSummary(r.slug(), r.title(), r.sortOrder())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminSummary> findAllSummaries() {
        return jpa.findAdminRows().stream().map(r -> new AdminSummary(r.id(), r.slug(), r.title(),
                r.publishedAt() == null ? CmsPageStatus.DRAFT
                        : r.unchangedSincePublish() ? CmsPageStatus.PUBLISHED : CmsPageStatus.PUBLISHED_WITH_CHANGES,
                r.sortOrder(), r.publishedAt(), r.version() == null ? 0L : r.version(), r.updatedAt())).toList();
    }

    /** Giao dịch ngắn chỉ cho việc đọc; use case render sau khi giao dịch này đã trả kết nối. */
    @Override
    @Transactional(readOnly = true)
    public Optional<CmsPage> findPublishedBySlug(String slug) {
        return jpa.findBySlugAndPublishedAtIsNotNull(slug).map(CmsPageJpaEntity::toDomain);
    }

    @Override
    public CmsPage save(CmsPage page) {
        try {
            CmsPageJpaEntity entity = jpa.findById(page.id()).orElse(null);
            if (entity == null) {
                entity = CmsPageJpaEntity.create(page);
            } else {
                entity.apply(page);
            }
            return jpa.saveAndFlush(entity).toDomain();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new CmsException(CmsErrorCode.CMS_VERSION_CONFLICT, "Trang đã bị người khác sửa, hãy tải lại");
        } catch (DataIntegrityViolationException e) {
            throw new CmsException(CmsErrorCode.CMS_SLUG_DUPLICATED, "Slug đã được dùng");
        }
    }

    @Override
    public void deleteById(UUID id) {
        jpa.deleteById(id);
        jpa.flush();
    }
}
