package lms.api.circulation.dto;

import java.util.List;

/** Một trang phiếu mượn của GET /api/borrowings/search. items[].fines luôn rỗng ở danh sách này. */
public record BorrowingPageView(List<BorrowingView> items, long total, int page, int size) {
}
