package lms.circulation.borrowing.application;

import java.util.UUID;

/**
 * Tham số GET /internal/borrowings/search, đều tùy chọn. status: ACTIVE hoặc RETURNED. overdue=true: phiếu còn bản sao
 * chưa trả và quá hạn (dueDate trước hôm nay). page mặc định 0, size mặc định 20, tối đa 100.
 */
public record SearchBorrowingsQuery(String status, Boolean overdue, UUID readerId, Integer page, Integer size) {
}
