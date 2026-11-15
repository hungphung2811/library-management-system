package lms.circulation.borrowing.infrastructure.persistence;

import lms.circulation.borrowing.application.SaveBorrowingPort;
import lms.circulation.borrowing.domain.BorrowingId;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.BorrowingRepository;
import java.util.Optional;
import java.util.UUID;

public class BorrowingPersistenceAdapter implements SaveBorrowingPort, BorrowingRepository {

    private final SpringDataBorrowingRepository jpa;

    public BorrowingPersistenceAdapter(SpringDataBorrowingRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public BorrowingRecord save(BorrowingRecord record) {
        jpa.save(BorrowingPersistenceMapper.toEntity(record));
        return record;
    }

    @Override
    public Optional<BorrowingRecord> findById(BorrowingId id) {
        return jpa.findInTenantById(id.value()).map(BorrowingPersistenceMapper::toDomain);
    }

    @Override
    public long countActiveItemsByUserId(UUID userId) {
        return jpa.countActiveItemsByUserId(userId);
    }
}
