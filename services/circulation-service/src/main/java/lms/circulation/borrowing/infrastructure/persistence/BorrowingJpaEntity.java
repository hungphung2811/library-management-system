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
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.TenantId;
import lms.tenant.TenantContext;

/**
 * Lượt mượn thuộc một tenant: tenant_id do Hibernate điền/lọc theo TenantContext (@TenantId), RLS lọc thêm ở DB.
 * Bảng phụ borrowing_items không có trường tenant ở JPA: cột tenant_id lấy mặc định từ biến phiên app.tenant_id
 * (migration V6) và khóa ngoại kép bảo đảm trùng tenant của lượt mượn.
 */
@Entity
@Table(name = "borrowings")
class BorrowingJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "borrow_date", nullable = false)
    private LocalDate borrowDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "renew_count", nullable = false)
    private int renewCount;

    /** Số ngày mượn theo loại độc giả lúc lập phiếu (migration V4), dùng cho gia hạn. */
    @Column(name = "loan_days", nullable = false)
    private int loanDays;

    /** BatchSize: danh sách phiếu (search) nạp items của cả trang bằng vài truy vấn IN thay vì mỗi phiếu một truy vấn. */
    @BatchSize(size = 100)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "borrowing_items", joinColumns = @JoinColumn(name = "borrowing_id"))
    private List<BorrowingItemEmbeddable> items = new ArrayList<>();

    protected BorrowingJpaEntity() {
    }

    BorrowingJpaEntity(UUID id, UUID userId, LocalDate borrowDate, LocalDate dueDate, int renewCount, int loanDays,
                       List<BorrowingItemEmbeddable> items) {
        // Điền sẵn tenant của phiên: merge (save entity mới dựng) không ghi đè tenant_id thành null, insert khớp
        // tenant phiên; không có tenant thì ném TenantRequiredException (fail-closed). Cột updatable=false.
        this.tenantId = TenantContext.require();
        this.id = id;
        this.userId = userId;
        this.borrowDate = borrowDate;
        this.dueDate = dueDate;
        this.renewCount = renewCount;
        this.loanDays = loanDays;
        this.items = new ArrayList<>(items);
    }

    UUID getId() { return id; }
    UUID getTenantId() { return tenantId; }
    UUID getUserId() { return userId; }
    LocalDate getBorrowDate() { return borrowDate; }
    LocalDate getDueDate() { return dueDate; }
    int getRenewCount() { return renewCount; }
    int getLoanDays() { return loanDays; }
    List<BorrowingItemEmbeddable> getItems() { return items; }
}
