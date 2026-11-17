package lms.circulation.borrowing.application;

import lms.circulation.borrowing.domain.BorrowingRepository;
import lms.circulation.borrowing.domain.BorrowingSearchCriteria;
import lms.circulation.borrowing.domain.BorrowingSearchResult;
import lms.circulation.borrowing.domain.BorrowingStatus;
import lms.circulation.borrowing.domain.InvalidBorrowingQueryException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * Danh sách phiếu mượn có phân trang và bộ lọc cho màn hình quản lý, sắp xếp borrowDate giảm dần rồi id tăng dần.
 * Quy tắc: status sai, page &lt; 0, size ngoài 1-100 thì InvalidBorrowingQueryException (400, không kẹp size);
 * overdue=true nghĩa là ACTIVE và dueDate &lt; hôm nay (theo Clock), kết hợp status=RETURNED cho kết quả rỗng.
 * Không truy vấn phạt (tránh N+1): fines của mỗi phần tử luôn rỗng.
 */
public class SearchBorrowingsUseCase {

    private final BorrowingRepository repository;
    private final Clock clock;

    public SearchBorrowingsUseCase(BorrowingRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BorrowingPageResponse execute(SearchBorrowingsQuery query) {
        BorrowingStatus status = parseStatus(query.status());
        boolean overdue = Boolean.TRUE.equals(query.overdue());
        int page = query.page() == null ? 0 : query.page();
        int size = query.size() == null ? BorrowingSearchCriteria.DEFAULT_PAGE_SIZE : query.size();
        if (overdue && status == BorrowingStatus.RETURNED) {
            new BorrowingSearchCriteria(null, null, null, page, size); // vẫn kiểm page/size
            return new BorrowingPageResponse(List.of(), 0, page, size);
        }
        LocalDate overdueBefore = overdue ? LocalDate.now(clock) : null;
        BorrowingSearchCriteria criteria = new BorrowingSearchCriteria(overdue ? BorrowingStatus.ACTIVE : status,
                query.readerId(), overdueBefore, page, size);
        BorrowingSearchResult result = repository.search(criteria);
        return new BorrowingPageResponse(result.items().stream().map(BorrowingMapper::toResponse).toList(),
                result.total(), page, size);
    }

    private static BorrowingStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return BorrowingStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidBorrowingQueryException("status phải là ACTIVE hoặc RETURNED");
        }
    }
}
