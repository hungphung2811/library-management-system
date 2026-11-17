package lms.circulation.borrowing.domain;

import java.util.List;

/** Một trang kết quả tìm phiếu mượn; total là tổng số phiếu khớp điều kiện (không chỉ trang này). */
public record BorrowingSearchResult(List<BorrowingRecord> items, long total) {
}
