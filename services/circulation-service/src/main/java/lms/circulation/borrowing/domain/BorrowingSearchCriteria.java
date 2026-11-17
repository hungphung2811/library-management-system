package lms.circulation.borrowing.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Điều kiện tìm phiếu mượn (màn hình quản lý), mọi điều kiện lọc đều tùy chọn (null = không lọc).
 * overdueBefore khác null: chỉ lấy phiếu còn bản sao chưa trả và dueDate < overdueBefore (không phụ thuộc status).
 * Phân trang có giới hạn: page >= 0, 1 <= size <= {@value #MAX_PAGE_SIZE}.
 */
public record BorrowingSearchCriteria(BorrowingStatus status, UUID readerId, LocalDate overdueBefore,
                                      int page, int size) {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    public BorrowingSearchCriteria {
        if (page < 0) throw new InvalidBorrowingQueryException("page phải >= 0");
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidBorrowingQueryException("size phải trong khoảng 1-" + MAX_PAGE_SIZE);
        }
    }
}
