package lms.circulation.fine.infrastructure.persistence;

import lms.circulation.fine.domain.Fine;
import lms.circulation.fine.domain.FineReason;
import lms.circulation.fine.domain.FineRepository;
import lms.circulation.fine.domain.FineStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class FinePersistenceAdapter implements FineRepository {

    private final SpringDataFineRepository jpa;

    FinePersistenceAdapter(SpringDataFineRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Fine save(Fine f) {
        jpa.saveAndFlush(new FineJpaEntity(f.id(), f.readerId(), f.borrowingId(), f.bookCopyId(), f.reason(),
                f.amount(), f.status(), f.createdAt(), f.paidAt()));
        return f;
    }

    @Override
    public boolean exists(UUID borrowingId, UUID bookCopyId, FineReason reason) {
        return jpa.existsByBorrowingIdAndBookCopyIdAndReason(borrowingId, bookCopyId, reason);
    }

    @Override
    public List<Fine> findByReader(UUID readerId) {
        return jpa.findByReaderIdOrderByCreatedAtDesc(readerId).stream().map(FinePersistenceAdapter::toDomain).toList();
    }

    @Override
    public List<Fine> findUnpaidByReaderForUpdate(UUID readerId) {
        return jpa.findByReaderAndStatusForUpdate(readerId, FineStatus.UNPAID).stream()
                .map(FinePersistenceAdapter::toDomain).toList();
    }

    @Override
    public BigDecimal sumUnpaidByReader(UUID readerId) {
        return jpa.sumByReaderAndStatus(readerId, FineStatus.UNPAID);
    }

    private static Fine toDomain(FineJpaEntity e) {
        return Fine.restore(e.getId(), e.getReaderId(), e.getBorrowingId(), e.getBookCopyId(), e.getReason(),
                e.getAmount(), e.getStatus(), e.getCreatedAt(), e.getPaidAt());
    }
}
