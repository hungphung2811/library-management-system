package lms.circulation.fine.infrastructure.persistence;

import lms.circulation.fine.domain.Fine;
import lms.circulation.fine.domain.FineReason;
import lms.circulation.fine.domain.FineRepository;
import lms.circulation.fine.domain.FineStatus;
import lms.circulation.policy.application.DamageLevelUsagePort;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Lưu khoản phạt; cũng cài cổng DamageLevelUsagePort của policy (bậc hỏng đã được dùng chưa). */
@Repository
class FinePersistenceAdapter implements FineRepository, DamageLevelUsagePort {

    private static final Set<FineStatus> COLLECTIBLE = Set.of(FineStatus.UNPAID, FineStatus.REDUCED);

    private final SpringDataFineRepository jpa;

    FinePersistenceAdapter(SpringDataFineRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Fine save(Fine f) {
        jpa.saveAndFlush(new FineJpaEntity(f));
        return f;
    }

    @Override
    public boolean exists(UUID borrowingId, UUID bookCopyId, FineReason reason) {
        return jpa.existsByBorrowingIdAndBookCopyIdAndReason(borrowingId, bookCopyId, reason);
    }

    @Override
    public List<Fine> findByReader(UUID readerId) {
        return jpa.findByReaderIdOrderByCreatedAtDesc(readerId).stream().map(FineJpaEntity::toDomain).toList();
    }

    @Override
    public List<Fine> findUnpaidByReaderForUpdate(UUID readerId) {
        return jpa.findByReaderAndStatusInForUpdate(readerId, COLLECTIBLE).stream().map(FineJpaEntity::toDomain).toList();
    }

    @Override
    public BigDecimal sumUnpaidByReader(UUID readerId) {
        return jpa.sumPayableByReaderAndStatusIn(readerId, COLLECTIBLE);
    }

    @Override
    public Optional<Fine> findByIdForUpdate(UUID id) {
        return jpa.findByIdForUpdate(id).map(FineJpaEntity::toDomain);
    }

    @Override
    public Optional<Fine> findByItemAndReason(UUID borrowingId, UUID bookCopyId, FineReason reason) {
        return jpa.findByBorrowingIdAndBookCopyIdAndReason(borrowingId, bookCopyId, reason).map(FineJpaEntity::toDomain);
    }

    @Override
    public boolean isUsed(String code) {
        return jpa.existsByDamageLevelCode(code);
    }
}
