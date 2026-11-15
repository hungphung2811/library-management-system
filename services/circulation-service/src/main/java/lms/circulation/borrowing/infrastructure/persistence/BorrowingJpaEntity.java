package lms.circulation.borrowing.infrastructure.persistence;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "borrowings")
class BorrowingJpaEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "borrow_date", nullable = false)
    private LocalDate borrowDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "renew_count", nullable = false)
    private int renewCount;

    @Column(name = "loan_days", nullable = false)
    private int loanDays;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "borrowing_items", joinColumns = @JoinColumn(name = "borrowing_id"))
    private List<BorrowingItemEmbeddable> items = new ArrayList<>();

    protected BorrowingJpaEntity() {
    }

    BorrowingJpaEntity(UUID id, UUID userId, LocalDate borrowDate, LocalDate dueDate, int renewCount, int loanDays,
                       List<BorrowingItemEmbeddable> items) {
        this.loanDays = loanDays;
        this.id = id;
        this.userId = userId;
        this.borrowDate = borrowDate;
        this.dueDate = dueDate;
        this.renewCount = renewCount;
        this.items = new ArrayList<>(items);
    }

    UUID getId() { return id; }
    UUID getUserId() { return userId; }
    LocalDate getBorrowDate() { return borrowDate; }
    LocalDate getDueDate() { return dueDate; }
    int getRenewCount() { return renewCount; }
    int getLoanDays() { return loanDays; }
    List<BorrowingItemEmbeddable> getItems() { return items; }
}
