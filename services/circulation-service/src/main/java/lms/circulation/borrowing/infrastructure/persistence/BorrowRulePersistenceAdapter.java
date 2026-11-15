package lms.circulation.borrowing.infrastructure.persistence;

import lms.circulation.borrowing.domain.BorrowRule;
import lms.circulation.borrowing.domain.BorrowRuleRepository;
import lms.circulation.borrowing.domain.ReaderType;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Đọc hạn mức mượn từ bảng borrow_rules mỗi lần lập phiếu nên đổi quy định không cần sửa code hay khởi động lại. */
@Repository
class BorrowRulePersistenceAdapter implements BorrowRuleRepository {

    private final SpringDataBorrowRuleRepository jpa;

    BorrowRulePersistenceAdapter(SpringDataBorrowRuleRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<BorrowRule> findByReaderType(ReaderType readerType) {
        return jpa.findById(readerType).map(e -> new BorrowRule(e.getReaderType(), e.getMaxItems(), e.getLoanDays()));
    }
}
