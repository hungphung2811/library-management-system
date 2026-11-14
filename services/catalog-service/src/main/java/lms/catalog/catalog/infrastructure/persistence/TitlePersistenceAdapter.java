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
        jpa.save(new TitleJpaEntity(title.id(), title.name(), title.author(), title.isbn(), title.coverPrice()));
        return title;
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
    public List<Title> search(String keyword) {
        boolean all = keyword == null || keyword.isBlank();
        List<TitleJpaEntity> rows = all ? jpa.findAllByOrderByName() : jpa.search(keyword.trim());
        return rows.stream().map(TitlePersistenceAdapter::toDomain).toList();
    }

    @Override
    public List<Title> findByIds(Collection<UUID> ids) {
        return jpa.findAllById(ids).stream().map(TitlePersistenceAdapter::toDomain).toList();
    }

    private static Title toDomain(TitleJpaEntity e) {
        return Title.restore(e.getId(), e.getName(), e.getAuthor(), e.getIsbn(), e.getCoverPrice());
    }
}
