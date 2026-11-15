package lms.circulation.borrowing.infrastructure.persistence;

import lms.circulation.borrowing.domain.ReaderType;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataBorrowRuleRepository extends JpaRepository<BorrowRuleJpaEntity, ReaderType> {
}
