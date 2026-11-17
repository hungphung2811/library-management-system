package lms.circulation.borrowing.application;

import java.util.List;

/** Một trang phiếu mượn (cùng hình dạng PageResponse của audit). items[].fines luôn rỗng ở danh sách này. */
public record BorrowingPageResponse(List<BorrowingResponse> items, long total, int page, int size) {
}
