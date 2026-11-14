package lms.catalog.inventory.infrastructure.persistence;

import lms.catalog.inventory.domain.BookCopy;
import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.CopyDetails;
import lms.catalog.inventory.domain.CopyStatus;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class BookCopyPersistenceAdapter implements BookCopyRepository {

    private final SpringDataBookCopyRepository jpa;

    BookCopyPersistenceAdapter(SpringDataBookCopyRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public BookCopy save(BookCopy copy) {
        jpa.save(new BookCopyJpaEntity(copy.id(), copy.titleId(), copy.barcode(), copy.status()));
        return copy;
    }

    @Override
    public Optional<BookCopy> findById(UUID id) {
        return jpa.findById(id).map(BookCopyPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<BookCopy> findByIdForUpdate(UUID id) {
        return jpa.findByIdForUpdate(id).map(BookCopyPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<CopyDetails> findDetailsById(UUID id) {
        return jpa.findDetailsById(id).map(BookCopyPersistenceAdapter::toDetails);
    }

    @Override
    public Optional<CopyDetails> findDetailsByBarcode(String barcode) {
        return jpa.findDetailsByBarcode(barcode).map(BookCopyPersistenceAdapter::toDetails);
    }

    private static CopyDetails toDetails(SpringDataBookCopyRepository.DetailsRow r) {
        return new CopyDetails(r.getId(), r.getTitleId(), r.getTitleName(), r.getBarcode(),
                CopyStatus.valueOf(r.getStatus()), r.getCoverPrice());
    }

    @Override
    public boolean existsByBarcode(String barcode) {
        return jpa.existsByBarcode(barcode);
    }

    @Override
    public boolean titleExists(UUID titleId) {
        return jpa.titleExists(titleId);
    }

    @Override
    public List<BookCopy> findByTitleId(UUID titleId) {
        return jpa.findByTitleIdOrderByBarcode(titleId).stream().map(BookCopyPersistenceAdapter::toDomain).toList();
    }

    @Override
    public Map<UUID, Long> countByTitleIds(Collection<UUID> titleIds, CopyStatus status) {
        Map<UUID, Long> result = new HashMap<>();
        jpa.countByTitleIds(titleIds, status).forEach(row -> result.put(row.getTitleId(), row.getCnt()));
        return result;
    }

    @Override
    public Set<UUID> findTitleIdsHavingStatus(CopyStatus status) {
        return jpa.findDistinctTitleIdsByStatus(status);
    }

    private static BookCopy toDomain(BookCopyJpaEntity e) {
        return BookCopy.restore(e.getId(), e.getTitleId(), e.getBarcode(), e.getStatus());
    }
}
