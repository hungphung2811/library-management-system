package lms.catalog.catalog.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceBatchConflictException;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

/** Điều phối bốn bảng danh mục theo {@link ReferenceKind}; mọi truy vấn qua JPA nên được @TenantId lọc, RLS lọc thêm. */
@Repository
class ReferencePersistenceAdapter implements ReferenceRepository {

    /** Giới hạn phần tử mỗi mệnh đề IN (ADR-0024). */
    private static final int IN_BATCH = 1000;

    private final SpringDataAuthorRepository authors;
    private final SpringDataPublisherRepository publishers;
    private final SpringDataCategoryRepository categories;
    private final SpringDataShelfLocationRepository shelves;
    private final SpringDataSeriesRepository series;

    ReferencePersistenceAdapter(SpringDataAuthorRepository authors, SpringDataPublisherRepository publishers,
                                SpringDataCategoryRepository categories, SpringDataShelfLocationRepository shelves,
                                SpringDataSeriesRepository series) {
        this.authors = authors;
        this.publishers = publishers;
        this.categories = categories;
        this.shelves = shelves;
        this.series = series;
    }

    @PersistenceContext
    private EntityManager em;

    private ReferenceJpaRepository<? extends ReferenceJpaEntity> repo(ReferenceKind kind) {
        return switch (kind) {
            case AUTHOR -> authors;
            case PUBLISHER -> publishers;
            case CATEGORY -> categories;
            case SHELF_LOCATION -> shelves;
            case SERIES -> series;
        };
    }

    @Override
    public Reference save(Reference r) {
        switch (r.kind()) {
            case AUTHOR -> authors.save(new AuthorJpaEntity(r));
            case PUBLISHER -> publishers.save(new PublisherJpaEntity(r));
            case CATEGORY -> categories.save(new CategoryJpaEntity(r));
            case SHELF_LOCATION -> shelves.save(new ShelfLocationJpaEntity(r));
            case SERIES -> series.save(new SeriesJpaEntity(r));
        }
        // Đọc lại để trả bản có tenantId.
        return findById(r.kind(), r.id()).orElseThrow();
    }

    @Override
    public Optional<Reference> findById(ReferenceKind kind, UUID id) {
        return repo(kind).findById(id).map(ReferenceJpaEntity::toDomain);
    }

    @Override
    public List<Reference> findByIds(ReferenceKind kind, Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return repo(kind).findAllById(ids).stream().map(ReferenceJpaEntity::toDomain).toList();
    }

    @Override
    public boolean existsByNameKey(ReferenceKind kind, String nameKey, UUID excludeId) {
        ReferenceJpaRepository<? extends ReferenceJpaEntity> r = repo(kind);
        return excludeId == null ? r.existsByNameKey(nameKey) : r.existsByNameKeyAndIdNot(nameKey, excludeId);
    }

    @Override
    public List<Reference> findByNameKeys(ReferenceKind kind, Collection<String> nameKeys) {
        List<String> keys = nameKeys.stream().distinct().toList();
        List<Reference> result = new ArrayList<>();
        for (int i = 0; i < keys.size(); i += IN_BATCH) {
            List<String> part = keys.subList(i, Math.min(keys.size(), i + IN_BATCH));
            repo(kind).findByNameKeyIn(part).forEach(e -> result.add(e.toDomain()));
        }
        return result;
    }

    /** persist (không merge: không SELECT trước INSERT) rồi flush để lỗi trùng khóa lộ ra ngay, gom thành batch JDBC. */
    @Override
    public void insertAll(Collection<Reference> references) {
        try {
            for (Reference r : references) {
                em.persist(switch (r.kind()) {
                    case AUTHOR -> new AuthorJpaEntity(r);
                    case PUBLISHER -> new PublisherJpaEntity(r);
                    case CATEGORY -> new CategoryJpaEntity(r);
                    case SHELF_LOCATION -> new ShelfLocationJpaEntity(r);
                    case SERIES -> new SeriesJpaEntity(r);
                });
            }
            em.flush();
        } catch (DataIntegrityViolationException | PersistenceException e) {
            throw new ReferenceBatchConflictException(e);
        }
    }

    @Override
    public boolean hasChildren(UUID categoryId) {
        return categories.existsByParentId(categoryId);
    }

    @Override
    public Page search(ReferenceKind kind, String keyword, Boolean active, UUID parentId, int page, int size) {
        var result = findPage(repo(kind), kind, keyword, active, parentId,
                PageRequest.of(page, size, Sort.by("nameKey")));
        return new Page(result.getContent().stream().map(ReferenceJpaEntity::toDomain).toList(),
                result.getTotalElements());
    }

    @Override
    public List<Reference> options(ReferenceKind kind, String keyword, int limit) {
        var result = findPage(repo(kind), kind, keyword, true, null, PageRequest.of(0, limit, Sort.by("nameKey")));
        return result.getContent().stream().map(ReferenceJpaEntity::toDomain).toList();
    }

    private static <E extends ReferenceJpaEntity> org.springframework.data.domain.Page<E> findPage(
            ReferenceJpaRepository<E> repo, ReferenceKind kind, String keyword, Boolean active, UUID parentId,
            PageRequest pageable) {
        Specification<E> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (keyword != null && !keyword.isBlank()) {
                String pattern = "%" + escapeLike(Reference.normalizeName(keyword).toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(cb.like(root.<String>get("nameKey"), pattern, '\\'),
                        cb.like(cb.lower(root.<String>get("description")), pattern, '\\')));
            }
            if (active != null) predicates.add(cb.equal(root.get("active"), active));
            if (parentId != null && kind == ReferenceKind.CATEGORY) {
                predicates.add(cb.equal(root.get("parentId"), parentId));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return repo.findAll(spec, pageable);
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
