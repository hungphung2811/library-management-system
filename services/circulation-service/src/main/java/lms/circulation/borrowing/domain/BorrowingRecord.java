package lms.circulation.borrowing.domain;

import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate Root: phiếu mượn sách. Mọi invariant mượn/trả/báo mất/gia hạn được kiểm soát tại đây.
 * Hạn mức và số ngày mượn do {@link BorrowRule} (theo loại độc giả, đọc từ CSDL) quyết định, không còn là hằng số.
 */
public class BorrowingRecord {

    public static final int DEFAULT_LOAN_DAYS = 14;
    public static final int MAX_RENEWALS = 2;

    private final BorrowingId id;
    private final UUID userId;
    private final List<BorrowingItem> items;
    private final LocalDate borrowDate;
    private final int loanDays;
    private LocalDate dueDate;
    private int renewCount;
    /** Tenant sở hữu, chỉ có khi dựng lại từ persistence (đọc); phiếu mới lập chưa có (null). Không tham gia nghiệp vụ. */
    private UUID tenantId;
    private final List<DomainEvent> events = new ArrayList<>();

    private BorrowingRecord(BorrowingId id, UUID userId, List<BorrowingItem> items, LocalDate borrowDate,
                            LocalDate dueDate, int renewCount, int loanDays) {
        if (userId == null) throw new InvalidBorrowingException("userId không được null");
        if (items == null || items.isEmpty()) throw new InvalidBorrowingException("Phiếu mượn phải có ít nhất một bản sao");
        if (loanDays <= 0) throw new InvalidBorrowingException("Số ngày mượn phải > 0");
        if (new HashSet<>(items.stream().map(BorrowingItem::bookCopyId).toList()).size() != items.size()) {
            throw new InvalidBorrowingException("Bản sao bị trùng trong phiếu mượn");
        }
        this.id = id;
        this.userId = userId;
        this.items = new ArrayList<>(items);
        this.borrowDate = borrowDate;
        this.dueDate = dueDate;
        this.renewCount = renewCount;
        this.loanDays = loanDays;
    }

    /** UC06: lập phiếu. Hạn mức theo loại độc giả đã được {@link BorrowingDomainService} kiểm trước. */
    public static BorrowingRecord borrow(UUID userId, List<UUID> bookCopyIds, LocalDate today, int loanDays) {
        if (bookCopyIds == null) throw new InvalidBorrowingException("Danh sách bản sao không được null");
        List<BorrowingItem> items = bookCopyIds.stream().map(BorrowingItem::borrowed).toList();
        BorrowingRecord record = new BorrowingRecord(BorrowingId.newId(), userId, items, today, today.plusDays(loanDays), 0, loanDays);
        record.events.add(new BookBorrowedEvent(UUID.randomUUID(), record.id.value(), userId,
                List.copyOf(bookCopyIds), record.dueDate, Instant.now()));
        return record;
    }

    public static BorrowingRecord borrow(UUID userId, List<UUID> bookCopyIds, LocalDate today) {
        return borrow(userId, bookCopyIds, today, DEFAULT_LOAN_DAYS);
    }

    /** Dựng lại aggregate từ persistence, không phát sinh event. */
    public static BorrowingRecord restore(BorrowingId id, UUID userId, List<BorrowingItem> items,
                                          LocalDate borrowDate, LocalDate dueDate, int renewCount, int loanDays) {
        return new BorrowingRecord(id, userId, items, borrowDate, dueDate, renewCount, loanDays);
    }

    /** Như {@link #restore} kèm tenant sở hữu (đọc xuyên tenant, ADR-0008). */
    public static BorrowingRecord restore(BorrowingId id, UUID userId, List<BorrowingItem> items,
                                          LocalDate borrowDate, LocalDate dueDate, int renewCount, int loanDays,
                                          UUID tenantId) {
        BorrowingRecord record = new BorrowingRecord(id, userId, items, borrowDate, dueDate, renewCount, loanDays);
        record.tenantId = tenantId;
        return record;
    }

    /** UC07: nhận trả một bản sao với tình trạng nguyên vẹn hoặc hỏng. */
    public void returnCopy(UUID bookCopyId, ItemCondition condition, LocalDate today) {
        if (condition == null || condition == ItemCondition.LOST) {
            throw new InvalidBorrowingException("Tình trạng khi trả phải là GOOD hoặc DAMAGED");
        }
        int index = openItemIndex(bookCopyId);
        items.set(index, items.get(index).closedOn(today, condition));
        events.add(new BookReturnedEvent(UUID.randomUUID(), id.value(), userId, bookCopyId,
                today, overdueDays(today), Instant.now(), condition.name()));
    }

    /** UC08: báo mất, đóng lượt mượn với tình trạng LOST. Phí trễ không áp dụng (QĐ8) nên do context fine quyết định. */
    public void reportLost(UUID bookCopyId, LocalDate today) {
        int index = openItemIndex(bookCopyId);
        items.set(index, items.get(index).closedOn(today, ItemCondition.LOST));
        events.add(new BookLostEvent(UUID.randomUUID(), id.value(), userId, bookCopyId, today, Instant.now()));
    }

    private int openItemIndex(UUID bookCopyId) {
        for (int i = 0; i < items.size(); i++) {
            BorrowingItem item = items.get(i);
            if (item.bookCopyId().equals(bookCopyId)) {
                if (item.isReturned()) throw new InvalidBorrowingException("Bản sao đã được trả: " + bookCopyId);
                return i;
            }
        }
        throw new InvalidBorrowingException("Bản sao không thuộc phiếu mượn này: " + bookCopyId);
    }

    public void renew(LocalDate today) {
        if (status() == BorrowingStatus.RETURNED) throw new InvalidBorrowingException("Phiếu mượn đã trả hết, không thể gia hạn");
        if (isOverdue(today)) throw new InvalidBorrowingException("Phiếu mượn đã quá hạn, không thể gia hạn");
        if (renewCount >= MAX_RENEWALS) throw new InvalidBorrowingException("Đã gia hạn tối đa " + MAX_RENEWALS + " lần");
        dueDate = dueDate.plusDays(loanDays);
        renewCount++;
    }

    public BorrowingStatus status() {
        return items.stream().allMatch(BorrowingItem::isReturned) ? BorrowingStatus.RETURNED : BorrowingStatus.ACTIVE;
    }

    public boolean isOverdue(LocalDate today) {
        return status() == BorrowingStatus.ACTIVE && today.isAfter(dueDate);
    }

    public long overdueDays(LocalDate returnDate) {
        return Math.max(0, ChronoUnit.DAYS.between(dueDate, returnDate));
    }

    public long activeItemCount() {
        return items.stream().filter(i -> !i.isReturned()).count();
    }

    public List<DomainEvent> pullEvents() {
        List<DomainEvent> copy = List.copyOf(events);
        events.clear();
        return copy;
    }

    public BorrowingId id() { return id; }
    public UUID userId() { return userId; }
    public List<BorrowingItem> items() { return List.copyOf(items); }
    public LocalDate borrowDate() { return borrowDate; }
    public LocalDate dueDate() { return dueDate; }
    public int renewCount() { return renewCount; }
    public int loanDays() { return loanDays; }
    /** Null với phiếu mới lập chưa qua persistence. */
    public UUID tenantId() { return tenantId; }
}
