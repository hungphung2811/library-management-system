package lms.catalog.catalog.infrastructure.persistence;

import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class TitlePersistenceAdapter implements TitleRepository {

    private final SpringDataTitleRepository jpa;

    TitlePersistenceAdapter(SpringDataTitleRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Title save(Title title) {
        TitleJpaEntity entity = new TitleJpaEntity(title.id(), title.name(), title.author(), title.isbn(), title.coverPrice(),
                title.description(), title.previewText());
        jpa.save(entity);
        return toDomain(entity);
    }

    @Override
    public Optional<Title> findById(UUID id) {
        return jpa.findById(id).map(TitlePersistenceAdapter::toDomain);
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
        return rows.stream().map(TitlePersistenceAdapter::toDomain).toList();
    }

    @Override
    public List<Title> searchLimited(String keyword, int limit) {
        boolean all = keyword == null || keyword.isBlank();
        var page = org.springframework.data.domain.PageRequest.of(0, limit);
        List<TitleJpaEntity> rows = all ? jpa.findAllByOrderByName(page) : jpa.searchPage(keyword.trim(), page);
        return rows.stream().map(TitlePersistenceAdapter::toDomain).toList();
    }

    @Override
    public List<Title> findByIds(Collection<UUID> ids) {
        return jpa.findAllById(ids).stream().map(TitlePersistenceAdapter::toDomain).toList();
    }

    private static Title toDomain(TitleJpaEntity e) {
        return Title.restore(e.getId(), e.getName(), e.getAuthor(), e.getIsbn(), e.getCoverPrice(), e.getTenantId(),
                e.getDescription(), e.getPreviewText());
    }
}
