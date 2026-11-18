package lms.circulation.borrowing.infrastructure.persistence;

import lms.circulation.borrowing.application.SaveBorrowingPort;
import lms.circulation.borrowing.domain.BorrowingId;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.BorrowingRejectedException;
import lms.circulation.borrowing.domain.BorrowingRepository;
import lms.circulation.borrowing.domain.BorrowingSearchCriteria;
import lms.circulation.borrowing.domain.BorrowingSearchResult;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

public class BorrowingPersistenceAdapter implements SaveBorrowingPort, BorrowingRepository {

    private final SpringDataBorrowingRepository jpa;

    public BorrowingPersistenceAdapter(SpringDataBorrowingRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public BorrowingRecord save(BorrowingRecord record) {
        try {
            jpa.saveAndFlush(BorrowingPersistenceMapper.toEntity(record));
        } catch (DataIntegrityViolationException e) {
            // unique index uq_borrowing_items_open_copy: bản sao đã thuộc một lượt mượn đang mở (ví dụ lập phiếu đồng thời ở quầy khác)
            throw new BorrowingRejectedException("COPY_ALREADY_BORROWED",
                    "Một bản sao đã được lập phiếu mượn ở nơi khác, bỏ bản sao đó ra và xác nhận lại");
        }
        return record;
    }

    @Override
    public Optional<BorrowingRecord> findById(BorrowingId id) {
        return jpa.findInTenantById(id.value()).map(BorrowingPersistenceMapper::toDomain);
    }

    @Override
    public Optional<BorrowingRecord> findByIdAndUserId(BorrowingId id, UUID userId) {
        return jpa.findInTenantByIdAndUserId(id.value(), userId).map(BorrowingPersistenceMapper::toDomain);
    }

    @Override
    public Optional<BorrowingRecord> findByIdForUpdate(BorrowingId id) {
        return jpa.findByIdForUpdate(id.value()).map(BorrowingPersistenceMapper::toDomain);
    }

    @Override
    public Optional<BorrowingRecord> findOpenByCopyIdForUpdate(UUID bookCopyId) {
        return jpa.findOpenByCopyIdForUpdate(bookCopyId).map(BorrowingPersistenceMapper::toDomain);
    }

    @Override
    public Optional<BorrowingRecord> findLatestLostByCopyId(UUID bookCopyId) {
        return jpa.findLostByCopyIdNewestFirst(bookCopyId, lms.circulation.borrowing.domain.ItemCondition.LOST,
                PageRequest.of(0, 1)).stream().findFirst().map(BorrowingPersistenceMapper::toDomain);
    }

    @Override
    public List<BorrowingRecord> findByUserId(UUID userId) {
        return jpa.findByUserIdOrdered(userId).stream().map(BorrowingPersistenceMapper::toDomain).toList();
    }

    @Override
    public long countActiveItemsByUserId(UUID userId) {
        return jpa.countActiveItemsByUserId(userId);
    }

    @Override
    public long countOverdueItemsByUserId(UUID userId, LocalDate today) {
        return jpa.countOverdueItemsByUserId(userId, today);
    }

    @Override
    public BorrowingSearchResult search(BorrowingSearchCriteria criteria) {
        // Phân trang ở DB trên bảng borrowings (không join fetch collection); items nạp sau theo lô nhờ @BatchSize
        Page<BorrowingJpaEntity> page = jpa.findAll(BorrowingSpecifications.matching(criteria),
                PageRequest.of(criteria.page(), criteria.size(),
                        Sort.by(Sort.Order.desc("borrowDate"), Sort.Order.asc("id"))));
        return new BorrowingSearchResult(
                page.getContent().stream().map(BorrowingPersistenceMapper::toDomain).toList(), page.getTotalElements());
    }
}
