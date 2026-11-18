package lms.catalog.inventory.infrastructure.persistence;

import lms.catalog.inventory.domain.BookCopy;
import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.CopyDetails;
import lms.catalog.inventory.domain.CopyLookupItem;
import lms.tenant.TenantContext;
import lms.catalog.inventory.domain.CopyStatus;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import lms.catalog.inventory.domain.CopyBatchConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
class BookCopyPersistenceAdapter implements BookCopyRepository {

    @PersistenceContext
    private EntityManager em;

    private final SpringDataBookCopyRepository jpa;

    BookCopyPersistenceAdapter(SpringDataBookCopyRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public BookCopy save(BookCopy copy) {
        BookCopyJpaEntity entity = new BookCopyJpaEntity(copy.id(), copy.titleId(), copy.barcode(), copy.status(),
                copy.shelfLocationId()).withAcquisition(copy.acquisition());
        jpa.save(entity);
        // Trả bản có tenantId (lấy từ entity) để phản hồi tạo mới cũng mang tenantId; trạng thái giữ nguyên đối tượng gốc.
        return BookCopy.restore(copy.id(), copy.titleId(), copy.barcode(), copy.status(), entity.getTenantId(),
                copy.shelfLocationId(), copy.acquisition());
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
                CopyStatus.valueOf(r.getStatus()), r.getCoverPrice(), r.getShelfLocationId(), r.getShelfLocationCode());
    }

    @Override
    public List<CopyLookupItem> findLookupByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jpa.findLookupRows(TenantContext.require(), ids).stream()
                .map(r -> new CopyLookupItem(r.getId(), r.getTitleId(), r.getTitleName(), r.getIsbn(), r.getBarcode(),
                        CopyStatus.valueOf(r.getStatus())))
                .toList();
    }

    @Override
    public Optional<Boolean> findShelfLocationActive(UUID shelfLocationId) {
        return jpa.findShelfLocationActive(shelfLocationId);
    }

    @Override
    public Map<UUID, String> findShelfLocationCodes(Collection<UUID> shelfLocationIds) {
        Map<UUID, String> result = new HashMap<>();
        if (shelfLocationIds.isEmpty()) return result;
        jpa.findShelfLocationCodes(shelfLocationIds).forEach(row -> result.put(row.getId(), row.getCode()));
        return result;
    }

    @Override
    public Set<String> findExistingBarcodes(Collection<String> barcodes) {
        List<String> keys = barcodes.stream().distinct().toList();
        Set<String> result = new java.util.HashSet<>();
        for (int i = 0; i < keys.size(); i += 1000) {
            result.addAll(jpa.findBarcodesIn(keys.subList(i, Math.min(keys.size(), i + 1000))));
        }
        return result;
    }

    /** persist theo lô (không merge) rồi flush để trùng mã vạch lộ ra ngay và các INSERT đi thành batch JDBC. */
    @Override
    public void insertAll(Collection<BookCopy> copies) {
        try {
            for (BookCopy c : copies) {
                em.persist(new BookCopyJpaEntity(c.id(), c.titleId(), c.barcode(), c.status(), c.shelfLocationId())
                        .withAcquisition(c.acquisition()));
            }
            em.flush();
        } catch (DataIntegrityViolationException | PersistenceException e) {
            throw new CopyBatchConflictException(e);
        }
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
        return BookCopy.restore(e.getId(), e.getTitleId(), e.getBarcode(), e.getStatus(), e.getTenantId(),
                e.getShelfLocationId(), e.toAcquisition());
    }
}
