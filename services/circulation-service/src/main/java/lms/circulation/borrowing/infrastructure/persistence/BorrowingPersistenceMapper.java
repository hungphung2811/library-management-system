package lms.circulation.borrowing.infrastructure.persistence;

import lms.circulation.borrowing.domain.BorrowingId;
import lms.circulation.borrowing.domain.BorrowingItem;
import lms.circulation.borrowing.domain.BorrowingRecord;
import java.util.List;

/** Tách Domain Model khỏi Persistence Model. */
final class BorrowingPersistenceMapper {

    private BorrowingPersistenceMapper() {
    }

    static BorrowingJpaEntity toEntity(BorrowingRecord record) {
        List<BorrowingItemEmbeddable> items = record.items().stream()
                .map(i -> new BorrowingItemEmbeddable(i.bookCopyId(), i.returnDate(), i.condition()))
                .toList();
        return new BorrowingJpaEntity(record.id().value(), record.userId(), record.borrowDate(),
                record.dueDate(), record.renewCount(), record.loanDays(), items);
    }

    static BorrowingRecord toDomain(BorrowingJpaEntity entity) {
        List<BorrowingItem> items = entity.getItems().stream()
                .map(i -> new BorrowingItem(i.getBookCopyId(), i.getReturnDate(), i.getCondition()))
                .toList();
        return BorrowingRecord.restore(new BorrowingId(entity.getId()), entity.getUserId(), items,
                entity.getBorrowDate(), entity.getDueDate(), entity.getRenewCount(), entity.getLoanDays(),
                entity.getTenantId());
    }
}
