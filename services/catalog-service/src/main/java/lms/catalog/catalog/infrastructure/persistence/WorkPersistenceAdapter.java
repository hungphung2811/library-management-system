package lms.catalog.catalog.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.Work;
import lms.catalog.catalog.domain.WorkRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

/** Lưu tác phẩm qua JPA: @TenantId lọc tenant, RLS lọc thêm ở DB. Tác giả chuẩn nạp theo lô (không N+1). */
@Repository
class WorkPersistenceAdapter implements WorkRepository {

    private static final int IN_BATCH = 1000;

    @PersistenceContext
    private EntityManager em;

    private final SpringDataWorkRepository jpa;
    private final SpringDataWorkAuthorRepository workAuthors;

    WorkPersistenceAdapter(SpringDataWorkRepository jpa, SpringDataWorkAuthorRepository workAuthors) {
        this.jpa = jpa;
        this.workAuthors = workAuthors;
    }

    @Override
    public Work save(Work work) {
        jpa.save(new WorkJpaEntity(work));
        // Bulk delete tự flush để dòng works đã có trước khi ghi lại work_authors (khóa ngoại kép).
        workAuthors.deleteByWorkId(work.id());
        for (int i = 0; i < work.authorIds().size(); i++) {
            workAuthors.save(new WorkAuthorJpaEntity(work.id(), work.authorIds().get(i), i));
        }
        em.flush();
        return findById(work.id()).orElseThrow();
    }

    /** persist theo lô (tác phẩm mới, không merge, không xóa work_authors) rồi flush: works trước, work_authors sau. */
    @Override
    public void insertAll(Collection<Work> works) {
        try {
            for (Work w : works) em.persist(new WorkJpaEntity(w));
            em.flush();
            for (Work w : works) {
                for (int i = 0; i < w.authorIds().size(); i++) {
                    em.persist(new WorkAuthorJpaEntity(w.id(), w.authorIds().get(i), i));
                }
            }
            em.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException | jakarta.persistence.PersistenceException e) {
            throw new lms.catalog.catalog.domain.TitleBatchConflictException(e);
        }
    }

    @Override
    public Optional<Work> findById(UUID id) {
        return jpa.findById(id).map(e -> toDomains(List.of(e)).get(0));
    }

    @Override
    public List<Work> findByIds(Collection<UUID> ids) {
        List<UUID> keys = ids.stream().distinct().toList();
        List<WorkJpaEntity> rows = new ArrayList<>();
        for (int i = 0; i < keys.size(); i += IN_BATCH) {
            rows.addAll(jpa.findAllById(keys.subList(i, Math.min(keys.size(), i + IN_BATCH))));
        }
        return toDomains(rows);
    }

    @Override
    public void delete(UUID id) {
        workAuthors.deleteByWorkId(id);
        jpa.deleteById(id);
        em.flush();
    }

    @Override
    public List<UUID> findIdsBySeries(UUID seriesId) {
        return jpa.findIdsBySeriesId(seriesId);
    }

    @Override
    public Page search(Criteria c, int page, int size) {
        Specification<WorkJpaEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (c.keyword() != null && !c.keyword().isBlank()) {
                String pattern = "%" + escapeLike(Reference.normalizeName(c.keyword()).toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.<String>get("title")), pattern, '\\'),
                        cb.like(cb.lower(root.<String>get("author")), pattern, '\\'),
                        cb.like(cb.lower(root.<String>get("originalTitle")), pattern, '\\')));
            }
            if (c.seriesId() != null) predicates.add(cb.equal(root.get("seriesId"), c.seriesId()));
            if (c.categoryId() != null) predicates.add(cb.equal(root.get("categoryId"), c.categoryId()));
            if (c.authorId() != null) {
                Subquery<Integer> sub = query.subquery(Integer.class);
                Root<WorkAuthorJpaEntity> wa = sub.from(WorkAuthorJpaEntity.class);
                sub.select(cb.literal(1)).where(cb.equal(wa.get("workId"), root.get("id")),
                        cb.equal(wa.get("authorId"), c.authorId()));
                predicates.add(cb.exists(sub));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        var result = jpa.findAll(spec, PageRequest.of(page, size, Sort.by("title", "id")));
        return new Page(toDomains(result.getContent()), result.getTotalElements());
    }

    /** Dựng domain cho cả loạt, nạp tác giả chuẩn bằng một truy vấn. */
    private List<Work> toDomains(List<WorkJpaEntity> rows) {
        if (rows.isEmpty()) return List.of();
        Map<UUID, List<UUID>> authors = new HashMap<>();
        for (WorkAuthorJpaEntity wa : workAuthors.findByWorkIdInOrderBySortOrder(
                rows.stream().map(WorkJpaEntity::getId).toList())) {
            authors.computeIfAbsent(wa.getWorkId(), k -> new ArrayList<>()).add(wa.getAuthorId());
        }
        return rows.stream().map(e -> e.toDomain(authors.getOrDefault(e.getId(), List.of()))).toList();
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
