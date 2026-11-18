package lms.catalog.catalog.infrastructure.persistence;

import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import lms.catalog.catalog.domain.TitleBatchConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
class TitlePersistenceAdapter implements TitleRepository {

    @PersistenceContext
    private EntityManager em;

    private final SpringDataTitleRepository jpa;
    private final SpringDataTitleAuthorRepository titleAuthors;

    TitlePersistenceAdapter(SpringDataTitleRepository jpa, SpringDataTitleAuthorRepository titleAuthors) {
        this.jpa = jpa;
        this.titleAuthors = titleAuthors;
    }

    @Override
    public Title save(Title title) {
        TitleJpaEntity entity = new TitleJpaEntity(title.id(), title.name(), title.author(), title.isbn(), title.coverPrice(),
                title.description(), title.previewText(), title.publisherId(), title.categoryId())
                .withExtras(title.extras());
        jpa.save(entity);
        // Đồng bộ bảng nối title_authors (xóa rồi ghi lại theo thứ tự); bulk delete tự flush để dòng titles đã có trước.
        titleAuthors.deleteByTitleId(title.id());
        for (int i = 0; i < title.authorIds().size(); i++) {
            titleAuthors.save(new TitleAuthorJpaEntity(title.id(), title.authorIds().get(i), i));
        }
        return toDomain(entity, title.authorIds());
    }

    @Override
    public Optional<Title> findById(UUID id) {
        return jpa.findById(id).map(e -> toDomains(List.of(e)).get(0));
    }

    @Override
    public boolean existsByIsbn(String isbn) {
        return jpa.existsByIsbn(isbn);
    }

    @Override
    public boolean existsByIsbnAndIdNot(String isbn, UUID id) {
        return jpa.existsByIsbnAndIdNot(isbn, id);
    }

    @Override
    public List<Title> search(String keyword) {
        boolean all = keyword == null || keyword.isBlank();
        List<TitleJpaEntity> rows = all ? jpa.findAllByOrderByName() : jpa.search(keyword.trim());
        return toDomains(rows);
    }

    @Override
    public List<Title> searchLimited(String keyword, int limit) {
        boolean all = keyword == null || keyword.isBlank();
        var page = org.springframework.data.domain.PageRequest.of(0, limit);
        List<TitleJpaEntity> rows = all ? jpa.findAllByOrderByName(page) : jpa.searchPage(keyword.trim(), page);
        return toDomains(rows);
    }

    @Override
    public List<Title> findByIds(Collection<UUID> ids) {
        return toDomains(jpa.findAllById(ids));
    }

    @Override
    public Map<String, UUID> findIdsByIsbns(Collection<String> isbns) {
        List<String> keys = isbns.stream().distinct().toList();
        Map<String, UUID> result = new HashMap<>();
        for (int i = 0; i < keys.size(); i += 1000) {
            jpa.findIdsByIsbnIn(keys.subList(i, Math.min(keys.size(), i + 1000)))
                    .forEach(r -> result.put(r.getIsbn(), r.getId()));
        }
        return result;
    }

    /** persist theo lô (không merge, không xóa title_authors vì đầu sách mới) rồi flush: titles trước, title_authors sau. */
    @Override
    public void insertAll(Collection<Title> titles) {
        try {
            for (Title t : titles) {
                em.persist(new TitleJpaEntity(t.id(), t.name(), t.author(), t.isbn(), t.coverPrice(), t.description(),
                        t.previewText(), t.publisherId(), t.categoryId()).withExtras(t.extras()));
            }
            em.flush();
            for (Title t : titles) {
                for (int i = 0; i < t.authorIds().size(); i++) {
                    em.persist(new TitleAuthorJpaEntity(t.id(), t.authorIds().get(i), i));
                }
            }
            em.flush();
        } catch (DataIntegrityViolationException | PersistenceException e) {
            throw new TitleBatchConflictException(e);
        }
    }

    @Override
    public List<Title> findByIsbns(Collection<String> isbns) {
        return isbns.isEmpty() ? List.of() : toDomains(jpa.findByIsbnIn(isbns));
    }

    /** Dựng domain cho cả loạt, nạp tác giả chuẩn bằng một truy vấn. */
    private List<Title> toDomains(List<TitleJpaEntity> rows) {
        if (rows.isEmpty()) return List.of();
        Map<UUID, List<UUID>> authors = new HashMap<>();
        for (TitleAuthorJpaEntity ta : titleAuthors.findByTitleIdInOrderBySortOrder(
                rows.stream().map(TitleJpaEntity::getId).toList())) {
            authors.computeIfAbsent(ta.getTitleId(), k -> new ArrayList<>()).add(ta.getAuthorId());
        }
        return rows.stream().map(e -> toDomain(e, authors.getOrDefault(e.getId(), List.of()))).toList();
    }

    private static Title toDomain(TitleJpaEntity e, List<UUID> authorIds) {
        return Title.restore(e.getId(), e.getName(), e.getAuthor(), e.getIsbn(), e.getCoverPrice(), e.getTenantId(),
                e.getDescription(), e.getPreviewText(), e.getPublisherId(), e.getCategoryId(), authorIds, e.toExtras());
    }
}
