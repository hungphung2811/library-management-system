package lms.circulation.borrowing.domain;

import lms.circulation.borrowing.domain.BookBorrowedEvent;
import lms.circulation.borrowing.domain.BookReturnedEvent;
import lms.circulation.borrowing.domain.InvalidBorrowingException;
import lms.common.kernel.DomainEvent;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Aggregate Root: phiếu mượn sách. Mọi invariant mượn/trả/gia hạn được kiểm soát tại đây. */
public class BorrowingRecord {

    public static final int LOAN_DAYS = 14;
    public static final int MAX_RENEWALS = 2;
    public static final int MAX_ITEMS_PER_BORROWING = 5;

    private final BorrowingId id;
    private final UUID userId;
    private final List<BorrowingItem> items;
    private final LocalDate borrowDate;
    private LocalDate dueDate;
    private int renewCount;
    private final List<DomainEvent> events = new ArrayList<>();

    private BorrowingRecord(BorrowingId id, UUID userId, List<BorrowingItem> items,
                            LocalDate borrowDate, LocalDate dueDate, int renewCount) {
        if (userId == null) throw new InvalidBorrowingException("userId không được null");
        if (items == null || items.isEmpty()) throw new InvalidBorrowingException("Phiếu mượn phải có ít nhất một bản sao");
        if (items.size() > MAX_ITEMS_PER_BORROWING) {
            throw new InvalidBorrowingException("Một phiếu mượn tối đa " + MAX_ITEMS_PER_BORROWING + " bản sao");
        }
        if (new HashSet<>(items.stream().map(BorrowingItem::bookCopyId).toList()).size() != items.size()) {
            throw new InvalidBorrowingException("Bản sao bị trùng trong phiếu mượn");
        }
        this.id = id;
        this.userId = userId;
        this.items = new ArrayList<>(items);
        this.borrowDate = borrowDate;
        this.dueDate = dueDate;
        this.renewCount = renewCount;
    }

    public static BorrowingRecord borrow(UUID userId, List<UUID> bookCopyIds, LocalDate today) {
        if (bookCopyIds == null) throw new InvalidBorrowingException("Danh sách bản sao không được null");
        List<BorrowingItem> items = bookCopyIds.stream().map(BorrowingItem::borrowed).toList();
        BorrowingRecord record = new BorrowingRecord(BorrowingId.newId(), userId, items, today, today.plusDays(LOAN_DAYS), 0);
        record.events.add(new BookBorrowedEvent(UUID.randomUUID(), record.id.value(), userId,
                List.copyOf(bookCopyIds), record.dueDate, java.time.Instant.now()));
        return record;
    }

    /** Dựng lại aggregate từ persistence, không phát sinh event. */
    public static BorrowingRecord restore(BorrowingId id, UUID userId, List<BorrowingItem> items,
                                          LocalDate borrowDate, LocalDate dueDate, int renewCount) {
        return new BorrowingRecord(id, userId, items, borrowDate, dueDate, renewCount);
    }

    public void returnCopy(UUID bookCopyId, LocalDate today) {
        for (int i = 0; i < items.size(); i++) {
            BorrowingItem item = items.get(i);
            if (item.bookCopyId().equals(bookCopyId)) {
                if (item.isReturned()) throw new InvalidBorrowingException("Bản sao đã được trả: " + bookCopyId);
                items.set(i, item.returnedOn(today));
                events.add(new BookReturnedEvent(UUID.randomUUID(), id.value(), userId, bookCopyId,
                        today, overdueDays(today), java.time.Instant.now()));
                return;
            }
        }
        throw new InvalidBorrowingException("Bản sao không thuộc phiếu mượn này: " + bookCopyId);
    }

    public void renew(LocalDate today) {
        if (status() == BorrowingStatus.RETURNED) throw new InvalidBorrowingException("Phiếu mượn đã trả hết, không thể gia hạn");
        if (isOverdue(today)) throw new InvalidBorrowingException("Phiếu mượn đã quá hạn, không thể gia hạn");
        if (renewCount >= MAX_RENEWALS) throw new InvalidBorrowingException("Đã gia hạn tối đa " + MAX_RENEWALS + " lần");
        dueDate = dueDate.plusDays(LOAN_DAYS);
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
}
