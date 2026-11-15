package lms.circulation.borrowing.domain;

import java.util.Optional;

public interface BorrowRuleRepository {
    Optional<BorrowRule> findByReaderType(ReaderType readerType);
}
