package lms.circulation.reporting.domain;

/**
 * Số liệu mượn trả tại một ngày: phiếu ACTIVE (còn ít nhất một bản sao chưa trả), phiếu quá hạn (ACTIVE và dueDate &lt;
 * ngày đó), số bản sao chưa trả của các phiếu quá hạn, số bản sao mượn và trả đúng ngày đó.
 */
public record LendingCounts(long activeBorrowings, long overdueBorrowings, long overdueCopies,
                            long borrowedToday, long returnedToday) {
}
