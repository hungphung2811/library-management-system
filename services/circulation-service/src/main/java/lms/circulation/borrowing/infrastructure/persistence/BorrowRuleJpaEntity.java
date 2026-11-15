package lms.circulation.borrowing.infrastructure.persistence;

import lms.circulation.borrowing.domain.ReaderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "borrow_rules")
class BorrowRuleJpaEntity {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "reader_type")
    private ReaderType readerType;

    @Column(name = "max_items", nullable = false)
    private int maxItems;

    @Column(name = "loan_days", nullable = false)
    private int loanDays;

    protected BorrowRuleJpaEntity() {
    }

    ReaderType getReaderType() { return readerType; }
    int getMaxItems() { return maxItems; }
    int getLoanDays() { return loanDays; }
}
